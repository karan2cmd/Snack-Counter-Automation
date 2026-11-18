# Snack Counter Automation: Design

**Goal:** an empty box gets refilled within hours instead of days, by exactly one person, chosen fairly, within budget, and that person is paid back without having to chase it. "Automate" here means **coordination and paperwork**, not robots: a chat bot plus a small service decides who buys, guards the budget, files the HR claim and produces the monthly report.

## 1. Refill lifecycle: from "empty" to "reimbursed"

```
report empty ──► OFFERED ──accept──► COMMITTED ──receipt──► PURCHASED ──► CLAIM_SUBMITTED ──► REIMBURSED
   (anyone)        │  ▲ decline / 4h    │  24h, no receipt      │ over budget          (HR 1-10 days)  └► CLAIM_REJECTED
                   │  └ next person ◄───┘                        └► NEEDS_APPROVAL ─coordinator─┘
                   ├► UNASSIGNED     (nobody present → coordinator + channel; anyone present can take it)
                   └► BLOCKED_BUDGET (month's budget used up → resumes automatically next month)
```

1. **Report empty:** anyone types `/snacks empty` in chat. If the office already has an open refill, the reporter is told who is on it and **no second refill is created**.
2. **Offer:** the service picks one volunteer (rules in §3) and DMs them: *"Your turn, spend up to 1 200 NOK, reply accept/decline within 4 h."*
3. **Commit:** on accept, the spend cap is **reserved** from the monthly budget and the office channel gets *"Lars is refilling, no need for anyone else to buy."* The buyer has 24 h to upload a receipt.
4. **Timeouts:** a decline, an unanswered offer or a missing receipt moves the refill to the next person (the reservation is released). People who passed are not asked again for that refill.
5. **Purchase:** the buyer uploads amount + receipt photo. If it fits the remaining budget the **HR claim is filed immediately and automatically**. Otherwise it waits for the office coordinator's approval.
6. **Reimbursement:** HR decides (webhook or polling). The buyer is notified, and a claim pending longer than 10 days is escalated to the coordinator once.

A periodic `tick()` (e.g. every 5 min) handles all timeouts, retries and escalations, so there are no timers spread through the code.

## 2. Main parts and the data each keeps

| Part | Responsibility | Data |
|---|---|---|
| **Chat bot** (adapter) | `/snacks empty`, accept/decline buttons, receipt upload, notifications | none (stateless) |
| **RefillService** | lifecycle state machine, per-office locking, timeouts | **Refill**: id, office, status, offeree + offer expiry, committer + deadline, reservation, excluded people, purchase, HR claim id/dates, audit history |
| **VolunteerSelector** | who is asked next | reads volunteers, availability, refill history |
| **BudgetService** | budget check before spending | *derived* from refills: spent + reserved per office per month (no separate ledger to drift) |
| **HR adapter** | files claims, receives decisions | idempotency key = refill id |
| **ReportService** | monthly management report | reads refills; **Purchase** stores amount, currency, receipt ref, time and the **FX rate frozen at purchase** |
| **Config** | business rules | per office: currency, time zone, budget, typical min/max, coordinator; timeouts |
| Volunteer directory / calendar | opt-in list; who is in which office today | employee id, name, home office; holiday / remote / visiting |

## 3. How each problem is prevented

| Problem | Mechanism |
|---|---|
| Box sits empty, everyone assumes someone else will do it | One **named** person is asked, with a deadline. Silence passes it on automatically. If nobody is available, the coordinator is alerted and the channel is told. |
| Two people buy on the same day | **At most one open refill per office**, and commit is an **atomic** check-and-set under a per-office lock (a conditional DB update in production). Only the offeree can accept, and the channel announces who is buying. |
| A few people do all the work | Selection ranks by **fewest refills in the last 30 days**, then **least money still unreimbursed**, then longest since last refill. Only people **physically in that office today** are asked, including visitors from the other office. |
| Slow reimbursement | The claim is filed the moment the receipt is uploaded, with exactly the fields HR needs. HR outages are retried idempotently, and claims stuck past HR's 10-day SLA are escalated. |
| Nobody knows the spend | Every purchase is recorded. The monthly report gives per-office spend vs budget, utilisation, refills per volunteer, time-to-refill, time-to-reimburse and a combined total. |
| Overspending | The budget is checked **before** buying: the cap is reserved at commit and the buyer is told the cap. When budget < a minimal purchase, refills pause until next month. Over-budget receipts need approval. |

**Currencies.** A `Money` type refuses to add NOK to INR. Spend counts against the **office where it was bought**: an Oslo employee visiting Bengaluru spends INR from Bengaluru's budget. The HR claim is in the receipt currency, and HR pays out in the payroll currency, as stated in the brief. Months are computed in each office's own time zone. The combined report converts using the rate stored on each purchase, so past reports never change.

## 4. Trade-offs

- **Push (assign one person) over pull (ask the channel).** Pull is what fails today. The cost is one DM per hop, and the 4 h timeout bounds the delay.
- **Reserve a cap at commit** rather than checking after purchase. A little budget is locked while someone shops, but nobody is ever surprised by an overspend.
- **Derived budget, no ledger table.** One source of truth. Recomputing per call is fine at roughly 10 refills per office per month.
- **Lock per office in memory.** Simple and correct for one instance. With a database, use a version column or `UPDATE … WHERE status='OFFERED' AND offered_to=?`.
- **Periodic `tick()` instead of per-refill timers.** Deterministic, restart-safe and testable with an injected clock. Timeouts can be up to one tick late.
- **Fixed FX table** (assumption, see below) instead of a live feed. Totals are reproducible and finance can audit them.

## 5. Deliberately not built

- **Inventory or "which snacks are eaten" tracking** (sensors, stock counts, per-item OCR). It costs a lot for little value. Proxies: how often each box empties, and the free-text items on receipts. A monthly 1-click poll could come later.
- **Receipt OCR / amount verification:** the buyer types the amount and HR still checks the receipt.
- **Payroll currency conversion:** HR already does this.
- **Own UI, login or user management:** chat + company SSO already exist.
- **Purchasing via a vendor, company cards, or scheduled restocking:** good later steps, but outside "automate the current process".
- **Real integrations and persistence:** behind interfaces (`ChatNotifier`, `HrClient`, `RefillRepository`, `AvailabilityProvider`, `FxRateProvider`). The exercise uses fakes.

## 6. Assumptions and open questions

1. **FX rate for the report:** assumed a finance-provided monthly table, captured per purchase, with the report in **NOK**. Which rate and which currency does management want?
2. **Availability source:** assumed a calendar/desk-booking feed telling us who is in which office today. If none exists, a daily "I'm in" check-in in chat works too.
3. **Timeouts:** 4 h to accept and 24 h to buy are guesses. Should they respect working hours and local holidays?
4. **Over-budget handling:** assumed the coordinator approves. Is it HR, the office manager, or a hard block?
5. **Coordinator per office:** assumed one person (or a rota) receives escalations.
6. **HR feedback:** assumed a webhook or polling API for claim status exists. If not, status comes from the employee or HR's email.
