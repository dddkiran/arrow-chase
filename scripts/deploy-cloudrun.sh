#!/usr/bin/env bash
# Deploys backend and frontend as two independent Cloud Run services, built from source with Cloud Build.
# Usage:  PROJECT=my-gcp-project REGION=europe-west1 ./scripts/deploy-cloudrun.sh [api|web|all]
# See docs/DEPLOY.md for what each setting means and why.
set -euo pipefail

: "${PROJECT:?set PROJECT to your GCP project id}"
REGION="${REGION:-europe-west1}"
API="${API_SERVICE:-arrow-chase-api}"
WEB="${WEB_SERVICE:-arrow-chase-web}"
TARGET="${1:-all}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

url_of() {
  gcloud run services describe "$1" --project "$PROJECT" --region "$REGION" --format 'value(status.url)' 2>/dev/null || true
}

deploy_api() {
  # One instance, always on, CPU always allocated: the game state lives in this process's memory and the
  # game loop must keep ticking with no request in flight. See docs/DEPLOY.md before changing these.
  local web_url
  web_url="$(url_of "$WEB")"
  gcloud run deploy "$API" \
    --project "$PROJECT" --region "$REGION" \
    --source "$ROOT/backend" \
    --allow-unauthenticated \
    --min-instances 1 --max-instances 1 \
    --no-cpu-throttling --cpu 1 --memory 1Gi \
    --concurrency 1000 \
    --timeout 3600 \
    --set-env-vars "ALLOWED_ORIGINS=${web_url:-http://localhost:*}"
}

deploy_web() {
  local api_url
  api_url="$(url_of "$API")"
  if [[ -z "$api_url" ]]; then
    echo "Backend service $API not found; deploy it first (./scripts/deploy-cloudrun.sh api)." >&2
    exit 1
  fi
  gcloud run deploy "$WEB" \
    --project "$PROJECT" --region "$REGION" \
    --source "$ROOT/frontend" \
    --allow-unauthenticated \
    --min-instances 0 --max-instances 5 \
    --cpu 1 --memory 256Mi \
    --concurrency 1000 \
    --timeout 3600 \
    --set-env-vars "BACKEND_URL=$api_url"

  # The backend only accepts WebSockets from origins it trusts; point it at the frontend's URL.
  gcloud run services update "$API" --project "$PROJECT" --region "$REGION" \
    --update-env-vars "ALLOWED_ORIGINS=$(url_of "$WEB")"
}

case "$TARGET" in
  api) deploy_api ;;
  web) deploy_web ;;
  all) deploy_api; deploy_web ;;
  *) echo "usage: $0 [api|web|all]" >&2; exit 2 ;;
esac

echo "Game: $(url_of "$WEB")"
