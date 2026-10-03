# Engineering estimate: slice → shippable game

**Team:** 1 backend (BE), 1 frontend (FE), 1 QA. **"Ship"** here means: a real-money launch in **one** regulated market, on our existing platform, for one game table, with a target of up to 20k concurrent viewers.

## Headline

| | Duration | Confidence |
|---|---|---|
| Engineering complete (feature, scale, hardening) | **12 weeks** + 2 weeks contingency | medium |
| Go-live | **~16–20 weeks**, gated by **external RNG/maths certification**, not by engineering | low–medium |

Play-money launch (no certification) is possible at about **week 8**, after milestone M3.

## Assumptions (if any of these are false, add 4–6 weeks)

- The platform already provides: player authentication/session, a wallet API with reserve/settle (or debit/credit with idempotency keys), KYC and responsible-gambling settings storage, a Kubernetes or similar deploy target, and CI runners.
- Design support (about 2–3 days a week of a product designer) for weeks 1–4.
- A product owner who freezes the game rules (grid, speed, window, RTP, limits) by **week 5**, so certification can start.

## Scope

**In:** auth integration; wallet integration with reconciliation; a durable bet/event log with crash recovery; engine/gateway split and a standby engine; certified-RNG swap; responsible-gambling limits (stake, loss and session limits, self-exclusion check); bet history for players; audit export for compliance; slippage rule ("reject if the price moved more than X"); mobile-responsive UI; accessibility (WCAG AA basics); i18n framework with one language; metrics, alerts and runbooks; load, soak, chaos and penetration testing.

**Out:** several tables/risk profiles; native mobile apps; chat/social features; tournaments or promotions; back-office game configuration UI (config is in code and reviewed); more than one market; more than one language.

## Plan and ownership

| Weeks | Milestone | BE | FE | QA |
|---|---|---|---|---|
| 1–2 | **M1 Foundations** | Auth integration, CI/CD and containers, environments, metrics/log baseline (tick lag, bet→lock latency, sockets) | Production UX with the designer: flows, error and reconnect states, mobile layout | Test strategy, test environments, WS contract-test harness, load tool chosen (k6/Gatling) |
| 3–5 | **M2 Money is safe** | Event log (Postgres) + snapshot/replay recovery; wallet reserve/settle with idempotency on `betId`; reconciliation job | Implement production UI; bet history; slippage UX; robust reconnect | Automated API/WS contract and e2e suites in CI; "kill the server mid-bet" tests; money-invariant checks against the ledger |
| 6–8 | **M3 Scale and resilience** → *play-money launch possible* | Gateway/engine split over pub/sub; standby engine with leader election; delta protocol if the load tests say so | Rendering performance on low-end mobile; accessibility pass; i18n framework | Load test to 20k sockets; chaos (leader kill, broker outage, slow clients); performance budgets in CI |
| 9–10 | **M4 Compliance** | Certified DRBG swap; responsible-gambling limits enforced server-side; audit export; maths/RTP report for the lab | RG limit UI, session-time reminders, history and export screens | Regression suite, exploratory sessions, RG and limit edge cases, compliance checklist |
| 11–12 | **M5 Hardening and launch** | Pen-test fixes, runbooks, on-call alerts, feature flags for staged rollout | Polish, analytics events, copy | Soak test (72 h), pen-test retest, go/no-go sign-off |
| 13–14 | Contingency | | | |

**Critical path:** BE through M2 and M3. **Bus factor of one on the backend** is the biggest delivery risk. Mitigations: FE pairs on the gateway (stateless, mostly I/O) from week 6, QA owns the load tooling end to end, and every engine change gets a design review with someone outside the squad.

**External track (in parallel, needs an owner outside the squad):** certification lab engagement at week 5 (rules frozen), RNG/maths submission at week 10, lab turnaround 6–10 weeks. Legal and compliance review of terms and RG features from week 6.

## What would slip, and what I'd cut first

When dates are at risk, cut in this order. Each item is listed with what we'd lose.

1. **Hot standby engine** → fall back to fast restart from the event log. Open bets survive, but there's a 1–2 minute outage instead of under 2 s. *Saves about 1.5 weeks BE.*
2. **Delta protocol** → keep full snapshots if the load test passes at 20k. *Saves about 1 week BE and FE.*
3. **i18n framework** → hard-code one language. *Saves about 0.5 week FE.*
4. **Audit export UI** → a scripted export for compliance on request. *Saves about 1 week across BE and FE.*

**What does not get cut:** wallet idempotency and reconciliation, crash recovery of open bets, responsible-gambling limits, certified RNG, and load/soak testing. Those are the difference between a demo and a product.

**Most likely sources of slip:** wallet API quirks (idempotency semantics, error codes): +1–2 weeks. Certification findings that need maths or rule changes: +2–4 weeks on go-live. Late rule changes from product, which reset the certification clock.
