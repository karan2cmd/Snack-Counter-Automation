package com.framsikt.snack.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One refill of one office's snack box. This is the aggregate root: every state change goes through
 * a method here that validates the transition and appends to the audit history.
 *
 * <p>Not thread-safe by itself; {@code RefillService} serialises all changes per office.
 */
public final class RefillRequest {

    private final String id;
    private final String officeId;
    private final String reportedBy;
    private final Instant reportedAt;

    private RefillStatus status = RefillStatus.UNASSIGNED;
    private String offeredTo;
    private Instant offerExpiresAt;
    private String committedBy;
    private Instant purchaseDeadline;
    private Money reservation;
    private Purchase purchase;
    private String claimId;
    private Instant claimSubmittedAt;
    private Instant claimResolvedAt;
    private boolean staleClaimAlertSent;
    /** People who declined or let this refill time out; they are not asked again for it. */
    private final Set<String> excluded = new LinkedHashSet<>();
    private final List<String> history = new ArrayList<>();

    public RefillRequest(String id, String officeId, String reportedBy, Instant reportedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.officeId = Objects.requireNonNull(officeId, "officeId");
        this.reportedBy = Objects.requireNonNull(reportedBy, "reportedBy");
        this.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt");
        log(reportedAt, "reported empty by " + reportedBy);
    }

    // ---- transitions -------------------------------------------------------------------------

    public void offerTo(String volunteerId, Instant expiresAt, Instant now) {
        moveTo(RefillStatus.OFFERED);
        clearHolder();
        offeredTo = volunteerId;
        offerExpiresAt = expiresAt;
        log(now, "offered to " + volunteerId + " until " + expiresAt);
    }

    public void commit(String volunteerId, Money reserved, Instant deadline, Instant now) {
        moveTo(RefillStatus.COMMITTED);
        clearHolder();
        committedBy = volunteerId;
        reservation = reserved;
        purchaseDeadline = deadline;
        log(now, volunteerId + " committed, reserved " + reserved + ", buy by " + deadline);
    }

    /** Removes the current offeree/committer (decline or timeout) and remembers not to ask them again. */
    public String releaseHolder(String reason, Instant now) {
        String holder = currentHolder().orElseThrow(
                () -> new InvalidStateTransitionException(status, RefillStatus.OFFERED));
        excluded.add(holder);
        clearHolder();
        log(now, holder + " released: " + reason);
        return holder;
    }

    public void markUnassigned(Instant now) {
        moveTo(RefillStatus.UNASSIGNED);
        clearHolder();
        log(now, "nobody available, escalated");
    }

    public void markBlockedByBudget(Instant now) {
        moveTo(RefillStatus.BLOCKED_BUDGET);
        clearHolder();
        log(now, "blocked: monthly budget exhausted");
    }

    public void recordPurchase(Purchase p, boolean needsApproval, Instant now) {
        moveTo(needsApproval ? RefillStatus.NEEDS_APPROVAL : RefillStatus.PURCHASED);
        purchase = Objects.requireNonNull(p, "purchase");
        reservation = null;
        purchaseDeadline = null;
        log(now, "purchased " + p.amount() + (needsApproval ? " (over budget, needs approval)" : ""));
    }

    public void approveOverBudget(String approverId, Instant now) {
        moveTo(RefillStatus.PURCHASED);
        log(now, "over-budget purchase approved by " + approverId);
    }

    public void claimSubmitted(String hrClaimId, Instant now) {
        moveTo(RefillStatus.CLAIM_SUBMITTED);
        claimId = hrClaimId;
        claimSubmittedAt = now;
        log(now, "HR claim " + hrClaimId + " submitted");
    }

    public void claimResolved(boolean approved, Instant now) {
        moveTo(approved ? RefillStatus.REIMBURSED : RefillStatus.CLAIM_REJECTED);
        claimResolvedAt = now;
        log(now, approved ? "reimbursed" : "claim rejected by HR");
    }

    public void markStaleClaimAlertSent(Instant now) {
        staleClaimAlertSent = true;
        log(now, "stale claim escalated");
    }

    private void moveTo(RefillStatus next) {
        if (!status.canMoveTo(next)) {
            throw new InvalidStateTransitionException(status, next);
        }
        status = next;
    }

    private void clearHolder() {
        offeredTo = null;
        offerExpiresAt = null;
        committedBy = null;
        purchaseDeadline = null;
        reservation = null;
    }

    private void log(Instant at, String event) {
        history.add(at + " " + event);
    }

    // ---- queries ------------------------------------------------------------------------------

    /** The person currently responsible: offeree while OFFERED, committer while COMMITTED. */
    public Optional<String> currentHolder() {
        return switch (status) {
            case OFFERED -> Optional.ofNullable(offeredTo);
            case COMMITTED -> Optional.ofNullable(committedBy);
            default -> Optional.empty();
        };
    }

    /** The money reserved against the budget while COMMITTED (nothing otherwise). */
    public Optional<Money> activeReservation() {
        return status == RefillStatus.COMMITTED ? Optional.ofNullable(reservation) : Optional.empty();
    }

    public String id() { return id; }
    public String officeId() { return officeId; }
    public String reportedBy() { return reportedBy; }
    public Instant reportedAt() { return reportedAt; }
    public RefillStatus status() { return status; }
    public Optional<String> offeredTo() { return Optional.ofNullable(offeredTo); }
    public Optional<Instant> offerExpiresAt() { return Optional.ofNullable(offerExpiresAt); }
    public Optional<String> committedBy() { return Optional.ofNullable(committedBy); }
    public Optional<Instant> purchaseDeadline() { return Optional.ofNullable(purchaseDeadline); }
    public Optional<Purchase> purchase() { return Optional.ofNullable(purchase); }
    public Optional<String> claimId() { return Optional.ofNullable(claimId); }
    public Optional<Instant> claimSubmittedAt() { return Optional.ofNullable(claimSubmittedAt); }
    public Optional<Instant> claimResolvedAt() { return Optional.ofNullable(claimResolvedAt); }
    public boolean staleClaimAlertSent() { return staleClaimAlertSent; }
    public Set<String> excluded() { return Collections.unmodifiableSet(excluded); }
    public List<String> history() { return Collections.unmodifiableList(history); }

    @Override
    public String toString() {
        return "Refill[" + id + ", " + officeId + ", " + status + "]";
    }
}
