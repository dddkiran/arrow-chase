# Engineering estimate: slice → shippable game

**Team:** 2 full-stack engineers and a shared QA. **"Ship"** here means: a real-money launch in **one** regulated market, on our existing platform, for one game table, with a target of up to 20k concurrent viewers.

**How the team works.** There is no backend/frontend split: both engineers work across the engine, the gateway and the UI. Each owns one area, though, so the hard parts have someone accountable for their depth: **Engineer A** owns the engine, money and resilience; **Engineer B** owns the client, UX and the test and load tooling. Tests are written as part of development (unit, contract, e2e, and the load, soak and chaos runs). The shared QA doesn't write the suites. They certify them: they review the automation packs, sign off visual testing, keep the CI pipeline and its quality gates sound, and give independent sign-off on the money and RNG paths, which the certification lab and compliance expect.

## Headline

| | Duration | Confidence |
|---|---|---|
| Engineering complete (feature, scale, hardening) | **12 weeks** + 2 weeks contingency | medium |
| Go-live | **~16–20 weeks**, gated by **external RNG/maths certification**, not by engineering | low–medium |

Play-money launch (no certification) is possible at about **week 8**, after milestone M3.

This holds the same 12 weeks as a team with a dedicated QA. Most of the test automation is produced with AI as part of the development flow, so it doesn't need a person of its own. Both engineers can also work on the critical path, so there's no single-person backend bottleneck.

## Assumptions (if any of these are false, add 4–6 weeks)

- The platform already provides: player authentication/session, a wallet API with reserve/settle (or debit/credit with idempotency keys), KYC and responsible-gambling settings storage, a Kubernetes or similar deploy target, and CI runners.
- Design support (about 2–3 days a week of a product designer) for weeks 1–4.
- A product owner who freezes the game rules (grid, speed, window, RTP, limits) by **week 5**, so certification can start.
- Test automation (unit, contract, e2e, invariant checks, harnesses) is produced with AI assistance as part of each feature, not as a separate phase.
- The shared QA is available about 2 days a week, and fully at the checkpoints in the plan (weeks 1–2, end of M2, end of M3, go/no-go).

## Scope

**In:** auth integration; wallet integration with reconciliation; a durable bet/event log with crash recovery; engine/gateway split and a standby engine; certified-RNG swap; responsible-gambling limits (stake, loss and session limits, self-exclusion check); bet history for players; audit export for compliance; slippage rule ("reject if the price moved more than X"); mobile-responsive UI; accessibility (WCAG AA basics); i18n framework with one language; metrics, alerts and runbooks; load, soak, chaos and penetration testing.

**Out:** several tables/risk profiles; native mobile apps; chat/social features; tournaments or promotions; back-office game configuration UI (config is in code and reviewed); more than one market; more than one language.

## Plan and ownership

| Weeks | Milestone | Engineer A (engine, money, resilience) | Engineer B (client, UX, test tooling) | Shared QA (certifies) |
|---|---|---|---|---|
| 1–2 | **M1 Foundations** | Auth integration, CI/CD and containers, environments, metrics/log baseline (tick lag, bet→lock latency, sockets) | Production UX with the designer: flows, error and reconnect states, mobile layout; WS contract-test harness; load tool chosen (k6/Gatling) | Test strategy, CI quality gates, test environments |
| 3–5 | **M2 Money is safe** | Event log (Postgres) + snapshot/replay recovery; wallet reserve/settle with idempotency on `betId`; "kill the server mid-bet" tests | Production UI; bet history; slippage UX; robust reconnect; reconciliation job and money-invariant checks against the ledger | Certifies the API/WS contract and e2e packs in CI; independent sign-off on the money paths |
| 6–8 | **M3 Scale and resilience** → *play-money launch possible* | Gateway/engine split over pub/sub; standby engine with leader election; chaos runs (leader kill, broker outage, slow clients) | Load test to 20k sockets; delta protocol if the load tests say so; rendering performance on low-end mobile; accessibility pass; i18n framework | Reviews the load and chaos results; performance budgets in CI |
| 9–10 | **M4 Compliance** | Responsible-gambling limits enforced server-side; maths/RTP report for the lab | RG limit UI, session-time reminders, history and export screens; audit export | Compliance checklist; exploratory sessions on RG, limit and money edge cases; visual regression sign-off |
| 11–12 | **M5 Hardening and launch** | Pen-test fixes, runbooks, on-call alerts, feature flags for staged rollout | Polish, analytics events, copy; soak test (72 h) | Pen-test retest; regression pack sign-off; go/no-go |
| 13–14 | Contingency | | | |

**Critical path:** the engine and money work through M2 and M3. Both engineers can work on it, so there's no bus factor of one on the backend. The remaining risk is **depth**: the event log, failover and wallet idempotency need distributed-systems care. Mitigations: Engineer B reviews every engine and money change (and takes over the reconciliation job), every engine design change also gets a review from someone outside the squad, and the owners swap one task per milestone so neither area has only one person who knows it.

**External track (in parallel, needs an owner outside the squad):** certification lab engagement at week 5 (rules frozen), RNG/maths submission at week 10, lab turnaround 6–10 weeks. Legal and compliance review of terms and RG features from week 6.

## What would slip, and what I'd cut first

When dates are at risk, cut in this order. Each item is listed with the milestone it belongs to and what we'd lose.

1. **Hot standby engine** (M3 Scale and resilience, weeks 6–8, Engineer A) → fall back to fast restart from the event log. Open bets survive, but there's a 1–2 minute outage instead of under 2 s. *Saves about 1.5 engineer-weeks.*
2. **Delta protocol** (M3 Scale and resilience, weeks 6–8, Engineer B) → keep full snapshots if the load test passes at 20k. *Saves about 2 engineer-weeks.*
3. **i18n framework** (M3 Scale and resilience, weeks 6–8, Engineer B) → hard-code one language. *Saves about 0.5 engineer-weeks.*
4. **Audit export UI** (M4 Compliance, weeks 9–10, Engineer B) → a scripted export for compliance on request. *Saves about 1 engineer-week.*

Three of the four cuts land in M3, so cutting early relieves the milestone that gates the play-money launch (week 8).

**What does not get cut:** wallet idempotency and reconciliation, crash recovery of open bets, responsible-gambling limits, certified RNG, and load/soak testing. Those are the difference between a demo and a product.