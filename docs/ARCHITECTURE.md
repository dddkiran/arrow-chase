# Architecture note

## 1. What the slice is

### In one paragraph

One server runs **one shared game** for everyone. Every 0.7 seconds it moves the arrow one block, recalculates the multiplier on every block, settles any bets the move decided, and sends the new state to every connected browser. Browsers only **display** that state and **send bets**; they never decide anything. When a player bets, the server records the block's multiplier at that moment (the *lock-in*) and takes the stake. If the arrow enters that block within the next 10 moves, the server pays `stake × locked multiplier`; otherwise the bet is lost.

### The pieces

```
   Browser (React)                                Server (Spring Boot)
  ┌─────────────────────┐                 ┌──────────────────────────────────────────────┐
  │ shows the board,    │  ◄── state ───  │  1. Gateway        2. Game loop   3. Engine   │
  │ your bets, balance  │                 │     (WebSocket)  ─► (one thread) ─► (rules)   │
  │                     │  ─── bets ───►  │                                              │
  └─────────────────────┘    WebSocket    └──────────────────────────────────────────────┘
       many players                          everything lives in this one process's memory
```

| Part | Its one job | Code |
|---|---|---|
| **Browser** | Draw what the server sends. Send a bet when a block is clicked. Reconnect by itself if the connection drops. | `frontend/src/` |
| **1. Gateway** | Hold the WebSocket connections, check incoming messages are well-formed, and pass bets on to the game loop. Send each message to the right players. Disconnect clients too slow to keep up. | `backend/.../ws/` |
| **2. Game loop** | The **only** thread that touches game state. It does two kinds of work, strictly one at a time: a *tick* every 0.7 s, and *bet requests* from the gateway. | `backend/.../app/GameLoop.java` |
| **3. Engine** | The rules: where the arrow goes, what each block pays, wallets, accepting or rejecting bets, settling them. Plain Java with no framework, network or clock, so it can be tested exactly. | `backend/.../game/` |

### The life of one bet

1. **The board is live.** On every tick the engine moves the arrow, settles bets, and re-prices all 64 blocks. The gateway sends this as a `step` message to every player (about 1.4 per second).
2. **A player clicks a block.** The browser sends `placeBet` with the block, the stake and a unique `requestId`, and shows the tile as "locking…".
3. **The server decides.** The gateway queues the request onto the game loop. Because the loop does one thing at a time, the bet lands cleanly *between* two moves, never in the middle of one. The engine checks that the block is open, the stake is within limits, the player can afford it, and they are under the cap of 20 open bets.
4. **Lock-in.** If accepted, the engine takes the stake and records the block's current multiplier on the bet. The player gets `betLocked` with that multiplier, and the tile shows 🔒. From now on the live number on the tile no longer matters for this bet. If rejected, the player gets `betRejected` with the reason, and nothing is charged.
5. **The next 10 moves decide it.** After each move, the engine checks every open bet. If the arrow has just entered the bet's block, the bet is **won** and pays `stake × locked multiplier`. If 10 moves pass without that, the bet is **lost**. Either way the player gets `betSettled` with the result and the new balance, and every player's win feed shows the wins.
6. **If the connection drops,** the browser reconnects and the server sends a full `welcome` snapshot: board, balance, open and recent bets. Any bet the browser sent but never saw confirmed is re-sent with the **same** `requestId`. The server recognises it and returns the original answer instead of charging twice.

### The rules, precisely

- **How the arrow moves.** Each move goes one block: straight ahead 60% of the time, a left or right turn 20% each. It never reverses, and at a wall it turns. The next move is drawn only when it happens, so there is no stored future path that could leak.
- **How multipliers are set.** A block's multiplier follows from the chance that the arrow enters it within the next 10 moves. If that chance is 1 in 4, a *fair* payout would be 4×. The game pays 95% of fair, so 3.80×. That 95% is the **return to player (RTP)**; the other 5% is the house margin. A small ±3% wobble is added each move so the board visibly changes, and results are rounded down to 0.01.
  - The chance is **calculated exactly** from the same movement rules that move the arrow, not estimated. A test checks the calculation against 200,000 simulated walks, and a 900,000-bet simulation returned 94.8% to players.
  - **Closed blocks.** A block is shown as closed (–) and can't be bet on if the arrow almost certainly can't reach it (under 2% chance), or if it's so likely to be hit that the multiplier would drop below 1.05×. That's more honest than showing a price that isn't real.
- **What gets locked.** The multiplier the server holds **when it processes the bet**. Usually that's the number the player clicked. If the arrow moved in the instant between click and arrival, it's the next move's price, and the confirmation shows the actual locked value. (Whether to reject the bet instead when the price moved is a product decision; see §3.)
- **Which block counts.** Only moves *after* the bet count. The block the arrow is standing on when you bet only wins if the arrow comes back to it within the window.

### Messages

| Direction | Message | When |
|---|---|---|
| server → one player | `welcome` | On connect: board, rules, balance, open and recent bets |
| server → everyone | `step` | Every move: arrow position and trail, all multipliers, players online, recent wins |
| player → server | `placeBet` | Block, stake (in cents) and a unique `requestId` |
| server → that player | `betLocked` / `betRejected` | The answer to `placeBet`: the locked bet and new balance, or the reason it was refused |
| server → that player | `betSettled` | The bet won or lost, with payout and new balance |

All money is sent as **whole cents** and multipliers as **whole hundredths** (245 = 2.45×), so no floating-point rounding happens anywhere.

### Design decisions that matter

| Decision | Why | What it costs or defers |
|---|---|---|
| **One thread owns all game state** (the game loop) | Bets and moves happen strictly one after another, so there are no race conditions and no locks to get wrong. Messages leave in the order things happened. | One game can use only one CPU core. That's plenty: a move costs about 64 lookups. |
| **The rules engine has no framework, clock or hidden randomness** | Every rule is tested exactly with a scripted arrow path. A fixed seed replays a whole game. The engine can move to another process unchanged. | A little wiring code. |
| **The arrow and the prices use the same movement rules** | Prices can't drift from how the arrow really moves, so the 95% return is built in and testable. | Changing the movement changes the prices too. That's correct, but needs a maths sign-off. |
| **Money as integer cents, payouts rounded down** | No rounding errors in settlement, in Java or in the browser. | None worth mentioning. |
| **Every bet carries a `requestId`; repeats return the first answer** | A retry after a network blip can never charge twice. | The list of seen requests is in memory (last 50,000). It must become durable before real money (see roadmap). |
| **A full snapshot on every connect** | Reconnecting, refreshing or opening a second tab all end up showing exactly what the server has. No recovery logic in the browser. | A slightly larger first message. |
| **Plain WebSocket with JSON messages** (no messaging framework on top) | A small, explicit protocol that's easy to read and test. | No built-in pub/sub. We add our own when scaling out (§2). |

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
