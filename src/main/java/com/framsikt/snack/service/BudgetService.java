package com.framsikt.snack.service;

import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.Office;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.port.RefillRepository;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Monthly budget per office, always in the office's own currency.
 *
 * <p>Budget state is <em>derived</em> from refills (no separate ledger to get out of sync):
 * <ul>
 *   <li><b>spent</b> = purchases made in that office during the calendar month (office's time zone)</li>
 *   <li><b>reserved</b> = spend caps held by volunteers who committed but have not bought yet</li>
 *   <li><b>remaining</b> = budget - spent - reserved</li>
 * </ul>
 * Reserving at commit time means the budget is checked <em>before</em> money is spent, not after.
 */
public final class BudgetService {

    public record Snapshot(Money budget, Money spent, Money reserved) {
        public Money remaining() {
            return budget.minus(spent).minus(reserved);
        }
    }

    private final SnackConfig config;
    private final RefillRepository refills;

    public BudgetService(SnackConfig config, RefillRepository refills) {
        this.config = Objects.requireNonNull(config);
        this.refills = Objects.requireNonNull(refills);
    }

    public Snapshot snapshot(String officeId, Instant at) {
        return snapshot(officeId, at, null);
    }

    /** @param ignoreRefillId refill whose own reservation should not count (used when it is being replaced) */
    public Snapshot snapshot(String officeId, Instant at, String ignoreRefillId) {
        Office office = config.office(officeId);
        YearMonth month = YearMonth.from(at.atZone(office.zone()));
        Money spent = Money.zero(office.currency());
        Money reserved = Money.zero(office.currency());
        for (RefillRequest r : refills.findAll()) {
            if (!r.officeId().equals(officeId) || r.id().equals(ignoreRefillId)) {
                continue;
            }
            if (r.status().hasPurchase() && r.purchase().isPresent()) {
                var p = r.purchase().get();
                if (YearMonth.from(p.purchasedAt().atZone(office.zone())).equals(month)) {
                    spent = spent.plus(p.amount());
                }
            }
            reserved = r.activeReservation().map(reserved::plus).orElse(reserved);
        }
        return new Snapshot(office.monthlyBudget(), spent, reserved);
    }

    /** Enough left for at least a minimal sensible purchase? */
    public boolean canAffordRefill(String officeId, Instant at) {
        Office office = config.office(officeId);
        return !snapshot(officeId, at).remaining().isLessThan(office.typicalMin());
    }

    /** What the next buyer may spend: the typical maximum, capped by what is left this month. */
    public Money spendCap(String officeId, Instant at) {
        Office office = config.office(officeId);
        return office.typicalMax().min(snapshot(officeId, at).remaining());
    }
}
