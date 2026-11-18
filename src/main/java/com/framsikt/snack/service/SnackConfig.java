package com.framsikt.snack.service;

import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.NotFoundException;
import com.framsikt.snack.domain.Office;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * All tunable business rules in one place.
 *
 * @param offerTimeout    how long an offeree has to accept before the refill moves on
 * @param purchaseTimeout how long a committed buyer has to upload a receipt
 * @param staleClaimAfter HR claims older than this are escalated (HR's SLA is 10 days)
 * @param fairnessWindow  how far back "refills done recently" is counted when choosing who buys
 * @param reportCurrency  currency of the combined management report
 */
public record SnackConfig(
        Map<String, Office> offices,
        Duration offerTimeout,
        Duration purchaseTimeout,
        Duration staleClaimAfter,
        Duration fairnessWindow,
        Currency reportCurrency) {

    public static final String OSLO = "OSL";
    public static final String BENGALURU = "BLR";

    public SnackConfig {
        offices = Map.copyOf(Objects.requireNonNull(offices, "offices"));
        Objects.requireNonNull(offerTimeout, "offerTimeout");
        Objects.requireNonNull(purchaseTimeout, "purchaseTimeout");
        Objects.requireNonNull(staleClaimAfter, "staleClaimAfter");
        Objects.requireNonNull(fairnessWindow, "fairnessWindow");
        Objects.requireNonNull(reportCurrency, "reportCurrency");
    }

    /** The two offices from the brief, with their budgets and typical purchase ranges. */
    public static SnackConfig defaults() {
        Office oslo = new Office(OSLO, "Oslo", Currency.getInstance("NOK"), ZoneId.of("Europe/Oslo"),
                Money.of(6_000, "NOK"), Money.of(300, "NOK"), Money.of(1_200, "NOK"), "osl-coordinator");
        Office blr = new Office(BENGALURU, "Bengaluru", Currency.getInstance("INR"), ZoneId.of("Asia/Kolkata"),
                Money.of(15_000, "INR"), Money.of(500, "INR"), Money.of(3_000, "INR"), "blr-coordinator");
        return new SnackConfig(
                List.of(oslo, blr).stream().collect(Collectors.toMap(Office::id, Function.identity())),
                Duration.ofHours(4),
                Duration.ofHours(24),
                Duration.ofDays(10),
                Duration.ofDays(30),
                Currency.getInstance("NOK"));
    }

    public Office office(String officeId) {
        Office o = offices.get(officeId);
        if (o == null) {
            throw new NotFoundException("Office", officeId);
        }
        return o;
    }
}
