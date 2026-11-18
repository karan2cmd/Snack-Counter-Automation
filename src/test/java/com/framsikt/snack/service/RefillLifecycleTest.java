package com.framsikt.snack.service;

import com.framsikt.snack.Fixture;
import com.framsikt.snack.SnackSystem;
import com.framsikt.snack.domain.InvalidStateTransitionException;
import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.RefillConflictException;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.RefillStatus;
import com.framsikt.snack.port.ClaimRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.framsikt.snack.service.SnackConfig.OSLO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefillLifecycleTest {

    private SnackSystem s;

    @BeforeEach
    void setUp() {
        s = Fixture.system();
    }

    @Test
    @DisplayName("happy path: empty -> offered -> committed -> claim filed -> reimbursed")
    void happy_path_end_to_end() {
        RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
        assertThat(r.status()).isEqualTo(RefillStatus.OFFERED);
        String buyer = r.offeredTo().orElseThrow();
        assertThat(s.chat.directMessagesTo(buyer)).anyMatch(m -> m.contains("your turn"));

        s.service.commit(r.id(), buyer);
        assertThat(r.status()).isEqualTo(RefillStatus.COMMITTED);
        assertThat(s.chat.channelMessages(OSLO)).anyMatch(m -> m.contains("no need for anyone else to buy"));

        s.service.recordPurchase(r.id(), buyer, Money.of(800, "NOK"), "fruit", "rcpt.jpg");
        assertThat(r.status()).isEqualTo(RefillStatus.CLAIM_SUBMITTED);

        ClaimRequest claim = s.hr.claims().get(0).request();
        assertThat(claim.employeeId()).isEqualTo(buyer);
        assertThat(claim.amount()).isEqualTo(Money.of(800, "NOK"));
        assertThat(claim.receiptRef()).isEqualTo("rcpt.jpg");
        assertThat(claim.description()).contains("Oslo");

        s.service.onClaimDecision(r.claimId().orElseThrow(), true);
        assertThat(r.status()).isEqualTo(RefillStatus.REIMBURSED);
    }

    @Test
    void second_empty_report_reuses_the_open_refill() {
        RefillRequest first = s.service.reportEmpty(OSLO, "reporter-1");
        RefillRequest second = s.service.reportEmpty(OSLO, "reporter-2");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(s.refills.findAll()).hasSize(1);
        assertThat(s.chat.directMessagesTo("reporter-2")).anyMatch(m -> m.contains("already being handled"));
    }

    @Test
    void purchase_before_commit_is_rejected() {
        RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
        assertThatThrownBy(() -> s.service.recordPurchase(r.id(), r.offeredTo().orElseThrow(),
                Money.of(500, "NOK"), "", "rcpt"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Nested
    @DisplayName("two people cannot both commit to the same refill")
    class SingleCommitter {

        @Test
        void only_the_offeree_can_commit() {
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            String offeree = r.offeredTo().orElseThrow();
            String other = offeree.equals("anna") ? "bjorn" : "anna";

            assertThatThrownBy(() -> s.service.commit(r.id(), other))
                    .isInstanceOf(RefillConflictException.class);
            s.service.commit(r.id(), offeree);
            assertThat(r.committedBy()).contains(offeree);
        }

        @Test
        void committing_twice_fails_for_everyone_after_the_first() {
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            String offeree = r.offeredTo().orElseThrow();
            s.service.commit(r.id(), offeree);

            assertThatThrownBy(() -> s.service.commit(r.id(), offeree))
                    .isInstanceOf(RefillConflictException.class)
                    .hasMessageContaining("already taken");
        }

        @Test
        void concurrent_self_volunteers_on_an_unassigned_refill_exactly_one_wins() throws Exception {
            // Everyone is "away" so the refill becomes UNASSIGNED; then they arrive and race for it.
            LocalDate today = LocalDate.of(2026, 11, 2);
            for (String id : List.of("anna", "bjorn", "carl")) {
                s.availability.away(id, today, today);
            }
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            assertThat(r.status()).isEqualTo(RefillStatus.UNASSIGNED);
            for (String id : List.of("anna", "bjorn", "carl")) {
                s.availability.visiting(id, OSLO, today, today); // now in the office
            }

            int threads = 3;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (String id : List.of("anna", "bjorn", "carl")) {
                Callable<Boolean> attempt = () -> {
                    start.await();
                    try {
                        s.service.commit(r.id(), id);
                        return true;
                    } catch (RefillConflictException e) {
                        return false;
                    }
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> f : results) {
                if (f.get(5, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            pool.shutdownNow();

            assertThat(winners).isEqualTo(1);
            assertThat(r.status()).isEqualTo(RefillStatus.COMMITTED);
        }
    }

    @Nested
    @DisplayName("a refill nobody completes is handed to someone else")
    class Handover {

        @Test
        void unanswered_offer_moves_to_next_volunteer_after_timeout() {
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            String first = r.offeredTo().orElseThrow();

            s.clock.advance(Duration.ofHours(3));
            s.service.tick();
            assertThat(r.offeredTo()).contains(first); // not yet

            s.clock.advance(Duration.ofHours(1));
            s.service.tick();
            assertThat(r.status()).isEqualTo(RefillStatus.OFFERED);
            assertThat(r.offeredTo()).isPresent().get().isNotEqualTo(first);
            assertThat(r.excluded()).contains(first);
        }

        @Test
        void expired_offer_can_no_longer_be_accepted() {
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            String first = r.offeredTo().orElseThrow();
            s.clock.advance(Duration.ofHours(5)); // tick has not run yet

            assertThatThrownBy(() -> s.service.commit(r.id(), first))
                    .isInstanceOf(RefillConflictException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        void decline_moves_on_immediately() {
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            String first = r.offeredTo().orElseThrow();
            s.service.decline(r.id(), first);
            assertThat(r.offeredTo()).isPresent().get().isNotEqualTo(first);
        }

        @Test
        void committed_but_no_receipt_is_reassigned_and_reservation_released() {
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            String first = r.offeredTo().orElseThrow();
            s.service.commit(r.id(), first);
            assertThat(s.budget.snapshot(OSLO, s.clock.instant()).reserved()).isEqualTo(Money.of(1200, "NOK"));

            s.clock.advance(Duration.ofHours(24));
            s.service.tick();

            assertThat(r.status()).isEqualTo(RefillStatus.OFFERED);
            assertThat(r.offeredTo()).isPresent().get().isNotEqualTo(first);
            assertThat(s.budget.snapshot(OSLO, s.clock.instant()).reserved()).isEqualTo(Money.of(0, "NOK"));
            assertThat(s.chat.directMessagesTo(first)).anyMatch(m -> m.contains("handed to someone else"));
        }

        @Test
        void when_everyone_has_passed_it_escalates_once_and_anyone_present_can_take_it() {
            RefillRequest r = s.service.reportEmpty(OSLO, "reporter");
            for (int i = 0; i < 3; i++) { // anna, bjorn, carl all decline
                s.service.decline(r.id(), r.offeredTo().orElseThrow());
            }
            assertThat(r.status()).isEqualTo(RefillStatus.UNASSIGNED);
            assertThat(s.chat.directMessagesTo("osl-coordinator")).hasSize(1);

            s.service.tick(); // retry must not spam
            assertThat(s.chat.directMessagesTo("osl-coordinator")).hasSize(1);

            s.service.commit(r.id(), "carl"); // changes their mind via the channel post
            assertThat(r.committedBy()).contains("carl");
        }
    }

    @Nested
    class HrIntegration {

        @Test
        void hr_outage_is_retried_without_creating_duplicate_claims() {
            s.hr.failNextSubmissions(1);
            RefillRequest r = Fixture.completeRefill(s, OSLO, Money.of(600, "NOK"));
            assertThat(r.status()).isEqualTo(RefillStatus.PURCHASED);

            s.service.tick();
            s.service.tick();

            assertThat(r.status()).isEqualTo(RefillStatus.CLAIM_SUBMITTED);
            assertThat(s.hr.claims()).hasSize(1);
        }

        @Test
        void claims_pending_longer_than_ten_days_are_escalated_once() {
            Fixture.completeRefill(s, OSLO, Money.of(600, "NOK"));
            s.clock.advance(Duration.ofDays(9));
            s.service.tick();
            assertThat(s.chat.directMessagesTo("osl-coordinator")).isEmpty();

            s.clock.advance(Duration.ofDays(1));
            s.service.tick();
            s.service.tick();
            assertThat(s.chat.directMessagesTo("osl-coordinator")).hasSize(1);
        }

        @Test
        void rejected_claim_notifies_buyer_and_coordinator() {
            RefillRequest r = Fixture.completeRefill(s, OSLO, Money.of(600, "NOK"));
            s.service.onClaimDecision(r.claimId().orElseThrow(), false);
            assertThat(r.status()).isEqualTo(RefillStatus.CLAIM_REJECTED);
            assertThat(s.chat.directMessagesTo("osl-coordinator")).anyMatch(m -> m.contains("rejected"));
        }
    }
}
