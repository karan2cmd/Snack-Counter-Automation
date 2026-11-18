package com.framsikt.snack.port;

public class HrUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public HrUnavailableException(String message) {
        super(message);
    }
}
