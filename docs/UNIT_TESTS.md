# How to run unit tests

Unit tests live **inside each app** and need nothing running: no server, no browser, no network. They are the fast feedback loop and run on every change in CI. Functional tests of the running system are in [E2E_TESTS.md](E2E_TESTS.md).

Prerequisites: JDK 25 and Node 20+, set up as in [RUN_AND_TEST.md](RUN_AND_TEST.md#1-prerequisites).

## Run everything

From the repository root:

```bash
npm install   # first time only
npm test      # backend suite, then frontend suite (about 20 s)
```

Expected, in this order:

```
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
...
 Test Files  3 passed (3)
      Tests  32 passed (32)
```

`npm test` stops at the first failing suite and exits non-zero, so it can gate a commit or a CI step.

## Backend: 34 tests

JUnit 5 + AssertJ, in [backend/src/test/java/com/arrowchase/game/](../backend/src/test/java/com/arrowchase/game/). They test the game engine directly, without Spring. The arrow's path and the multipliers are either scripted or seeded, so every result is exact and repeatable.

| Test class | What it locks |
|---|---|
| `GameEngineTest` (20) | Lock-in (the multiplier at placement is the one paid; the stake is debited), settlement (win within the window, loss at expiry, last-step hit, a bet settles exactly once, no hit counted on the block the arrow was on when betting), rejections (closed block, off-grid, stake limits, insufficient funds, unknown player, open-bet cap), and idempotency (a retried `requestId` is charged once, scoped per player). |
| `PathModelTest` (7) | Movement rules: valid probabilities, one block per move, never reverses, never leaves the grid, corners force the only turn, a seed replays the same path, every block is eventually visited. |
| `PricingTest` (6) | Pricing honesty: exact hit probabilities match a 200,000-run Monte Carlo of the live path; no open block anywhere has an expected return of 1 or more; unlikely and near-certain blocks are closed; a configuration that would pay out more than it takes is refused. |
| `GameSimulationTest` (1) | 20,000 steps with random players: money is conserved for every player, every accepted bet settles exactly once within its window, and the observed return to player is between 85% and 99%. |

Run from the repository root:

```bash
npm run test:api                                        # all backend unit tests
```

Or from `backend/` (Windows PowerShell: `.\mvnw.cmd` instead of `./mvnw`):

```bash
./mvnw test                                             # all
./mvnw test -Dtest=GameEngineTest                       # one class, including its nested groups
./mvnw test -Dtest='PricingTest#exact*'                 # matching test methods
```

Reports: `backend/target/surefire-reports/` (one `.txt` and `.xml` per class).

## Frontend: 32 tests

Vitest + Testing Library (jsdom), next to the code they test in [frontend/src/](../frontend/src/):

| Test file | What it locks |
|---|---|
| `game/state.test.ts` (9) | The client only shows what the server said: welcome snapshot, pending → locked, the locked multiplier stays when the live board changes, outcome only on server settlement, a settled bet is never reopened, rejections clear the pending bet, pending bets survive a reconnect, the win feed and the arrow trail. |
| `game/format.test.ts` (17) | Money and multiplier formatting, and stake parsing with no floating-point error (`"2.5"` → 250 cents; `"1e3"`, `"1.234"`, `"-1"` rejected). |
| `components/Board.test.tsx` (6) | Tile: shows the live multiplier and bets on click, shows the locked multiplier separately with moves left, shows "locking…" while unconfirmed, can't be clicked when closed. Stake input: limits and balance validation, quick-stake chips. |

Run from the repository root:

```bash
npm run test:web                                        # all frontend unit tests
```

Or from `frontend/`:

```bash
npm test                                                # all, once
npm run test:watch                                      # re-run on every save
npx vitest run src/game/state.test.ts                   # one file
npx vitest run -t "locked multiplier"                   # tests whose name matches
npm run build                                           # type check + production build
```

## Writing unit tests: team conventions

- **Put the test next to what it tests.** Backend tests go in `backend/src/test/java/...`, in the same package as the class under test. Frontend tests go in `*.test.ts(x)` beside the source file.
- **No network, no clock, no real randomness.** Backend: inject an `ArrowPath` and a `MultiplierSource` (see the `ScriptedPath` and `SettableQuotes` helpers in `GameEngineTest`), or seed a `SplittableRandom`. Frontend: feed server messages into the reducer instead of opening a socket. A test that needs a running server belongs in [e2e/](E2E_TESTS.md).
- **Name tests after the behaviour,** for example `payout_uses_the_locked_multiplier_not_the_live_one`. A failing name should tell you which rule broke.
- **Money is integer cents in tests too.** Assert exact values, never "close to".
- **Check that a new test can fail.** Break the code or flip the assertion once before you commit it.

## CI

[.github/workflows/backend.yml](../.github/workflows/backend.yml) and [frontend.yml](../.github/workflows/frontend.yml) each run only when their own app changes. Each runs that app's unit tests (the frontend workflow also runs `npm run build`), then builds its container image. Backend test reports are uploaded when a run fails.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `The backend needs JDK 25 or newer, but … is Java 8` | Install JDK 25 and set `JAVA_HOME`; see [RUN_AND_TEST.md](RUN_AND_TEST.md#1-prerequisites). |
| `No tests matching pattern` from Maven | Check the `-Dtest` spelling. Quote patterns that contain `*` or `#`. |
| Frontend tests can't find `vitest` | Run `npm install` at the root, or `npm ci` in `frontend/`. |
