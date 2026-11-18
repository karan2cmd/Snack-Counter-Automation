package com.framsikt.snack.adapter;

import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.port.RefillRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRefillRepository implements RefillRepository {

    private final Map<String, RefillRequest> refills = new ConcurrentHashMap<>();

    @Override
    public void save(RefillRequest refill) {
        refills.put(refill.id(), refill);
    }

    @Override
    public Optional<RefillRequest> findById(String id) {
        return Optional.ofNullable(refills.get(id));
    }

    @Override
    public Optional<RefillRequest> findByClaimId(String claimId) {
        return refills.values().stream()
                .filter(r -> r.claimId().filter(claimId::equals).isPresent())
                .findFirst();
    }

    @Override
    public Optional<RefillRequest> findAwaitingPurchase(String officeId) {
        return refills.values().stream()
                .filter(r -> r.officeId().equals(officeId) && r.status().isAwaitingPurchase())
                .findFirst();
    }

    @Override
    public List<RefillRequest> findAll() {
        return refills.values().stream()
                .sorted(Comparator.comparing(RefillRequest::reportedAt).thenComparing(RefillRequest::id))
                .toList();
    }
}
