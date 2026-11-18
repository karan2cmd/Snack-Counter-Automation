package com.framsikt.snack;

import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.Volunteer;
import com.framsikt.snack.service.SnackConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static com.framsikt.snack.service.SnackConfig.BENGALURU;
import static com.framsikt.snack.service.SnackConfig.OSLO;

/** Runnable walkthrough of the main scenarios, printing chat traffic and the monthly report. */
public final class App {

    private App() {}

    public static void main(String[] args) {
        SnackSystem s = new SnackSystem(SnackConfig.defaults(), Instant.parse("2026-11-02T08:00:00Z"), true);
        s.volunteers.add(new Volunteer("ingrid", "Ingrid", OSLO))
                .add(new Volunteer("lars", "Lars", OSLO))
                .add(new Volunteer("priya", "Priya", BENGALURU))
                .add(new Volunteer("arjun", "Arjun", BENGALURU))
                .add(new Volunteer("meera", "Meera", BENGALURU));
        s.availability.away("ingrid", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6))      // holiday
                .visiting("arjun", OSLO, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 13)); // visiting Oslo

        step("1. Oslo box empty. Ingrid is on holiday, so Lars or visiting Arjun is asked.");
        RefillRequest osl = s.service.reportEmpty(OSLO, "someone");
        String first = osl.offeredTo().orElseThrow();
        step("2. A second report for the same box does not create a second refill.");
        s.service.reportEmpty(OSLO, "someone-else");

        step("3. " + first + " ignores the offer; after 4h the refill moves on.");
        s.clock.advance(Duration.ofHours(4));
        s.service.tick();
        String second = s.service.find(osl.id()).offeredTo().orElseThrow();

        step("4. " + second + " accepts, buys and uploads the receipt -> HR claim filed automatically.");
        s.service.commit(osl.id(), second);
        s.clock.advance(Duration.ofHours(3));
        s.service.recordPurchase(osl.id(), second, Money.of(850, "NOK"), "fruit, crackers", "receipt-001.jpg");

        step("5. Bengaluru: two refills in the month.");
        for (int i = 0; i < 2; i++) {
            RefillRequest blr = s.service.reportEmpty(BENGALURU, "someone");
            String who = blr.offeredTo().orElseThrow();
            s.service.commit(blr.id(), who);
            s.service.recordPurchase(blr.id(), who, Money.of(2_400, "INR"), "chips, biscuits", "receipt-blr.jpg");
            s.clock.advance(Duration.ofDays(5));
        }

        step("6. HR approves the Oslo claim; time passes; stale claims are escalated.");
        s.service.onClaimDecision(s.service.find(osl.id()).claimId().orElseThrow(), true);
        s.clock.advance(Duration.ofDays(11));
        s.service.tick();

        step("7. Monthly report");
        System.out.println(s.reports.monthly(YearMonth.of(2026, 11)).toText());
    }

    private static void step(String text) {
        System.out.println();
        System.out.println(text);
    }
}
