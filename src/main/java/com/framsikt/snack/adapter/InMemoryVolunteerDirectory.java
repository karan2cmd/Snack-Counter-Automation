package com.framsikt.snack.adapter;

import com.framsikt.snack.domain.Volunteer;
import com.framsikt.snack.port.VolunteerDirectory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryVolunteerDirectory implements VolunteerDirectory {

    private final Map<String, Volunteer> volunteers = new LinkedHashMap<>();

    public InMemoryVolunteerDirectory add(Volunteer v) {
        volunteers.put(v.employeeId(), v);
        return this;
    }

    @Override
    public synchronized List<Volunteer> all() {
        return List.copyOf(volunteers.values());
    }

    @Override
    public synchronized Optional<Volunteer> find(String employeeId) {
        return Optional.ofNullable(volunteers.get(employeeId));
    }
}
