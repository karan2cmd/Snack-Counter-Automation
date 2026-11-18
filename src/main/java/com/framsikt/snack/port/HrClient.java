package com.framsikt.snack.port;

/**
 * Outbound port to the external HR system. Implementations must treat
 * {@link ClaimRequest#idempotencyKey()} as unique: re-sending the same key returns the existing
 * claim instead of creating a duplicate payout.
 */
public interface HrClient {

    /**
     * @return the HR system's claim id
     * @throws HrUnavailableException if the HR system cannot be reached (caller retries later)
     */
    String submitClaim(ClaimRequest request);
}
