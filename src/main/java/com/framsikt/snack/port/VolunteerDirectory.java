package com.framsikt.snack.port;

import com.framsikt.snack.domain.Volunteer;

import java.util.List;
import java.util.Optional;

/** All employees who opted in to refill snacks (from either office). */
public interface VolunteerDirectory {

    List<Volunteer> all();

    Optional<Volunteer> find(String employeeId);
}
