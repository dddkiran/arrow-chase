# How to run e2e tests

The e2e tests check the **running system**, one folder per app, and **nothing is mocked**:

| Folder | Tests | What runs |
|---|---|---|
| [e2e/backend/](../e2e/backend/) | 28 | The real backend, driven black-box over its public WebSocket API, the way any client would. No browser. |
| [e2e/frontend/](../e2e/frontend/) | 6 | A real Chromium browser → the real frontend → the real backend. |

They use [Playwright](https://playwright.dev) with one config ([e2e/playwright.config.ts](../e2e/playwright.config.ts)) and two projects, `backend` and `frontend`. Unit tests are covered in [UNIT_TESTS.md](UNIT_TESTS.md).

## Prerequisites

- JDK 27 and Node 20+, set up as in [RUN.md](RUN.md#1-prerequisites). Playwright starts the backend with Maven, so `JAVA_HOME` matters here too.
- Dependencies and the browser, once:

```bash
npm install           # at the root; also installs e2e/ dependencies
npm run e2e:install   # downloads Chromium (~115 MB); only the frontend tests need it
```

## Run

From the repository root:

```bash
npm run e2e            # all 34 tests
npm run e2e:backend    # backend API tests only (no browser needed)
npm run e2e:frontend   # browser tests only
```

**You don't need to start anything first.** Playwright checks `http://localhost:8080/actuator/health` and `http://localhost:5173`. It starts whichever app isn't running, and stops what it started at the end. If `npm run dev` is already running, the tests use it, which is much faster when you run them repeatedly.

Expected (about 25 s with the apps already running; the first cold run takes longer while the backend builds):

```
  ok  1 [backend] › backend\edge-cases.spec.ts:15:1 › each simple rejection reaches the client with its reason and charges nothing (90ms)
  ok  2 [backend] › backend\game-api.spec.ts:13:1 › a player can join, bet, see the lock-in and see the bet settle (7.7s)
  ...
  ok 34 [backend] › backend\edge-cases.spec.ts:200:1 › step broadcasts follow the rules of the board (4.2s)
  34 passed (23.4s)
```

### Narrower runs

From `e2e/`:

```bash
npx playwright test --list                          # list tests without starting anything
npx playwright test -g "retried"                    # tests whose title matches
npx playwright test backend/game-api.spec.ts:36     # one test by file and line
npx playwright test --project=frontend --headed     # watch the browser tests run
npx playwright test --project=frontend --ui         # interactive runner with time-travel debugging
```

### Against a deployed backend

The backend tests only use the public API, so they can smoke-test a real environment:

```bash
API_URL=https://<api-service-url> npm run e2e:backend
```

The origin tests expect `http://localhost:5173` to be **allowed**. A deployment where `ALLOWED_ORIGINS` is the production frontend URL will (correctly) fail that one test. Treat it as an environment check, or skip it with `-g` to filter.

## What the tests cover

**Backend, [game-api.spec.ts](../e2e/backend/game-api.spec.ts):**

1. Join, bet, see the lock-in and see the bet settle. The payout equals `stake × locked multiplier`, the balance matches, and the bet settles inside its window (a loss exactly when the window ends).
2. A retried request (same `requestId`) returns the same bet and is charged once.
3. Reconnecting restores an open bet with the exact balance; reconnecting after a bet settled while away shows it settled, with the payout in the balance.
4. A second connection doesn't give a player a second bet allowance: two sockets of one player share one rate limit.
5. A connection without a valid player id is closed with a policy violation (1008).
6. Browsers from untrusted origins are refused (HTTP 403).
7. Browsers from allowed origins are accepted.

**Backend, [edge-cases.spec.ts](../e2e/backend/edge-cases.spec.ts):**

1. Off-grid blocks (`INVALID_CELL`) and stakes below the minimum, above the maximum or zero (`INVALID_STAKE`) are refused with the reason, the echoed `requestId` and an unchanged balance.
2. A bet on a closed block is refused as `CELL_CLOSED`.
3. The 21st open bet is refused as `TOO_MANY_OPEN_BETS`, at exactly 20 open.
4. A stake above the balance is refused as `INSUFFICIENT_FUNDS`, with nothing charged.
5. Malformed frames (not JSON, no or unknown `type`, missing or invalid `requestId`, wrongly typed fields) get an `error`, and the connection stays usable.
6. Player ids that are missing, too short (7), too long (65) or contain other characters are closed with 1008; 8 and 64 characters are accepted.
7. Display names arrive as typed (spaces, accents), with markup characters removed, cut to 20 characters, and "Player" when nothing is left.
8. Every connection of a player sees that player's bets lock and settle.
9. A request retried on a new connection, as the browser does after a drop, returns the original bet (even with a different block or stake) and the balance as it is now.
10. A rate-limited `requestId` is processed normally when sent again later: the limiter caches nothing.
11. Step broadcasts follow the rules: consecutive step numbers, the arrow moves one block and stays on the grid, the trail starts at the arrow, every quote is closed (0) or within 1.05x–50x.

**Frontend, [game.spec.ts](../e2e/frontend/game.spec.ts):**

1. A player sets a stake, bets on a block, sees the 🔒 lock-in and the balance drop, then sees a win or a miss in "My bets", and the balance then matches the server's to the cent.
2. An invalid stake (not a number, too many decimals, below the minimum, above the maximum) shows why and disables every block.
3. Two separate players (separate browser contexts) see the same arrow.

**Frontend, [resilience.spec.ts](../e2e/frontend/resilience.spec.ts):**

1. When the connection drops, the page shows "reconnecting…", reconnects by itself, still shows the bet placed before the drop, matches the server, and the player can keep betting.
2. When the server accepts a bet but the confirmation is lost with the connection, the page re-sends it with the same `requestId` after reconnecting, and it is charged once.
3. Reloading the page keeps the same player, name, balance and bets.

The resilience tests route the game socket through Playwright's [`page.routeWebSocket`](https://playwright.dev/docs/api/class-page#page-route-web-socket) as a pass-through proxy to the real backend. Nothing is faked; the proxy only lets a test cut the connection, or lose one message, at a chosen moment.

The arrow's path is random and decided live, so **no test assumes where the arrow goes**. They assert the rules instead: the payout matches the locked multiplier, the bet settles within its window, and everyone sees the same arrow.

## Results and debugging

| Output | Where |
|---|---|
| Screenshot after a settled bet | `e2e/test-results/after-settlement.png` |
| Screenshot and trace of any failing browser test | `e2e/test-results/<test-name>/` |
| HTML report (written in CI; locally with `--reporter=html`) | `e2e/playwright-report/`, open with `npm --prefix e2e run report` |

Open a trace with `npx playwright show-trace e2e/test-results/<test-name>/trace.zip` to step through every action, with network traffic and DOM snapshots.

## Writing e2e tests: team conventions

- **Put each test in the folder of the app it exercises:** `e2e/backend/` for the API, `e2e/frontend/` for the browser.
- **Talk to the backend only through its public protocol.** Use [e2e/support/game-client.ts](../e2e/support/game-client.ts) (`connect`, `waitFor`, `placeBetOnAnOpenBlock`) rather than reaching into the server. Browser helpers live in [e2e/support/game-page.ts](../e2e/support/game-page.ts).
- **Use a fresh player per test** (`newPlayerId()`). The server may be shared and long-running.
- **Never assume the path.** Assert rules and invariants. Allow for a block closing between reading the board and betting (`placeBetOnAnOpenBlock` retries on `CELL_CLOSED`). When a bet must still be open a moment later, bet on `farBlock`: the arrow moves one block per step, so a block at least 4 moves away cannot be won for 4 steps.
- **Wait for messages, don't sleep.** Use `waitFor` / `expect.poll`, with timeouts based on the game's speed (a bet lasts at most 10 moves × 0.7 s).
- **Keep these tests few and valuable.** Each one costs seconds. A rule that can be checked without a running system belongs in the [unit tests](UNIT_TESTS.md).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Executable doesn't exist ... chromium` | Run `npm run e2e:install` once. |
| `The backend needs JDK 27 or newer` / timed out waiting for `http://localhost:8080/actuator/health` | Playwright couldn't start the backend. Set `JAVA_HOME` to JDK 27, or start the backend yourself first (`npm run dev`) to see its log. |
| `Port 8080/5173 is already in use` but tests can't connect | Something other than this app holds the port. Stop it, or run `npm run dev` so Playwright reuses the real apps. |
| `timed out ... waiting for a matching message` | The backend is slow or overloaded (for example the first run after a build). Rerun. If it keeps happening, check that `game.step-millis` hasn't been raised. |
| Origin test fails against a deployed `API_URL` | Expected when `ALLOWED_ORIGINS` doesn't include `http://localhost:5173`; see [Against a deployed backend](#against-a-deployed-backend). |
