package com.framsikt.snack.domain;

public class InvalidStateTransitionException extends SnackException {
    private static final long serialVersionUID = 1L;

    public InvalidStateTransitionException(RefillStatus from, RefillStatus to) {
        super("Refill cannot move from " + from + " to " + to);
    }
}
