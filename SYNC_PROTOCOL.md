# Relay sync protocol — Slice 1 (protocol version 1)

The phone is the hub and the LAN server; linked devices (desktop, another
phone, a tablet) are clients. Slice 1 covers discovery, pairing, and the
authenticated encrypted channel — no message content, contacts, or
notifications cross the channel yet. The desktop repo carries a matching
copy of this spec in `ARCHITECTURE.md` — keep the two in sync.

## Discovery (mDNS)

- Service type `_relay._tcp.`, instance name `SMS Relay`, fixed port
  **47831**.
- TXT records: `v=1`, `hub=<hub-id>` (the phone's stable hub id, a UUID
  generated on first run and stored on the phone).
- The phone advertises with `android.net.nsd` (NsdManager). The QR flow
  does not carry an address — discovery finds it.

## Pairing (QR)

- A linking device generates a stable Ed25519 keypair and a stable device
  id (UUID). Its QR encodes:
  `relay://pair?v=1&id=<device-id>&kind=<desktop|phone|tablet>&name=<url-encoded display name>&key=<base64url 32-byte Ed25519 public key>`
- The phone scans it in the Linked devices screen (CameraX preview +
  ZXing core decoding — no Play Services dependency), the user confirms,
  and the phone stores the device: id, kind, display name, public key,
  linked time. Pairing is one-way registration — the linking device needs
  no key from the phone.

## Transport + TLS

- WebSocket over TLS: `wss://<host>:47831/sync`. Server: Ktor (Netty
  engine, WebSockets plugin), running in a foreground service. Netty,
  not CIO: Ktor 3.x's CIO server engine has no HTTPS support — it throws
  on any `sslConnector`, which crashed the app on first server start
  (2026-11-13); `SyncEngineTlsTest` pins the engine+TLS combination.
- The phone generates a self-signed X.509 certificate on first run
  (CN = hub id, 10-year validity). The client pins its SHA-256 fingerprint
  on first connect (TOFU). A fingerprint mismatch after pinning is a hard
  refusal. There is no unencrypted fallback. TLS 1.2 minimum.
- The TLS certificate is transport-only. Device authentication is the
  Ed25519 challenge/response below — two separate key systems by design.

## Wire protocol (JSON over WebSocket, protocol version 1)

Every message is a JSON object with a `type` field. The client speaks
first:

| Direction | Message | Shape |
|---|---|---|
| Client → server | `hello` | `{"type":"hello","protocol":1,"device_id":"...","kind":"desktop","name":"..."}` |
| Server → client | `challenge` | `{"type":"challenge","challenge":"<base64url 32 random bytes>"}` |
| Client → server | `auth` | `{"type":"auth","device_id":"...","signature":"<base64url 64-byte Ed25519 signature over the raw challenge bytes>"}` |
| Server → client | `auth_ok` | `{"type":"auth_ok","hub_id":"...","hub_name":"SMS Relay"}` |
| Server → client | `auth_failed` | `{"type":"auth_failed","reason":"unknown_device"\|"bad_signature"\|"revoked"\|"protocol"}` |
| Either way | `ping` / `pong` | `{"type":"ping","t":<unix ms>}` / `{"type":"pong","t":<same t>}` |
| Server → client | `revoked` | `{"type":"revoked"}` |
| Either way | `error` | `{"type":"error","message":"..."}` |

Auth flow: connect → `hello` → `challenge` → `auth` → `auth_ok` (or
`auth_failed`). A socket that has not completed auth within 10 seconds is
closed by the server. The challenge is issued to any client; the device
check happens at `auth` verification against the stored public key.

Heartbeat: the client sends `ping` every 30 seconds; the server answers
`pong`. The client treats a missed `pong` (10s) as a dead channel and
reconnects with backoff (1s, 2s, 5s, 10s, 30s cap). The server drops
clients silent for more than 90 seconds.

Revocation: removing a device on the phone closes its live socket
(sending `revoked` first) and deletes its stored key. Later auth attempts
from it get `unknown_device`.

## Key storage (phone)

Linked-device records in the app's store: device id, kind, display name,
public key (base64url), linked time, last seen. TLS certificate and key in
app-private storage / the AndroidKeyStore.

## Slice 1 scope boundary

No message content, contacts, or notifications cross the channel yet.
Later slices add sync message types in the same envelope (new `type`
values); the protocol version stays 1 until a breaking change needs it.

## Glue points (Android)

The sync layer is kept separable from the rest of the Android app, so
it survives if the app's base is ever swapped out. Everything in
`com.filestech.sms.sync` — protocol, crypto, QR payload format,
transport, discovery, the linked-device store, the TLS identity —
imports no app code outside that package. The ONLY coupling is this
thin, explicit glue:

1. **The foreground service wrapper** (`sync/SyncService.kt`): an
   Android `Service` that hosts `SyncServer`, owns its notification
   channel and strings, and is started/stopped by `MainApplication`
   (toggle observer) and `BootReceiver` (boot restart).
2. **The Settings navigation entry** (`ui/navigation/Routes.kt`,
   `ui/AppRoot.kt`, `ui/screens/settings/SettingsScreen.kt`): one
   route, one destination, one row.
3. **The Linked devices screen** (`ui/screens/linkeddevices/`): the
   ViewModel talks only to `LinkedDeviceStore` and `SyncServer`; the
   screen reuses the app's theme and shared settings components
   (`RelaySettingsGroup`, `RelaySwitchRow`, …) — the one visual
   dependency.
4. **Future glue, not built in Slice 1:** reading/writing messages
   via the repositories, when a later slice syncs message content.
   Nothing in the sync package pre-builds it.

A future port re-wires those four points and lifts the `sync` package
as-is.
