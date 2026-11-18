package com.framsikt.snack.adapter;

import com.framsikt.snack.port.ClaimRequest;
import com.framsikt.snack.port.HrClient;
import com.framsikt.snack.port.HrUnavailableException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated external HR system. Idempotent on {@link ClaimRequest#idempotencyKey()} and can be told
 * to fail the next N calls to exercise retry logic. Approval decisions are fed back to the service
 * by the caller (in production: HR webhook or a polling job).
 */
public final class FakeHrSystem implements HrClient {

    public record StoredClaim(String claimId, ClaimRequest request) {}

    private final Map<String, StoredClaim> byKey = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger failuresToSimulate = new AtomicInteger();
    private final AtomicInteger submitCalls = new AtomicInteger();

    @Override
    public String submitClaim(ClaimRequest request) {
        submitCalls.incrementAndGet();
        if (failuresToSimulate.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new HrUnavailableException("HR system temporarily unavailable");
        }
        return byKey.computeIfAbsent(request.idempotencyKey(),
                key -> new StoredClaim("HR-" + sequence.incrementAndGet(), request)).claimId();
    }

    public void failNextSubmissions(int count) {
        failuresToSimulate.set(count);
    }

    public List<StoredClaim> claims() {
        return List.copyOf(byKey.values());
    }

    public int submitCalls() {
        return submitCalls.get();
    }
}
