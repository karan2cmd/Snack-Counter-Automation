# Snack Counter Automation

Framsikt take-home. The design document is **[DESIGN.pdf](DESIGN.pdf)** (same content in [DESIGN.md](DESIGN.md)).

## How to test

You only need **Java 17 or newer**. No Maven install needed: `mvnw` downloads it on first run.

| | Windows | macOS / Linux |
|---|---|---|
| **1. Run the tests** (41) | `mvnw.cmd test` | `./mvnw test` |
| **2. Watch a scripted demo** | `mvnw.cmd -q compile exec:java` | `./mvnw -q compile exec:java` |
| **3. Try it yourself** | `mvnw.cmd -q compile exec:java "-Dexec.args=interactive"` | `./mvnw -q compile exec:java -Dexec.args=interactive` |

### Try it yourself (step 3)

The console stands in for the company chat. You type what people do, and it prints every message
the system would send. Paste these lines one at a time:

```
empty OSL someone          Oslo box is empty -> Ingrid is asked to buy
accept R-1 lars            refused: it was offered to Ingrid, not Lars
wait 4h                    Ingrid did not answer -> Lars is asked
accept R-1 lars            Lars commits; channel: "no need for anyone else to buy"
buy R-1 lars 850 fruit     receipt uploaded -> HR claim filed automatically
budget                     850 NOK spent, 5150 NOK left this month
hr HR-1 approve            HR approves -> Lars is reimbursed
report                     monthly report across NOK and INR
quit
```

Type `help` for all commands (holidays, visiting the other office, HR outage, over-budget approval).

## What the tests prove

| Rule | Test class |
|---|---|
| Two people cannot both commit to the same refill (incl. a real concurrent race) | `RefillLifecycleTest` |
| A refill nobody completes is handed to someone else | `RefillLifecycleTest` |
| Fair choice of buyer; people on holiday skipped; visitors included | `VolunteerSelectorTest` |
| Budget is checked before money is spent; totals correct across NOK and INR | `BudgetAndCurrencyTest` |

## Where to look in the code

All under `src/main/java/com/framsikt/snack/`:

- `service/RefillService.java`: the refill lifecycle (empty → reimbursed), timeouts and locking
- `service/VolunteerSelector.java`: who is asked to buy
- `service/BudgetService.java`: the budget check
- `domain/`: the core types (`Money`, `RefillRequest`, `RefillStatus` state machine)
- `adapter/`: the simulated chat tool and HR system
