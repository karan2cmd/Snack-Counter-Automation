package com.framsikt.snack.domain;

import java.util.Currency;

public class CurrencyMismatchException extends SnackException {
    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected " + expected + " but got " + actual);
    }
}
