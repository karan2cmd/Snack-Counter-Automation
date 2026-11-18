package com.framsikt.snack.service;

import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.Office;
import com.framsikt.snack.domain.Purchase;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.Volunteer;
import com.framsikt.snack.port.AvailabilityProvider;
import com.framsikt.snack.port.RefillRepository;
import com.framsikt.snack.port.VolunteerDirectory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides who is asked to buy. Rules, in order:
 * <ol>
 *   <li>Eligible: physically in this office today (home office <em>or visiting</em>), not on holiday or
 *       remote, has not already declined / timed out on this refill, and is not already holding
 *       another open refill.</li>
 *   <li>Fairness: fewest refills in the recent window first.</li>
 *   <li>Then whoever has the least money still waiting on reimbursement (don't pile on people who
 *       are already out of pocket).</li>
 *   <li>Then whoever refilled longest ago, then employee id (deterministic, testable).</li>
 * </ol>
 */
public final class VolunteerSelector {

    record Candidate(Volunteer volunteer, long recentRefills, Money outstanding, Instant lastRefill) {}

    private final SnackConfig config;
    private final VolunteerDirectory directory;
    private final AvailabilityProvider availability;
    private final RefillRepository refills;

    public VolunteerSelector(SnackConfig config, VolunteerDirectory directory,
                             AvailabilityProvider availability, RefillRepository refills) {
        this.config = Objects.requireNonNull(config);
        this.directory = Objects.requireNonNull(directory);
        this.availability = Objects.requireNonNull(availability);
        this.refills = Objects.requireNonNull(refills);
    }

    public Optional<Volunteer> pickFor(RefillRequest refill, Instant now) {
        return rankedCandidates(refill, now).stream().findFirst().map(Candidate::volunteer);
    }

    /** True if this person may take the refill themselves (used for self-volunteering). */
    public boolean isPresentAndFree(String employeeId, String officeId, Instant now, String refillId) {
        return isInOffice(employeeId, officeId, now) && !holdsOtherOpenRefill(employeeId, refillId);
    }

    List<Candidate> rankedCandidates(RefillRequest refill, Instant now) {
        List<RefillRequest> all = refills.findAll();
        Set<String> busy = all.stream()
                .filter(r -> !r.id().equals(refill.id()))
                .flatMap(r -> r.currentHolder().stream())
                .collect(Collectors.toSet());

        return directory.all().stream()
                .filter(v -> !refill.excluded().contains(v.employeeId()))
                .filter(v -> !busy.contains(v.employeeId()))
                .filter(v -> isInOffice(v.employeeId(), refill.officeId(), now))
                .map(v -> toCandidate(v, all, now))
                .sorted(Comparator.comparingLong(Candidate::recentRefills)
                        .thenComparing(c -> c.outstanding().amount())
                        .thenComparing(Candidate::lastRefill)
                        .thenComparing(c -> c.volunteer().employeeId()))
                .toList();
    }

    private boolean isInOffice(String employeeId, String officeId, Instant now) {
        Office office = config.office(officeId);
        LocalDate localDate = LocalDate.ofInstant(now, office.zone());
        return availability.officeOn(employeeId, localDate).filter(officeId::equals).isPresent();
    }

    private boolean holdsOtherOpenRefill(String employeeId, String refillId) {
        return refills.findAll().stream()
                .filter(r -> !r.id().equals(refillId))
                .anyMatch(r -> r.currentHolder().filter(employeeId::equals).isPresent());
    }

    private Candidate toCandidate(Volunteer v, List<RefillRequest> all, Instant now) {
        Instant windowStart = now.minus(config.fairnessWindow());
        long recent = 0;
        Money outstanding = Money.zero(config.reportCurrency());
        Instant last = Instant.EPOCH;
        for (RefillRequest r : all) {
            Optional<Purchase> p = r.purchase().filter(x -> x.buyerId().equals(v.employeeId()));
            if (p.isEmpty()) {
                continue;
            }
            Instant at = p.get().purchasedAt();
            if (at.isAfter(windowStart)) {
                recent++;
            }
            if (at.isAfter(last)) {
                last = at;
            }
            if (r.status().isReimbursementOutstanding()) {
                outstanding = outstanding.plus(p.get().amountInReportCurrency());
            }
        }
        return new Candidate(v, recent, outstanding, last);
    }
}
