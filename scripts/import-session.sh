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
#
# The state file is a live credential. Once it has been sent to the gateway it is
# destroyed, whether the import succeeded or not. A run that stops before sending
# (wrong environment name, no admin token) keeps it and says so.

set -euo pipefail

ENVIRONMENT="${1:-prod}"
STATE="${2:-$HOME/portable-state.json}"
TOKEN_FILE="${TOKEN_FILE:-/etc/bricklink/gateway-admin-token}"

# Stops before anything was sent to the gateway. The state file is left in place,
# so the run can be repeated once the problem is fixed.
stop_before_sending() {
  echo "ERROR: $1" >&2
  if [[ -f "$STATE" ]]; then
    echo "Nothing was sent. The state file was kept: $STATE" >&2
    echo "It is a live credential: run the import again or delete it." >&2
  fi
  exit 2
}

case "$ENVIRONMENT" in
  sandbox|dev|prod) ;;
  *) stop_before_sending "environment must be sandbox, dev or prod (got: $ENVIRONMENT)" ;;
esac
# Override only to reach the gateway some other way (for example a local run).
BASE="${BASE_URL:-https://bricklink-web-gateway.${ENVIRONMENT}.internal.legohunter.io}"

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
  stop_before_sending "no admin token. Set BRICKLINK_GATEWAY_ADMIN_TOKEN or populate $TOKEN_FILE."
fi

# From here on the state file goes to the gateway, so it is destroyed on exit,
# success or not. Everything that can stop the run without sending is above.
cleanup() {
  local status=$?
  shred -u "$STATE" 2>/dev/null || rm -f "$STATE"
  if [[ $status -ne 0 ]]; then
    echo >&2
    echo "The import did not complete (see the answer above and runbook.md section 5.3)." >&2
    echo "The state file has been deleted: $STATE. Export again before another try." >&2
  fi
}
trap cleanup EXIT

echo "[1/2] Importing the session into $BASE ..."
# The token goes to curl on standard input, so it never appears in the process list.
printf 'header = "Authorization: Bearer %s"
' "$TOKEN" | curl --config - --fail-with-body --silent --show-error   -F "state=@${STATE}"   "${BASE}/admin/session/import"

echo
echo "[2/2] Verifying gateway session state ..."
curl --fail --silent --show-error "${BASE}/session"

echo
echo "Done. The state file will now be deleted. If a signed-out alert was open for this"
echo "environment, it clears a few minutes after the gateway reports SIGNED_IN."
