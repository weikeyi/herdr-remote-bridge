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

## Existing 1Panel / OpenResty TLS proxy (no new host 80/443 listener)

Use a **dedicated hostname at the root path**, not a subdirectory. Keep the
existing 1Panel certificate, HTTPS virtual host and HTTP-to-HTTPS redirect.
The phone app and gateway both use this hostname: `https://YOUR_HOST` and
`wss://YOUR_HOST`. Build `frontend/dist` and prepare the private `.env` as above, or use the verified
release bundle: verify its SHA256 manifest first, then set
`HERDR_PHONE_WEB_ROOT=/absolute/path/to/bundle/web` in `.env`. The web directory
must belong to the same build as the Windows executable; do not mix releases.
All three Compose templates default to `../../frontend/dist` when unset/empty.
Confirm the selected directory contains `index.html` and `version.json` before
starting (a missing bind source can otherwise become an empty directory).
Do not expose an API token in a URL, browser, screenshot or Compose output.

Choose exactly **one standalone Compose file** below. Never combine these files
with `compose.yaml` using multiple `-f` arguments: Compose may merge published
ports and unintentionally claim 80/443. Do not run the original stack alongside
this replacement. No commands in this section have been deployed or run on a VPS.

### OpenResty in the host network

This includes an OpenResty container explicitly using host networking. Confirm
that mode first; ordinary container loopback is not the host loopback.

```sh
# From relay/bridge, after preparing .env and the frontend build:
docker compose -f compose.openresty-host.yaml config --quiet
docker compose -f compose.openresty-host.yaml up --build -d
```

Only `127.0.0.1:18080/tcp` is published, forwarding to internal Caddy HTTP 8080.
The gateway has no host port. Port 18080 must be free. Require Docker Engine
28.0.0 or later: older Docker releases have known same-L2 reachability issues
for localhost-published ports ([Docker documentation](https://docs.docker.com/engine/network/port-publishing/)). Verify from another machine that 18080 is not
reachable. Do not work around a connection failure by publishing `0.0.0.0`.

Replace the dedicated site's existing `location /` with
[`openresty-location.conf.example`](openresty-location.conf.example). Do not paste
a second conflicting location. The snippet is for the **HTTPS server only**;
it uses `http://127.0.0.1:18080` internally. Validate the actual complete
OpenResty config with its `nginx -t` command before an authorized reload.

### OpenResty in a Docker bridge network

Do not target `127.0.0.1` from this container. Instead, arrange a **dedicated
Docker network containing only OpenResty and this stack's Caddy**, and set
`HERDR_OPENRESTY_NETWORK` in `.env` to its existing exact name. Provisioning that
network and attaching OpenResty are separate operator actions. Persist its
attachment in the 1Panel-managed OpenResty configuration: a one-off
`docker network connect` can be lost when 1Panel recreates the container. Never
attach the gateway to that ingress network or use a network shared with
untrusted application containers.

```sh
docker compose -f compose.openresty-network.yaml config --quiet
docker compose -f compose.openresty-network.yaml up --build -d
```

This file publishes **no host ports**. In the example location, replace only
`proxy_pass http://127.0.0.1:18080;` with
`proxy_pass http://herdr-bridge-http:8080;`. OpenResty must resolve that alias
on the dedicated network. Start Caddy before validating/reloading OpenResty;
standard nginx resolves this static upstream at config load. Revalidate/reload
OpenResty after a Caddy container recreation changes its address. Do not pin an
ephemeral container IP or expose the backend publicly as a workaround.

### Proxy boundary and acceptance

`Caddyfile.openresty` listens on HTTP 8080 only, disables automatic HTTPS and
its admin endpoint, and keeps the original app headers, no-store policy,
WebSocket routing and `/probe` rejection. It neither issues certificates nor
opens UDP. The private backend network is internal; the gateway has no egress
requirement for this relay path. The registration token is a container environment variable, visible to VPS root
and Docker-authorized users through container inspection. They must be trusted;
this is not encrypted server-side credential storage. Restrict `.env` permissions
(e.g. owner-only mode 600) and exclude it from backups shared with others.
Do not transcribe pairing links or QR data into server logs or support messages.

Internal HTTP is appropriate only on this
same trusted VPS, not between machines over a public network.

The OpenResty snippet overwrites forwarding headers using its direct peer
address (not `$proxy_add_x_forwarded_for`), clears `Forwarded` and `X-Real-IP`,
and Caddy explicitly passes this sanitized address to the gateway. The trusted
proxy flag is safe only with this restricted topology. Local host processes
(host mode), OpenResty and containers on the ingress network can impersonate
forwarded addresses; they must be trusted. If a CDN or another proxy precedes
OpenResty, do not blindly enable real-IP trust for all addresses. Until a
separate trusted-proxy configuration is reviewed, rate limits group traffic
under that proxy's address. Existing global `real_ip` settings must also be
audited before use.

Do not enable proxy caching, body/header debug logging or access logging for
this location. Existing server-level error logs may still contain request
URLs on errors; keep them private with short retention, never publish them.
Preserve the app's Origin header and CSP; do not inject credentials or change
Origin to bypass checks. The 3600-second WebSocket idle allowance is not a
promise of indefinite connectivity: mobile sleep, outer proxies and restarts
can still disconnect. Reconnect/snapshot recovery must be verified.

Static tests: `python3 relay/bridge/test_openresty_templates.py` from the repo
root (requires PyYAML). These check topology and safety invariants only, not
Docker networking, nginx/Caddy runtime validity or a 1Panel version's UI.
Before claiming deployment success: validate both effective configs, check
public HTTPS app/no-store headers, `/probe` 404, `/healthz` with no STUN port,
unauthorized registration rejection, and phone-to-Windows WSS pairing and
reconnect. Confirm externally that 18080/8080/8443 and UDP3478 are closed.
This adapter has not undergone live VPS or 1Panel acceptance. Docker is not
available in this test environment; in particular, loopback publication on the
`internal: true` host-mode network has not been runtime-tested. If this does not
work on the installed Docker version, stop and inspect the topology rather than
removing isolation or publishing the backend externally.

The new `Caddyfile.openresty` was also adapted and provision-validated with the
official Caddy 2.11.9 Linux binary, after checking its release archive against
the published SHA-512. This started no HTTP listener and requested no
certificate. It does not validate the actual OpenResty configuration or Docker
networking.
