package com.framsikt.snack.domain;

public class BudgetExceededException extends SnackException {
    private static final long serialVersionUID = 1L;

    public BudgetExceededException(String message) {
        super(message);
    }
}
