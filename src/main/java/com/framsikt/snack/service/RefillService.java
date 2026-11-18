package com.framsikt.snack.service;

import com.framsikt.snack.domain.BudgetExceededException;
import com.framsikt.snack.domain.CurrencyMismatchException;
import com.framsikt.snack.domain.InvalidStateTransitionException;
import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.NotFoundException;
import com.framsikt.snack.domain.Office;
import com.framsikt.snack.domain.Purchase;
import com.framsikt.snack.domain.RefillConflictException;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.RefillStatus;
import com.framsikt.snack.domain.Volunteer;
import com.framsikt.snack.port.ChatNotifier;
import com.framsikt.snack.port.ClaimRequest;
import com.framsikt.snack.port.FxRateProvider;
import com.framsikt.snack.port.HrClient;
import com.framsikt.snack.port.HrUnavailableException;
import com.framsikt.snack.port.RefillRepository;
import com.framsikt.snack.port.VolunteerDirectory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Orchestrates the refill lifecycle: report empty → offer → commit → purchase → HR claim → reimbursed.
 *
 * <p><b>Concurrency:</b> every state change for an office runs under that office's lock, so
 * "check status then change it" is atomic. That is what guarantees one active refill per office
 * and that two people can never both commit to the same refill. (With a database this becomes a
 * conditional update / optimistic version check on the refill row.)
 *
 * <p><b>Time:</b> nothing happens on wall-clock threads. {@link #tick()} is called periodically by a
 * scheduler (e.g. every 5 minutes) and handles every timeout and retry, which keeps the logic
 * deterministic and testable with an injected {@link Clock}.
 */
public final class RefillService {

    private final SnackConfig config;
    private final RefillRepository refills;
    private final VolunteerDirectory directory;
    private final VolunteerSelector selector;
    private final BudgetService budget;
    private final ChatNotifier chat;
    private final HrClient hr;
    private final FxRateProvider fx;
    private final Clock clock;

    private final Map<String, ReentrantLock> officeLocks = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    public RefillService(SnackConfig config, RefillRepository refills, VolunteerDirectory directory,
                         VolunteerSelector selector, BudgetService budget, ChatNotifier chat,
                         HrClient hr, FxRateProvider fx, Clock clock) {
        this.config = Objects.requireNonNull(config);
        this.refills = Objects.requireNonNull(refills);
        this.directory = Objects.requireNonNull(directory);
        this.selector = Objects.requireNonNull(selector);
        this.budget = Objects.requireNonNull(budget);
        this.chat = Objects.requireNonNull(chat);
        this.hr = Objects.requireNonNull(hr);
        this.fx = Objects.requireNonNull(fx);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---- commands ---------------------------------------------------------------------------

    /**
     * Anyone (volunteer or not) reports the box is empty. If a refill is already open for this
     * office, the reporter is told who is on it and no second refill is created.
     */
    public RefillRequest reportEmpty(String officeId, String reporterId) {
        Office office = config.office(officeId);
        return withOfficeLock(officeId, () -> {
            Instant now = clock.instant();
            Optional<RefillRequest> open = refills.findAwaitingPurchase(officeId);
            if (open.isPresent()) {
                RefillRequest r = open.get();
                chat.sendDirect(reporterId, "Thanks! The " + office.name() + " snack box is already being handled"
                        + r.currentHolder().map(h -> " by " + h).orElse("") + ".");
                return r;
            }
            RefillRequest r = new RefillRequest("R-" + idSequence.incrementAndGet(), officeId, reporterId, now);
            assignNext(r, now, true);
            refills.save(r);
            return r;
        });
    }

    /**
     * Volunteer accepts. Allowed for the current offeree (before the offer expires), or for anyone
     * in the office when the refill is UNASSIGNED. Reserves the spend cap from the budget.
     *
     * @throws RefillConflictException if someone else holds or already took the refill
     */
    public RefillRequest commit(String refillId, String volunteerId) {
        Volunteer volunteer = directory.find(volunteerId)
                .orElseThrow(() -> new NotFoundException("Volunteer", volunteerId));
        RefillRequest r = find(refillId);
        Office office = config.office(r.officeId());
        return withOfficeLock(office.id(), () -> {
            Instant now = clock.instant();
            switch (r.status()) {
                case OFFERED -> {
                    if (!r.offeredTo().filter(volunteerId::equals).isPresent()) {
                        throw new RefillConflictException("Refill " + refillId + " is offered to someone else");
                    }
                    if (!now.isBefore(r.offerExpiresAt().orElseThrow())) {
                        throw new RefillConflictException("Offer for refill " + refillId + " has expired");
                    }
                }
                case UNASSIGNED -> {
                    if (!selector.isPresentAndFree(volunteerId, office.id(), now, refillId)) {
                        throw new RefillConflictException(
                                volunteer.name() + " must be in the " + office.name() + " office today to take this refill");
                    }
                }
                case COMMITTED -> throw new RefillConflictException(
                        "Refill " + refillId + " is already taken by " + r.committedBy().orElse("someone"));
                default -> throw new RefillConflictException("Refill " + refillId + " is " + r.status());
            }
            if (!budget.canAffordRefill(office.id(), now)) {
                throw new BudgetExceededException(office.name() + " snack budget is used up for this month");
            }
            Money cap = budget.spendCap(office.id(), now);
            Instant deadline = now.plus(config.purchaseTimeout());
            r.commit(volunteerId, cap, deadline, now);
            refills.save(r);
            chat.sendDirect(volunteerId, "Thanks for taking the " + office.name() + " refill! Spend up to " + cap
                    + " and upload the receipt by " + deadline + ". Reimbursement is filed for you automatically.");
            chat.postToOffice(office.id(), volunteer.name() + " is refilling the snack box - no need for anyone else to buy.");
            return r;
        });
    }

    /** Offeree says no; the refill moves to the next person immediately. */
    public RefillRequest decline(String refillId, String volunteerId) {
        RefillRequest r = find(refillId);
        return withOfficeLock(r.officeId(), () -> {
            if (r.status() != RefillStatus.OFFERED || !r.offeredTo().filter(volunteerId::equals).isPresent()) {
                throw new RefillConflictException("Refill " + refillId + " is not offered to " + volunteerId);
            }
            Instant now = clock.instant();
            r.releaseHolder("declined", now);
            assignNext(r, now, false);
            refills.save(r);
            return r;
        });
    }

    /**
     * Buyer uploads the receipt. Purchases within the remaining budget go straight to HR; anything
     * above what is left this month waits for the office coordinator's approval.
     */
    public RefillRequest recordPurchase(String refillId, String buyerId, Money amount,
                                        String description, String receiptRef) {
        RefillRequest r = find(refillId);
        Office office = config.office(r.officeId());
        return withOfficeLock(office.id(), () -> {
            if (r.status() != RefillStatus.COMMITTED) {
                throw new InvalidStateTransitionException(r.status(), RefillStatus.PURCHASED);
            }
            if (!r.committedBy().filter(buyerId::equals).isPresent()) {
                throw new RefillConflictException("Refill " + refillId + " was committed by someone else");
            }
            if (!amount.currency().equals(office.currency())) {
                throw new CurrencyMismatchException(office.currency(), amount.currency());
            }
            Instant now = clock.instant();
            Money remaining = budget.snapshot(office.id(), now, r.id()).remaining();
            boolean overBudget = amount.isGreaterThan(remaining);
            Purchase purchase = new Purchase(buyerId, amount, description, receiptRef, now,
                    config.reportCurrency(), fx.rate(office.currency(), config.reportCurrency()));
            r.recordPurchase(purchase, overBudget, now);
            if (overBudget) {
                chat.sendDirect(office.coordinatorId(), "Approval needed: " + buyerId + " spent " + amount
                        + " on refill " + r.id() + " but only " + remaining + " was left this month.");
                chat.sendDirect(buyerId, "Receipt received. It is above this month's remaining budget, so the "
                        + office.name() + " coordinator will review it before it goes to HR.");
            } else {
                trySubmitClaim(r, now);
            }
            refills.save(r);
            return r;
        });
    }

    /** Office coordinator approves a purchase that went over the monthly budget. */
    public RefillRequest approveOverBudget(String refillId, String approverId) {
        RefillRequest r = find(refillId);
        Office office = config.office(r.officeId());
        return withOfficeLock(office.id(), () -> {
            if (!office.coordinatorId().equals(approverId)) {
                throw new RefillConflictException(approverId + " is not the " + office.name() + " coordinator");
            }
            Instant now = clock.instant();
            r.approveOverBudget(approverId, now);
            trySubmitClaim(r, now);
            refills.save(r);
            return r;
        });
    }

    /** HR decided on a claim (in production: HR webhook or polling job). */
    public RefillRequest onClaimDecision(String claimId, boolean approved) {
        RefillRequest r = refills.findByClaimId(claimId)
                .orElseThrow(() -> new NotFoundException("Claim", claimId));
        return withOfficeLock(r.officeId(), () -> {
            Instant now = clock.instant();
            r.claimResolved(approved, now);
            refills.save(r);
            String buyer = r.purchase().orElseThrow().buyerId();
            chat.sendDirect(buyer, approved
                    ? "Your snack claim " + claimId + " was approved - reimbursement is on its way."
                    : "Your snack claim " + claimId + " was rejected by HR. The office coordinator has been told.");
            if (!approved) {
                chat.sendDirect(config.office(r.officeId()).coordinatorId(),
                        "HR rejected snack claim " + claimId + " for " + buyer + ". Please follow up.");
            }
            return r;
        });
    }

    /**
     * Periodic job: expire offers and commitments, retry assignments and HR submissions,
     * escalate claims HR has sat on for too long.
     */
    public void tick() {
        for (RefillRequest snapshot : refills.findAll()) {
            withOfficeLock(snapshot.officeId(), () -> {
                RefillRequest r = find(snapshot.id());
                processTimeouts(r, clock.instant());
                refills.save(r);
                return r;
            });
        }
    }

    // ---- queries ----------------------------------------------------------------------------

    public RefillRequest find(String refillId) {
        return refills.findById(refillId).orElseThrow(() -> new NotFoundException("Refill", refillId));
    }

    // ---- internals --------------------------------------------------------------------------

    private void processTimeouts(RefillRequest r, Instant now) {
        switch (r.status()) {
            case OFFERED -> {
                if (!now.isBefore(r.offerExpiresAt().orElseThrow())) {
                    String holder = r.releaseHolder("did not respond in time", now);
                    chat.sendDirect(holder, "No worries - the snack refill has been passed to someone else.");
                    assignNext(r, now, false);
                }
            }
            case COMMITTED -> {
                if (!now.isBefore(r.purchaseDeadline().orElseThrow())) {
                    String holder = r.releaseHolder("no receipt before deadline", now);
                    chat.sendDirect(holder, "The snack refill was handed to someone else because no receipt arrived in time. "
                            + "If you already bought snacks, please contact the office coordinator.");
                    assignNext(r, now, false);
                }
            }
            case UNASSIGNED, BLOCKED_BUDGET -> assignNext(r, now, false);
            case PURCHASED -> trySubmitClaim(r, now);
            case CLAIM_SUBMITTED -> {
                Duration waiting = Duration.between(r.claimSubmittedAt().orElseThrow(), now);
                if (!r.staleClaimAlertSent() && waiting.compareTo(config.staleClaimAfter()) >= 0) {
                    chat.sendDirect(config.office(r.officeId()).coordinatorId(), "HR claim " + r.claimId().orElse("?")
                            + " has been pending " + waiting.toDays() + " days. Please chase HR.");
                    r.markStaleClaimAlertSent(now);
                }
            }
            default -> { /* terminal or waiting for a person */ }
        }
    }

    /**
     * Finds the next person to ask. Notifications for "blocked" / "nobody available" are sent only
     * when the refill first enters that state, so the periodic retry does not spam the channel.
     */
    private void assignNext(RefillRequest r, Instant now, boolean isNew) {
        Office office = config.office(r.officeId());
        RefillStatus before = r.status();

        if (!budget.canAffordRefill(office.id(), now)) {
            if (isNew || before != RefillStatus.BLOCKED_BUDGET) {
                r.markBlockedByBudget(now);
                chat.sendDirect(office.coordinatorId(), office.name() + " snack budget for this month is used up; "
                        + "refill " + r.id() + " is on hold until next month or until you raise the budget.");
                chat.postToOffice(office.id(), "Snack budget for this month is used up - please don't buy snacks; "
                        + "the box will be refilled next month.");
            }
            return;
        }

        Optional<Volunteer> next = selector.pickFor(r, now);
        if (next.isPresent()) {
            Volunteer v = next.get();
            Instant expires = now.plus(config.offerTimeout());
            r.offerTo(v.employeeId(), expires, now);
            chat.sendDirect(v.employeeId(), "Hi " + v.name() + ", the " + office.name() + " snack box is empty and it's your turn. "
                    + "Can you refill it today (budget up to " + budget.spendCap(office.id(), now) + ")? "
                    + "Reply accept or decline before " + expires + ".");
            return;
        }

        if (isNew || before != RefillStatus.UNASSIGNED) {
            r.markUnassigned(now);
            chat.sendDirect(office.coordinatorId(), "Nobody is available to refill the " + office.name()
                    + " snack box (refill " + r.id() + ").");
            chat.postToOffice(office.id(), "The snack box is empty and no volunteer is available today. "
                    + "If you are in the office, you can take refill " + r.id() + ".");
        }
    }

    private void trySubmitClaim(RefillRequest r, Instant now) {
        Purchase p = r.purchase().orElseThrow();
        Office office = config.office(r.officeId());
        String description = "Snack refill, " + office.name() + " office, "
                + LocalDate.ofInstant(p.purchasedAt(), office.zone())
                + (p.description().isBlank() ? "" : ": " + p.description());
        ClaimRequest request = new ClaimRequest(r.id(), p.buyerId(), p.amount(), description, p.receiptRef());
        try {
            String claimId = hr.submitClaim(request);
            r.claimSubmitted(claimId, now);
            chat.sendDirect(p.buyerId(), "Your reimbursement claim " + claimId + " for " + p.amount()
                    + " has been filed with HR (usually 1-10 days). Thank you!");
        } catch (HrUnavailableException e) {
            // Stay in PURCHASED; tick() retries. The idempotency key (refill id) prevents duplicates.
        }
    }

    private <T> T withOfficeLock(String officeId, Supplier<T> action) {
        ReentrantLock lock = officeLocks.computeIfAbsent(officeId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
