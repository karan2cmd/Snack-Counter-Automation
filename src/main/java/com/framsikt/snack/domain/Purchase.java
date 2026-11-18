package com.framsikt.snack.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/**
 * What the volunteer actually bought. The FX rate to the reporting currency is frozen at purchase
 * time so the monthly report never changes after the fact.
 */
public record Purchase(
        String buyerId,
        Money amount,
        String description,
        String receiptRef,
        Instant purchasedAt,
        Currency reportCurrency,
        BigDecimal rateToReportCurrency) {

    public Purchase {
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(receiptRef, "receiptRef");
        Objects.requireNonNull(purchasedAt, "purchasedAt");
        Objects.requireNonNull(reportCurrency, "reportCurrency");
        Objects.requireNonNull(rateToReportCurrency, "rateToReportCurrency");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Purchase amount must be positive");
        }
        description = description == null ? "" : description;
    }

    public Money amountInReportCurrency() {
        return amount.convertTo(reportCurrency, rateToReportCurrency);
    }
}
