package com.framsikt.snack.domain;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;

/**
 * An office with its own snack box, budget and local currency.
 *
 * @param typicalMin   smallest sensible purchase; if less budget than this is left, we stop asking people to buy
 * @param typicalMax   per-refill spend cap told to the buyer (and reserved from the budget when they commit)
 * @param coordinatorId employee who receives escalations (nobody available, over-budget receipts, stale claims)
 */
public record Office(
        String id,
        String name,
        Currency currency,
        ZoneId zone,
        Money monthlyBudget,
        Money typicalMin,
        Money typicalMax,
        String coordinatorId) {

    public Office {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(coordinatorId, "coordinatorId");
        for (Money m : new Money[] {monthlyBudget, typicalMin, typicalMax}) {
            Objects.requireNonNull(m, "money");
            if (!m.currency().equals(currency)) {
                throw new CurrencyMismatchException(currency, m.currency());
            }
        }
        if (typicalMin.isGreaterThan(typicalMax)) {
            throw new IllegalArgumentException("typicalMin must be <= typicalMax");
        }
    }
}
