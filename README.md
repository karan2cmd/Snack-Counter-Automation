# Snack Counter Automation

Take-home assignment for Framsikt. The design is in **[DESIGN.md](DESIGN.md)** (also as `DESIGN.pdf`).

Core logic in plain Java 17 (no frameworks), with simulated chat tool, HR system, calendar and FX rates.

## Run

Requires JDK 17+ and Maven 3.9+.

```bash
mvn test                 # 39 tests
mvn -q compile exec:java # walkthrough: offers, timeout hand-over, HR claims, monthly report
```

## Code map

```
src/main/java/com/framsikt/snack/
  domain/   Money, Office, Volunteer, Purchase, RefillRequest (aggregate + audit trail), RefillStatus (state machine)
  port/     interfaces to the outside world: ChatNotifier, HrClient, AvailabilityProvider, FxRateProvider,
            VolunteerDirectory, RefillRepository
  adapter/  simulated implementations: FakeChatNotifier, FakeHrSystem, InMemory*, FixedFxRateProvider, MutableClock
  service/  RefillService (lifecycle, locking, timeouts), VolunteerSelector (who buys),
            BudgetService (budget check), ReportService (monthly report), SnackConfig (business rules)
  SnackSystem.java  composition root (wiring)
  App.java          runnable demo
```

## What the tests prove

| Rule | Test |
|---|---|
| Two people cannot both commit to the same refill (incl. a concurrent race) | `RefillLifecycleTest.SingleCommitter` |
| A second "empty" report does not create a second refill | `RefillLifecycleTest.second_empty_report_reuses_the_open_refill` |
| A refill nobody completes is handed to someone else (decline, offer timeout, purchase timeout) | `RefillLifecycleTest.Handover` |
| Nobody available → escalates once; anyone present can take it | `RefillLifecycleTest.Handover` |
| HR outage retried without duplicate claims; stale claims escalated | `RefillLifecycleTest.HrIntegration` |
| Fair selection, holiday/remote skipped, visitors included | `VolunteerSelectorTest` |
| Budget reserved before spend, cap, block + month rollover, over-budget approval | `BudgetAndCurrencyTest` |
| Totals correct across NOK and INR; rate frozen at purchase; office time zones | `BudgetAndCurrencyTest` |
| Currencies cannot be mixed; invalid state transitions rejected | `MoneyTest`, `RefillStatusTest` |
