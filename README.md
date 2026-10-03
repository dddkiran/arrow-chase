# Arrow Chase — working slice

A continuous, shared multiplayer game. An arrow moves across an 8×8 grid in real time. Each block shows a live multiplier. You stake an amount and click a block, and the server locks that block's multiplier for your bet. If the arrow enters the block within the next 10 moves, you win `stake × locked multiplier`.

The server owns the path, the lock-in and settlement. The browser only renders what the server says.

| Document | What it covers |
|---|---|
| This README | **Execution guide**: install, run, test, expected output |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design, scaling, failure modes, roadmap, and the summary for leadership |
| [docs/AI_NOTE.md](docs/AI_NOTE.md) | How AI assistants were used, and the standards I'd set for a squad |
| [docs/ESTIMATE.md](docs/ESTIMATE.md) | Bonus: estimate to ship with 1 BE, 1 FE and 1 QA |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Deploying backend and frontend as separate Cloud Run services, and CI |

**Quick start** (JDK 25 and Node 20+ installed):

```bash
npm install     # root: installs the dev runner and the frontend's dependencies
npm run dev     # starts backend (:8080) and frontend (:5173) together; open http://localhost:5173
npm test        # runs backend and frontend test suites
```

---

## 1. Prerequisites

| Tool | Version | Check with |
|---|---|---|
| JDK | **25** or newer (Temurin, Corretto, etc.) | `java -version` |
| Node.js | **20** or newer (built and tested on 24) | `node -v` |
| Internet access | the first build downloads Maven and npm packages | |

You don't need to install Maven. The repo includes the Maven wrapper (`mvnw` / `mvnw.cmd`).

> **Check `JAVA_HOME`.** Point it at a JDK 25 or newer, or the backend won't compile.
> PowerShell: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25..."`
> bash: `export JAVA_HOME=/path/to/jdk-25`

Ports used: **8080** (backend) and **5173** (frontend dev server).

## 2. Run the game

### One command (recommended)

From the repository root:

```bash
npm install
npm run dev
```

Expected: the output is interleaved with `[api]` and `[web]` prefixes. After the first-time dependency download (1–3 minutes) you'll see `[api] ... Started ArrowChaseApplication` and `[web] ... Local: http://localhost:5173/`. Open **http://localhost:5173**. If the page says "Connecting…" for a few seconds, the backend is still starting; the page connects on its own when it's ready. `Ctrl+C` stops both.

### Or run each app on its own

The two apps are independent (they're deployed separately; see [docs/DEPLOY.md](docs/DEPLOY.md)). To run them by hand, use two terminals, both starting at the repository root.

**Terminal 1: backend**

```bash
cd backend
./mvnw spring-boot:run          # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

Expected: after the dependency download (1–3 minutes the first time), the log ends with:

```
Tomcat started on port 8080 (http) with context path '/'
Game loop started: one step every 700 ms
Started ArrowChaseApplication in ~2 seconds
```

Health check: `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}`.

**Terminal 2: frontend**

```bash
cd frontend
npm ci
npm run dev
```

Expected: `VITE ... ready` and `Local: http://localhost:5173/`.

**Play:** open **http://localhost:5173**. Each browser profile gets its own player and a demo balance of 1,000.00. To see the shared game, open a second window in private/incognito mode, which counts as a second player. Both windows show the same arrow, and wins from either one appear in both "Live wins" feeds.

### What to do as a reviewer

1. **Set a stake**: type an amount or click a chip (0.50 … 25.00).
2. **Select a block**: click any block that isn't dimmed. For a moment it shows `locking…`.
3. **See the lock-in**: when the server confirms, the tile shows a gold 🔒 badge with the **locked** multiplier. The big number above it keeps changing, but your bet keeps the locked value. The small number in the corner counts the moves left. Your balance drops by the stake.
4. **Watch the arrow**: everyone sees the same arrow. Its recent path is shaded.
5. **See the result**: if the arrow enters your block within 10 moves (about 7 s), the tile flashes green, a "Won …" toast appears, and your balance goes up by `stake × locked multiplier`. Otherwise you see "Missed". Results are listed in **My bets**.

Dimmed blocks (–) are closed: the arrow either can't reach them within the window, or is so likely to hit them that the multiplier would fall below 1.05×.

Things worth trying: bet on several blocks at once, refresh the page in the middle of a bet (your balance and open bets come back), or stop the backend and start it again (the client reconnects by itself; balances reset because state is in memory, see the architecture note).

## 3. Run the tests

### Test layers

| Layer | Where | What is real | Count |
|---|---|---|---|
| Backend unit, property and simulation | `backend/src/test/.../game/` | Pure engine; path and quotes scripted or seeded | 34 |
| Backend integration (API level) | `backend/src/test/.../ws/GameWebSocketTest` | Real Spring server and WebSocket client, no browser | 6 |
| Frontend unit and component | `frontend/src/**/*.test.ts(x)` | Reducer and components, no network | 32 |
| **Full-stack end-to-end** | `e2e/tests/` | Real browser → real frontend → real backend. **Nothing mocked.** | 2 |

### Run them

**Unit and integration, everything at once**, from the repository root (after `npm install`):

```bash
npm test
```

This runs the backend suite, then the frontend suite. Each prints the expected output shown below. To run one app's tests on its own:

**Backend: 40 tests** (engine rules, path and pricing properties, a 20,000-step simulation, and real WebSocket tests including origin checks)

```bash
cd backend
./mvnw test                     # Windows PowerShell: .\mvnw.cmd test
```

Expected (takes about 15 s after the first build):

```
[INFO] Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Frontend: 32 tests** (state reducer, formatting and money parsing, tile and stake components)

```bash
cd frontend
npm ci        # if you haven't already
npm test
```

Expected:

```
 Test Files  3 passed (3)
      Tests  32 passed (32)
```

Type check and production build: `npm run build` (should finish with no errors).

**End-to-end (2 tests)** in [e2e/](e2e/). These play one full bet in a real browser (stake → lock-in → win or miss), and check that two separate players see the same arrow. Playwright **starts the backend and frontend itself**, or reuses them if `npm run dev` is already running. From the repository root:

```bash
npm run e2e:install   # one-time: downloads Chromium (~115 MB)
npm run e2e
```

Expected: `2 passed` (about 25 s; the first cold run takes longer while the backend builds). It saves a screenshot to `e2e/test-results/after-settlement.png`. You may see `[vite] ws proxy error: write ECONNABORTED` lines near the end. They're harmless: Playwright closes the browser while the server is still streaming moves.

## 4. Configuration

All game tuning is in [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml) under `game.*`: grid size, step speed, bet window, target RTP, jitter, stake limits and starting balance. Any value can be overridden on the command line, for example:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--game.step-millis=400 --game.seed=42"
```

Setting `game.seed` makes the arrow path and quotes repeat exactly, which helps when debugging.

## 5. Project layout

Monorepo: two independently built and deployed apps. The root holds the cross-app e2e suite, orchestration (dev runner, CI, deploy script) and docs.

```
package.json                  Root scripts only: dev / test / build / e2e across both apps
scripts/                      mvnw.mjs (cross-platform Maven wrapper runner), deploy-cloudrun.sh
docker-compose.yml            Production-like local run of both Cloud Run images (needs Docker)
.github/workflows/            CI: backend.yml and frontend.yml (path-filtered), e2e.yml (both apps)
backend/                      Java 25, Spring Boot 3.5, plain WebSocket + JSON  (own Dockerfile)
  src/main/java/com/arrowchase/
    game/                     Pure domain, no Spring: engine, path model, pricing (unit-testable, deterministic)
    app/                      Game loop (single thread that drives the engine), wiring, config
    ws/                       WebSocket gateway, wire protocol, client fan-out
  src/test/java/...           JUnit 5 + AssertJ
frontend/                     React 19 + TypeScript + Vite  (own Dockerfile: nginx serves the build, proxies /ws)
  src/game/                   Protocol types, state reducer, WebSocket hook (reconnect + resend)
  src/components/             Board/Tile, stake input, bet list, win feed
e2e/                          Full-stack Playwright tests (own package; starts both apps itself)
docs/                         Architecture, AI note, estimate, deployment
```

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| `release version 25 not supported` / `invalid target release` | `JAVA_HOME` points at an old JDK. Point it at JDK 25 or newer. |
| `Port 8080 was already in use` | Stop the other process, or run with `-Dspring-boot.run.arguments=--server.port=8081` and start the frontend with `BACKEND_URL=http://localhost:8081 npm run dev`. |
| Page stays on "Connecting…" / "Server unreachable, retrying…" | The backend isn't running, or isn't on 8080. The frontend proxies `/ws` to it. With `npm run dev`, check the `[api]` lines for a startup error. |
| WebSocket closes immediately (403) when the page isn't on localhost | The backend only accepts origins listed in `ALLOWED_ORIGINS` (default `http://localhost:*,http://127.0.0.1:*`). |
| Every block click is ignored | The stake is invalid (the hint under the input turns red), or the connection is down. |
