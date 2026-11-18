# Snack Counter Automation

Take-home assignment for Framsikt. The design is in **[DESIGN.md](DESIGN.md)** (also as `DESIGN.pdf`).

Core logic in plain Java 17 (no frameworks), with simulated chat tool, HR system, calendar and FX rates.

## Run

Requires only **JDK 17+**. The Maven Wrapper downloads Maven automatically on first run.
On Windows use `mvnw.cmd`, on macOS/Linux `./mvnw`.

```bash
./mvnw test                                           # 41 tests
./mvnw -q compile exec:java                           # scripted walkthrough of the main scenarios
./mvnw -q compile exec:java "-Dexec.args=interactive" # interactive console (play the chat bot yourself)
```

## Interactive console

You type what people would do in chat; every message the system sends is printed. Time only moves
when you type `wait`, which also runs the scheduler (timeouts, retries, escalations).

```
> empty OSL someone          # box reported empty -> one volunteer is offered the refill
> accept R-1 lars            # REFUSED: offered to someone else
> accept R-1 ingrid          # committed; channel told nobody else should buy
> wait 25h                   # no receipt in 24h -> handed to the next person
> buy R-1 lars 850 fruit     # receipt -> HR claim filed automatically
> hr HR-1 approve            # reimbursed
> budget / status / people / history R-1 / report / help / quit
```

Other useful commands: `decline`, `away <who> <days>`, `visit <who> <OSL|BLR> <days>`,
`approve <refillId> <coordinator>` (over-budget receipts), `hrdown <n>` (simulate HR outage).

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
  Console.java      interactive console (stands in for the chat bot)
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
| Console drives the full flow and explains bad input | `ConsoleTest` |
