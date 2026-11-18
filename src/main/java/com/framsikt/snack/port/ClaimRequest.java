package com.framsikt.snack.port;

import com.framsikt.snack.domain.Money;

import java.util.Objects;

/** Exactly the fields the HR system accepts, plus an idempotency key. */
public record ClaimRequest(
        String idempotencyKey,
        String employeeId,
        Money amount,
        String description,
        String receiptRef) {

    public ClaimRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(receiptRef, "receiptRef");
    }
}
