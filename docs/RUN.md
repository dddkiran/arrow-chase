# How to run

How to install, run and play the slice on a clean machine. To run the tests, follow [UNIT_TESTS.md](UNIT_TESTS.md) and [E2E_TESTS.md](E2E_TESTS.md); both use the prerequisites below.

## 1. Prerequisites

| Tool | Version | Check with |
|---|---|---|
| JDK | **25** or newer (Temurin, Corretto, etc.) | `java -version` |
| Node.js | **20** or newer (built and tested on 24) | `node -v` |
| Internet access | the first build downloads Maven and npm packages | |

You don't need to install Maven. The repo includes the Maven wrapper (`mvnw` / `mvnw.cmd`).

> **Check `JAVA_HOME`.** Maven uses `JAVA_HOME` if it's set, and otherwise `java` on your PATH, which on many machines is an old Java 8 or 17. The root scripts check this first and tell you if it's wrong.
>
> Windows (PowerShell) install and setup:
> ```powershell
> winget install --id EclipseAdoptium.Temurin.25.JDK -e
> $jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-25*' | Select-Object -First 1).FullName
> [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')   # then open a NEW terminal
> ```
> macOS: `brew install --cask temurin@25 && export JAVA_HOME=$(/usr/libexec/java_home -v 25)`
> Linux: install a JDK 25 (for example `sdk install java 25-tem`) and `export JAVA_HOME=/path/to/jdk-25`

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

The two apps are independent (they're deployed separately; see [DEPLOY.md](DEPLOY.md)). To run them by hand, use two terminals, both starting at the repository root.

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

API docs: Swagger UI at http://localhost:8080/swagger-ui.html (OpenAPI JSON at `/v3/api-docs`). It lists the actuator endpoints and, under **Schemas**, every WebSocket message on `/ws`, with the connect URL and message flow in the description at the top.

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

Things worth trying: bet on several blocks at once, refresh the page in the middle of a bet (your balance and open bets come back), or stop the backend and start it again (the client reconnects by itself; balances reset because state is in memory, see the [architecture note](ARCHITECTURE.md)).

## 3. Configuration

All game tuning is in [backend/src/main/resources/application.yml](../backend/src/main/resources/application.yml) under `game.*`: grid size, step speed, bet window, target RTP, jitter, stake limits and starting balance. Any value can be overridden on the command line, for example:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--game.step-millis=400 --game.seed=42"
```

Setting `game.seed` makes the arrow path and quotes repeat exactly, which helps when debugging.

## 4. Troubleshooting

| Symptom | Fix |
|---|---|
| `The backend needs JDK 25 or newer, but … is Java 8` | Maven would run on an old JDK. Install JDK 25 and set `JAVA_HOME` (see Prerequisites), then open a new terminal. |
| `Unrecognized option: --sun-misc-unsafe-memory-access` or `release version 25 not supported` when running `./mvnw` directly | Same cause: the JDK is older than 25. |
| `Port 8080 was already in use` | Stop the other process, or run with `-Dspring-boot.run.arguments=--server.port=8081` and start the frontend with `BACKEND_URL=http://localhost:8081 npm run dev`. |
| Page stays on "Connecting…" / "Server unreachable, retrying…" | The backend isn't running, or isn't on 8080. The frontend proxies `/ws` to it. With `npm run dev`, check the `[api]` lines for a startup error. |
| WebSocket closes immediately (403) when the page isn't on localhost | The backend only accepts origins listed in `ALLOWED_ORIGINS` (default `http://localhost:*,http://127.0.0.1:*`). |
| Every block click is ignored | The stake is invalid (the hint under the input turns red), or the connection is down. |

Test-specific problems are covered in [UNIT_TESTS.md](UNIT_TESTS.md#troubleshooting) and [E2E_TESTS.md](E2E_TESTS.md#troubleshooting).
