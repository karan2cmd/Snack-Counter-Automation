package com.framsikt.snack;

import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.Volunteer;

import java.time.Instant;

import static com.framsikt.snack.service.SnackConfig.BENGALURU;
import static com.framsikt.snack.service.SnackConfig.OSLO;

/** Shared test setup: a fresh system with a few volunteers in each office. */
public final class Fixture {

    public static final Instant MONDAY_9AM_OSLO = Instant.parse("2026-11-02T08:00:00Z");

    private Fixture() {}

    public static SnackSystem system() {
        SnackSystem s = SnackSystem.withDefaults(MONDAY_9AM_OSLO);
        s.volunteers.add(new Volunteer("anna", "Anna", OSLO))
                .add(new Volunteer("bjorn", "Bjorn", OSLO))
                .add(new Volunteer("carl", "Carl", OSLO))
                .add(new Volunteer("deepa", "Deepa", BENGALURU))
                .add(new Volunteer("esha", "Esha", BENGALURU));
        return s;
    }

    /** Runs a full refill (report → commit by whoever is offered → purchase) and returns it. */
    public static RefillRequest completeRefill(SnackSystem s, String officeId, Money amount) {
        RefillRequest r = s.service.reportEmpty(officeId, "reporter");
        String buyer = r.offeredTo().orElseThrow();
        s.service.commit(r.id(), buyer);
        return s.service.recordPurchase(r.id(), buyer, amount, "snacks", "receipt.jpg");
    }
}
