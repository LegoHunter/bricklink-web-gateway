# bricklink-web-gateway runbook

Operational procedures for the BrickLink web gateway: the session core (state, probe, keep-alive,
session import). Lot operations come in a later phase.

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

- The gateway's `GET /session` reports `SIGNED_OUT`.
- A `SIGNED_OUT` alert has fired and stayed for more than a few minutes.
- A brand-new gateway instance is being set up (bootstrap).
- Callers are getting `503 SESSION_SIGNED_OUT` from the gateway.

**Do not** do a manual login just because an operation failed once with `SESSION_UNKNOWN` or
`SESSION_RECOVERING` — those are temporary. `UNKNOWN` usually means BrickLink itself was
unreachable, not that you are signed out. Check `GET /session` first; only `SIGNED_OUT` calls for a
login.

---

## 3. Before you start (prerequisites)

- The **Windows PC**, with this repository checked out. The sign-in scripts are in
  `scripts\session`, that is `D:\dev\projects\lego-collection\bricklink-web-gateway\scripts\session`.
- Node.js and Chrome installed. Once, in that folder: `npm install` (`refresh-session.ps1` does
  this itself on its first run).
- Your **LEGO Account** sign-in credentials — you type them yourself, into LEGO's real page. They
  are never stored, pasted into chat, or put in any file for this procedure.
- Access to the **sign-in code email**. LEGO emails a 6-digit code to the LEGO Account address,
  which forwards to the dedicated sign-in code mailbox (and to your normal inbox). You can
  read the code from whichever you prefer.
- The target environment's **admin token** (section 10), for the gateway-side import step, and a
  machine on the home network or VPN: the gateway's hostname resolves only there.

The session directory defaults to `D:\dev\config\bricklink-session` (override with the
`BRICKLINK_SESSION_DIR` environment variable). It sits outside every Git repository and holds live
session cookies — treat the whole folder as a secret.

---

## 4. Procedure A — keep-alive (normal running, no login)

This is what keeps the session fresh day to day. **You normally do nothing here.**

- The gateway visits a signed-in page on a schedule and, in doing so, slides the renewable layers
  forward.
- To confirm the session is healthy at any time:
  `GET /session` → expect `SIGNED_IN`, with recent `lastProbe` and the
  per-layer expiry dates moving forward over time.
- To force a keep-alive immediately (e.g. after a cluster event):
  `POST /session/probe`.

The gateway is at `https://bricklink-web-gateway.<env>.internal.legohunter.io` (home network and
VPN only). Reading the state is open; anything that acts needs a bearer token:

```sh
curl https://bricklink-web-gateway.sandbox.internal.legohunter.io/session
curl -X POST -H "Authorization: Bearer <caller or admin token>" \
     https://bricklink-web-gateway.sandbox.internal.legohunter.io/session/probe
```

| Endpoint | Token |
|---|---|
| `GET /session`, health, metrics | none |
| `POST /session/probe` (and, later, the lot operations) | caller token, or the admin token |
| `POST /admin/session/import` | admin token only |

The keep-alive runs hourly. It pauses while the state is `SIGNED_OUT` (there is nothing to keep
alive) and resumes after a session import.

To check the session held on the PC itself:

```sh
cd D:\dev\projects\lego-collection\bricklink-web-gateway\scripts\session
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
> - `scripts/import-session.sh` — the import half only, for running on an always-on machine on
>   the home network such as `rasppi5-01`, after you have signed in and exported on the PC.
>
> Run the PowerShell script without `-DoImport` to do just the login and export. The manual steps
> below remain the source of truth for what the scripts do.

### 5.1 Sign in on the PC

1. Open a terminal on the PC and go to the session scripts:

   ```sh
   cd D:\dev\projects\lego-collection\bricklink-web-gateway\scripts\session
   ```

2. Launch the headed login. A **real Chrome window** opens on BrickLink:

   ```sh
   node login.mjs
   ```

3. In that window, sign in the way you always do:
   - Enter your **username / email**, click **Continue**.
   - Enter your **password**, continue.
   - LEGO emails a **6-digit code**. Read it from the sign-in code mailbox or your inbox (it is
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

The gateway takes `portable-state.json` on `POST /admin/session/import`, over HTTPS on its
internal hostname and protected by the admin token. It loads the
BrickLink and LEGO Account cookies into its browser, writes them to its volume, probes, and
reports the result. The file is read from memory only: the gateway never stores or logs it. The
scripts in `scripts/` do all of this:

- On the PC:
  `powershell -File scripts\refresh-session.ps1 -Environment sandbox -DoImport`
- On `rasppi5-01` (import half only, after copying the export over):
  `./scripts/import-session.sh sandbox ~/portable-state.json`

Both need the admin token of the environment you are importing into. The PowerShell script asks
for it at the start with the input hidden (or reads `BRICKLINK_GATEWAY_ADMIN_TOKEN` if you set it
first); the bash script reads that variable or a protected token file. Neither takes the token as
a command-line argument, because shells save command lines in plain-text history. Both then POST
the state file and confirm with `GET /session`.

Answers from the import call:

| HTTP | Meaning |
|---|---|
| 200 | Imported, and the gateway probed as `SIGNED_IN` |
| 422 | Imported, but the session did not probe as signed in; the body shows the state. Sign in again on the PC and re-export |
| 400 `INVALID_SESSION_STATE` | Not a session export, or exported from a signed-out browser. Nothing was changed |
| 401 `ADMIN_TOKEN_REQUIRED` | Admin token missing or wrong |
| 503 `ADMIN_TOKEN_NOT_CONFIGURED` | The gateway has no admin token (see section 10) |

The session now lives on the **volume**, so ordinary pod restarts and reschedules do **not** need
another login.

### 5.4 Verify, then destroy the export

1. Confirm the gateway is signed in: `GET /session` → `SIGNED_IN`.

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
| Code email never arrives | Code went only to the forwarder, or landed in spam | Check the sign-in code mailbox and your inbox spam; codes expire in 6 minutes, so request a fresh one if needed |
| `check.mjs` says `NOT SIGNED IN` straight after login | The sign-in did not complete, or you closed the window too early | Repeat section 5.1, and wait until you clearly see your signed-in BrickLink page |
| Import answers 422 / gateway still `SIGNED_OUT` | The exported session was not signed in, or BrickLink did not accept it from the cluster | Run `node check.mjs --http` on the PC; if that is signed in, re-export and import again; if not, sign in again |
| Import answers 503 `ADMIN_TOKEN_NOT_CONFIGURED` | The environment's admin token secret is missing, or the pod started before it existed | Create the secret (section 10) and restart the pod |
| The gateway's hostname does not resolve | You are off the home network | Connect to the VPN, or run the import on `rasppi5-01` |
| Everything imports fine but signs out again within minutes | Another session may be displacing this one (section 6), or an IP change | Check the other environments; check whether the home public IP changed |
| `GET /session` shows `UNKNOWN`, not `SIGNED_OUT` | BrickLink was unreachable | Do **not** log in; wait and re-probe — this is not a sign-out |

---

## 9. How often should I expect to do this?

Rarely. The keep-alive handles normal running, and the renewable layers slide forward with use. A
full manual login is expected only at **bootstrap** and when the session **fully lapses**.

What has been observed so far (a 6-hourly check of the session held on the PC, 2026-11-14 to
2026-11-18, 24 checks, all signed in):

- The two BrickLink layers expire 28 days after their last use, and every use moves that date
  forward. With the hourly keep-alive they should not lapse.
- The LEGO Account layer has a fixed expiry (2027-02-14 for the sign-in of 2026-11-14, about three
  months) that use does **not** move. This is the expected worst-case "must log in again" date.
- Whether the BrickLink layers can still be renewed after the LEGO Account layer expires has not
  been observed yet. The first real answer comes around 2027-02-14.

Each gateway reports the same figures for its own session as metrics:
`bricklink.gateway.session.state`, `bricklink.gateway.session.signed.in.probe.age` and
`bricklink.gateway.session.layer.expiry` (seconds until each layer expires).

---

## 10. One-time setup of an environment

Needed once per environment (`sandbox`, `dev`, `prod`), before the first deploy there.

1. **Tokens.** In Infisical, in that environment (not Global), create two secrets, each with a
   long random value of your own. They are two files so that a calling service can read the
   caller token without also seeing the admin token.

   `bricklink-web-gateway.yml` (read by the gateway only):

   ```yaml
   bricklink:
     web-gateway:
       admin-token: <long random value>
   ```

   `bricklink-web-gateway-caller.yml` (read by the gateway and by `lego-data-ingress`):

   ```yaml
   bricklink:
     web-gateway:
       caller-token: <another long random value>
   ```

   - **admin-token** replaces the session (`/admin/session/import`). Only you use it.
   - **caller-token** is for the services that use the gateway (`lego-data-ingress`). It cannot
     replace the session.
   - An environment set up before the second file existed may still have `caller-token` in
     `bricklink-web-gateway.yml`. The gateway reads the caller file last, so that one wins; once it
     is in place, remove `caller-token` from the first file.

   The files reach the pod under `/etc/.credentials`, like the other services' credential files,
   and are read at startup: restart the pod after changing it. Without it the gateway still runs
   and `GET /session` works, but every token-protected endpoint answers 503. Each environment has
   its own tokens; never copy one between environments.

2. **Profile volume and network policy.** The deploy pipeline may not create these, so an
   administrator applies them once:

   ```sh
   kubectl --context lcms-admin -n services-<env> apply -f kubernetes/cluster-setup/profile-volume.yaml
   kubectl --context lcms-admin -n services-<env> apply -f kubernetes/cluster-setup/network-policy.yaml
   ```

   The volume holds the signed-in session. Deleting the claim deletes the session.

3. **Quay repository.** `quay.io/tvattima/bricklink-web-gateway` must exist, with Write permission
   for the `tvattima+github` robot (once, not per environment).

4. **First sign-in.** Deploy, then run Procedure B (section 5) against that environment.
