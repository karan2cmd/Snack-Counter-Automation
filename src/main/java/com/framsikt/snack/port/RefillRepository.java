package com.framsikt.snack.port;

import com.framsikt.snack.domain.RefillRequest;

import java.util.List;
import java.util.Optional;

/** Persistence port for refills. The in-memory adapter is used for the exercise. */
public interface RefillRepository {

    void save(RefillRequest refill);

    Optional<RefillRequest> findById(String id);

    Optional<RefillRequest> findByClaimId(String claimId);

    /** The refill for this office whose box is still empty, if any (there is at most one). */
    Optional<RefillRequest> findAwaitingPurchase(String officeId);

    List<RefillRequest> findAll();
}
