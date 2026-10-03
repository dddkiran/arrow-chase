# Architecture note

## 1. What the slice is

```
 Browser (React)                       Server (Spring Boot, one JVM)
 ───────────────                       ──────────────────────────────────────────────────────────
                     WebSocket /ws     ┌───────────────────┐   queue    ┌──────────────────────┐
  render board  ◄──── step (1.4/s) ────┤ ClientRegistry    │◄───────────┤ GameLoop             │
  render bets   ◄──── betLocked ───────┤  fan-out, slow-   │            │  ONE thread          │
                ◄──── betSettled ──────┤  client eviction  │            │  ticks every 700 ms  │
  click block   ───── placeBet ───────►│ GameWebSocket-    ├──command──►│  runs bet commands   │
  (requestId)                          │ Handler: validate │            │          │           │
                                       └───────────────────┘            │  ┌───────▼────────┐  │
                                                                        │  │ GameEngine     │  │
                                                                        │  │ path, quotes,  │  │
                                                                        │  │ wallets, bets, │  │
                                                                        │  │ settlement     │  │
                                                                        │  └────────────────┘  │
                                                                        └──────────────────────┘
```

**The rules as implemented**

- **Path:** a random walk on an 8×8 grid. Each step the arrow goes straight (60%) or turns left or right (20% each). It never reverses, and at a wall it turns. Each step is drawn when it happens, so there is no precomputed path anyone could leak.
- **Live multiplier:** for each block, `RTP / P(arrow enters block within the next 10 moves) × (1 ± 3% jitter)`, rounded down to 0.01. `P` is **exact**: a dynamic program over the same Markov chain that drives the arrow, precomputed once for all 256 (cell, heading) states. A Monte Carlo test checks it against the live random walk. Blocks with P < 2% (out of reach), or with a multiplier below 1.05×, are **closed** rather than mispriced.
- **Lock-in:** the multiplier locked is the server's quote **at the moment the game thread processes the bet**, not the number the player saw. The server confirms with `betLocked`, which carries the locked value.
- **Settlement:** a bet placed at step *s* covers the arrow's positions at steps *s+1 … s+10*. The first time the arrow enters the block, the bet is WON and pays `stake × locked`. Otherwise it is LOST at step *s+10*. The block the arrow is standing on when you bet doesn't count unless the arrow comes back to it.

**Design decisions that matter**

| Decision | Why | Cost / what it defers |
|---|---|---|
| **The engine is single-threaded, driven by one game-loop thread (actor model)** | Every bet is strictly before or after a step, so there are no races and no locks. Outbound messages leave in engine order. | Throughput per game is bounded by one core. That's more than enough: a step is about 64 table lookups. |
| **The engine is pure: no clock, no Spring, randomness injected** | Every rule can be tested exactly with a scripted path. A seed replays a whole game. The engine can later move into another process unchanged. | A little wiring code. |
| **The path and the pricing share one transition table** | Quotes can't drift away from how the arrow actually behaves. RTP is a property of the code, not a hope. | Changing movement rules changes prices automatically, which is a feature, but it needs a maths sign-off. |
| **Money in integer cents, multipliers ×100, payout floored** | No floating-point error in settlement on either side of the wire. | None worth mentioning. |
| **Idempotent `requestId` per player** | The client re-sends unconfirmed bets after a reconnect, and a retry can never double-charge. | Bounded in-memory dedup cache (50k). That needs to become durable (see roadmap). |
| **A full snapshot on every connect** | Reconnect, refresh and a second tab all converge on server truth. No client-side recovery logic. | Slightly bigger first message (fine). |
| **Plain WebSocket + JSON, not STOMP/SockJS** | A small, explicit protocol that's easy to read and test. | No broker semantics. We'd add our own pub/sub when we scale out (below). |

## 2. How it scales

**Where the load actually is.** The engine is cheap: one step is microseconds. What costs money is **fan-out**. Each viewer gets about 1.4 step messages per second, roughly 0.5 KB each, so about 0.7 KB/s per viewer. 10k viewers is about 7 MB/s (~60 Mbit/s) from one game. Cost grows with **watchers**, not bettors.

**Today on Cloud Run** ([DEPLOY.md](DEPLOY.md)): the frontend (nginx) scales freely, but the API is pinned to **exactly one always-on instance**, because the game lives in that process's memory. Step 1 below is what removes that pin.

**The path to scale, in order of need:**

1. **Split the engine from the edge.** A *game engine* service runs one leader per game table: single writer, the same actor model as today. Many *stateless WebSocket gateways* hold the sockets. The engine publishes each step once to a pub/sub topic (Redis Streams / NATS / Kafka), and every gateway forwards it to its own sockets. Bets travel the other way through a per-table command queue. Gateways scale horizontally behind an L4/L7 load balancer, and sticky sessions aren't needed because a reconnect gets a fresh snapshot.
2. **Shard by table, not by player.** One table means one ordered stream and one engine leader. More tables (or several grids with different speeds and risk) means more leaders. Partition the leaders across nodes.
3. **Wallet as its own service.** The engine **reserves** the stake (debit) before it accepts the bet, then **settles** (credit) idempotently using `betId` as the key. That is double-entry ledger semantics. The engine stops owning balances.
4. **Durable event log.** The engine appends `BetPlaced` / `StepAdvanced` / `BetSettled` to a log **before** acknowledging. Recovery is snapshot plus replay. Because the engine is deterministic for a given seed and command order, replay reproduces the exact same state. That determinism is why the purity matters now.
5. **Wire efficiency, once it pays off:** send multiplier deltas instead of the full grid, use a binary encoding, and reduce the step rate for viewers who aren't betting.

## 3. How it fails

| Failure | Today (slice) | Target |
|---|---|---|
| **Server process dies** | **All state is lost**: balances, open bets, the path. Clients reconnect and get a fresh wallet. *This is the biggest gap and it's deliberate for a slice.* | A durable event log plus a hot standby engine (leader election). Open bets survive and settle after failover. |
| Gateway / network blip | The client reconnects with backoff, gets a snapshot, and re-sends any unconfirmed bet with the same `requestId`, which the server de-duplicates. | Same. |
| Slow or stuck client | Its send buffer is capped (512 KB / 5 s), then it's disconnected. It can't slow the game for anyone else. | Same, plus metrics on evictions. |
| A tick throws an exception | Caught and logged. The schedule keeps running (an uncaught exception would silently freeze the game). | Alert on tick errors and on tick lag. |
| Tick overrun (GC pause, CPU starvation) | `scheduleAtFixedRate` catches up with back-to-back steps, so players see the arrow jump. | Measure tick lag. Decide whether to catch up or skip. Cap the catch-up. |
| Bet arrives as the arrow moves | It's queued on the game thread and gets the quote from whichever step it lands on. The player may lock a different number than the one they saw. | A product decision: accept the move, or let the client send `maxAcceptableChange` and reject if the price moved more than that (like "slippage" in trading). |
| Duplicate settlement | Impossible within one engine: the bet is removed from the open set in the same step. | Ledger credits are idempotent on `betId`. |
| RNG | `SplittableRandom` seeded from `SecureRandom`. That's fine for a demo, but it's **not** a certified or cryptographically strong generator. | A certified DRBG (for example a SHA-256/HMAC DRBG) with GLI-19-style certification, and seed handling audited. |

**Security gaps, known and accepted for the slice:** identity is a client-generated ID with no authentication (anyone who knows the ID can bet as that player). There's no rate limiting beyond the open-bet cap. Allowed origins are localhost. Real money needs auth (JWT from the platform session), per-player rate limits, and responsible-gambling limits.

## 4. Technical roadmap

| Phase | Goal | Main work | Exit criteria |
|---|---|---|---|
| **0 — Slice (this)** | Show the game loop and that pricing is honest | Engine, pricing, WS, UI, tests | A reviewer plays it. Invariants are tested. |
| **1 — Real players, one node** | Can run for real users on play money | Auth, wallet service integration (reserve/settle), Postgres event log + snapshot recovery, metrics (tick lag, bet→lock p99, sockets, observed vs. target RTP), structured logs, CI pipeline, containerised deploy | Restarting the server loses no bets. RTP dashboard. Load test at 2k sockets. |
| **2 — Scale and resilience** | Many viewers, no single point of failure | Gateway/engine split over pub/sub, engine leader election + hot standby, delta protocol, load test to 50k sockets (k6/Gatling), chaos tests (kill the leader mid-bet) | Leader failover under 2 s with no lost bets. Cost per 1k viewers known. |
| **3 — Real money and product** | Ready for a regulated market | Certified RNG, independent maths/RTP review, responsible-gambling limits, audit trail export, multiple tables and risk profiles, mobile UX, i18n | Certification passed. Go-live in the first jurisdiction. |

## 5. For non-technical leadership

**What we have:** a working, honest prototype. Many people watch the same arrow live, place bets, and are paid by rules the server enforces. Our tests show money is never created or lost by mistake over tens of thousands of simulated rounds, and that over time the game returns about 95% of stakes to players by design (measured: 94.8% over 900,000 simulated bets).

**The risks, in plain terms:**

1. **Regulatory approval is the long pole, not the code.** Real-money games need the randomness and the payout maths certified by an independent lab. That typically takes months and can't really start until the rules are frozen. *Decision needed early: which market first, and freeze the rules before certification.*
2. **Today, a server crash would wipe out open bets and balances.** That's acceptable for a demo, and unacceptable with real money. Fixing it (a durable record of every bet, and a standby server) is the first piece of real work.
3. **Money errors cost reputation, not just money.** One double payout makes headlines. We've designed so that retries can't double-charge and settlement happens exactly once, and we test those properties automatically. Before launch, the wallet must be a proper ledger with reconciliation.
4. **Players far from our servers see the arrow slightly later.** The price they lock can differ a little from the one they clicked. We need a *product* rule ("reject if the price moved more than X%") so it feels fair. This is a design choice, not a bug.
5. **Running costs grow with viewers, not with bets.** A popular game that people mostly watch costs bandwidth without earning. We should measure the viewer-to-bettor ratio early and design the "watch only" mode to be cheap.
6. **The house edge is a setting, and it has to be governed.** Changing the speed, grid or window changes payouts automatically. Every change to those needs a sign-off, the same way we treat pricing changes elsewhere.

**What we'd ask for:** a small squad (1 backend, 1 frontend, 1 QA) for about 10–12 weeks to reach a play-money launch that survives a server restart (see [ESTIMATE.md](ESTIMATE.md)). Certification and compliance run in parallel, with a named owner.
