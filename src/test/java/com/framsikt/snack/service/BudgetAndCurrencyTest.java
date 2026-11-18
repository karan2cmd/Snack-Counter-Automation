package com.framsikt.snack.service;

import com.framsikt.snack.Fixture;
import com.framsikt.snack.SnackSystem;
import com.framsikt.snack.domain.CurrencyMismatchException;
import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.RefillConflictException;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.RefillStatus;
import com.framsikt.snack.port.ClaimRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static com.framsikt.snack.service.SnackConfig.BENGALURU;
import static com.framsikt.snack.service.SnackConfig.OSLO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetAndCurrencyTest {

    private SnackSystem s;

    @BeforeEach
    void setUp() {
        s = Fixture.system();
    }

    @Test
    void committing_reserves_the_spend_cap_before_any_money_is_spent() {
        RefillRequest r = s.service.reportEmpty(OSLO, "x");
        s.service.commit(r.id(), r.offeredTo().orElseThrow());

        BudgetService.Snapshot snap = s.budget.snapshot(OSLO, s.clock.instant());
        assertThat(snap.reserved()).isEqualTo(Money.of(1200, "NOK"));
        assertThat(snap.remaining()).isEqualTo(Money.of(4800, "NOK"));
    }

    @Test
    void reservation_is_replaced_by_actual_spend() {
        Fixture.completeRefill(s, OSLO, Money.of(700, "NOK"));
        BudgetService.Snapshot snap = s.budget.snapshot(OSLO, s.clock.instant());
        assertThat(snap.spent()).isEqualTo(Money.of(700, "NOK"));
        assertThat(snap.reserved()).isEqualTo(Money.of(0, "NOK"));
        assertThat(snap.remaining()).isEqualTo(Money.of(5300, "NOK"));
    }

    @Test
    void spend_cap_shrinks_to_what_is_left_and_blocks_when_below_a_minimal_purchase() {
        for (int i = 0; i < 4; i++) {
            Fixture.completeRefill(s, OSLO, Money.of(1200, "NOK")); // 4800 spent
            s.clock.advance(Duration.ofHours(1));
        }
        assertThat(s.budget.spendCap(OSLO, s.clock.instant())).isEqualTo(Money.of(1200, "NOK"));
        Fixture.completeRefill(s, OSLO, Money.of(950, "NOK")); // 5750 spent, 250 left < 300 minimum

        assertThat(s.budget.canAffordRefill(OSLO, s.clock.instant())).isFalse();
        RefillRequest blocked = s.service.reportEmpty(OSLO, "x");
        assertThat(blocked.status()).isEqualTo(RefillStatus.BLOCKED_BUDGET);
        assertThat(s.chat.channelMessages(OSLO)).anyMatch(m -> m.contains("please don't buy"));
        assertThatThrownBy(() -> s.service.commit(blocked.id(), "anna"))
                .isInstanceOf(RefillConflictException.class);
    }

    @Test
    void cap_is_limited_by_remaining_budget() {
        for (int i = 0; i < 4; i++) {
            Fixture.completeRefill(s, OSLO, Money.of(1200, "NOK"));
        }
        Fixture.completeRefill(s, OSLO, Money.of(500, "NOK")); // 5300 spent, 700 left
        assertThat(s.budget.spendCap(OSLO, s.clock.instant())).isEqualTo(Money.of(700, "NOK"));
    }

    @Test
    void blocked_refill_resumes_automatically_when_the_new_month_starts() {
        for (int i = 0; i < 5; i++) {
            Fixture.completeRefill(s, OSLO, Money.of(1200, "NOK")); // full 6000 used
        }
        RefillRequest r = s.service.reportEmpty(OSLO, "x");
        assertThat(r.status()).isEqualTo(RefillStatus.BLOCKED_BUDGET);

        s.clock.set(Instant.parse("2026-11-30T23:30:00Z")); // 00:30 on 1 Dec in Oslo
        s.service.tick();
        assertThat(r.status()).isEqualTo(RefillStatus.OFFERED);
    }

    @Test
    void purchase_above_remaining_budget_needs_coordinator_approval_before_hr() {
        for (int i = 0; i < 4; i++) {
            Fixture.completeRefill(s, OSLO, Money.of(1200, "NOK"));
        }
        Fixture.completeRefill(s, OSLO, Money.of(700, "NOK")); // 5500 spent, 500 left
        RefillRequest r = s.service.reportEmpty(OSLO, "x");
        String buyer = r.offeredTo().orElseThrow();
        s.service.commit(r.id(), buyer);
        int claimsBefore = s.hr.claims().size();

        s.service.recordPurchase(r.id(), buyer, Money.of(650, "NOK"), "", "rcpt");
        assertThat(r.status()).isEqualTo(RefillStatus.NEEDS_APPROVAL);
        assertThat(s.hr.claims()).hasSize(claimsBefore);

        assertThatThrownBy(() -> s.service.approveOverBudget(r.id(), buyer))
                .isInstanceOf(RefillConflictException.class);
        s.service.approveOverBudget(r.id(), "osl-coordinator");
        assertThat(r.status()).isEqualTo(RefillStatus.CLAIM_SUBMITTED);
    }

    @Test
    void visitor_purchase_is_charged_to_the_host_office_in_host_currency() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        s.availability.away("deepa", today, today).away("esha", today, today)
                .visiting("anna", BENGALURU, today, today.plusDays(7)); // Oslo employee in Bengaluru

        RefillRequest r = s.service.reportEmpty(BENGALURU, "x");
        assertThat(r.offeredTo()).contains("anna");
        s.service.commit(r.id(), "anna");
        s.service.recordPurchase(r.id(), "anna", Money.of(2000, "INR"), "", "rcpt");

        assertThat(s.budget.snapshot(BENGALURU, s.clock.instant()).spent()).isEqualTo(Money.of(2000, "INR"));
        assertThat(s.budget.snapshot(OSLO, s.clock.instant()).spent()).isEqualTo(Money.of(0, "NOK"));
        ClaimRequest claim = s.hr.claims().get(0).request();
        assertThat(claim.employeeId()).isEqualTo("anna");
        assertThat(claim.amount()).isEqualTo(Money.of(2000, "INR")); // HR pays out in anna's NOK payroll
    }

    @Test
    void receipt_in_the_wrong_currency_is_rejected() {
        RefillRequest r = s.service.reportEmpty(OSLO, "x");
        String buyer = r.offeredTo().orElseThrow();
        s.service.commit(r.id(), buyer);
        assertThatThrownBy(() -> s.service.recordPurchase(r.id(), buyer, Money.of(500, "INR"), "", "rcpt"))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void monthly_report_totals_are_correct_per_office_and_combined() {
        Fixture.completeRefill(s, OSLO, Money.of(800, "NOK"));
        Fixture.completeRefill(s, OSLO, Money.of("450.50", "NOK"));
        Fixture.completeRefill(s, BENGALURU, Money.of(2400, "INR"));
        Fixture.completeRefill(s, BENGALURU, Money.of(1600, "INR"));

        ReportService.MonthlyReport report = s.reports.monthly(YearMonth.of(2026, 11));
        ReportService.OfficeLine blr = report.offices().get(0);
        ReportService.OfficeLine osl = report.offices().get(1);

        assertThat(osl.spent()).isEqualTo(Money.of("1250.50", "NOK"));
        assertThat(osl.refills()).isEqualTo(2);
        assertThat(blr.spent()).isEqualTo(Money.of(4000, "INR"));
        assertThat(blr.spentInReportCurrency()).isEqualTo(Money.of(500, "NOK")); // 4000 * 0.125
        assertThat(blr.utilisationPercent()).isEqualByComparingTo("26.7");
        assertThat(report.combinedTotal()).isEqualTo(Money.of("1750.50", "NOK"));
    }

    @Test
    void report_uses_the_rate_frozen_at_purchase_time() {
        Fixture.completeRefill(s, BENGALURU, Money.of(800, "INR")); // 100 NOK at 0.125
        s.fx.withRate(java.util.Currency.getInstance("INR"), "0.5"); // rate changes later
        assertThat(s.reports.monthly(YearMonth.of(2026, 11)).combinedTotal()).isEqualTo(Money.of(100, "NOK"));
    }

    @Test
    void purchases_are_assigned_to_months_in_the_office_time_zone() {
        // 31 Oct 23:30 UTC is already 1 Nov 05:00 in Bengaluru
        s.clock.set(Instant.parse("2026-10-31T23:30:00Z"));
        LocalDate nov1 = LocalDate.of(2026, 11, 1);
        s.availability.visiting("deepa", BENGALURU, nov1, nov1);
        Fixture.completeRefill(s, BENGALURU, Money.of(1000, "INR"));

        assertThat(s.reports.monthly(YearMonth.of(2026, 10)).combinedTotal()).isEqualTo(Money.of(0, "NOK"));
        assertThat(s.reports.monthly(YearMonth.of(2026, 11)).combinedTotal()).isEqualTo(Money.of(125, "NOK"));
    }
}
