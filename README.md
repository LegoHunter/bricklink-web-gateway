# bricklink-web-gateway

A small cluster service that owns one signed-in BrickLink website session, keeps it alive, and
exposes task-level REST endpoints to other Lego Collection services.

It exists for the two store-lot updates the official BrickLink REST API cannot do: the custom
thumbnail image and the Extended Description.

Design: `00-workspace/context/bricklink-web-gateway/bricklink-web-gateway-design.md` in the
workspace repository. Operations: [runbook.md](runbook.md).

## Status

**Phase 2: lot operations.** On top of the session core (state, probe, keep-alive, session import),
the service sets, replaces and deletes a lot's custom image and sets its Extended Description,
behind the stockroom guard.

## Endpoints

| Method and path | Purpose |
|---|---|
| `GET /session` | The session as last observed: state, last probe, expiry of each session layer. Never cookie values. Open. |
| `POST /session/probe` | Probe BrickLink now (read-only there). Needs the caller token (the admin token also passes). |
| `POST /admin/session/import` | Take a session exported on the owner's PC (multipart field `state`). Needs the admin token. |
| `GET /lots/{lotId}/image` | Whether the lot has a custom image, and its thumbnail URL. Caller token. |
| `PUT /lots/{lotId}/image` | Set or replace the custom image (multipart field `image`: JPEG or GIF, the formats BrickLink takes; the format is read from the file itself; at most 2,500,000 bytes, setting `bricklink.web-gateway.max-image-size`). A replace deletes the old image first, so if BrickLink then turns the new file away the lot is left with no custom image and the answer is `WRITE_NOT_APPLIED`. Caller token. |
| `DELETE /lots/{lotId}/image` | Remove the custom image. Caller token. |
| `GET /lots/{lotId}/extended-description` | The lot's Extended Description. Caller token. |
| `PUT /lots/{lotId}/extended-description` | Set or replace it (JSON `{"extendedDescription": "..."}`; empty clears it). The answer carries the text as BrickLink stored it, which is HTML: `"` comes back as `&quot;`, a plain `&` as `&amp;`, a loose `<` or `>` as `&lt;` / `&gt;`, tags stay as sent, and CRLF becomes LF. Caller token. |
| `GET /actuator/health`, `/actuator/info`, `/actuator/prometheus` | Health, build details and metrics. |

Lot errors: `503 SESSION_SIGNED_OUT` (a sign-in is needed: stop and retry later),
`503 SESSION_RECOVERING` / `SESSION_UNKNOWN` (temporary), `409 LOT_GUARD_REFUSED`,
`503 LOT_GUARD_UNAVAILABLE` (the BrickLink API could not be asked; nothing was changed),
`404 LOT_NOT_FOUND`, `409 LOT_NOT_SUPPORTED`, `502 UNEXPECTED_PAGE`, `502 WRITE_NOT_APPLIED`,
`400 INVALID_REQUEST`.

Session states: `SIGNED_IN`, `SIGNED_OUT`, `RECOVERING` (an import is running) and `UNKNOWN`
(BrickLink unreachable, or no probe yet). An outage is `UNKNOWN`, never `SIGNED_OUT`.

Metrics: `bricklink_gateway_session_state{state}`, `bricklink_gateway_session_signed_in_probe_age_seconds`,
`bricklink_gateway_session_layer_expiry_seconds{layer}`, `bricklink_gateway_session_probes_total{outcome}`.

## The stockroom guard

Every environment shares one BrickLink account. A lower environment may only change lots that are
in a stockroom (so buyers cannot see them) and in its own stockroom letter: SANDBOX `C`, DEV `B`.
Production has no stockroom restriction. An environment with neither setting changes no lot.

Before any write the lot is checked twice, from two independent sources: the official BrickLink
REST API (`is_stock_room`, `stock_room_id`), and the website's own lot data read just before the
change. Either check failing stops the write. Every write ends with a read-back, and success is
only reported when the read-back shows the change.

## How it works

- Spring Boot 3.5, Java 21, Playwright for Java driving Chromium with a persistent profile.
- All BrickLink traffic goes through the browser. Every browser operation runs on one thread, one
  at a time, with a minimum pause between operations.
- The browser starts on demand and closes when idle. The session survives in the profile directory.
- Lot reads use the inventory page's own data call, which answers JSON. The image upload is the
  classic upload form. The image delete and the Extended Description are changed by opening the
  lot's inventory page, changing the one control and pressing the page's own submit button, so
  every other field of the lot keeps its current value.
- The official REST API is used for one thing only: reading a lot's stockroom for the guard. Its
  keys are the shared `bricklink-client-api-keys.yml`.
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
