# bricklink-web-gateway runbook

Operational procedures for the BrickLink web gateway. This document is usable before the service
is built: the parts that run on your PC use the proven spike tooling today, and the parts that run
against the gateway are marked **(planned — phase 1, HOMELAB-90)** until the service exists.

Design and background: `00-workspace/context/bricklink-web-gateway/bricklink-web-gateway-design.md`
in the `lego-collection-workspace` repository.

---

## 1. What the session is, and why it occasionally needs you

The gateway works by holding one **signed-in BrickLink website session** and reusing it, exactly as
a logged-in person's browser would. That session is several cookies layered on top of each other:

| Layer | Lives about | Renews how |
|---|---|---|
| Classic-page (ASP) session | Until the browser closes | Automatically, the moment any classic page is opened |
| BrickLink token | ~28 days | Slides forward each time the session is used |
| LEGO Account sign-in (`id.a`) | ~3 months | Not observed to renew; may be a hard cap |
| Full sign-in (password + emailed code) | — | **You**, by hand (this runbook) |

**The key point:** keeping the session alive is not a login. As long as the gateway keeps using the
session (its scheduled keep-alive), the renewable layers keep sliding forward and no human is
needed. A real sign-in is required only in two situations:

1. **Bootstrap** — the first time an environment's gateway is set up, its volume has no session.
2. **Full recovery** — the session has lapsed entirely or LEGO has revoked it (a password change, a
   "sign out everywhere", a long outage beyond the token lifetime, or the ~3-month LEGO layer
   expiring).

> **Why this is a manual procedure.** Automating the LEGO sign-in was tested on 2026-11-15
> (HOMELAB-93) and LEGO's bot filtering blocks it: the automated browser gets HTTP 403 at the
> username step, before the password. We deliberately do **not** automate it, to avoid any risk of
> the account being locked. Signing in by hand, occasionally, is the chosen trade-off.

---

## 2. When do I need to do this? (symptoms)

Do a fresh manual login (section 5) when any of these are true:

- The gateway's `GET /session` reports `SIGNED_OUT` **(planned — phase 1)**.
- A `SIGNED_OUT` alert has fired and stayed for more than a few minutes.
- A brand-new gateway instance is being set up (bootstrap).
- Callers are getting `503 SESSION_SIGNED_OUT` from the gateway.

**Do not** do a manual login just because an operation failed once with `SESSION_UNKNOWN` or
`SESSION_RECOVERING` — those are temporary. `UNKNOWN` usually means BrickLink itself was
unreachable, not that you are signed out. Check `GET /session` first; only `SIGNED_OUT` calls for a
login.

---

## 3. Before you start (prerequisites)

- The **Windows PC** that holds the spike tooling, at
  `D:\dev\projects\lego-collection\bricklink-html\spike\homelab-79`.
- Node.js installed (the spike already runs there).
- Your **LEGO Account** sign-in credentials — you type them yourself, into LEGO's real page. They
  are never stored, pasted into chat, or put in any file for this procedure.
- Access to the **sign-in code email**. LEGO emails a 6-digit code to the LEGO Account address,
  which forwards to the `bricklink-otp@legohunter.io` mailbox (and to your normal inbox). You can
  read the code from whichever you prefer.
- `kubectl` access to the target environment, for the gateway-side import step.

The session directory defaults to `D:\dev\config\bricklink-session` (override with the
`BRICKLINK_SESSION_DIR` environment variable). It sits outside every Git repository and holds live
session cookies — treat the whole folder as a secret.

---

## 4. Procedure A — keep-alive (normal running, no login)

This is what keeps the session fresh day to day. **You normally do nothing here.**

- The gateway visits a signed-in page on a schedule and, in doing so, slides the renewable layers
  forward. **(planned — phase 1)**
- To confirm the session is healthy at any time:
  **(planned — phase 1)** `GET /session` → expect `SIGNED_IN`, with recent `lastProbe` and the
  per-layer expiry dates moving forward over time.
- To force a keep-alive immediately (e.g. after a cluster event):
  **(planned — phase 1)** `POST /session/probe`.

Until the gateway exists, the equivalent check on the PC is:

```sh
cd D:\dev\projects\lego-collection\bricklink-html\spike\homelab-79
node check.mjs --http
```

Expect `browser session: SIGNED IN` and `plain HTTP replay: SIGNED IN`. If it says `NOT SIGNED IN`,
go to section 5.

---

## 5. Procedure B — fresh manual login and session import (bootstrap / full recovery)

This is the real "recreate the token from a fresh login" procedure. It has two halves: **sign in on
the PC**, then **hand the session to the gateway**.

> **Scripted shortcut.** The steps below are wrapped by two scripts in `scripts/`:
> - `scripts/refresh-session.ps1` — run on the PC; does the whole flow (login → verify → export →
>   import → verify → delete the export). Your only manual action is signing in to the browser
>   window that opens.
> - `scripts/import-session.sh` — the import half only, for running on an always-on machine with
>   kubectl such as `rasppi5-01`, after you have signed in and exported on the PC.
>
> The login/verify/export steps work today. The **import** call in both scripts hits the gateway's
> admin endpoint, which is **wired up in phase 1 (HOMELAB-90)**; until then run the PowerShell
> script without `-DoImport` to do just the login and export. The manual steps below remain the
> source of truth for what the scripts do.

### 5.1 Sign in on the PC

1. Open a terminal on the PC and go to the spike directory:

   ```sh
   cd D:\dev\projects\lego-collection\bricklink-html\spike\homelab-79
   ```

2. Launch the headed login. A **real Chrome window** opens on BrickLink:

   ```sh
   node login.mjs
   ```

3. In that window, sign in the way you always do:
   - Enter your **username / email**, click **Continue**.
   - Enter your **password**, continue.
   - LEGO emails a **6-digit code**. Read it from the `bricklink-otp` mailbox or your inbox (it is
     in the subject line, valid for 6 minutes) and type it in.
   - Wait until you land on your signed-in BrickLink page (your store / "My BrickLink").

4. Leave the page on a signed-in BrickLink view and let the script finish. It saves the session
   into the profile directory and writes a report under `…\reports\login-<timestamp>.json`. Close
   the window when prompted.

5. Verify the new session locally:

   ```sh
   node check.mjs --http
   ```

   You want `SIGNED IN` on both lines before continuing. If it still says `NOT SIGNED IN`, repeat
   step 2–4; do not proceed with a bad session.

### 5.2 Export the session for the gateway

The Chrome profile folder itself is not portable between machines (Windows encrypts its cookie
store), so export the cookies as a portable state file:

```sh
node export-state.mjs
```

This writes `D:\dev\config\bricklink-session\portable-state.json`. **This file is a live
credential** — it is the signed-in session in a form any machine can load. Handle it like a
password: do not commit it, print it, email it, or leave it lying around. You will delete it in
step 5.4.

### 5.3 Import it into the gateway

**Wired up in phase 1 (HOMELAB-90):** the gateway exposes a session-import mechanism that takes
`portable-state.json` over an authenticated, cluster-internal channel (reached by `kubectl
port-forward`, since the gateway has no public route), writes it onto the gateway's volume, probes,
and reports the result — without ever logging the file. The scripts in `scripts/` call it:

- On the PC: `pwsh scripts/refresh-session.ps1 -Namespace services-prod -DoImport`
- On `rasppi5-01` (import half only, after copying the export over): `./scripts/import-session.sh services-prod ~/portable-state.json`

Both authenticate with the gateway admin token from your secret store
(`BRICKLINK_GATEWAY_ADMIN_TOKEN`), port-forward to the pod, POST the state file to
`/admin/session/import`, and confirm with `GET /session`.

Until the phase-1 endpoint exists, the equivalent **proven manual copy** (what the portability test
did on 2026-11-15) is:

1. Copy the state file into the gateway pod's profile volume at the path the deployment expects.
2. Restart the pod (`Recreate`), so it starts from the imported session.

Either way, the session now lives on the **volume**, so ordinary pod restarts and reschedules do
**not** need another login.

### 5.4 Verify, then destroy the export

1. Confirm the gateway is signed in:
   **(planned — phase 1)** `GET /session` → `SIGNED_IN`.
   Until then, confirm from the pod with the same probe the spike uses.

2. **Delete the export from everywhere it touched:**

   ```sh
   del D:\dev\config\bricklink-session\portable-state.json
   ```

   and remove any copy you placed in a temp location or the pod's filesystem outside the volume.
   The only place the session should remain is the gateway's volume.

3. Confirm, one last time, that an ordinary **pod restart** still comes back `SIGNED_IN`. That
   proves the session is really on the volume and the login "took".

---

## 6. One-account / concurrency note

All environments share **one** BrickLink account. It is not yet confirmed whether signing in a new
instance (a new environment's gateway, or your PC) signs **out** the other sessions. Until that is
tested (tracked on HOMELAB-90):

- After importing a session into one environment, check that the **other** environments' gateways
  are still `SIGNED_IN`.
- If a new sign-in displaces an existing one, you have hit the concurrency limit — stop, and raise
  it, because it affects the one-instance-per-environment design.

---

## 7. Security rules (always)

- Never commit, print, email, or copy to a shared location: the browser profile, `portable-state.json`,
  any cookie, token, or the sign-in code.
- Your LEGO password is typed only into LEGO's real page, never stored for this procedure.
- The session directory and the gateway volume are secrets. No backups to unencrypted locations,
  and **never** copy a session between environments — each environment signs in on its own.
- Delete `portable-state.json` as soon as the import is verified.

---

## 8. Troubleshooting

| Symptom | Likely cause | Action |
|---|---|---|
| `login.mjs` opens but BrickLink never shows the code step | Wrong username/password, or you closed the Continue step too fast | Re-run; make sure you reach the password field and submit it |
| Code email never arrives | Code went only to the forwarder, or landed in spam | Check the `bricklink-otp` mailbox and your inbox spam; codes expire in 6 minutes, so request a fresh one if needed |
| `check.mjs` says `NOT SIGNED IN` straight after login | The sign-in did not complete, or you closed the window too early | Repeat section 5.1, and wait until you clearly see your signed-in BrickLink page |
| Gateway still `SIGNED_OUT` after import | State file not placed where the deployment expects, or pod not restarted | Re-check the volume path; restart the pod; verify the file was the one you just exported |
| Everything imports fine but signs out again within minutes | Another session may be displacing this one (section 6), or an IP change | Check the other environments; check whether the home public IP changed |
| `GET /session` shows `UNKNOWN`, not `SIGNED_OUT` | BrickLink was unreachable | Do **not** log in; wait and re-probe — this is not a sign-out |

---

## 9. How often should I expect to do this?

Rarely. The keep-alive handles normal running, and the renewable layers slide forward with use. A
full manual login is expected only at **bootstrap** and when the session **fully lapses**. The
6-hourly lifetime check (HOMELAB-79) is measuring the real cadence; this runbook will be updated
with the observed figure once it is known. The ~3-month LEGO Account layer (expiry around
2027-02-14 at the time of writing) is the current best guess for a worst-case "must log in again"
horizon if nothing else forces it sooner.
