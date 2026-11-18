package com.framsikt.snack.domain;

/** Base type for all business-rule violations in the snack domain. */
public class SnackException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SnackException(String message) {
        super(message);
    }
}
