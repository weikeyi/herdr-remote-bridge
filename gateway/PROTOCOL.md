# Native read-only vertical slice: audited source contract

Scope: synthetic/offline evidence, not a working Android network client or live
Windows/VPS acceptance. No credentials, service endpoint, WebView or UI generated.
Source is the unchanged `recovered/rc1/source` checkout. `source-sha256.json`
records the exact audited files. Paths below are relative to that source root.

## Layer order (do not collapse these protocols)

1. WSS `configured-origin/connect`, verified TLS, normal WebSocket text handshake.
   `internal/gateway/client.go:74-145`, `internal/gatewaywire/gatewaywire.go:185-228`,
   `frontend/src/lib/transports/gateway.ts:102-179`.
   Server sends `gateway_hello` protocol 1 and 32-byte base64url nonce. Phone sends
   `connect`, protocol 1, `relay_id`, proof. Proof is HMAC-SHA256 over UTF-8
   `herdr-gw-connect\0` + UTF-8 relay ID + raw nonce, using 32-byte rendezvous key.
   Invitation supplies relay ID and rendezvous key: a native phone must NOT ask
   for the Windows registration token or bootstrap relay key. `gateway-auth.json`
   contains only an explicitly synthetic deterministic key to compare Go/TS.
2. Wait for text `ready` before sending binary data. Gateway ready means routing,
   NOT authenticated device access. First-message/challenge errors, bad version,
   early binary, or post-ready text are fatal to this carrier. Frontend handshake
   timeout is 10s. `unknown_relay` / `quota_exceeded` are fatal config conditions;
   rate-limit/capacity/busy may back off. Do not erase credentials based solely on
   an unauthenticated gateway error (`encrypted.ts:187-190`). No STUN/WebRTC used.
3. Every subsequent phone WS binary payload is a **chunk**. Version 1, flags
   START=1/END=2; START adds unsigned 32-bit big-endian total logical byte count.
   Continuations have only the first two bytes. Ceiling 262144 including header;
   logical max 21 MiB. Exactly one assembly per direction, reliable ordered stream;
   no message ID/interleaving. Half-message stalls after 30s. Invalid assembly
   closes permanently. See `transports/chunking.ts:1-128`. The 6-byte **relay-link
   multiplex** header in gatewaywire is NOT a phone protocol header.
4. Reassembled handshake JSON is UTF-8 bytes; actual E2EE binary data is identified
   by the E2EE codec header. Client hello -> server hello -> client finish ->
   encrypted server finish; only after validating server finish and durably saving
   the enrolled credential may the app show authenticated. See `e2ee.ts:192-356`,
   `transports/encrypted.ts:111-151,171-182`. This is E2EE v2, distinct from gateway
   v1 and application v3. Each direction is strictly sequenced and processed on
   serialized queues. Full crypto/credential persistence belongs to the separate
   crypto milestone, not this framing fixture. Never send plaintext commands
   directly on the gateway or reuse session keys/counters after reconnect.

## First usable application state

`internal/app/server.go:2834-2867` sends `push_config`, `agents`, `workspaces`,
`activity_history`, then inventory status. These travel encrypted. `push_config`
contains `protocol:3`, version/revision, capabilities, inventory. Require app
protocol 3 before exposing commands; ignore unknown advisory messages safely.
There is no `session_list` command: native session rows are agent/terminal entries
in `agents`; later `agent_update` incrementally replaces an identified entry.
`refresh_agents` is the optional read-only refresh command.

Minimal row data from `internal/coordinator/state.go:19-53`: `pane_id`,
`raw_pane_id`, `terminal_id`, `server_session_id`, `generation`, optional
`agent_session_id`, and display-only `name`, `agent`, `status`, `cwd`/`project`.
Display titles/tab/workspace names are NOT identity. Multi-session server assigns
server_session_id; retain it exactly. If exact identity is missing, render a
non-selectable row rather than guessing a target. Existing old agents contract
fixture lacks server_session_id: it is kept verbatim and **is not sufficient to
issue an authenticated current read**.

Initial inventory may be starting/error. Do not replace the last useful list
with a placeholder empty list until inventory is authoritative. See
`store.ts:1555-1584`. Protocol/new connection resets process-local revisions
(`store.ts:1366-1381`); a stale display must be visibly marked stale.

## Minimum read-only request

See generated `readonly-commands.json`, serialized through actual Go Inbound and
validated by actual DecodeMap/ClassifyAction/Compatible. Exact scope:

- `type: read_pane`, `protocol: 3`, fresh `request_id`.
- top-level `server_session_id` and `pane_id` match the nested `target`.
- target has server_session_id, pane_id, terminal_id, generation, agent_session_id.
- generation is a safe nonnegative integer and must match the observed row.
- agent_session_id may be absent in agents; then send empty string in target.
- `lines:80`, `format:ansi`, `content_fingerprint:""` forces a full first read.

Source: `store.ts:2100-2103,2701-2721`; routing
`protocol.go:295-314`; authorization `server.go:417-460,810-822`.
Go checks current target against the live terminal, including generation and
agent session; a reused pane ID is not authority to read another terminal.
The frontend-only `relay_id` is local routing identity, not a required TargetRef
wire field. No tab_id/workspace_id requirement. `read_pane` stops any active
watch owned by the client. No separate server-wide subscription is needed to
receive the initial agents list.

Response: `pane_content`, matching request_id/pane_id/target, `content` string,
`format`, `content_fingerprint`, possibly `error`, truncation/viewport flags,
attention metadata, no_echo. Actual response builder:
`coordinator/dispatch.go:1187-1255`; enrichment:
`app/server.go:3017-3133`. Treat content as untrusted terminal text, never HTML,
never OS escape handlers. First milestone can render plain monospaced text with
ANSI/control stripping, explicitly not a full VT emulator. No resize lease,
keyboard/input, approval, terminal command, or clipboard escape action.
Match request ID and exact target before updating display (`store.ts:1629-1678`).
Do not acknowledge/display error content as successful evidence.

## Optional next slice: realtime output (not required for snapshot MVP)

`pane_realtime_delta` capability gates watch. First `read_pane` obtains a
fingerprint; `watch_pane` adds interval_ms, lines, format, content_fingerprint,
target/scope. Server either sends full `pane_content` with ack_required or
`pane_delta` with base_fingerprint/content_fingerprint and segments. Segment is
`{copy_start?,copy_lines}` or `{text}`; line boundaries retain newline characters.
The Go-produced `pane-delta-algorithm.json` is an **algorithm fixture**, not an
invented outbound full-message fixture. `panedelta.Build/Apply` roundtrip verifies
it. Actual watch envelope builder is `app/pane_watch.go:306-347`.

After successfully applying/rendering a matching frame, send `pane_applied`
with the new fingerprint and exact target, not a mutating `acknowledge_pane`.
Mismatch/missing base, malformed segment, or `pane_resync`: discard delta and
issue a forced read with empty fingerprint. `pane_unchanged` can satisfy a read
without replacing its contents. On leave send `unwatch_pane`. See
`store.ts:1676-1771,2724-2769`, `app/pane_watch.go:47-90,206-271`.
Keep this out of first native snapshot milestone unless correctness tests cover
replacement, lost ACK, duplicate receipt, reconnect and mismatched fingerprints.

## Reset/reconnect and remaining blockers

Close means discard chunk buffers, pending read IDs, E2EE sessions and watch
state. Use fresh gateway nonce proof and fresh E2EE handshake; only stored
credential/rendezvous config persists. Do not replay consumed invitations or
mutation requests. Re-fetch authoritative agents and resolve the exact selected
target before re-reading. On background/stop disconnect or suspend with explicit
stale state; this MVP does not require a foreground service or auto input.

Smallest native acceptance: paste scoped invitation -> gateway proof -> E2EE
verified enrollment + protected credential persistence -> authoritative list ->
one exact `read_pane` -> native text snapshot -> disconnect/reconnect with stored
credential and refresh. No WebView, no terminal input or resize, no subscription
needed initially. Blockers are native E2EE interop and durable credential commit,
Android WSS lifecycle/TLS, response parsing/scoped correlation and real-device
read-only Windows/VPS acceptance. Current generated vectors do NOT prove any of
those live behaviors, nor full server target authorization from DecodeMap alone.

## Reproduce offline evidence

From this directory, with pinned Go 1.27.3/Bun 1.4.4 installed:
Set `BRIDGE_SOURCE` to an absolute path of a checkout matching source-sha256.json,
then run `bash run.sh`. The script makes a temporary Go module replacement;
no hidden workspace path is assumed and no source files are modified.
The audited upstream/fork checkout must be obtained separately; these fixtures
alone are not the complete server source. Never substitute an arbitrary newer
checkout without reviewing the resulting protocol differences.
Generated auth and command fixtures contain synthetic data only. Test code
copies exact existing contract files rather than asserting invented session JSON.
Run ChunkCodecTest with JUnit4.13.2 and JDK17; its byte vectors are the output of
the actual TS chunk exporter. Reassembler.tick requires an external monotonic
clock timer; adapter must close the socket when it throws. Java intentionally
also rejects >262144-byte carrier chunks and a backwards clock.
