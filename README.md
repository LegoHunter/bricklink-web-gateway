# bricklink-web-gateway

A small cluster service that owns one signed-in BrickLink website session, keeps it alive, and
exposes task-level REST endpoints to other Lego Collection services.

First capability: set, replace and delete the custom thumbnail image on a store lot, which the
official BrickLink REST API cannot do.

## Status

**Not built yet.** This repository holds no service code. The design is in the workspace
repository:

`00-workspace/context/bricklink-web-gateway/bricklink-web-gateway-design.md`

## Planned shape

- Spring Boot 3.5, Java 21, Playwright for Java with bundled Chromium.
- One replica per environment; the browser profile lives on a persistent volume.
- Cluster-internal only. No generic form proxy: each endpoint does one named task.
- Lower environments may only touch lots in their own stockroom.

## Relationship to `bricklink-html`

This service replaces the website-login part of the legacy `bricklink-html` library, whose login
stopped working when BrickLink moved sign-in to the LEGO Account. `bricklink-html` is left as it
is for its legacy consumers.

## Never commit

Browser profiles, exported session state, cookies, mailbox credentials or account credentials.
