#!/usr/bin/env bash
#
# import-session.sh - import an already-exported BrickLink session into a running
# gateway pod. The IMPORT HALF ONLY: it does not (and cannot) do the LEGO sign-in,
# which must happen in a real browser on a PC (see runbook.md section 5.1).
#
# Use this on an always-on machine that has kubectl access to the cluster, such as
# rasppi5-01, when you would rather run the cluster-side import there than on the PC.
# First sign in and export on the PC, then copy the state file over:
#
#     scp D:\dev\config\bricklink-session\portable-state.json \
#         sysadmin@rasppi5-01:~/portable-state.json
#     ssh rasppi5-01 './import-session.sh services-prod ~/portable-state.json'
#
# The /admin/session/import and /session endpoints are WIRED UP IN PHASE 1
# (HOMELAB-90). Until then this script will fail at the curl call because the
# gateway does not exist yet - that is expected.
#
# The admin token must come from your secret store, never hard-coded or passed on
# the command line. Point TOKEN_FILE at a root-readable file, or export
# BRICKLINK_GATEWAY_ADMIN_TOKEN before running.

set -euo pipefail

NS="${1:-services-prod}"
STATE="${2:-$HOME/portable-state.json}"
DEPLOYMENT="${DEPLOYMENT:-deploy/bricklink-web-gateway}"
LOCAL_PORT="${LOCAL_PORT:-8443}"
TOKEN_FILE="${TOKEN_FILE:-/etc/bricklink/gateway-admin-token}"
CA_FILE="${CA_FILE:-/etc/bricklink/gateway-ca.pem}"

if [[ ! -f "$STATE" ]]; then
  echo "ERROR: state file not found: $STATE" >&2
  echo "Sign in and export on the PC first, then copy it here (see header)." >&2
  exit 2
fi

# Token: prefer the env var, else a protected file.
TOKEN="${BRICKLINK_GATEWAY_ADMIN_TOKEN:-}"
if [[ -z "$TOKEN" && -r "$TOKEN_FILE" ]]; then
  TOKEN="$(cat "$TOKEN_FILE")"
fi
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: no admin token. Set BRICKLINK_GATEWAY_ADMIN_TOKEN or populate $TOKEN_FILE." >&2
  exit 2
fi

# Always destroy the state file and stop the port-forward on exit, success or not.
PF_PID=""
cleanup() {
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  shred -u "$STATE" 2>/dev/null || rm -f "$STATE"
}
trap cleanup EXIT

echo "[1/3] Port-forwarding to $DEPLOYMENT in $NS ..."
kubectl -n "$NS" port-forward "$DEPLOYMENT" "${LOCAL_PORT}:8443" >/dev/null 2>&1 &
PF_PID=$!
sleep 3

BASE="https://localhost:${LOCAL_PORT}"
CACERT_ARG=()
[[ -r "$CA_FILE" ]] && CACERT_ARG=(--cacert "$CA_FILE")

echo "[2/3] Importing the session (phase-1 endpoint) ..."
curl --fail --silent --show-error "${CACERT_ARG[@]}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "state=@${STATE}" \
  "${BASE}/admin/session/import"

echo
echo "[3/3] Verifying gateway session state ..."
curl --fail --silent --show-error "${CACERT_ARG[@]}" \
  -H "Authorization: Bearer ${TOKEN}" \
  "${BASE}/session"

echo
echo "Done. The local export has been shredded. Confirm the SIGNED_OUT alert clears."
