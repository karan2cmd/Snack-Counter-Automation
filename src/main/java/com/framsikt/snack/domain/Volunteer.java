package com.framsikt.snack.domain;

import java.util.Objects;

/** An employee who has opted in to refill the snack box. Identified by their HR employee ID. */
public record Volunteer(String employeeId, String name, String homeOfficeId) {
    public Volunteer {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(homeOfficeId, "homeOfficeId");
    }
}
