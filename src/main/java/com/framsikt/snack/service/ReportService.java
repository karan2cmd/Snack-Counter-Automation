package com.framsikt.snack.service;

import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.Office;
import com.framsikt.snack.domain.Purchase;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.port.RefillRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * The single monthly report management asked for. Each office is reported in its own currency;
 * the combined total uses the FX rate frozen on each purchase, so the numbers never drift.
 */
public final class ReportService {

    public record OfficeLine(
            Office office,
            Money spent,
            Money budget,
            BigDecimal utilisationPercent,
            int refills,
            Money spentInReportCurrency,
            Money awaitingReimbursement,
            OptionalDouble avgHoursEmptyToRefilled,
            OptionalDouble avgDaysToReimburse,
            Map<String, Long> refillsPerVolunteer) {}

    public record MonthlyReport(YearMonth month, List<OfficeLine> offices, Money combinedTotal) {

        public String toText() {
            StringBuilder sb = new StringBuilder("Snack spending report " + month + "\n");
            for (OfficeLine l : offices) {
                sb.append(String.format("  %-10s spent %s of %s (%s%%), %d refills, = %s%n",
                        l.office().name(), l.spent(), l.budget(), l.utilisationPercent(), l.refills(),
                        l.spentInReportCurrency()));
                sb.append(String.format("             awaiting reimbursement %s, avg empty->refilled %s h, avg reimbursement %s days%n",
                        l.awaitingReimbursement(), fmt(l.avgHoursEmptyToRefilled()), fmt(l.avgDaysToReimburse())));
                sb.append("             refills per volunteer ").append(l.refillsPerVolunteer()).append('\n');
            }
            sb.append("  TOTAL (both offices): ").append(combinedTotal).append('\n');
            return sb.toString();
        }

        private static String fmt(OptionalDouble d) {
            return d.isPresent() ? String.format("%.1f", d.getAsDouble()) : "-";
        }
    }

    private final SnackConfig config;
    private final RefillRepository refills;

    public ReportService(SnackConfig config, RefillRepository refills) {
        this.config = Objects.requireNonNull(config);
        this.refills = Objects.requireNonNull(refills);
    }

    public MonthlyReport monthly(YearMonth month) {
        List<RefillRequest> all = refills.findAll();
        List<OfficeLine> lines = new ArrayList<>();
        Money combined = Money.zero(config.reportCurrency());

        List<Office> offices = config.offices().values().stream()
                .sorted(Comparator.comparing(Office::id)).toList();
        for (Office office : offices) {
            Money spent = Money.zero(office.currency());
            Money inReport = Money.zero(config.reportCurrency());
            Money awaiting = Money.zero(office.currency());
            int count = 0;
            List<Double> hoursToRefill = new ArrayList<>();
            List<Double> daysToReimburse = new ArrayList<>();
            Map<String, Long> perVolunteer = new TreeMap<>();

            for (RefillRequest r : all) {
                if (!r.officeId().equals(office.id()) || r.purchase().isEmpty()) {
                    continue;
                }
                Purchase p = r.purchase().get();
                if (!YearMonth.from(p.purchasedAt().atZone(office.zone())).equals(month)) {
                    continue;
                }
                count++;
                spent = spent.plus(p.amount());
                inReport = inReport.plus(p.amountInReportCurrency());
                if (r.status().isReimbursementOutstanding()) {
                    awaiting = awaiting.plus(p.amount());
                }
                perVolunteer.merge(p.buyerId(), 1L, Long::sum);
                hoursToRefill.add(Duration.between(r.reportedAt(), p.purchasedAt()).toMinutes() / 60.0);
                if (r.claimSubmittedAt().isPresent() && r.claimResolvedAt().isPresent()) {
                    daysToReimburse.add(Duration.between(r.claimSubmittedAt().get(), r.claimResolvedAt().get()).toHours() / 24.0);
                }
            }
            BigDecimal utilisation = spent.amount()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(office.monthlyBudget().amount(), 1, RoundingMode.HALF_UP);
            lines.add(new OfficeLine(office, spent, office.monthlyBudget(), utilisation, count, inReport, awaiting,
                    hoursToRefill.stream().mapToDouble(Double::doubleValue).average(),
                    daysToReimburse.stream().mapToDouble(Double::doubleValue).average(),
                    perVolunteer));
            combined = combined.plus(inReport);
        }
        return new MonthlyReport(month, List.copyOf(lines), combined);
    }
}
