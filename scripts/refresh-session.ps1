<#
.SYNOPSIS
  Refresh the BrickLink web gateway's signed-in session: manual login on this PC,
  then import the session into the running gateway.

.DESCRIPTION
  Run this on the Windows PC when the gateway reports SIGNED_OUT (or at bootstrap).
  It wraps the whole recovery so your only manual action is signing in to the
  browser window that opens.

  The LEGO sign-in itself cannot be automated: LEGO blocks automated browsers.
  `login.mjs` opens a REAL Chrome and waits for YOU to sign in by hand; that is
  allowed. Everything else here is scripted.

  Steps:
    1. node login.mjs        - opens Chrome; you enter username, password, emailed code
    2. node check.mjs        - verifies the new session
    3. node export-state.mjs - writes portable-state.json (a live credential)
    4. POST /admin/session/import - hand the session to the gateway over HTTPS
    5. GET /session          - confirm SIGNED_IN
    6. delete portable-state.json

  Without -DoImport the script stops after step 3 (sign in and export only).
  See runbook.md section 5.

.NOTES
  With -DoImport the script needs the environment's admin token (each environment has
  its own). It asks for it at the start, with the input hidden, so the token never
  lands in your PowerShell history. To skip the question, set it beforehand:
      $env:BRICKLINK_GATEWAY_ADMIN_TOKEN = "..."
  There is deliberately no command-line parameter for the token: PowerShell saves every
  command line, token included, in a plain-text history file.
#>
[CmdletBinding()]
param(
  [ValidateSet("sandbox", "dev", "prod")]
  [string]$Environment = "prod",
  [string]$SessionDir  = "D:\dev\config\bricklink-session",
  # The Node scripts that drive the browser on this PC (scripts\session in this repository).
  [string]$ScriptsDir  = (Join-Path $PSScriptRoot "session"),
  # Override only to reach the gateway some other way (for example a local run).
  [string]$BaseUrl     = "",
  # Omit to stop after the sign-in and export.
  [switch]$DoImport
)

$ErrorActionPreference = "Stop"
$state = Join-Path $SessionDir "portable-state.json"
if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
  $BaseUrl = "https://bricklink-web-gateway.$Environment.internal.legohunter.io"
}

# Get the admin token before anything else, so a missing token does not waste a sign-in.
$token = $null
if ($DoImport) {
  $token = $env:BRICKLINK_GATEWAY_ADMIN_TOKEN
  if ([string]::IsNullOrWhiteSpace($token)) {
    $secure = Read-Host -AsSecureString "Admin token for the $Environment gateway (input hidden)"
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  }
  if ([string]::IsNullOrWhiteSpace($token)) {
    throw "No admin token given. Nothing was changed."
  }
  $token = $token.Trim()
}

# --- 1-3: sign in on this PC and export the session -----------------------------
Push-Location $ScriptsDir
try {
  if (-not (Test-Path "node_modules")) {
    Write-Host "`n[0/6] First run here: installing the one dependency of the scripts (npm install)..." -ForegroundColor Cyan
    npm install --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw "npm install failed in $ScriptsDir." }
  }
  Write-Host "`n[1/6] Opening Chrome for manual sign-in. Enter your username, password, and the emailed code, then wait until you land on your BrickLink page." -ForegroundColor Cyan
  node login.mjs

  Write-Host "`n[2/6] Verifying the new session..." -ForegroundColor Cyan
  node check.mjs --http

  Write-Host "`n[3/6] Exporting the portable session..." -ForegroundColor Cyan
  node export-state.mjs
}
finally {
  Pop-Location
}

if (-not (Test-Path $state)) {
  throw "Export did not produce $state - the sign-in likely did not complete. Nothing imported."
}

if (-not $DoImport) {
  Write-Host "`nLogin and export complete. Re-run with -DoImport to push it to the $Environment gateway." -ForegroundColor Yellow
  Write-Host "The export at $state is a live credential - delete it when you are done with it." -ForegroundColor Yellow
  return
}

# --- 4-5: import into the running gateway ---------------------------------------
try {
  Write-Host "`n[4/6] Importing the session into $BaseUrl ..." -ForegroundColor Cyan
  # The token goes to curl on standard input, so it never appears in the process list.
  "header = `"Authorization: Bearer $token`"" | curl.exe --config - --fail-with-body --silent --show-error `
    -F "state=@$state" `
    "$BaseUrl/admin/session/import"
  if ($LASTEXITCODE -ne 0) { throw "The import was refused (see the answer above and runbook.md section 5.3)." }

  Write-Host "`n[5/6] Verifying gateway session state..." -ForegroundColor Cyan
  curl.exe --fail --silent --show-error "$BaseUrl/session"
  if ($LASTEXITCODE -ne 0) { throw "Could not read $BaseUrl/session." }
}
finally {
  # --- 6: destroy the export no matter what happened above ----------------------
  Write-Host "`n[6/6] Deleting the local export..." -ForegroundColor Cyan
  Remove-Item $state -Force -ErrorAction SilentlyContinue
}

Write-Host "`nDone. Confirm the SIGNED_OUT alert clears and parked work drains." -ForegroundColor Green
