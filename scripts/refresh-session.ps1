<#
.SYNOPSIS
  Refresh the BrickLink web gateway's signed-in session: manual login on this PC,
  then import the session into a running gateway pod.

.DESCRIPTION
  Run this on the Windows PC when the gateway reports SIGNED_OUT (or at bootstrap).
  It wraps the whole recovery so your only manual action is signing in to the
  browser window that opens.

  The LEGO sign-in itself cannot be automated — LEGO blocks automated browsers
  (HOMELAB-93). `login.mjs` opens a REAL Chrome and waits for YOU to sign in by
  hand; that is allowed. Everything else here is scripted.

  Steps:
    1. node login.mjs      - opens Chrome; you enter username, password, emailed code
    2. node check.mjs      - verifies the new session
    3. node export-state.mjs - writes portable-state.json (a live credential)
    4. kubectl port-forward  - reach the gateway privately (no public route)
    5. POST to the gateway admin import endpoint   [WIRED UP IN PHASE 1 / HOMELAB-90]
    6. GET /session          - confirm SIGNED_IN
    7. delete portable-state.json

  Until phase 1 lands the import endpoint, steps 1-3 work today and steps 4-6 are
  guarded behind -DoImport so the script does not call an endpoint that does not
  exist yet. See runbook.md section 5.

.NOTES
  The admin token must come from your secret store, not the command line. Set it as
  an environment variable before running:  $env:BRICKLINK_GATEWAY_ADMIN_TOKEN = "..."
#>
[CmdletBinding()]
param(
  [string]$Namespace  = "services-prod",
  [string]$SessionDir = "D:\dev\config\bricklink-session",
  [string]$SpikeDir   = "D:\dev\projects\lego-collection\bricklink-html\spike\homelab-79",
  [string]$Deployment = "deploy/bricklink-web-gateway",
  [int]   $LocalPort  = 8443,
  # Omit on a login-only dry run; add once the phase-1 import endpoint exists.
  [switch]$DoImport
)

$ErrorActionPreference = "Stop"
$state = Join-Path $SessionDir "portable-state.json"

# --- 1-3: sign in on this PC and export the session (works today) ---------------
Push-Location $SpikeDir
try {
  Write-Host "`n[1/7] Opening Chrome for manual sign-in. Enter your username, password, and the emailed code, then wait until you land on your BrickLink page." -ForegroundColor Cyan
  node login.mjs

  Write-Host "`n[2/7] Verifying the new session..." -ForegroundColor Cyan
  node check.mjs --http

  Write-Host "`n[3/7] Exporting the portable session..." -ForegroundColor Cyan
  node export-state.mjs
}
finally {
  Pop-Location
}

if (-not (Test-Path $state)) {
  throw "Export did not produce $state - the sign-in likely did not complete. Nothing imported."
}

if (-not $DoImport) {
  Write-Host "`nLogin and export complete. Re-run with -DoImport once the phase-1 import endpoint exists to push it to the $Namespace gateway." -ForegroundColor Yellow
  Write-Host "The export at $state is a live credential - delete it when you are done with it." -ForegroundColor Yellow
  return
}

# --- 4-6: import into the running gateway pod -----------------------------------
# NOTE: /admin/session/import and GET /session are WIRED UP IN PHASE 1 (HOMELAB-90).
$token = $env:BRICKLINK_GATEWAY_ADMIN_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
  throw "Set `$env:BRICKLINK_GATEWAY_ADMIN_TOKEN (from your secret store) before importing."
}

Write-Host "`n[4/7] Port-forwarding to $Deployment in $Namespace..." -ForegroundColor Cyan
$pf = Start-Process kubectl -PassThru -ArgumentList `
  "-n", $Namespace, "port-forward", $Deployment, "$($LocalPort):8443"
try {
  Start-Sleep -Seconds 3
  $base = "https://localhost:$LocalPort"

  Write-Host "[5/7] Importing the session (phase-1 endpoint)..." -ForegroundColor Cyan
  curl.exe --fail --silent --show-error `
    -H "Authorization: Bearer $token" `
    -F "state=@$state" `
    "$base/admin/session/import"

  Write-Host "`n[6/7] Verifying gateway session state..." -ForegroundColor Cyan
  curl.exe --fail --silent --show-error `
    -H "Authorization: Bearer $token" `
    "$base/session"
}
finally {
  Stop-Process -Id $pf.Id -ErrorAction SilentlyContinue

  # --- 7: destroy the export no matter what happened above ---------------------
  Write-Host "`n[7/7] Deleting the local export..." -ForegroundColor Cyan
  Remove-Item $state -Force -ErrorAction SilentlyContinue
}

Write-Host "`nDone. Confirm the SIGNED_OUT alert clears and parked work drains." -ForegroundColor Green
