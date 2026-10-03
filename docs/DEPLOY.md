# Deploying to Cloud Run

Backend and frontend are **two independent Cloud Run services**. Each is built from its own folder and Dockerfile, so each can be released on its own schedule.

```
 browser ──https/wss──► arrow-chase-web (nginx)          ──wss /ws──► arrow-chase-api (Spring Boot)
                         static React build                           game engine, single instance
                         proxies /ws to BACKEND_URL                   accepts Origin = web URL only
```

The browser only ever talks to the web service's origin. nginx forwards `/ws` to the API, which means:

- there's no backend URL baked into the frontend build, so one image works for every environment;
- there's no CORS, and the API only has to trust one origin (`ALLOWED_ORIGINS`).

## One-time setup

```bash
gcloud auth login
gcloud config set project <PROJECT_ID>
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com
```

## Deploy

From the repository root (bash or Git Bash):

```bash
PROJECT=<PROJECT_ID> REGION=europe-west1 ./scripts/deploy-cloudrun.sh all   # first time: api, then web
PROJECT=<PROJECT_ID> ./scripts/deploy-cloudrun.sh api                       # backend only
PROJECT=<PROJECT_ID> ./scripts/deploy-cloudrun.sh web                       # frontend only
```

The script uses `gcloud run deploy --source <folder>`. Cloud Build builds that folder's Dockerfile and pushes the image to Artifact Registry. Then:

1. **api** deploys with `ALLOWED_ORIGINS` set to the web URL (`http://localhost:*` on the very first deploy, before the web service exists).
2. **web** deploys with `BACKEND_URL` set to the API's URL, then updates the API's `ALLOWED_ORIGINS` to the web URL.

The script prints the game URL at the end.

## Settings, and why

| Service | Setting | Why |
|---|---|---|
| api | `--max-instances 1` | **The game state lives in one process's memory.** A second instance would run a second, different game, and players would see different arrows depending on which instance they hit. This is the slice's main scaling limit (see ARCHITECTURE.md §2–3). |
| api | `--min-instances 1` | The game is continuous. Scaling to zero would stop the arrow and wipe balances. |
| api | `--no-cpu-throttling` | The game loop ticks in the background, not only while a request is in flight. With throttled CPU the arrow would stall between requests. |
| api | `--timeout 3600` | WebSockets count as one long request. Cloud Run's maximum is 60 minutes. After that the client reconnects on its own and gets a fresh snapshot. |
| api | `--concurrency 1000` | One instance has to hold every socket. |
| web | `--timeout 3600`, scales 0–5 | nginx is stateless; it only proxies the socket. |

**Known effects of this setup (fine for a slice, listed on the roadmap):**

- **During a backend deploy, Cloud Run briefly runs the old and new revisions side by side.** That's two separate games for a few seconds, and the new one starts with fresh balances because state is in memory. Clients reconnect and resync. The fix is the durable event log in roadmap Phase 1.
- **The API is publicly reachable** (`--allow-unauthenticated`), because nginx proxies to it without an identity token. Production would set `--ingress internal` on the API and route the web service through a VPC connector, or put both behind one HTTPS load balancer with path routing.
- **No custom domain or CDN.** Both are a load-balancer configuration change; the code doesn't need to change.

## Check a deployment

```bash
WEB=$(gcloud run services describe arrow-chase-web --region europe-west1 --format 'value(status.url)')
API=$(gcloud run services describe arrow-chase-api --region europe-west1 --format 'value(status.url)')
curl -s "$API/actuator/health"        # {"status":"UP"}
open "$WEB"                           # or paste into a browser; two windows should show the same arrow
gcloud run services logs read arrow-chase-api --region europe-west1 --limit 50
```

## Try the production images locally first (needs Docker)

```bash
docker compose up --build    # game at http://localhost:8081, API on :8080
```

## CI

`.github/workflows/` has one workflow per app, each triggered only by changes in its own folder. Each runs that app's tests, then builds its Cloud Run image. A third workflow, `e2e`, runs the full-stack Playwright suite in [e2e/](../e2e/) whenever either app or the suite changes. Deployment is deliberately not automated yet. The next step would be a deploy job using Workload Identity Federation (no service-account keys), gated on `main` and a manual approval for production.
