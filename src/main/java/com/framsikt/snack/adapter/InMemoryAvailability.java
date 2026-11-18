package com.framsikt.snack.adapter;

import com.framsikt.snack.port.AvailabilityProvider;
import com.framsikt.snack.port.VolunteerDirectory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Simulated calendar. By default a volunteer is in their home office every day; overrides mark them
 * away (holiday / remote) or visiting another office for a date range. Later overrides win.
 */
public final class InMemoryAvailability implements AvailabilityProvider {

    private record Entry(String employeeId, LocalDate from, LocalDate to, String officeIdOrNull) {
        boolean covers(String id, LocalDate d) {
            return employeeId.equals(id) && !d.isBefore(from) && !d.isAfter(to);
        }
    }

    private final VolunteerDirectory directory;
    private final List<Entry> overrides = new ArrayList<>();

    public InMemoryAvailability(VolunteerDirectory directory) {
        this.directory = directory;
    }

    public synchronized InMemoryAvailability away(String employeeId, LocalDate from, LocalDate to) {
        overrides.add(new Entry(employeeId, from, to, null));
        return this;
    }

    public synchronized InMemoryAvailability visiting(String employeeId, String officeId, LocalDate from, LocalDate to) {
        overrides.add(new Entry(employeeId, from, to, officeId));
        return this;
    }

    @Override
    public synchronized Optional<String> officeOn(String employeeId, LocalDate date) {
        for (int i = overrides.size() - 1; i >= 0; i--) {
            Entry o = overrides.get(i);
            if (o.covers(employeeId, date)) {
                return Optional.ofNullable(o.officeIdOrNull());
            }
        }
        return directory.find(employeeId).map(v -> v.homeOfficeId());
    }
}
