#!/bin/bash
# refresh-dashboards.sh — re-fetch Grafana dashboards from the main branch
# and nudge Grafana to reload them. Safe to run idempotently.
#
# Why this exists: user-data fetches dashboards from GitHub raw on first
# boot with a non-retrying `curl -fsSL || echo Warning`. Any transient
# failure (raw CDN hiccup, GitHub rate-limit for a moment) silently leaves
# the instance missing dashboards and only a warning in cloud-init.log.
# This script re-runs the fetches with retries + a Mozilla UA, then
# restarts Grafana so the provisioner picks up the restored files.
#
# Usage (from any shell with sudo):
#   curl -fsSL https://raw.githubusercontent.com/jnoecker/AmbonMUD/main/infra/scripts/refresh-dashboards.sh | sudo bash

set -euo pipefail

DASH_DIR=/app/grafana/provisioning/dashboards
DASH_BASE="https://raw.githubusercontent.com/jnoecker/AmbonMUD/main/infra/grafana/provisioning/dashboards"
DASHBOARDS=(
  ambon_overview
  ambon_engine
  ambon_engine_health
  ambon_gameplay
  ambon_jvm
  ambon_persistence
  ambon_scheduler
  ambon_transport
)

mkdir -p "$DASH_DIR"

failed=()
for dash in "${DASHBOARDS[@]}"; do
  url="$DASH_BASE/${dash}.json"
  dest="$DASH_DIR/${dash}.json"
  if curl -fsSL \
       --retry 10 --retry-delay 5 --retry-all-errors \
       -A "Mozilla/5.0 (compatible; AmbonMUD-refresh/1.0)" \
       -o "$dest" "$url"; then
    echo "ok: $dash"
  else
    echo "FAIL: $dash" >&2
    failed+=("$dash")
  fi
done

if [ "${#failed[@]}" -gt 0 ]; then
  echo "Some dashboards failed to fetch: ${failed[*]}" >&2
  exit 1
fi

if systemctl is-active --quiet grafana; then
  echo "Restarting grafana to pick up refreshed provisioning files..."
  systemctl restart grafana
else
  echo "grafana.service not active — skipping restart. Dashboards on disk; start grafana to load them."
fi

echo "Done. Visit Grafana at /grafana/ or :3000 to confirm."
