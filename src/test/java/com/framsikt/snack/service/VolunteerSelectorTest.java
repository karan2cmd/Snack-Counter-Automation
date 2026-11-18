package com.framsikt.snack.service;

import com.framsikt.snack.Fixture;
import com.framsikt.snack.SnackSystem;
import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.Volunteer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;

import static com.framsikt.snack.service.SnackConfig.BENGALURU;
import static com.framsikt.snack.service.SnackConfig.OSLO;
import static org.assertj.core.api.Assertions.assertThat;

class VolunteerSelectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private SnackSystem s;

    @BeforeEach
    void setUp() {
        s = Fixture.system();
    }

    private RefillRequest newRefill(String officeId) {
        return new RefillRequest("probe", officeId, "x", s.clock.instant());
    }

    @Test
    void picks_by_employee_id_when_everyone_is_equal() {
        assertThat(s.selector.pickFor(newRefill(OSLO), s.clock.instant()))
                .map(Volunteer::employeeId).contains("anna");
    }

    @Test
    void spreads_the_work_people_with_fewer_recent_refills_go_first() {
        // anna (first alphabetically) does a refill and is reimbursed; next pick must not be anna
        RefillRequest r = Fixture.completeRefill(s, OSLO, Money.of(500, "NOK"));
        assertThat(r.purchase().orElseThrow().buyerId()).isEqualTo("anna");
        s.service.onClaimDecision(r.claimId().orElseThrow(), true);

        s.clock.advance(Duration.ofDays(2));
        assertThat(s.selector.pickFor(newRefill(OSLO), s.clock.instant()))
                .map(Volunteer::employeeId).contains("bjorn");
    }

    @Test
    void refills_older_than_the_fairness_window_no_longer_count() {
        RefillRequest r = Fixture.completeRefill(s, OSLO, Money.of(500, "NOK")); // anna
        s.service.onClaimDecision(r.claimId().orElseThrow(), true);
        Fixture.completeRefill(s, OSLO, Money.of(500, "NOK")); // bjorn
        Fixture.completeRefill(s, OSLO, Money.of(500, "NOK")); // carl

        s.clock.advance(Duration.ofDays(31));
        // all have 0 recent refills; anna has nothing outstanding, the others are still waiting on HR
        assertThat(s.selector.pickFor(newRefill(OSLO), s.clock.instant()))
                .map(Volunteer::employeeId).contains("anna");
    }

    @Test
    void skips_people_on_holiday_or_working_from_home() {
        s.availability.away("anna", TODAY, TODAY).away("bjorn", TODAY, TODAY);
        assertThat(s.selector.pickFor(newRefill(OSLO), s.clock.instant()))
                .map(Volunteer::employeeId).contains("carl");
    }

    @Test
    void a_visitor_can_refill_the_office_they_are_visiting_but_not_their_home_office() {
        s.availability.away("anna", TODAY, TODAY).away("bjorn", TODAY, TODAY).away("carl", TODAY, TODAY);
        s.availability.visiting("deepa", OSLO, TODAY, TODAY.plusDays(7));

        assertThat(s.selector.pickFor(newRefill(OSLO), s.clock.instant()))
                .map(Volunteer::employeeId).contains("deepa");
        assertThat(s.selector.pickFor(newRefill(BENGALURU), s.clock.instant()))
                .map(Volunteer::employeeId).contains("esha");
    }

    @Test
    void people_who_declined_this_refill_are_not_asked_again() {
        RefillRequest r = s.service.reportEmpty(OSLO, "x");
        s.service.decline(r.id(), "anna");
        assertThat(r.offeredTo()).contains("bjorn");
        s.service.decline(r.id(), "bjorn");
        assertThat(r.offeredTo()).contains("carl");
    }

    @Test
    void nobody_eligible_returns_empty() {
        s.availability.away("anna", TODAY, TODAY).away("bjorn", TODAY, TODAY).away("carl", TODAY, TODAY);
        assertThat(s.selector.pickFor(newRefill(OSLO), s.clock.instant())).isEmpty();
    }
}
