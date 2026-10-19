# Bluetooth Device Bridge — Design (2026-07-29)

Users connect their Bluetooth (BLE) devices **through the browser** and grant
agents access to read from / write to them. Requested by the maintainer 2026-07-29 with
three constraints: loosely coupled, lightweight on JVM/memory, and an explicit
question — should it live in `sidecar`, `code-intel`, or `devices`?

Status: **design only, nothing implemented.** No Bluetooth code exists anywhere
in the repo today (checked `core/`, `web/`, `docs/` — zero hits).

---

## 1. The one fact that decides the whole architecture

**Web Bluetooth runs only in the browser.** The browser process owns the BLE
radio; a server process — JVM, sidecar's Python, code-intel's Python, anything
in Docker on the host — physically cannot see the *user's* Bluetooth adapter,
which is on the user's laptop/phone, not on the server. There is no design in
which the backend "does Bluetooth."

That settles the placement question:

| Candidate | Verdict | Why |
|---|---|---|
| `sidecar` | **No** | It's the knowledge/RAG pipeline (chunking, embeddings, OCR). Wrong domain, and Python-on-the-server can't reach the user's radio anyway. |
| `code-intel` | **No** | RAG-over-codebase. Same two objections. |
| `core/devices` | **Yes** | It already *is* "user hardware paired to a tenant, exposed to agents as tools": pairing, revocation, a command queue, tenant-scoped entities, and four `read_device_*` agent tools follow from it. A BLE device is a second kind of device, not a new concept. |

And it settles the lightweight question for free: since the browser holds the
GATT connection, the JVM piece is **a message relay and a registry** — no BLE
stack, no native libraries, no polling threads, no per-device state beyond one
map entry. The heavy lifting (GATT, pairing dialogs, the radio) is the
browser's, at zero cost to us.

## 2. Research: Web Bluetooth constraints that shape the design

Facts verified against the Web Bluetooth spec / Chromium status as of
knowledge cutoff — re-verify browser-version specifics before implementation:

- **Chromium-only.** Chrome/Edge/Opera/Samsung Internet on desktop + Android.
  **Safari does not support it (WebKit has formally declined), so no iOS at
  all** (iOS Chrome is WebKit too). Firefox: no. The bridge page must
  feature-detect (`"bluetooth" in navigator`) and show a "use Chrome or Edge"
  card otherwise. This is a hard platform constraint to surface in the UI, not
  something we can engineer around.
- **HTTPS required.** Fine — everything is served from `https://app.staging.example.com`
  (RULE 0; also means no localhost testing surprises, test through the domain).
- **User gesture required** for `navigator.bluetooth.requestDevice()` — the
  browser shows its own device chooser. This is a security *feature* for us:
  the user physically picks the device; a page (or an agent) can never
  silently enumerate or grab devices.
- **Per-origin, per-device permission**, persisted by the browser.
  `navigator.bluetooth.getDevices()` returns previously-granted devices, so
  the bridge can auto-reconnect on page load without re-prompting.
- **The browser enforces a GATT blocklist** (e.g. FIDO/HID services) — we get
  that protection for free.
- **A tab must be open** for the connection to live. Web Bluetooth has no
  service-worker/background story we can rely on cross-version. The bridge is
  therefore an explicit "this tab is your device hub" surface (same mental
  model as WhatsApp Web), with auto-reconnect + a watchdog.

## 3. Architecture

```
┌──────────────── user's machine ────────────────┐      ┌───────────── server ─────────────┐
│  Chrome/Edge tab: app.staging.example.com/devices/ble │      │  core (JVM)                      │
│  ┌──────────────┐   ┌───────────────────────┐  │ wss  │  ┌────────────────────────────┐  │
│  │ BLE device(s)│◄──┤ Web Bluetooth (GATT)  │  │◄────►│  │ core/devices (extended)    │  │
│  │ HR band, ESP32│  │ + bridge JS (web/)    │  │      │  │  BleBridgeWebSocketHandler │  │
│  └──────────────┘   └───────────────────────┘  │      │  │  BleDeviceService/registry │  │
└────────────────────────────────────────────────┘      │  └─────────▲──────────────────┘  │
                                                        │            │ ObjectProvider      │
                                                        │  ┌─────────┴──────────────────┐  │
                                                        │  │ core/agents                │  │
                                                        │  │  3 ble_* ToolCallbacks     │  │
                                                        │  └────────────────────────────┘  │
                                                        └──────────────────────────────────┘
```

Three touch points, each following an existing in-repo pattern:

### 3.1 `web/` — the bridge page (owner: `web` agent)

`web/app/devices/bluetooth/page.tsx` plus a `lib/ble-bridge.ts` module.
(`/devices` today is just a redirect to the Channels screen's Mobile SDK tab —
the Channels screen gets a "Bluetooth" tab linking here, same IA merge logic.)

Responsibilities:
- `requestDevice()` flow (user picks device), then register it with core
  (`POST /api/devices/ble`) → tenant-scoped row.
- Hold GATT connections; on page load, `getDevices()` + reconnect loop with
  exponential backoff (`gattserverdisconnected` listener). Watchdog ping over
  the WS every ~20s (WhatsApp keep-alive precedent).
- One WebSocket to core per tab. Speaks the tiny JSON protocol in §5.
- Execute relayed commands: `read` (readValue), `write` (writeValue —
  with-response variant), `subscribe`/`unsubscribe` (startNotifications).
- **Throttle notification streams in the browser**: coalesce to latest-value,
  max ~2 messages/sec/characteristic, before anything touches the server.
  This is the main "don't load the JVM" lever and it lives client-side.
- Per-device access toggles (agent access on/off, read-only vs read+write) —
  rendered here, stored server-side.

### 3.2 `core/devices` — registry + relay (owner: the core maintainers)

**Why not a new module:** `devices` already depends on exactly what this needs
(tenancy, security, JPA) and nothing this doesn't. A separate `blegateway`
module would need those same deps plus a dependency edge from `agents` — more
coupling, not less. Loose coupling is preserved the same way Mobile SDK's is:
`agents` sees only a service interface via `ObjectProvider` (§3.3), and nothing
else in core knows BLE exists.

New pieces (all in `com.agenticai.core.devices.ble`):

- **`BleDevice` entity** (tenant schema, new `ble_device` table — new Flyway
  migration; take the next free V-number *at build time*, the sequence is
  shared across in-flight work). Columns: id, browser-side device id, name,
  granted service UUIDs, `agent_access` (NONE / READ / READ_WRITE),
  created/last-seen/revoked. **Deliberately NOT a `MobileDevice` row**: that
  entity's core semantic is the `X-Device-Token` credential + poll loop, and a
  browser bridge authenticates as the *user* (Keycloak JWT), not as a headless
  device. Forcing BLE into it would corrupt both models. Two small entities,
  each honest, is the loosely-coupled shape.
- **`BleBridgeWebSocketHandler`** at `/api/devices/ble/bridge` — registered via
  a `WebSocketConfigurer` exactly like `VoiceRelayWebSocketConfig`, wrapped in
  `ConcurrentWebSocketSessionDecorator` (send-time limit + bounded buffer,
  same constants), tokens relayed from callers on whatever thread they arrive.
- **`BleSessionRegistry`** — `ConcurrentHashMap<tenantSchema+deviceId, session>`
  plus a last-known-value cache per subscribed characteristic (a few dozen
  bytes each). This is the *entire* server-side state. Single-instance core
  assumption (true of today's compose stack) — documented; the scale-out path
  is Redis pub/sub routing, NOT sticky sessions, and is explicitly out of v1.
- **`BleCommandRelay`** — the request/response bridge agents call:
  `CompletableFuture` per in-flight command, correlation id, **hard timeout
  (default 10s) and a bounded in-flight map (default 32/tenant)**; timeout or
  overflow fails the future with a clear message. No threads of its own — the
  future completes on the WS receive thread, callers block on their own
  boundedElastic thread exactly like every other tool call.
- REST for the console: `GET/POST/DELETE /api/devices/ble`, `PUT .../access`
  (the agent-access toggle). Standard Keycloak-authenticated controller.

**WS handshake auth:** browsers cannot set an `Authorization` header on a
WebSocket. Do NOT put the JWT in the query string (it lands in access logs).
Use the ticket pattern: `POST /api/devices/ble/ws-ticket` (authed, returns a
30-second single-use random ticket bound to tenant+user) → open
`wss://api.staging.example.com/api/devices/ble/bridge?ticket=...` → handshake
interceptor redeems the ticket and stashes tenant/user in session attributes.
A burned ticket in a log is worthless. (The `/webhook/**` permitAll posture of
the voice relay does not apply here — this socket must be tenant-authed.)

### 3.3 `core/agents` — three tools (owner: the core maintainers)

Mirror the `read_device_*` pattern precisely — package
`com.agenticai.core.agents.tools.ble`, wired in `AgentToolProvider` via
`ObjectProvider<BleCommandRelay>` (slim test contexts keep working; tools
always registered, "no connected Bluetooth device" comes back as a tool
*result*, per the same always-visible decision documented on
`resolveDeviceTools`):

- `list_bluetooth_devices` — registered devices, connection state, granted
  services, access level.
- `read_bluetooth_device(device, service, characteristic)` — served from the
  last-value cache when a notification stream is live (zero radio round
  trips), otherwise relayed as a GATT read.
- `write_bluetooth_device(device, service, characteristic, valueHex)` — only
  if the device's `agent_access = READ_WRITE`; otherwise the tool returns a
  "writes not enabled for this device — enable on the Devices page" result.
  Writes also flow through the *existing* guardrails/autonomy-tier machinery
  like every other side-effectful tool; nothing new to build there.

## 4. Security & safety

- **User picks the device** in the browser's own chooser (unfakeable by us or
  by an agent); browser blocklist stops FIDO/HID abuse classes.
- **Agent access is opt-in per device**, default NONE, read-only by default
  when enabled. Writes are a second explicit toggle. Revoke = row flag +
  registry eviction (socket told to drop the device).
- **Prompt-injection surface**: device names and characteristic values are
  attacker-controllable text (anyone can name a BLE peripheral
  `"ignore previous instructions..."`). Tool results must pass through the
  same control/invisible-character stripping used for MCP tool output, and
  values are returned as hex + best-effort UTF-8, clearly labeled as data.
- **Tenant isolation** is the existing schema-per-tenant story — `BleDevice`
  is an ordinary tenant-scoped entity; the WS session carries its tenant from
  ticket redemption and every relay lookup is keyed on it.
- **No PHI persistence by default** (heart-rate bands etc. are health data):
  v1 stores *no readings* server-side — only the in-memory last-value cache,
  gone on disconnect. If telemetry history is ever wanted, it's a separate,
  consciously-consented feature.

## 5. Wire protocol (browser ⇄ core), v1

Tiny JSON, one message type field, mirroring Conversation Relay's shape:

```
browser → core:  {type:"hello", devices:[{id,name,services:[...]}]}
                 {type:"deviceState", deviceId, connected}
                 {type:"value", deviceId, service, characteristic, hex, ts}     // read result or throttled notification
                 {type:"result", correlationId, ok, hex?|error?}
                 {type:"ping"}
core → browser:  {type:"read"|"write"|"subscribe"|"unsubscribe",
                  correlationId, deviceId, service, characteristic, hex?}
                 {type:"revoked", deviceId}
                 {type:"pong"}
```

Values are hex strings end-to-end (GATT is bytes; JSON has no bytes). Parsing
profiles (e.g. Heart Rate Measurement 0x2A37) happens browser-side where the
Bluetooth SIG-assigned-number knowledge already has to live for the UI; the
server relays opaque hex and stays dumb — that's what keeps it small.

## 6. Why this is lightweight (the actual numbers)

- JVM adds: 1 WS endpoint, 2 small services, 1 JPA entity, 3 tool callbacks.
  **Zero new dependencies** — `spring-boot-starter-websocket` already ships in
  the app via omnichannel's voice relay.
- Steady-state heap per tenant actively using it: one WS session + registry
  entries + last-value cache ≈ a few KB. Idle tenants: **zero** (no session,
  no cache, just DB rows).
- No schedulers, no polling loops, no background threads owned by this
  feature. All pushes ride the WS; all waits are caller-owned futures with
  timeouts; notification volume is throttled *before* it reaches the JVM.
- No native/BLE libraries anywhere on the server (impossible to need them —
  see §1).

## 7. Explicitly out of v1

- **Headless/server-side BLE hub** (a Raspberry-Pi-style gateway using BlueZ,
  for devices near the *server*): real use case, entirely different transport,
  would slot in as a second producer speaking the §5 protocol over the same
  WS endpoint — design leaves the door open, builds nothing.
- Multi-instance core session routing (Redis pub/sub) — documented path only.
- Reading history/telemetry persistence (see PHI note, §4).
- Classic Bluetooth (non-BLE) — Web Bluetooth simply doesn't do it.
- iOS/Safari support — not possible via Web Bluetooth; the honest fallback if
  ever needed is the Mobile SDK app owning phone-side BLE, which is exactly a
  `MobileDevice` feature, not this one.

## 8. Build order & ownership (per CLAUDE.md roster)

| # | Piece | Owner | Depends on |
|---|---|---|---|
| 1 | Entity + migration + REST + ws-ticket + WS handler + registry/relay | the core maintainers | — |
| 2 | Bridge page + ble-bridge.ts + Channels tab | `web` | 1 (contract in §5 is the interface; can stub against a mock WS) |
| 3 | The three `ble_*` tools in AgentToolProvider | the core maintainers | 1 |
| 4 | E2E through the domain with a real device (any BLE heart-rate band or an ESP32) | either | 1–3 |

Cross-agent dependency (`web` needs the core contract) is agreed up front; §5 of this doc *is* the contract, so
the two can proceed in parallel once it's agreed.
