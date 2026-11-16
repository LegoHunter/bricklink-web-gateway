# bricklink-web-gateway

A small cluster service that owns one signed-in BrickLink website session, keeps it alive, and
exposes task-level REST endpoints to other Lego Collection services.

It exists for the two store-lot updates the official BrickLink REST API cannot do: the custom
thumbnail image and the Extended Description.

Design: `00-workspace/context/bricklink-web-gateway/bricklink-web-gateway-design.md` in the
workspace repository. Operations: [runbook.md](runbook.md).

## Status

**Phase 1: session core.** The service holds the session, probes it, keeps it alive, reports its
state and takes an imported session. Lot operations (image, Extended Description) are the next
phase and are not built yet.

## Endpoints

| Method and path | Purpose |
|---|---|
| `GET /session` | The session as last observed: state, last probe, expiry of each session layer. Never cookie values. Open. |
| `POST /session/probe` | Probe BrickLink now (read-only there). Needs the caller token (the admin token also passes). |
| `POST /admin/session/import` | Take a session exported on the owner's PC (multipart field `state`). Needs the admin token. |
| `GET /actuator/health`, `/actuator/prometheus` | Health and metrics. |

Session states: `SIGNED_IN`, `SIGNED_OUT`, `RECOVERING` (an import is running) and `UNKNOWN`
(BrickLink unreachable, or no probe yet). An outage is `UNKNOWN`, never `SIGNED_OUT`.

Metrics: `bricklink_gateway_session_state{state}`, `bricklink_gateway_session_signed_in_probe_age_seconds`,
`bricklink_gateway_session_layer_expiry_seconds{layer}`, `bricklink_gateway_session_probes_total{outcome}`.

## How it works

- Spring Boot 3.5, Java 21, Playwright for Java driving Chromium with a persistent profile.
- All BrickLink traffic goes through the browser. Every browser operation runs on one thread, one
  at a time, with a minimum pause between operations.
- The browser starts on demand and closes when idle. The session survives in the profile directory.
- The sign-in itself is never automated. The owner signs in by hand on a PC and the session is
  imported (runbook section 5). The gateway stores no account credentials.
- One replica per environment (`Recreate`); the profile lives on a persistent volume.
- Served over HTTPS on the internal hostname `bricklink-web-gateway.<env>.internal.legohunter.io`,
  like the other LCMS services. Reaching it is not enough to use it: every endpoint that acts
  needs a bearer token, and a token that is not configured shuts its endpoints.

## Build and run locally

```sh
mvn verify
java -jar target/bricklink-web-gateway-0.0.1-SNAPSHOT.jar --server.port=18080
```

The default `local` profile uses the installed Chrome and a profile directory under
`~/.bricklink-web-gateway` (override with `BRICKLINK_GATEWAY_PROFILE_DIR`). With an empty profile
the first probe reports `SIGNED_OUT`, which is correct.

## Deployment

The shared LCMS service pipeline builds and deploys it (`.github/workflows/build-test-deploy.yml`).
Each environment needs a one-time setup first: runbook section 10.

The image is built from Playwright's Java image, so its tag in `kubernetes/Containerfile` must
match `<playwright.version>` in `pom.xml` (a test checks this).

## Relationship to `bricklink-html`

This service replaces the website-login part of the legacy `bricklink-html` library, whose login
stopped working when BrickLink moved sign-in to the LEGO Account. `bricklink-html` is left as it
is for its legacy consumers.

## Never commit

Browser profiles, exported session state, cookies, mailbox credentials or account credentials.
