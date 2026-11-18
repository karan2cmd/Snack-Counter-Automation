package com.framsikt.snack.port;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Where is an employee working on a given day? In production this would come from the office
 * check-in / desk booking / leave calendar. Empty means remote, on holiday or otherwise unavailable.
 */
public interface AvailabilityProvider {

    Optional<String> officeOn(String employeeId, LocalDate date);
}
