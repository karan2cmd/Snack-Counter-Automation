package com.framsikt.snack.domain;

public class NotFoundException extends SnackException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String what, String id) {
        super(what + " not found: " + id);
    }
}
