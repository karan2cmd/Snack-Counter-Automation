package com.framsikt.snack.domain;

/** Thrown when someone tries to take a refill they are not allowed to take (e.g. already taken). */
public class RefillConflictException extends SnackException {
    private static final long serialVersionUID = 1L;

    public RefillConflictException(String message) {
        super(message);
    }
}
