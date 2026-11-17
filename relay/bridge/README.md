# Private WSS-only Bridge deployment

This is a deployment template, not an already deployed service. It deliberately
has no Tailscale, public relay listener, WebRTC, STUN, HTTP/3 or published UDP
port. Gateway frames keep the existing end-to-end encryption. Registration
requires a private token shared only with the Windows relay, never the phone.

Prerequisites: a VPS you administer, Docker Compose, a DNS hostname pointing to
it, and TCP 80/443 reachable for HTTPS certificate issuance and phone traffic.
Copy `.env.example` to `.env`, keep its pinned official Caddy 2.11.9 image,
set the hostname, and generate a long random registration token locally.
Restrict `.env` access and do not commit it. Do not expose TCP 8443 or UDP 3478.

Build the modified frontend using the repository's pinned toolchain before
starting, from the repository root: `bun install --cwd frontend --frozen-lockfile`
and `bun run --cwd frontend build`. `frontend/dist` is mounted as the HTTPS phone app. Do not serve the
unmodified committed `web/` bundle: it does not know the relay-only policy.
From this directory, validate with `docker compose config` (its output contains
secrets; do not share it), then `docker compose up --build -d` when deployment
is authorized. These commands have not been executed as part of implementation.

Configure the Windows relay with its private gateway registration token,
`HERDR_GATEWAY_URL=wss://YOUR_HOST`, and `HERDR_TRANSPORT_FORCE_RELAY=1`.
Its pairing URL must use the modified HTTPS app at `https://YOUR_HOST/` and
include `path_policy=relay-only` in the existing secret URL fragment beside
`gateways`, invitation and rendezvous fields. New invitations issued by that
phone preserve the policy. Existing hybrid pairings do not silently change.

Acceptance: HTTPS loads the built phone app; `/healthz` reports no STUN port;
`/probe` is 404; unregistered relays cannot register without the token; the
phone pairs and receives a snapshot through WSS. Stop the gateway and verify
that the phone disconnects without opening WebRTC or a direct relay URL. Resume
it and verify a fresh snapshot. No live VPS/Windows/Android acceptance test has
been performed here. Gateway counters in this minimal template are in-memory;
do not rely on them for persistent monthly quotas or billing.

## Static serving and validation

Caddy does not read the frontend bundle's `_headers` file. This template sets
`Cache-Control: no-cache, no-store` for all responses, including the bootstrap,
manifest loader, both manifests, service worker, version metadata and SPA
fallbacks. This conservative choice avoids stale mobile loaders; it may use more
bandwidth than caching hashed assets. `Referrer-Policy: no-referrer`,
`X-Content-Type-Options: nosniff`, and `X-Frame-Options: DENY` protect the phone
app without replacing or broadening its existing CSP. Only HTTP/1.1 and HTTP/2
are enabled. Probe rejection precedes the WebSocket and static handlers.

The Caddyfile was adapted and provision-validated with the official Caddy
2.11.9 Linux binary (release archive verified against its published SHA-512).
The example image pins the corresponding official multi-platform Docker
manifest digest. Validation used `bridge.example.com`; no listener was started,
certificate requested, or service deployed. Docker/Compose itself was not
available, so image building, Compose runtime behavior, actual HTTP response
headers and Android acceptance remain deployment checks.

The gateway source subset copied by `Dockerfile.gateway` was checked against
`go list -deps` and compiled independently with CGO disabled. Its local imports
are `internal/gateway` and `internal/gatewaywire`, both copied by the Dockerfile.
This verifies source coverage, not a Docker image build.
