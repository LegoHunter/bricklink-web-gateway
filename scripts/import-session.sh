#!/usr/bin/env bash
#
# import-session.sh - import an already-exported BrickLink session into a running
# gateway. The IMPORT HALF ONLY: it does not (and cannot) do the LEGO sign-in,
# which must happen in a real browser on a PC (see runbook.md section 5.1).
#
# Use this on an always-on machine on the home network, such as rasppi5-01, when
# you would rather run the import there than on the PC. First sign in and export
# on the PC, then copy the state file over:
#
#     scp D:\dev\config\bricklink-session\portable-state.json \
#         sysadmin@rasppi5-01:~/portable-state.json
#     ssh rasppi5-01 './import-session.sh prod ~/portable-state.json'
#
# The admin token must come from your secret store, never hard-coded or passed on
# the command line. Point TOKEN_FILE at a root-readable file, or export
# BRICKLINK_GATEWAY_ADMIN_TOKEN before running. Each environment has its own token.

set -euo pipefail

ENVIRONMENT="${1:-prod}"
STATE="${2:-$HOME/portable-state.json}"
TOKEN_FILE="${TOKEN_FILE:-/etc/bricklink/gateway-admin-token}"

case "$ENVIRONMENT" in
  sandbox|dev|prod) ;;
  *) echo "ERROR: environment must be sandbox, dev or prod (got: $ENVIRONMENT)" >&2; exit 2 ;;
esac
# Override only to reach the gateway some other way (for example a local run).
BASE="${BASE_URL:-https://bricklink-web-gateway.${ENVIRONMENT}.internal.legohunter.io}"

if [[ ! -f "$STATE" ]]; then
  echo "ERROR: state file not found: $STATE" >&2
  echo "Sign in and export on the PC first, then copy it here (see header)." >&2
  exit 2
fi

# Always destroy the state file on exit, success or not.
cleanup() {
  shred -u "$STATE" 2>/dev/null || rm -f "$STATE"
}
trap cleanup EXIT

# Token: prefer the env var, else a protected file.
TOKEN="${BRICKLINK_GATEWAY_ADMIN_TOKEN:-}"
if [[ -z "$TOKEN" && -r "$TOKEN_FILE" ]]; then
  TOKEN="$(cat "$TOKEN_FILE")"
fi
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: no admin token. Set BRICKLINK_GATEWAY_ADMIN_TOKEN or populate $TOKEN_FILE." >&2
  exit 2
fi

echo "[1/2] Importing the session into $BASE ..."
# The token goes to curl on standard input, so it never appears in the process list.
printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" | curl --config - --fail-with-body --silent --show-error \
  -F "state=@${STATE}" \
  "${BASE}/admin/session/import"

echo
echo "[2/2] Verifying gateway session state ..."
curl --fail --silent --show-error "${BASE}/session"

echo
echo "Done. The local export has been shredded. Confirm the SIGNED_OUT alert clears."
