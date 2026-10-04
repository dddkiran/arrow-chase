# How to run e2e tests

The e2e tests check the **running system**, one folder per app, and **nothing is mocked**:

| Folder | Tests | What runs |
|---|---|---|
| [e2e/backend/](../e2e/backend/) | 6 | The real backend, driven black-box over its public WebSocket API, the way any client would. No browser. |
| [e2e/frontend/](../e2e/frontend/) | 2 | A real Chromium browser → the real frontend → the real backend. |

They use [Playwright](https://playwright.dev) with one config ([e2e/playwright.config.ts](../e2e/playwright.config.ts)) and two projects, `backend` and `frontend`. Unit tests are covered in [UNIT_TESTS.md](UNIT_TESTS.md).

## Prerequisites

- JDK 25 and Node 20+, set up as in [RUN_AND_TEST.md](RUN_AND_TEST.md#1-prerequisites). Playwright starts the backend with Maven, so `JAVA_HOME` matters here too.
- Dependencies and the browser, once:

```bash
npm install           # at the root; also installs e2e/ dependencies
npm run e2e:install   # downloads Chromium (~115 MB); only the frontend tests need it
```

## Run

From the repository root:

```bash
npm run e2e            # all 8 tests
npm run e2e:backend    # backend API tests only (no browser needed)
npm run e2e:frontend   # browser tests only
```

**You don't need to start anything first.** Playwright checks `http://localhost:8080/actuator/health` and `http://localhost:5173`. It starts whichever app isn't running, and stops what it started at the end. If `npm run dev` is already running, the tests use it, which is much faster when you run them repeatedly.

Expected (about 35 s; the first cold run takes longer while the backend builds):

```
  ok 1 [backend] › backend\game-api.spec.ts:13:1 › a player can join, bet, see the lock-in and see the bet settle (7.8s)
  ok 3 [backend] › backend\game-api.spec.ts:36:1 › a retried request is not charged twice
  ...
  ok 8 [frontend] › frontend\game.spec.ts:44:1 › two players watching see the same arrow
  8 passed (34.3s)
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

1. Join, bet, see the lock-in and see the bet settle. The payout equals `stake × locked multiplier`, and the balance matches.
2. A retried request (same `requestId`) returns the same bet and is charged once.
3. Reconnecting as the same player restores the balance and the open or recent bets.
4. A connection without a valid player id is closed with a policy violation (1008).
5. Browsers from untrusted origins are refused (HTTP 403).
6. Browsers from allowed origins are accepted.

**Frontend, [game.spec.ts](../e2e/frontend/game.spec.ts):**

1. A player sets a stake, bets on a block, sees the 🔒 lock-in and the balance drop, then sees a win or a miss in "My bets".
2. Two separate players (separate browser contexts) see the same arrow.

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
- **Talk to the backend only through its public protocol.** Use [e2e/support/game-client.ts](../e2e/support/game-client.ts) (`connect`, `waitFor`, `placeBetOnAnOpenBlock`) rather than reaching into the server.
- **Use a fresh player per test** (`newPlayerId()`). The server may be shared and long-running.
- **Never assume the path.** Assert rules and invariants. Allow for a block closing between reading the board and betting (`placeBetOnAnOpenBlock` retries on `CELL_CLOSED`).
- **Wait for messages, don't sleep.** Use `waitFor` / `expect.poll`, with timeouts based on the game's speed (a bet lasts at most 10 moves × 0.7 s).
- **Keep these tests few and valuable.** Each one costs seconds. A rule that can be checked without a running system belongs in the [unit tests](UNIT_TESTS.md).

## CI

[.github/workflows/e2e.yml](../.github/workflows/e2e.yml) runs when the backend, the frontend, `e2e/` or the Maven runner changes. It installs JDK 25, Node 24 and Chromium, lets Playwright start both apps, and uploads the HTML report and failure traces as the `e2e-report` artifact.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Executable doesn't exist ... chromium` | Run `npm run e2e:install` once. |
| `The backend needs JDK 25 or newer` / timed out waiting for `http://localhost:8080/actuator/health` | Playwright couldn't start the backend. Set `JAVA_HOME` to JDK 25, or start the backend yourself first (`npm run dev`) to see its log. |
| `Port 8080/5173 is already in use` but tests can't connect | Something other than this app holds the port. Stop it, or run `npm run dev` so Playwright reuses the real apps. |
| `timed out ... waiting for a matching message` | The backend is slow or overloaded (for example the first run after a build). Rerun. If it keeps happening, check that `game.step-millis` hasn't been raised. |
| Origin test fails against a deployed `API_URL` | Expected when `ALLOWED_ORIGINS` doesn't include `http://localhost:5173`; see [Against a deployed backend](#against-a-deployed-backend). |
