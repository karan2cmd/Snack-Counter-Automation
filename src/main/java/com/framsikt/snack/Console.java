package com.framsikt.snack;

import com.framsikt.snack.domain.Money;
import com.framsikt.snack.domain.Office;
import com.framsikt.snack.domain.RefillRequest;
import com.framsikt.snack.domain.SnackException;
import com.framsikt.snack.domain.Volunteer;
import com.framsikt.snack.service.BudgetService;
import com.framsikt.snack.service.SnackConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.framsikt.snack.service.SnackConfig.BENGALURU;
import static com.framsikt.snack.service.SnackConfig.OSLO;

/**
 * Interactive console that plays the role of the chat bot: you type what people would do in chat,
 * and every message the system sends is printed. Time only moves when you type {@code wait}.
 *
 * <pre>
 *   mvnw -q compile exec:java "-Dexec.args=interactive"
 * </pre>
 */
public final class Console {

    @FunctionalInterface
    private interface Command {
        void run(String[] args);
    }

    private record Entry(String usage, String help, Command command) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm");

    private final SnackSystem s;
    private final PrintStream out;
    private final Map<String, Entry> commands = new LinkedHashMap<>();

    public Console(PrintStream out) {
        this.out = out;
        this.s = new SnackSystem(SnackConfig.defaults(), Instant.parse("2026-11-02T08:00:00Z"), out);
        s.volunteers.add(new Volunteer("ingrid", "Ingrid", OSLO))
                .add(new Volunteer("lars", "Lars", OSLO))
                .add(new Volunteer("nora", "Nora", OSLO))
                .add(new Volunteer("priya", "Priya", BENGALURU))
                .add(new Volunteer("arjun", "Arjun", BENGALURU))
                .add(new Volunteer("meera", "Meera", BENGALURU));

        add("empty", "empty <OSL|BLR> <reporter>", "report the snack box as empty", a -> show(s.service.reportEmpty(a[0].toUpperCase(), a[1])));
        add("accept", "accept <refillId> <volunteer>", "volunteer accepts / takes the refill", a -> show(s.service.commit(a[0], a[1])));
        add("decline", "decline <refillId> <volunteer>", "offeree says no", a -> show(s.service.decline(a[0], a[1])));
        add("buy", "buy <refillId> <volunteer> <amount> [items...]", "upload receipt (amount in office currency)", this::buy);
        add("approve", "approve <refillId> <coordinator>", "coordinator approves an over-budget purchase", a -> show(s.service.approveOverBudget(a[0], a[1])));
        add("hr", "hr <claimId> <approve|reject>", "HR decides on a claim", a -> show(s.service.onClaimDecision(a[0], a[1].equalsIgnoreCase("approve"))));
        add("hrdown", "hrdown <n>", "make the HR system fail the next n submissions", a -> {
            s.hr.failNextSubmissions(Integer.parseInt(a[0]));
            out.println("  HR will fail the next " + a[0] + " submission(s)");
        });
        add("wait", "wait <n><m|h|d>", "move time forward (e.g. 4h, 2d) and run the scheduler", this::waitFor);
        add("away", "away <volunteer> <days>", "volunteer on holiday / remote from today", a -> {
            LocalDate today = today();
            s.availability.away(a[0], today, today.plusDays(Long.parseLong(a[1]) - 1));
            out.println("  " + a[0] + " is away until " + today.plusDays(Long.parseLong(a[1]) - 1));
        });
        add("visit", "visit <volunteer> <OSL|BLR> <days>", "volunteer works from an office from today", a -> {
            LocalDate today = today();
            s.availability.visiting(a[0], a[1].toUpperCase(), today, today.plusDays(Long.parseLong(a[2]) - 1));
            out.println("  " + a[0] + " is in " + a[1].toUpperCase() + " until " + today.plusDays(Long.parseLong(a[2]) - 1));
        });
        add("status", "status", "all refills and their state", a -> status());
        add("history", "history <refillId>", "audit trail of one refill", a -> s.service.find(a[0]).history().forEach(h -> out.println("  " + h)));
        add("people", "people", "volunteers and where they are today", a -> people());
        add("budget", "budget", "budget per office this month", a -> budget());
        add("report", "report [yyyy-mm]", "monthly management report", a -> out.print(s.reports.monthly(
                a.length > 0 ? YearMonth.parse(a[0]) : YearMonth.from(s.clock.instant().atZone(s.config.office(OSLO).zone())))
                .toText()));
        add("help", "help", "show commands", a -> help());
    }

    public static void main(String[] args) throws IOException {
        new Console(System.out).run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), true);
    }

    /** Reads commands until EOF or {@code quit}. */
    public void run(BufferedReader in, boolean showBanner) throws IOException {
        if (showBanner) {
            out.println("Snack Counter console. You are the chat bot: type what people do. 'help' lists commands, 'quit' exits.");
            out.println("Volunteers: ingrid, lars, nora (Oslo) / priya, arjun, meera (Bengaluru). Coordinators: osl-coordinator, blr-coordinator");
        }
        while (true) {
            out.print("\n[" + TIME.format(s.clock.instant().atZone(s.config.office(OSLO).zone())) + " Oslo] > ");
            out.flush();
            String line = in.readLine();
            if (line == null || line.trim().equalsIgnoreCase("quit") || line.trim().equalsIgnoreCase("exit")) {
                out.println();
                return;
            }
            execute(line);
        }
    }

    /** Runs one command line; errors are printed, never thrown. */
    public void execute(String line) {
        // strip a byte-order mark that Windows shells add when piping text in
        String[] parts = line.replace("﻿", "").trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        Entry entry = commands.get(parts[0].toLowerCase());
        if (entry == null) {
            out.println("  Unknown command '" + parts[0] + "'. Type 'help'.");
            return;
        }
        try {
            entry.command().run(Arrays.copyOfRange(parts, 1, parts.length));
        } catch (ArrayIndexOutOfBoundsException e) {
            out.println("  Usage: " + entry.usage());
        } catch (SnackException | IllegalArgumentException | java.time.format.DateTimeParseException e) {
            out.println("  REFUSED: " + e.getMessage());
        }
    }

    // ---- commands ---------------------------------------------------------------------------

    private void buy(String[] a) {
        RefillRequest r = s.service.find(a[0]);
        Office office = s.config.office(r.officeId());
        String items = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : "";
        Money amount = new Money(new java.math.BigDecimal(a[2]), office.currency());
        show(s.service.recordPurchase(r.id(), a[1], amount, items, "receipt-" + r.id() + ".jpg"));
    }

    private void waitFor(String[] a) {
        String spec = a[0].toLowerCase();
        long n = Long.parseLong(spec.substring(0, spec.length() - 1));
        Duration d = switch (spec.charAt(spec.length() - 1)) {
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Use e.g. 30m, 4h or 2d");
        };
        // advance in steps so day-by-day checks (e.g. stale claims) fire as they would in production
        Duration step = Duration.ofHours(1);
        Duration remaining = d;
        while (!remaining.isZero() && !remaining.isNegative()) {
            Duration next = remaining.compareTo(step) < 0 ? remaining : step;
            s.clock.advance(next);
            s.service.tick();
            remaining = remaining.minus(next);
        }
        out.println("  Time moved forward " + a[0]);
    }

    private void status() {
        if (s.refills.findAll().isEmpty()) {
            out.println("  No refills yet. Try: empty OSL someone");
        }
        for (RefillRequest r : s.refills.findAll()) {
            out.println("  " + line(r));
        }
    }

    private void people() {
        for (Volunteer v : s.volunteers.all()) {
            LocalDate date = LocalDate.ofInstant(s.clock.instant(), s.config.office(v.homeOfficeId()).zone());
            String where = s.availability.officeOn(v.employeeId(), date).map(o -> "in " + o).orElse("away");
            out.printf("  %-7s home %s, today %s%n", v.employeeId(), v.homeOfficeId(), where);
        }
    }

    private void budget() {
        for (String id : new String[] {OSLO, BENGALURU}) {
            BudgetService.Snapshot b = s.budget.snapshot(id, s.clock.instant());
            out.printf("  %s budget %s | spent %s | reserved %s | remaining %s%n",
                    id, b.budget(), b.spent(), b.reserved(), b.remaining());
        }
    }

    private void help() {
        out.println("  Commands:");
        commands.values().forEach(e -> out.printf("    %-46s %s%n", e.usage(), e.help()));
        out.println("    quit");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void show(RefillRequest r) {
        out.println("  => " + line(r));
    }

    private String line(RefillRequest r) {
        StringBuilder sb = new StringBuilder(r.id() + " " + r.officeId() + " " + r.status());
        r.currentHolder().ifPresent(h -> sb.append(" holder=").append(h));
        r.offerExpiresAt().ifPresent(t -> sb.append(" offer-expires=").append(fmt(t, r)));
        r.purchaseDeadline().ifPresent(t -> sb.append(" buy-by=").append(fmt(t, r)));
        r.purchase().ifPresent(p -> sb.append(" bought=").append(p.amount()).append(" by ").append(p.buyerId()));
        r.claimId().ifPresent(c -> sb.append(" claim=").append(c));
        if (!r.excluded().isEmpty()) {
            sb.append(" passed=").append(r.excluded());
        }
        return sb.toString();
    }

    private String fmt(Instant t, RefillRequest r) {
        return TIME.format(t.atZone(s.config.office(r.officeId()).zone()));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(s.clock.instant(), s.config.office(OSLO).zone());
    }

    private void add(String name, String usage, String help, Command command) {
        commands.put(name, new Entry(usage, help, command));
    }
}
