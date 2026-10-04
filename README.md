# Arrow Chase — working slice

A continuous, shared multiplayer game. An arrow moves across an 8×8 grid in real time. Each block shows a live multiplier. You stake an amount and click a block, and the server locks that block's multiplier for your bet. If the arrow enters the block within the next 10 moves, you win `stake × locked multiplier`.

The server owns the path, the lock-in and settlement. The browser only renders what the server says.

## Documents

**Execution guide:** start with [docs/RUN.md](docs/RUN.md), then run the tests with [docs/UNIT_TESTS.md](docs/UNIT_TESTS.md) and [docs/E2E_TESTS.md](docs/E2E_TESTS.md). Each covers install, commands and expected output.

| Document | What it covers |
|---|---|
| [docs/RUN.md](docs/RUN.md) | **How to run**: prerequisites, running the game, what to try as a reviewer, configuration, troubleshooting |
| [docs/UNIT_TESTS.md](docs/UNIT_TESTS.md) | **How to run unit tests** (backend and frontend): what each suite locks, single-test runs, conventions |
| [docs/E2E_TESTS.md](docs/E2E_TESTS.md) | **How to run e2e tests** (backend API and browser) against the running system: debugging, conventions |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design, scaling, failure modes, roadmap, and the summary for leadership |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Deploying backend and frontend as separate Cloud Run services, and CI |
| [docs/AI_NOTE.md](docs/AI_NOTE.md) | How AI assistants were used, and the standards I'd set for a squad |
| [docs/ESTIMATE.md](docs/ESTIMATE.md) | Bonus: estimate to ship with 1 BE, 1 FE and 1 QA |

## Quick start

JDK 25 and Node 20+ installed (setup steps in [RUN.md](docs/RUN.md#1-prerequisites)). From the repository root:

```bash
npm install     # installs the dev runner and the frontend and e2e dependencies
npm run dev     # starts backend (:8080) and frontend (:5173) together; open http://localhost:5173
npm test        # unit tests: backend, then frontend
npm run e2e     # functional tests of the running system (one-time setup: npm run e2e:install)
```

## Test layers

The rule: **each app's own folder holds unit tests** (fast, nothing running). **`e2e/` holds functional tests of the running system, one folder per app.** Nothing in `e2e/` is mocked.

| Layer | Where | What is real | Count | Guide |
|---|---|---|---|---|
| Backend unit, property and simulation | `backend/src/test/` | Pure engine; path and quotes scripted or seeded | 34 | [UNIT_TESTS.md](docs/UNIT_TESTS.md) |
| Frontend unit and component | `frontend/src/**/*.test.ts(x)` | Reducer and components, no network | 32 | [UNIT_TESTS.md](docs/UNIT_TESTS.md) |
| Backend functional | `e2e/backend/` | Running backend, driven black-box over its public WebSocket API | 6 | [E2E_TESTS.md](docs/E2E_TESTS.md) |
| Frontend functional (full stack) | `e2e/frontend/` | Real browser → running frontend → running backend | 2 | [E2E_TESTS.md](docs/E2E_TESTS.md) |

## Project layout

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
  src/test/java/...           Unit tests: JUnit 5 + AssertJ
frontend/                     React 19 + TypeScript + Vite  (own Dockerfile: nginx serves the build, proxies /ws)
  src/game/                   Protocol types, state reducer, WebSocket hook (reconnect + resend)
  src/components/             Board/Tile, stake input, bet list, win feed
e2e/                          Functional tests of the running system (own package; starts both apps itself)
  backend/                    Black-box WebSocket API tests (no browser)
  frontend/                   Browser tests through the real frontend
  support/                    Shared protocol-level test client
docs/                         Run, unit tests, e2e tests, architecture, deployment, AI note, estimate
```
