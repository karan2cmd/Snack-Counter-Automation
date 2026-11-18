package com.framsikt.snack.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one refill, from "box is empty" to "volunteer is reimbursed".
 *
 * <pre>
 *  report empty
 *      |
 *      v
 *  OFFERED --decline/timeout--> OFFERED (next person) / UNASSIGNED / BLOCKED_BUDGET
 *      | commit (reserves budget)
 *      v
 *  COMMITTED --purchase timeout--> OFFERED (next person)
 *      | receipt uploaded
 *      +--over remaining budget--> NEEDS_APPROVAL --coordinator approves--+
 *      v                                                                  |
 *  PURCHASED  <-----------------------------------------------------------+
 *      | claim sent to HR (retried if HR is down)
 *      v
 *  CLAIM_SUBMITTED --HR decision--> REIMBURSED | CLAIM_REJECTED
 * </pre>
 */
public enum RefillStatus {
    UNASSIGNED,
    BLOCKED_BUDGET,
    OFFERED,
    COMMITTED,
    NEEDS_APPROVAL,
    PURCHASED,
    CLAIM_SUBMITTED,
    REIMBURSED,
    CLAIM_REJECTED;

    private Set<RefillStatus> allowedNext() {
        return switch (this) {
            case UNASSIGNED -> EnumSet.of(UNASSIGNED, OFFERED, COMMITTED, BLOCKED_BUDGET);
            case BLOCKED_BUDGET -> EnumSet.of(BLOCKED_BUDGET, OFFERED, UNASSIGNED);
            case OFFERED -> EnumSet.of(OFFERED, COMMITTED, UNASSIGNED, BLOCKED_BUDGET);
            case COMMITTED -> EnumSet.of(PURCHASED, NEEDS_APPROVAL, OFFERED, UNASSIGNED, BLOCKED_BUDGET);
            case NEEDS_APPROVAL -> EnumSet.of(PURCHASED);
            case PURCHASED -> EnumSet.of(CLAIM_SUBMITTED);
            case CLAIM_SUBMITTED -> EnumSet.of(REIMBURSED, CLAIM_REJECTED);
            case REIMBURSED, CLAIM_REJECTED -> EnumSet.noneOf(RefillStatus.class);
        };
    }

    public boolean canMoveTo(RefillStatus next) {
        return allowedNext().contains(next);
    }

    /** True while the box is still empty and nobody has bought snacks yet. */
    public boolean isAwaitingPurchase() {
        return this == UNASSIGNED || this == BLOCKED_BUDGET || this == OFFERED || this == COMMITTED;
    }

    /** True once money has been spent (whether or not it has been reimbursed yet). */
    public boolean hasPurchase() {
        return this == NEEDS_APPROVAL || this == PURCHASED || this == CLAIM_SUBMITTED
                || this == REIMBURSED || this == CLAIM_REJECTED;
    }

    /** True while the buyer is still out of pocket. */
    public boolean isReimbursementOutstanding() {
        return this == NEEDS_APPROVAL || this == PURCHASED || this == CLAIM_SUBMITTED;
    }
}
