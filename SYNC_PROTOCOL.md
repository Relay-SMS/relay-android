# Relay sync protocol — Slice 1 (protocol version 1)

The phone is the hub and the LAN server; linked devices (desktop, another
phone, a tablet) are clients. Slice 1 covers discovery, pairing, and the
authenticated encrypted channel — no message content, contacts, or
notifications cross the channel yet. The desktop repo carries a matching
copy of this spec in `ARCHITECTURE.md` — keep the two in sync.

## Discovery (mDNS)

- Service type `_relay._tcp.`, instance name the phone's device model
  (e.g. `Pixel 8`), fixed port **47831**. The model (not a constant)
  so two hubs on one network are distinguishable; on an instance-name
  clash Android renames the second one (`Pixel 8 (2)`, …) — clients
  match on the service type and the `hub` TXT record, never the name.
- TXT records: `v=1`, `hub=<hub-id>` (the phone's stable hub id, a UUID
  generated on first run and stored on the phone), and — while a VPN
  tunnel is the phone's active network — `tip=<tunnel-ipv4>` (the
  phone's tunnel address; see "VPN / tunnel" below). Absent means no
  tunnel path right now.
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
  on first connect (TOFU) — one pin per hub, keyed by hub id
  (`hub_fingerprint_<hub-id>.bin`; the legacy single
  `hub_fingerprint.bin` is only the fallback when the hub id is
  unknown). A fingerprint mismatch after pinning is a hard refusal.
  There is no unencrypted fallback. TLS 1.2 minimum.
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
| Server → client | `auth_ok` | `{"type":"auth_ok","hub_id":"...","hub_name":"Pixel 8"}` |
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

## Slice 2 — message sync (protocol version 1)

Slice 2 syncs real conversations + messages from the phone to the
desktop over the Slice 1 channel. All ids on the wire are decimal
strings of the phone's Room ids. Attachments are metadata only in
Slice 2 — no bytes cross the channel yet.

| Direction | Message | Shape |
|---|---|---|
| Client → server | `sync_request` | `{"type":"sync_request","mode":"full"\|"new_only","since_message_id":"12345"}` |
| Client → server | `messages_request` | `{"type":"messages_request","conversation_id":"123","before_id":"789","limit":200}` |
| Server → client | `sync_started` | `{"type":"sync_started","mode":"full","total_conversations":87,"total_messages":12340,"cursor_message_id":"98765","hub_is_default_sms":true}` |
| Server → client | `conversations` | `{"type":"conversations","conversations":[CONVERSATION, "…"],"has_more":false}` |
| Server → client | `messages_batch` | `{"type":"messages_batch","conversation_id":"123","messages":[MESSAGE, "…"],"has_more":true,"next_before_id":"456"}` |
| Server → client | `sync_complete` | `{"type":"sync_complete","cursor_message_id":"98765"}` |
| Server → client | `category_mutes` | `{"type":"category_mutes","muted_categories":["otp","promotional"]}` |
| Server → client | `e2e_safety_numbers` | `{"type":"e2e_safety_numbers","peers":[{"address":"+33612345678","state":"encrypted","safety_number":"12345 67890 …","verified":false}]}` |
| Server → client | `e2e_notices` | `{"type":"e2e_notices","notices":[{"id":"12","conversation_id":"123","kind":"activated","date":1793000000000},{"id":"13","conversation_id":"124","kind":"group_key_changed","subject":"+33612345678","date":1793000001000}]}` |
| Client → server | `set_e2e_verified` | `{"type":"set_e2e_verified","address":"+33612345678","verified":true}` |
| Client → server | `retry_e2e_probe` | `{"type":"retry_e2e_probe","address":"+33612345678"}` |
| Server → client | `message_new` | `{"type":"message_new","message":MESSAGE}` |
| Server → client | `message_updated` | `{"type":"message_updated","message_id":"789","conversation_id":"123","status":"delivered","reaction":"❤️"}` |
| Server → client | `conversation_updated` | `{"type":"conversation_updated","conversation":CONVERSATION}` |
| Server → client | `conversation_deleted` | `{"type":"conversation_deleted","conversation_id":"123"}` |
| Server → client | `sync_error` | `{"type":"sync_error","code":"hub_no_sms_access","message":"…"}` |
| Server → client | `hub_status` | `{"type":"hub_status","is_default_sms":false}` |

Flow: the desktop sends `sync_request` after auth. The phone answers
`sync_started` (totals + the current max message id as the cursor),
streams the conversation snapshot in batches of 100 (most recent
first), then — in `full` mode — every conversation's messages in
batches of 200, newest first, most-recent conversation first, then
`sync_complete`. In `new_only` mode the message stream is skipped:
the list syncs, threads start empty, and the `sync_started` cursor
is the "now" cursor. A `sync_request` with `since_message_id > 0`
(reconnect) sends the full conversation snapshot plus a delta — the
messages above the cursor, per conversation — instead of the full
history; those delta batches carry `has_more: false` (no `before_id`
continuation for a delta range).

`messages_request` is the targeted fetch: gap-fill, repair of one
conversation, or on-demand fetch. The phone answers with one
`messages_batch`; an unknown conversation id gets a plain `error`
(the socket stays up). `limit` is capped at 200 by the server.

Live push runs from auth onwards, concurrently with the initial
stream — upserts by id make duplicates harmless. `message_new` is
one live message; `conversation_updated` carries the full row (and
is also the read-state indicator: `unread: 0` means the phone marked
the conversation read); `conversation_deleted` fires when a
conversation disappears from the phone. A new conversation arrives
as `conversation_updated` followed by a `message_new` for each of
its messages, oldest first — so a first text from a new number
notifies on the desktop like any other (the desktop notifies only
from `message_new`). `message_updated` is
partial — only changed fields are present (`status`, `reaction`,
`read`, `starred`); a removed reaction rides as
`"reaction_removed": true`, since a null `reaction` would be
omitted on encode and read as "unchanged".

`sync_error` codes: `hub_no_sms_access` (READ_SMS missing on the
phone — the debug build needs the manual grant, since only one app
can hold the default-SMS role), `hub_mirror_empty` (permission
granted but the phone's message import hasn't run yet), `internal`
(anything else; the message stays generic, never a stack trace).

`hub_status` is the default-SMS warning. Every `sync_started`
carries `hub_is_default_sms`, and the phone pushes `hub_status`
to all connected devices the moment the role changes: the service
listens for the system's `ACTION_DEFAULT_SMS_PACKAGE_CHANGED`
broadcast (sent to both the old and the new default package) and
answers it with an immediate re-check + broadcast. A 30 s re-check
on the silence-watchdog tick stays as a safety net in case the
broadcast is ever missed on some device. (`RoleManager`'s own
role-change listener is a system API, absent from the public SDK,
so the broadcast is the instant mechanism.) When the hub app is
not the default SMS app, new messages only reach its mirror on app
open or the periodic import — so the desktop shows a persistent
banner above the conversation search until the phone reports it is
the default again.

`hub_status` also carries the tunnel address: when the phone's VPN
tunnel comes up or goes down mid-session, the phone pushes
`hub_status` with `tunnel_ip` (see "VPN / tunnel" below), so the
desktop learns the tunnel address without a re-scan.

CONVERSATION: `id`, `thread_id` (may be null), `addresses`,
`display_name`, `custom_name`, `title` (the phone's computed title —
authoritative for display), `last_message_at`,
`last_message_preview`, `unread`, `pinned`, `archived`, `muted`,
`blocked`, `message_count`, `pinned_message_id`,
`pinned_message_at`. Not synced: `draft`, bubble color, avatar.
Vault conversations never leave the phone, by design.

E2E adds the optional `encrypted` (CONVERSATION + MESSAGE) and
`decrypt_failed` (MESSAGE) — fields, not frame types, so no new
frame is needed and the channel allowlist is untouched. All three
default to `false` and are omitted on the wire while false (the
encoder drops defaults), so a frame from an older hub still parses
on a new client and a new frame still parses on an older client
(unknown keys are ignored). `encrypted` on a conversation is true
when the 1:1 peer's state is ENCRYPTED on the hub; **round 49 — a
group conversation's flag is true when the group's state is
Encrypted (every member ENCRYPTED + group-capable — the hub
computes it from the member peer rows, like the 1:1 flag from the
peer row; a blocked member keeps the flag false)**. When a peer's state flips to ENCRYPTED the hub
pushes a `conversation_updated` carrying the new flag, so the
desktop re-renders the row badge + composer placeholder without a
re-sync; a member's flip that changes a group's state pushes the
same update for the group conversation. `encrypted` on a message is true when it was encrypted on
the wire; `decrypt_failed` marks the placeholder row for a message
whose ciphertext arrived but could not be decrypted (the client
renders "This message couldn't be decrypted." instead of a bubble).
The raw ciphertext is NEVER synced — the `ciphertext` column stays
on the hub; linked devices get the flags, not the bytes.

MESSAGE: `id`, `conversation_id`, `address` (the sender, not the
group), `body`, `type` (`sms` | `mms`), `direction` (`incoming` |
`outgoing`), `date`, `date_sent`, `read`, `starred`, `status`
(`pending` | `sent` | `delivered` | `failed` | `received` |
`scheduled`), `error_code`, `reply_to_message_id` (a phone Room id),
`reaction`, `attachments` (ATTACHMENT metadata: `id`, `mime_type`,
`file_name`, `size_bytes`, `width`, `height`, `duration_ms`).
Slice 3 adds the optional `client_message_id` (see below) — present
only on messages the desktop itself sent, omitted otherwise.

Message deletions are not synced in Slice 2 (the phone has no
deletion feed); the repair path is wipe + re-walk via
`messages_request`. Reactions on old messages (outside the ~200 most
recent pushed ids) are not live-pushed either — same repair path.

## Slice 3 — send from the desktop + write-back (protocol version 1)

Slice 3 makes the desktop a real messaging client: it sends text
messages through the phone and applies mark-read / pin / archive /
mute / reactions / pinned-message back onto the phone. All ids are
still decimal strings of the phone's Room ids. The one new id kind
is the **client message id**: a UUID v4 generated by the desktop per
send attempt, used for idempotency (below) and for matching the
optimistic row to the real message.

| Direction | Message | Shape |
|---|---|---|
| Client → server | `send_message` | `{"type":"send_message","client_message_id":"3f2a…c41d","conversation_id":"123","body":"See you at 6","reply_to_message_id":"555"}` |
| Client → server | `mark_read` | `{"type":"mark_read","conversation_id":"123"}` |
| Client → server | `mark_all_read` | `{"type":"mark_all_read"}` |
| Client → server | `set_pin` / `set_archived` / `set_muted` / `set_blocked` | `{"type":"set_pin","conversation_id":"123","value":true}` |
| Client → server | `set_category_muted` | `{"type":"set_category_muted","category":"otp","muted":true}` |
| Client → server | `set_reaction` | `{"type":"set_reaction","conversation_id":"123","message_id":"789","emoji":"❤️"}` |
| Client → server | `set_pinned_message` | `{"type":"set_pinned_message","conversation_id":"123","message_id":"789"}` |
| Server → client | `message_sent` | `{"type":"message_sent","client_message_id":"3f2a…c41d","message_id":"789","conversation_id":"123","status":"pending"}` |
| Server → client | `message_failed` | `{"type":"message_failed","client_message_id":"3f2a…c41d","conversation_id":"123","code":"hub_not_default_sms","message":"…"}` |
| Server → client | `action_result` | `{"type":"action_result","action":"set_pin","conversation_id":"123","ok":true}` |

`send_message` sends a text message in an existing conversation —
the phone resolves the recipients from the conversation (the desktop
never sends addresses; a stale address list must not send to the
wrong people). The phone appends the user's signature, exactly as
its own composer does — the desktop sends the body as typed. Group
text works with no extra work: the phone's send router decides group
MMS vs individual SMS from its own setting. `reply_to_message_id`
is optional; a dangling id is tolerated, same as the phone's own
send path. `message_sent` is the ack, matched by
`client_message_id`: `message_id` is the phone's Room id of the row
the desktop shows (the sent row for a 1:1 send, the group-thread row
for a group send), and `status` is the phone-side status at ack time
— normally `"pending"`, never a delivery guarantee. The sent /
delivered receipts arrive later via `message_updated`, and the real
row arrives via `message_new` — whichever arrives first, the desktop
reconciles by `client_message_id`. A `message_sent` may also carry
`"blocked_count": 1` when some recipients were blocked but others
were sent (group broadcast only).

`message_failed` codes: `hub_not_default_sms` (the linked app
doesn't hold the default SMS role — sending is impossible until it
does; the same condition the default-app banner warns about),
`unknown_conversation` (the conversation is gone, or is a vault
conversation the desktop must never address),
`blocked_recipient` (every recipient is blocked),
`message_too_long` (body over the 64 KB wire cap), `send_failed`
(the radio refused synchronously), `internal` (anything else; the
message stays generic, never a stack trace). A refused send is never
a protocol violation — the socket stays up.

`action_result` is the one ack type for every action: `action`
echoes the request type, `message_id` is present for `set_reaction`
/ `set_pinned_message`, `category` is present for
`set_category_muted` (the reply echoes the category it changed),
and `conversation_id` is absent for
`mark_all_read`. `ok: false` means the phone did not apply it — the
desktop reverts its optimistic change (the live
`conversation_updated` corrects it either way). Codes reuse the
`message_failed` set where they apply (`unknown_conversation`,
`internal`) plus `send_failed` for a reaction whose SMS didn't go
out — the local reaction state stays set on the phone in that case,
matching the phone's own behavior. `set_reaction` with `emoji: null`
(or absent) removes the reaction; `set_pinned_message` with
`message_id: null` (or absent) unpins. A reaction from the desktop
sends a real SMS from the phone, exactly as reacting on the phone
does — it is not free.

**Idempotency.** A send is a charged, real-world action: if the
desktop sends, the phone dispatches, and the ack is lost (socket
drop, phone restart), the desktop's retry — which REUSES the same
`client_message_id` — must NOT send the text a second time. The key
lives on the message row (`messages.client_message_id`, unique
index, schema v16), stamped at insert time, before the radio
dispatch, so the dedup survives a phone restart; one mutex in the
glue serializes all send handling, and the dispatch runs
non-cancellable so a dropped session mid-send cannot strand the
message. A retry that finds its key returns the stored result
(pending / sent / delivered) or re-dispatches the existing row
(failed) — never a new send. The MESSAGE shape gains the optional
`client_message_id` field (present only on messages the desktop
itself sent) so the desktop can match a live `message_new` to its
optimistic row when the push arrives before the ack.

**Validation (server-side, all cheap).** A `send_message` with a
missing/blank body, a body over 64 KB, an unknown or vault
conversation id, or a missing `client_message_id` is refused with
`message_failed`. Action frames for an unknown or vault conversation
get `action_result` with `ok: false`, code `unknown_conversation`.
An unauthenticated send or action frame is a protocol violation
(the socket closes), like an unauthenticated sync frame.

**Out of Slice 3:** MMS / media sending from the desktop (needs the
attachment-upload path — done in Slice 4, below), deleting
conversations or messages, block / unblock, mark-unread, starred
messages, scheduling (done in Phase 2, below), and sending to a new
conversation (no thread yet). Offline sends are refused, not queued — while disconnected the
desktop composer is disabled.

## Slice 4 — attachment bytes (protocol version 1)

Slice 4 moves the bytes behind the metadata Slice 2 synced: the
desktop downloads photos, videos, voice clips, and files on demand
(lazy only — opening a thread fetches its attachments, nothing is
prefetched), and sends photos, GIFs, stickers, and files through the
phone's MMS sender. Same JSON envelope, same `type` field, same
rules: the server parses with `ignoreUnknownKeys`, unknown types are
answered with `error` by the server (after auth) and ignored by the
client. All ids are decimal strings of the phone's Room ids, except
`client_message_id` (UUID v4) and upload indices (small integers).
No protocol bump: every addition is additive, and a Slice 3 desktop
talking to a Slice 4 phone simply never sends the new types. A
Slice 4 desktop talking to a Slice 3 phone gets `error` for
`attachment_request` — the engine treats that as "attachments
unavailable" and keeps the placeholders.

| Direction | Message | Shape |
|---|---|---|
| Client → server | `attachment_request` | `{"type":"attachment_request","attachment_id":"12","message_id":"789"}` |
| Server → client | `attachment_chunk` | `{"type":"attachment_chunk","attachment_id":"12","offset":0,"data_base64url":"…","final":false}` |
| Server → client | `attachment_error` | `{"type":"attachment_error","attachment_id":"12","code":"attachment_unreadable","message":"…"}` |
| Client → server | `attachment_upload_start` | `{"type":"attachment_upload_start","client_message_id":"3f2a…c41d","index":0,"mime_type":"image/jpeg","file_name":"photo.jpg","size_bytes":198402,"sha256":"ab12…"}` |
| Client → server | `attachment_upload_chunk` | `{"type":"attachment_upload_chunk","client_message_id":"3f2a…c41d","index":0,"offset":0,"data_base64url":"…","final":false}` |
| Server → client | `attachment_upload_ready` | `{"type":"attachment_upload_ready","client_message_id":"3f2a…c41d","index":0}` |
| Server → client | `attachment_upload_done` | `{"type":"attachment_upload_done","client_message_id":"3f2a…c41d","index":0}` |
| Server → client | `attachment_upload_error` | `{"type":"attachment_upload_error","client_message_id":"3f2a…c41d","index":0,"code":"upload_too_large","message":"…"}` |
| Client → server | `send_message` (extended) | `{"type":"send_message","client_message_id":"3f2a…c41d","conversation_id":"123","body":"Look at this","attachments":[{"upload_index":0,"mime_type":"image/jpeg","file_name":"photo.jpg","width":1600,"height":1200},{"sticker_emoji":"😂"}]}` |

**Download.** `attachment_request` names the attachment and its
message: `message_id` is the membership guard — the phone verifies
the attachment belongs to that message and the message to a
non-vault conversation, so a stale or malicious client cannot pull
vault bytes by id. The phone answers with `attachment_chunk` frames
(one per 64 KiB of raw bytes, base64url in JSON — no binary frames,
no new transport) or one `attachment_error`. `offset` is the raw
byte offset; chunks are sequential per attachment, so the desktop
appends in arrival order and checks `offset` against the bytes
accumulated so far (a gap is a protocol error — fail the download).
The final chunk carries `sha256` (hex) and `size_bytes` (actual
bytes read) — the phone hashes while streaming, one read pass — and
the desktop verifies both before the file enters its cache; a
mismatch discards the bytes. There is no separate "start" frame:
the first chunk is the start signal (a stalled download is caught
by the desktop's 30 s timeout). A file whose size is an exact
multiple of 64 KiB ends with one empty final chunk. The phone
refuses a download over 25 MB — a guard against a corrupt row
claiming gigabytes, not a limit anyone should hit. The desktop runs
at most 3 downloads at once and queues the rest FIFO; the phone
serves requests concurrently (one job each), chunks interleaving on
the wire, each tagged with its `attachment_id`. If the socket drops
mid-download, the desktop restarts from scratch on reconnect — no
resume in Slice 4.

`attachment_error` codes: `unknown_attachment` (no such row, or it
belongs to a different message / a vault conversation — same answer
for all three, so ids can't be probed), `attachment_unreadable`
(the row exists, but the bytes don't — file gone, part URI won't
open), `attachment_too_large` (over the 25 MB cap), `internal`
(anything else; the message stays generic, never a stack trace).

**Upload + media send.** One upload per attachment, sequential:
`attachment_upload_start` (validated by the phone — a single file
over 280 KB is refused, since it could never be sent — then
`attachment_upload_ready`), `attachment_upload_chunk` ×N (streamed,
same 64 KiB chunking and `offset` rule, no per-chunk ack — TCP is
the flow control), then `attachment_upload_done` once all chunks
arrived and size + SHA-256 verified. The phone stages uploads at
`cacheDir/sync_uploads/<client_message_id>/` and sweeps staging
dirs older than 24 h on each new upload; staged file names are
sanitized (basename only, path separators stripped — a malicious
client must never pick the path), falling back to
`attachment-<index>` plus the MIME extension.

`attachment_upload_error` codes: `upload_too_large` (single file
over 280 KB), `upload_bad_checksum` (SHA-256 mismatch at `final` —
the partial is deleted), `internal` (anything else; the message
stays generic, never a stack trace).

`send_message` gains one optional field; a text send is
byte-identical to Slice 3. Each entry of `attachments` is exactly
one of an upload reference (`upload_index` + `mime_type` + optional
`file_name`, `width`, `height`, `duration_ms`) or a sticker
(`sticker_emoji` — the phone renders it from the emoji, no bytes
cross at all). An entry with neither or both is a client bug and
the send fails with `message_failed` `internal`. The phone maps
the entries to MMS payloads and calls the same send router as a
text send, with the same `client_message_id` — the router decides
group MMS vs per-recipient MMS, and the 300 KB payload cap, the
group paths, and the key-on-one-row rule are all inherited,
unchanged. `message_failed` gains one code: `upload_expired` (the
staged upload is gone — the desktop re-uploads, then re-sends with
the same key). Photos are recompressed to fit the phone's budget
and GIFs walk their renditions on the desktop before upload; video
is never transcoded — a video that doesn't fit the remaining budget
is refused with the phone's own "MMS limit reached" words. The
desktop mirrors the phone's behavior and limits exactly; there are
no desktop-only send policies.

**Reply + media.** The router carries `reply_to_message_id` for SMS
only — the phone's own media sends drop the quote too. The desktop
sends the reply id if one is staged (harmless — the router ignores
it for MMS), shows the quote on the optimistic row, and accepts
that it drops on reconcile. A documented quirk that matches the
phone's data model, not a bug to fix.

**Idempotency for media sends.** The key is still the whole dedup:
`client_message_id` is stamped on the message row at insert, before
the radio dispatch — unchanged — and the upload is keyed by the
same id, so a retry finds its own bytes. The dedup check runs
BEFORE any upload validation: row exists → stored result (no
upload needed, no upload consulted); row missing → the staged
files are consulted (present → the send proceeds; absent →
`upload_expired` and the desktop re-uploads, then re-sends, same
key). The send consumes its staging (the use case *moves* the
staged files into durable storage during the send), so a completed
send leaves no staging behind to confuse a later retry — and a
retry after a completed send hits the row dedup first anyway.
Crash windows: mid-upload → partial `.part` files, nothing sent,
the retry re-uploads from scratch; after upload, before
`send_message` → staged files intact, the retry sends; after the
row insert, before dispatch → the PENDING row carries the key, the
retry returns it as `message_sent` (pending); after dispatch,
before the ack → the retry returns the stored result. Uploads are
not shared between sends — each send uploads its own bytes, even
for the same photo twice.

**Out of Slice 4:** prefetch / background attachment sync (lazy
only), server-side thumbnails, an in-app lightbox (click opens the
system viewer), in-app voice playback for AMR clips (download +
system player), video transcoding on either side, voice recording
from the desktop, cache eviction / storage management UI (wipe
paths only), upload/download resume, and sending to a new
conversation / deleting / blocking from the desktop
(scheduling from the desktop is Phase 2, below; the rest is
unchanged from Slice 3's out-list). Offline media sends follow
the Slice 3 rule: refused, not queued.

## Phase 2 — scheduled messages from the desktop (protocol version 1)

Phase 2 lets the desktop schedule a text message through the phone:
the phone stores it in its own scheduled-message store and sends it
at the requested time through its own scheduler — the desktop asks
the phone to schedule, it does not run a second scheduler. Same JSON
envelope, same `type` field, same rules. No protocol bump: the
additions are additive, and a Phase 2 desktop talking to a Slice 4
phone gets `error` for `schedule_message` — the desktop treats that
as "scheduling unavailable".

| Direction | Message | Shape |
|---|---|---|
| Client → server | `schedule_message` | `{"type":"schedule_message","client_message_id":"3f2a…c41d","conversation_id":"123","body":"Happy birthday!","send_at":1800000000000}` |
| Server → client | `message_scheduled` | `{"type":"message_scheduled","client_message_id":"3f2a…c41d","message_id":"4242","conversation_id":"123","send_at":1800000000000}` |
| Client → server | `cancel_scheduled` | `{"type":"cancel_scheduled","client_message_id":"3f2a…c41d"}` |
| Server → client | `schedule_cancelled` | `{"type":"schedule_cancelled","client_message_id":"3f2a…c41d"}` |
| Server → client | `message_failed` | `{"type":"message_failed","client_message_id":"3f2a…c41d","conversation_id":"123","code":"internal","message":"…"}` |

`schedule_message` schedules a text message in an existing
conversation for `send_at` (epoch millis) — the phone resolves the
recipients from the conversation (the desktop never sends
addresses), and the phone's own scheduler fires it: same store,
same WorkManager job, same send router as a schedule made on the
phone, including the retry/backoff cycle on a transient failure.
`message_scheduled` is the ack, matched by `client_message_id`:
`message_id` is the phone's scheduled-row id for a fresh or pending
schedule, or the sent message row's id when the key already fired
(see Idempotency), and `send_at` echoes the scheduled time. The
message itself arrives later via `message_new`, carrying
`client_message_id` so the desktop reconciles it with the scheduled
row — the key is stored on the scheduled row
(`scheduled_messages.client_message_id`, unique index, schema v17)
and stamped on the message row at fire time, exactly as for an
immediate send from the desktop. A scheduled message also appears
in the phone's own Scheduled Messages screen, like any schedule
made on the phone.

Failures reuse `message_failed` with the Slice 3 codes:
`unknown_conversation` (the conversation is gone, or is a vault
conversation the desktop must never address), `message_too_long`
(body over the 64 KB wire cap), `internal` (anything else —
including a past `send_at` or a blank body; the message stays
generic, never a stack trace). There is deliberately no
`hub_not_default_sms` refusal at schedule time: the phone's own
scheduler accepts a schedule while another app holds the default
role and only fails at fire time, so the desktop schedule mirrors
it — the failure surfaces later, on the phone, in its Scheduled
Messages screen. A refused schedule is never a protocol violation
— the socket stays up.

**Validation (server-side, all cheap).** A `schedule_message` with
a missing/blank body, a body over 64 KB, a `send_at` in the past,
an unknown or vault conversation id, or a missing
`client_message_id` is refused with `message_failed`. An
unauthenticated schedule frame is a protocol violation (the socket
closes), like an unauthenticated send.

**Idempotency.** Scheduling is a charged, real-world action in
waiting: if the desktop schedules, the phone stores it, and the ack
is lost (socket drop, phone restart), the desktop's retry — which
REUSES the same `client_message_id` — must NOT schedule the text a
second time. The dedup check runs BEFORE anything is created, under
the same mutex that serializes sends: a scheduled row already
carrying the key answers with its id (and its stored time — a retry
asking a different time does not move the schedule), and a key
whose message already fired is covered by the Slice 3 row dedup
(same key on the sent row), answering with the sent row's id. The
key lives on the scheduled row, so the dedup survives a phone
restart; the phone's own scheduler is what fires it, so a restart
between scheduling and fire changes nothing.

**Cancel.** `cancel_scheduled` cancels the scheduled message keyed
by `client_message_id` — without it, cancelling on the desktop
would only delete the local record while the phone still sent at
the chosen time. The phone cancels through its own delete path
(cancel the scheduled work + delete the row, the same path its own
Scheduled Messages screen deletes with) and answers
`schedule_cancelled`. The cancel is IDEMPOTENT: an unknown key
(already cancelled, deleted, or never scheduled) and a key whose
message already fired both ack as cancelled — the schedule is gone
either way, and a fired message is already reconciled on the
desktop via `message_new`. Only a genuine internal failure answers
`message_failed`, with an empty `conversation_id` (a cancel carries
no conversation — the desktop matches a cancel failure by
`client_message_id`). After a cancel the key is free: the row is
gone, so a later `schedule_message` with the same key creates a
fresh schedule — no tombstone blocks it. (The desktop generates a
fresh UUID per new schedule anyway, so reusing a cancelled key is
an edge case, but the phone does not forbid it.) An unauthenticated
cancel frame is a protocol violation (the socket closes), like an
unauthenticated schedule.

**Out of Phase 2:** scheduling a message with attachments (text
only — the phone's own scheduler supports MMS, but the desktop
schedule frame carries no attachments yet), editing a scheduled
message from the desktop, listing scheduled messages on the
desktop, and sending to a new conversation. Offline schedules
follow the Slice 3 rule: refused, not queued.

## Slice 5 — contact sync (protocol version 1)

Slice 5 gives the desktop the phone's contacts — display names +
phone numbers, synced over the existing encrypted LAN channel —
so group sender names, the detail pane's member list, and the
thread header's group subtitle show names instead of raw numbers,
and contact photos (avatars) show in the conversation list (1:1
rows), the thread header, and the detail pane. It also makes
`isContact` real: the desktop derives it from the synced
directory, so the stranger-only classification rules finally work
(the Slice 3 cold-pitch fix had to assume every sender is a
stranger because the desktop never knew contact state). Same JSON
envelope, same `type` field, same rules. No protocol bump: every
addition is additive. A Slice 4 desktop talking to a Slice 5
phone receives `contacts` frames it does not know and ignores
them; a Slice 5 desktop talking to a Slice 4 phone sends
`contacts_request` and gets `error` — the engine treats that as
"contacts unavailable" and keeps raw addresses + placeholders
(mixed-version behavior, documented, not an error state).

| Direction | Message | Shape |
|---|---|---|
| Client → server | `contacts_request` | `{"type":"contacts_request"}` |
| Client → server | `avatar_request` | `{"type":"avatar_request","address":"+15551234567"}` |
| Server → client | `contacts` | `{"type":"contacts","contacts":[CONTACT, "…"],"has_more":false}` |
| Server → client | `contact_updated` | `{"type":"contact_updated","contact":CONTACT,"removed_phones":["+15550001111"]}` |
| Server → client | `contact_deleted` | `{"type":"contact_deleted","phones":["+15551234567"]}` |
| Server → client | `contacts_error` | `{"type":"contacts_error","code":"hub_no_contacts_access","message":"…"}` |
| Server → client | `sync_started` (extended) | `{"type":"sync_started","mode":"full","total_conversations":12,"total_messages":3401,"cursor_message_id":"9876","hub_is_default_sms":true,"total_contacts":214}` |

**CONTACT.** `{"display_name":"Marie","phones":["+15551234567"],
"has_photo":true,"photo_updated_at":1794670000000}` — the name as
the phone shows it; the numbers, normalized with the phone's own
`normalizePhone` (the normalized form is the join key on both
sides — raw forms never cross); whether a photo exists; and the
photo-staleness version (`CONTACT_LAST_UPDATED_TIMESTAMP`),
present only when `has_photo` is true, else null. What does NOT
sync: emails, notes, groups/labels, birthdays, postal addresses,
custom ringtones, websites — and raw contact ids (they are
reassigned when contacts merge, split, or are restored from a
backup, so they are not stable identity; the phone uses them
internally only, to diff its own snapshots). The wire identity of
a contact is its set of normalized phone numbers. Contacts with
no phone number are excluded (they cannot send or receive texts),
as is the phone's own profile. A multi-number contact is one
CONTACT frame carrying all its numbers; the desktop stores one
row per number. Two contacts sharing one number resolve
last-write-wins in snapshot order (display-name order, the order
the phone's provider returns).

**Snapshot.** The phone streams the full contact list in batches
of 100, right after `sync_started` and before the conversation
snapshot — in BOTH sync modes (the mode choice is about message
history; a few hundred contacts cost nothing either way). The
desktop upserts each batch and, on `has_more: false`, prunes
local numbers absent from the snapshot — deletions included, no
tombstone feed needed. `sync_started` gains one optional field,
`total_contacts` (the snapshot size, for progress honesty); the
phone reads the snapshot once, before `sync_started`, so the
count and the stream never disagree. The snapshot is also the
safety net for the delta path: any missed change self-heals at
the next connect, the same way the message gap-fill heals a
missed `message_new`.

**Live delta.** The phone watches the contacts provider (a
ContentObserver, registered at startup) and pushes
`contact_updated` / `contact_deleted` to every authenticated
session the moment a contact changes — a rename on the phone
shows on the desktop within seconds, without reconnecting.
Provider changes fire in bursts during an account sync/import, so
the phone debounces 2 s before re-reading and diffing (by
CONTACT_ID, internal only). A `contact_updated` carries
`removed_phones` only when the contact lost numbers, so the
desktop deletes those rows instead of letting them linger until
the next full snapshot; a `contact_deleted` carries the contact's
last-known numbers. The server's 30 s watchdog tick also
re-checks contacts — a missed observer notification converges
within 30 s. A session that has not synced contacts yet harmlessly
upserts the delta rows; its `sync_request` snapshot + prune
reconciles everything anyway, so no per-session tracking is
needed.

**Avatars.** A separate `avatar_request` type (not
`attachment_request` with a namespaced id — avatars have no
message, so there is no `message_id` for the membership guard,
and sending a fake one would be a lie). The response reuses the
Slice 4 frames: `attachment_chunk` ×N under the
`avatar:<normalized-number>` id, or `attachment_error`. The phone
serves the THUMBNAIL (what it displays itself — plenty sharp at
48–80 px, one 64 KiB chunk in the common case), resolved with the
same `PhoneLookup` the UI uses, so the desktop's avatar for a
number is the contact the phone would show for it. No contact, no
photo, or an unreadable stream all answer `unknown_attachment` —
one code for all three, and the paired desktop already knows
`has_photo` from the CONTACT payload, so the error reveals
nothing new. The Slice 4 25 MB download cap applies to the shared
chunk sender — an over-cap avatar fails with
`attachment_too_large` and degrades to initials. Avatars download
lazily, on render — the same rule as attachments: a 500-contact
address book never prefetches 500 photos. A `contact_updated`
with a newer `photo_updated_at` (any contact edit bumps the
timestamp, so this is coarse — a name edit also re-downloads the
photo) deletes the cached file and the next render re-requests;
a `contact_updated` with `has_photo` flipped false, a
`contact_deleted`, or a snapshot prune deletes the cached file.
No orphaned photos for deleted contacts.

**Failure isolation.** Contacts failing must NEVER fail message
sync. The contact readiness check is separate from the SMS one
(which stays SMS-only), and a contacts failure surfaces as
`contacts_error`, not `sync_error`: message sync proceeds
regardless, and the desktop shows the reason in Settings →
Linked devices with a Retry (which sends `contacts_request`) —
it does not touch the SyncDialog state. `contacts_error` codes:
`hub_no_contacts_access` (READ_CONTACTS missing on the phone),
`internal` (anything else; the message stays generic, never a
stack trace). Without the permission, the desktop keeps raw
addresses and placeholder avatars — which looks like a sync bug
if you do not know, so the manual grant (Settings → Apps → Relay
→ Permissions → Contacts → Allow) is called out wherever the
debug app is set up.

**Matching.** The desktop ports the phone's `normalizePhone`
exactly and uses it for every lookup. Same number, different
formats (`+15551234567` vs `5551234567`): after an exact
normalized match misses, the desktop retries with the 10-digit
suffix — but only for NANP-shaped numbers (exactly 10 digits, or
11 digits starting with `1`). Within NANP the 10 digits ARE the
whole number, so the fallback cannot confuse two different
people; international numbers never take this path (exact match
only). Short codes and alphanumeric senders never match a contact
row and show raw, as today. The phone's conversation title stays
authoritative for the list and the thread header — the desktop
does NOT recompute 1:1 titles from its contact list (the phone's
`PhoneLookup` matching is more lenient than the desktop's join,
so recomputing could show a raw number where the phone shows a
name). The desktop uses contact names exactly where the phone
sends only raw addresses: group sender names, the detail pane
members, the thread header group subtitle.

**Privacy.** Contacts cross only the existing encrypted LAN
channel (TLS 1.2+ with the TOFU-pinned certificate, plus the
Ed25519 device authentication) — the same channel as messages, no
new endpoint, no new network dependency, no cloud, no Google
service. A device that is not paired cannot ask; an unpaired
socket gets nothing (the `withEngine` auth gate applies to
`contacts_request` / `avatar_request` exactly as to
`sync_request`). At rest on the desktop, `relay.db` and the avatar
files are plain files — no encryption at rest, exactly like the
message cache Slice 2 already stores (the protection is the OS
user account, same as the messages). Revoking the desktop stops
all future sync immediately but does not wipe the desktop's cache
— same as messages. Debug logging must never print contact names
or numbers: the engine and the server log counts only. Nothing in
this slice phones home, logs contact data, or adds an analytics
dependency.

**Out of Slice 5:** editing or creating contacts from the
desktop; group creation; calling; contact groups/labels; emails,
notes, birthdays, postal addresses; the phone's own profile;
per-conversation custom avatars (the phone's `avatarUri` stays
phone-only, like bubble color — appearance, not contact data); a
contacts-list UI (the directory exists to resolve names and
photos in the messaging UI, not as a destination of its own).

## Create conversation from the desktop (protocol version 1)

The desktop creates a conversation — 1:1 or group — through the
phone, the same way the phone's own composer does: the thread is
created immediately, with no message needed, and a group name
typed on the desktop is applied to it. Same JSON envelope, same
`type` field, same rules. No protocol bump: every addition is
additive.

| Direction | Message | Shape |
|---|---|---|
| Client → server | `create_conversation` | `{"type":"create_conversation","addresses":["+15551234567","+15550001111"],"custom_name":"Family"}` |
| Server → client | `conversation_created` | `{"type":"conversation_created","conversation":CONVERSATION}` |
| Server → client | `action_result` (failure) | `{"type":"action_result","action":"create_conversation","ok":false,"code":"invalid_request","message":"…"}` |

`create_conversation` carries `addresses` (a non-empty array of
normalized number strings — one number is a 1:1 conversation,
several is a group) and an optional `custom_name` (absent or
blank means an unnamed group, which shows member names, as on
the phone). There is NO `client_message_id`: the phone's
`findOrCreate` is idempotent for the same member set — sending
the same request twice returns the existing conversation, never
a duplicate thread — so a retry after a lost ack needs no dedup
key.

`conversation_created` is the ack: `conversation` is the full
CONVERSATION row, the exact same shape as one entry in the
`conversations` batches, with the real message count — so the
desktop opens an already-existing group with its history, not
an empty thread. If a conversation with the same members
already exists, it is returned in the same frame. The live
conversation push (`conversation_updated`) also fires for the
new row, as for any list change; the desktop upserts by id, so
the duplicate is harmless.

A failure answers `action_result` with `action:
"create_conversation"` and `ok: false` — with NO
`conversation_id` (none exists yet). Codes reuse the
`message_failed` set: `invalid_request` (the address list was
empty, or every entry blank — the desktop shows the message and
lets the user pick again) and `internal` (anything else; the
message stays generic, never a stack trace). A refused create
is never a protocol violation — the socket stays up.

## Contact photo from the desktop (round 3, protocol version 1)

The desktop sets a contact's photo — the camera badge on the
detail pane's avatar — through the phone: the user picks an
image, the desktop auto-crops it to a centered 512 px square
JPEG, applies it locally at once, and sends it to the phone,
which writes it to the contact's PHOTO row. Same JSON envelope,
same `type` field, same rules. No protocol bump: every addition
is additive.

| Direction | Message | Shape |
|---|---|---|
| Client → server | `set_contact_photo` | `{"type":"set_contact_photo","address":"+15551234567","photo_base64url":"/9j/4AAQ…"}` |
| Server → client | `action_result` | `{"type":"action_result","action":"set_contact_photo","address":"+15551234567","ok":true}` |

`set_contact_photo` carries `address` (the normalized number,
exactly as it appears in the CONTACT payload — the phone resolves
it with the same `PhoneLookup` the UI uses) and
`photo_base64url` (the prepared 512 px JPEG as base64url, the
protocol's byte alphabet, like the chunk frames), in ONE frame —
a 512 px JPEG is small (tens of KB), so the Slice 4 chunking is
not needed. The phone caps the decoded photo at 512 KB
(`MAX_CONTACT_PHOTO_BYTES`).

The answer reuses `action_result`, with `action:
"set_contact_photo"` and the `address` echoed, so the desktop
attributes the reply to exactly one in-flight pick — and unlike
a write-back, a refusal does NOT revert the desktop's local
choice: the picked photo stays applied and the detail pane only
shows the reason. Codes reuse the `message_failed` set:
`unknown_contact` (no contact exists for the number — the phone
never creates a contact for a photo set),
`hub_no_contacts_write` (the phone lacks WRITE_CONTACTS — the
user grants it from the Linked devices screen, or the phone's
app settings, and picks again), `photo_too_large` (over the cap),
`invalid_request` (undecodable or empty bytes), and `internal`
(anything else; the message stays generic, never a stack trace).
A refused photo set is never a protocol violation — the socket
stays up.

The write bumps the contact's `CONTACT_LAST_UPDATED_TIMESTAMP`,
so the next contact snapshot carries the new photo version and
the desktop's local choice converges to the synced photo (the
same image, no visible change). Until then the desktop keeps its
local photo across re-syncs: the directory only replaces it when
the phone's version moves. There is no remove/reset option in
this round, and no other contact editing from the desktop — the
Slice 5 scope note still stands.

## Live calls (protocol version 1)

The phone forwards its cellular call state to every linked
device, and a linked device can place a cellular call on the
phone. Signaling + dial only: the audio itself travels over
Bluetooth HFP on the desktop side, outside this protocol. Same
JSON envelope, same `type` field, same rules. No protocol bump:
every addition is additive.

| Direction | Message | Shape |
|---|---|---|
| Server → client | `incoming_call` | `{"type":"incoming_call","number":"+15551234567","timestamp":1794000000000}` |
| Server → client | `call_state` | `{"type":"call_state","state":"ringing","number":"+15551234567"}` |
| Client → server | `dial_request` | `{"type":"dial_request","number":"+15551234567"}` |
| Server → client | `dial_result` | `{"type":"dial_result","ok":true}` |

`incoming_call` is sent when the phone starts ringing, together
with a `call_state` `ringing` for the same transition — the
announcement carries the `timestamp` (epoch millis, when the
ring was observed), the state frame carries the state.
`call_state` is sent on EVERY transition (`ringing`, `dialing`,
`active`, `ended`), broadcast to every authenticated session.
`dialing` is an outgoing call going off-hook — the other person
has not answered yet, and Android reports no answer moment for a
dialed call (off-hook covers dialing and talking alike), so a
desktop with Bluetooth learns the answer from HFP, and one
without treats the dial as connected. `active` is an incoming
call answered. The number
is `""` when unknown: a withheld number, an outgoing call dialed
on the phone (the platform only reports a number while ringing),
or a phone without READ_CALL_LOG — since Android 9 the platform
passes an empty number to call-state listeners that lack it, so
a linked device still sees that the phone is ringing, just not
who is calling. The phone never reads the call log itself; the
permission only unlocks the number in the live call-state
callback.

`dial_request` places a cellular call on the phone (ACTION_CALL
+ CALL_PHONE) and is answered with `dial_result` — `ok: true`
when the call was placed, `ok: false` with a short snake_case
`error` otherwise: `permission_denied` (CALL_PHONE is not
granted on the phone — the user grants it from the Linked
devices screen), `no_number` (the request carried no usable
number), `failed` (anything else: no dialer installed, or the
OS refused). A refused dial is never a protocol violation — the
socket stays up. The resulting off-hook transition also arrives
as `call_state` `dialing`, so the desktop sees its own dial
land. A `dial_request` before auth is a protocol violation,
like any other post-auth frame.

Listening needs READ_PHONE_STATE (requested at cold start, as
a critical SMS permission — the observer reuses that granted
state); without it the observer stays off and no call frames
are sent. The observer runs while the server runs — one
listener for the whole server, not one per session — and stops
with it.

Android clients consume the same four frames: a linked tablet
shows a small incoming-call dropdown on `incoming_call` /
`call_state` `ringing` (avatar + name from its synced contacts,
one chat button that opens the conversation with that number),
dismisses it on `call_state` `ended` (or `active`, answered
elsewhere), and dials from a 1:1 thread with `dial_request`.
No answer/hangup frames exist: a linked Android device cannot
answer or end a call, and call audio stays on the phone.

## Category mutes (protocol version 1)

The phone syncs its muted categories — the tabs whose messages
do not notify — to every linked device, and a linked device can
mute or unmute a category on the phone. The phone is the source
of truth: the desktop replaces its own muted list with the
phone's set wholesale, and a toggle on the desktop writes
through to the phone. Same JSON envelope, same `type` field,
same rules. No protocol bump: every addition is additive.

| Direction | Message | Shape |
|---|---|---|
| Server → client | `category_mutes` | `{"type":"category_mutes","muted_categories":["otp","promotional"]}` |
| Client → server | `set_category_muted` | `{"type":"set_category_muted","category":"otp","muted":true}` |

`category_mutes` carries the FULL set of muted categories as
lowercase wire strings — `"personal"`, `"otp"`, `"promotional"`,
`"transactions"`, `"other"` (there is no "all": All is the
unfiltered view, not a category). The phone sends it after every
snapshot — immediately after `sync_complete` — and whenever the
set changes on the phone (a toggle in the tab row or in
Settings → Notifications, or a `set_category_muted` from any
linked device). The list is sorted, so the frame is stable; an
empty list means nothing is muted. A linked device applies the
frame whenever it arrives: it replaces its local muted list,
never merges. (If the phone cannot read its settings for one
sync, the frame is skipped for that sync — an empty list would
wrongly unmute everything — and the next change sends the set.)

`set_category_muted` mutes or unmutes one category. `muted` is
REQUIRED, like `set_muted`'s `value`: a missing flag is
malformed, never a silent unmute. An unknown category string is
refused with `action_result` `ok: false`, code
`invalid_request` — the phone applies nothing, and the desktop
reverts its optimistic change. A successful apply is answered
with `action_result` (`action: "set_category_muted"`, the
`category` echoed so the desktop attributes the reply to the
category it changed), and the updated set then broadcasts back
to every session via `category_mutes` — the desktop's
confirmation is the broadcast, not the ack. A
`set_category_muted` before auth is a protocol violation, like
any other post-auth frame.

Android clients ignore `category_mutes` for now (an unknown
server frame is dropped, never an error) — applying the hub's
set on a linked tablet is a later round.

## E2E safety numbers + notices (round 42, protocol version 1)

The phone syncs its end-to-end encryption state — the safety
number and verified flag of every peer, plus the in-thread
encryption notices — to every linked device, and a linked device
can mark a peer verified or retry the key exchange on the phone.
The phone is the source of truth: the desktop replaces its own
sets with the phone's wholesale, and a verified toggle or probe
retry on the desktop writes through to the phone. Same JSON
envelope, same `type` field, same rules. No protocol bump: every
addition is additive.

| Direction | Message | Shape |
|---|---|---|
| Server → client | `e2e_safety_numbers` | `{"type":"e2e_safety_numbers","peers":[{"address":"+33612345678","state":"encrypted","safety_number":"12345 67890 …","verified":false}]}` |
| Server → client | `e2e_notices` | `{"type":"e2e_notices","notices":[{"id":"12","conversation_id":"123","kind":"activated","date":1793000000000},{"id":"13","conversation_id":"124","kind":"group_key_changed","subject":"+33612345678","date":1793000001000}]}` |
| Client → server | `set_e2e_verified` | `{"type":"set_e2e_verified","address":"+33612345678","verified":true}` |
| Client → server | `retry_e2e_probe` | `{"type":"retry_e2e_probe","address":"+33612345678"}` |

`e2e_safety_numbers` carries the FULL set of E2E peers — every
address with a peer row — as `peers` entries: `address` (the
normalized number), `state` (the lowercase peer state:
`"encrypted"`, `"probing"`, `"unknown"`, `"not_relay"`),
`safety_number` (the formatted 60-digit number — 12 groups of 5
digits — or null when the peer has no keys yet), and `verified`.
The phone sends it after every snapshot — immediately after
`sync_complete`, next to `category_mutes` — and whenever any
peer row changes on the phone (a state flip, a key arrival, a
verified toggle from any linked device). A linked device applies
the frame whenever it arrives: it replaces its local set, never
merges. The safety number is derived in exactly one place on the
phone (the same derivation the verification sheet uses) and
crosses the link as the formatted string — the desktop stores
and displays it verbatim, never re-deriving, so the number it
shows is byte-identical to the phone's. (If the phone cannot
read its E2E store for one sync, the frame is skipped for that
sync — an empty list would wrongly clear the desktop's state —
and the next change sends the set.) Sync never creates an
identity: our keys are only loaded when at least one peer holds
keys and an account already exists, so a user who never uses E2E
pays nothing.

`e2e_notices` carries the FULL set of in-thread E2E notices as
`notices` entries: `id` and `conversation_id` (decimal strings
of the phone's Room ids, per the protocol v1 convention), `kind`
(the lowercase notice kind: `"activated"`, `"key_changed"`,
`"key_changed_verified"`, `"deactivated"` — **round 49 adds the
group kinds `"group_activated"`, `"group_deactivated"`,
`"group_key_changed"`, `"group_key_changed_verified"`**), and
`date` (epoch millis — the notice's position in the thread).
**Round 49 adds the optional `subject` field**: the canonical
address of the member the notice is about (the deactivation /
key-change notices name their member; the activation notices
carry no subject). A frame from an older phone build omits it,
and the client renders the notice without a name. The phone
sends it after every snapshot and whenever any notice row changes;
the desktop replaces its local set wholesale and renders each
notice as a centered system row in its thread. The notice kinds
carry no wording — the wording lives in each device's own
strings, so a notice renders in the user's current language.

`set_e2e_verified` marks (or unmarks) one peer verified on the
phone. `verified` is REQUIRED, like `set_muted`'s `value`: a
missing flag is malformed, never a silent unverify. The phone
updates its own store — creating the peer row when none exists
yet, like its own verification sheet does — and answers with
`action_result` (`action: "set_e2e_verified"`, the `address`
echoed so the desktop attributes the reply to the peer it acted
on); the updated set then broadcasts back to every session via
`e2e_safety_numbers` — the desktop's confirmation is the
broadcast, not the ack. Verification is optional (TOFU):
messaging works encrypted whether or not a peer is verified —
the flag is just a marker the user sets after comparing safety
numbers out of band. The flag is never sent to the peer; it
syncs only to the user's own linked devices over this encrypted
device link.

`retry_e2e_probe` runs the manual probe for one peer — the same
one-probe-now path as the phone's Details panel "Retry
encryption" (the budget and discovery-toggle gates are lifted;
the address, blocked, and ENCRYPTED gates still apply). The
phone answers with `action_result` (`action:
"retry_e2e_probe"`, the `address` echoed): `ok: false`
`unknown_conversation` when no 1:1 conversation exists for the
address, `send_failed` when the probe could not go out. A
`set_e2e_verified` / `retry_e2e_probe` before auth is a protocol
violation, like any other post-auth frame.

Android clients ignore `e2e_safety_numbers` and `e2e_notices`
for now (an unknown server frame is dropped, never an error) —
applying the hub's E2E state on a linked tablet is a later
round.

## Notes sync (round 36 phase 2, protocol version 1)

Notes sync to every linked device (desktop + tablets) over the
same device link. The phone is the source of truth: a linked
device mirrors the phone's notes and writes through it. Same
JSON envelope, same `type` field, same rules. No protocol bump:
every addition is additive. A note's bubble color does NOT sync
(each device keeps its own).

Wire shapes (per `notes-feature-design.md` §4.1):

- `SyncedNote`: `id`, `title`, `created_at`, `updated_at`,
  `last_message_at?`, `last_message_preview?`, `pinned`,
  `archived`, `message_count` (the desktop uses the count; the
  mobile mirror drops it — the list shows the preview only).
- `SyncedNoteMessage`: `id`, `note_id`, `body`, `date`,
  `attachments` (a list of `SyncedAttachment`, as in
  `messages_batch`), `client_message_id?`, and `edited`
  (round 36 phase 2 — the "Edited" marker; older clients ignore
  the unknown field, new clients reading old frames get `false`).

| Direction | Message | Shape |
|---|---|---|
| Server → client | `notes` | `{"type":"notes","notes":[…],"has_more":false}` |
| Server → client | `note_messages_batch` | `{"type":"note_messages_batch","note_id":"7","messages":[…],"has_more":false,"next_before_id":"42"}` |
| Server → client | `note_created` | `{"type":"note_created","note":{…}}` |
| Server → client | `note_new` | `{"type":"note_new","note":{…}}` |
| Server → client | `note_updated` | `{"type":"note_updated","note":{…}}` |
| Server → client | `note_deleted` | `{"type":"note_deleted","note_id":"7"}` |
| Server → client | `note_message_new` | `{"type":"note_message_new","message":{…}}` |
| Server → client | `note_message_deleted` | `{"type":"note_message_deleted","note_id":"7","message_id":"42"}` |
| Server → client | `note_message_updated` | `{"type":"note_message_updated","message":{…}}` |
| Server → client | `note_message_saved` | `{"type":"note_message_saved","client_message_id":"…","message_id":"42","note_id":"7"}` |
| Server → client | `note_message_failed` | `{"type":"note_message_failed","client_message_id":"…","note_id":"7","code":"unknown_note","message":"…"}` |
| Client → server | `create_note` | `{"type":"create_note","client_note_id":"…","title":"Shopping"}` |
| Client → server | `save_note_message` | `{"type":"save_note_message","client_message_id":"…","note_id":"7","body":"…","attachments":[…]}` |
| Client → server | `edit_note_message` | `{"type":"edit_note_message","note_id":"7","message_id":"42","body":"…"}` |
| Client → server | `rename_note` | `{"type":"rename_note","note_id":"7","title":"…"}` |
| Client → server | `delete_note` | `{"type":"delete_note","note_id":"7"}` |
| Client → server | `delete_note_message` | `{"type":"delete_note_message","note_id":"7","message_id":"42"}` |
| Client → server | `set_note_pin` | `{"type":"set_note_pin","note_id":"7","value":true}` |
| Client → server | `set_note_archived` | `{"type":"set_note_archived","note_id":"7","value":true}` |
| Client → server | `note_messages_request` | `{"type":"note_messages_request","note_id":"7","before_id":"42","limit":200}` |

### Stream order + cursors

The notes phase runs inside `runStream` after conversations and
before `messages_batch`: the note snapshot in 100-note batches
(`notes`), then — in FULL mode — the per-note entry pages
(`note_messages_batch`, 200 per page, newest first). `sync_request`
gains `since_note_message_id` (the note cursor, same rule as the
message cursor: absent on a first sync); `sync_started` and
`sync_complete` gain `cursor_note_message_id` (+ `total_notes` on
`sync_started`). FULL mode with a cursor above 0 streams only the
entries above it, per note (the delta — same shape as the SMS
delta); FULL mode with cursor 0 streams every entry; NEW_ONLY
streams the list only, no entries (live pushes cover new ones).
The note snapshot always streams in full, delta runs included,
so the client prunes mirrored notes absent from it (there is no
tombstone feed). The cursor is the max note message id the client
durably stored — never the server's cursor. Live pushes dedup by
upsert-by-id.

### Writes

`create_note` carries a client UUID dedup key (`client_note_id`):
the hub stores it on the row, so a retried create lands on the
same note instead of duplicating it. Success answers
`note_created` (full row, requesting session only); the other
sessions learn about it via `note_new`. A refusal answers
`action_result` (`action: "create_note"`, `ok: false`).

`save_note_message` carries a client UUID dedup key
(`client_message_id`) and reuses the `SendAttachment` shape for
its `attachments` array (upload references, as in
`send_message`). Success answers `note_message_saved`; failure
answers `note_message_failed` with one of `unknown_note`,
`unknown_message`, `message_too_long`, `upload_expired`,
`internal` (the `message_failed` codes, reused). `upload_expired`
means the staged upload is gone — the client re-uploads and
re-sends once, automatically (the desktop rule).

`edit_note_message` carries `{note_id, message_id, body}` — no
client key (edits are idempotent). The hub answers
`action_result` on success AND on failure (like every other
write-back action), and ALSO broadcasts `note_message_updated`
(the full edited row, `edited: true`) to ALL sessions, including
the requester. Either success signal confirms the edit. Failure
codes: `unknown_note`, `unknown_message`, `message_too_long`,
`internal`. Editing is text-only entries only.

`rename_note`, `delete_note`, `delete_note_message`,
`set_note_pin`, `set_note_archived` are answered by
`action_result` (the hub note id echoed in the `conversation_id`
slot, the hub entry id in `message_id`). `note_messages_request`
fetches one page of one note's entries (the server caps `limit`
at 200); the answer is a `note_messages_batch`.

### Note attachments

Attachment bytes reuse the chunk protocol (Slice 4), with two
differences:

- **Namespaced ids.** Hub note-attachment ids and MMS attachment
  ids are independent autoincrement sequences, so a bare id is
  ambiguous across the two tables. A note download request
  carries `attachment_id: "note:<hub id>"` and `message_id:
  "note:<hub entry id>"` (the `avatar:<number>` convention);
  chunk frames echo the prefixed id, so the client routes them
  back to the note download. Note frames themselves (`notes`,
  `note_messages_batch`, …) always carry RAW ids — the prefix
  applies only to the download request + its chunks.
- **Upload cap.** The MMS upload cap is the 280 KB carrier
  payload, which would break note photos (notes store originals).
  A note upload marks its `attachment_upload_start` with
  `kind: "note"`, which lifts the cap to 25 MB (the same value as
  the download cap and the note thread's staging cap). An upload
  without `kind` keeps the MMS cap.

Note attachment bytes cross the link as PLAINTEXT — decrypted
from the hub's AES-256-GCM envelope, re-encrypted by the
receiving device into its own envelope. Plaintext exists only in
transit inside the TLS device link; note text stays SQLCipher
and attachments stay envelope-encrypted at rest on both ends.

Desktop note: the `SYNC_MESSAGE_TYPES` allowlist in
`channel.rs` must include every new server→client type above
(`notes`, `note_messages_batch`, `note_created`, `note_new`,
`note_updated`, `note_deleted`, `note_message_new`,
`note_message_deleted`, `note_message_updated`,
`note_message_saved`, `note_message_failed`) — a missing type is
silently dropped.

## VPN / tunnel (protocol version 1)

When the user runs a VPN tunnel on the phone (WireGuard to their
home router, for example), a linked device that dials the phone's
LAN address never connects: the phone's replies route via the
tunnel with the tunnel address as their source, so the desktop's
TCP stack drops the SYN-ACK (it expects an answer from the LAN
address it dialed). The fix is to dial the phone's tunnel address
instead — the reply then carries the address the desktop dialed,
and the connection establishes. The phone is the server either
way (it listens on 0.0.0.0:47831, tunnel interface included); only
the address the desktop dials changes. No protocol bump: every
addition is additive, and old clients ignore the new fields.

The phone learns its tunnel address from `ConnectivityManager`
(`VpnAddressProvider`): the IPv4 address of the VPN network that
IS the phone's active (default) network — first non-loopback,
non-link-local IPv4 from its link addresses, with a `tun`/`wg`
interface-name fallback if the `ConnectivityManager` read fails.
IPv4 only. Absent (null) means "no tunnel path right now" —
tunnel down, tunnel not the default route, or IPv6-only tunnel —
and the feature degrades to LAN-only. The tunnel IP is never
shown in the phone's UI: it is learned and used internally only.

| Direction | Message | Shape |
|---|---|---|
| mDNS TXT | (advertisement) | `tip=10.6.0.2` (present only while a tunnel is up) |
| Server → client | `sync_started` (extended) | `{"type":"sync_started","mode":"full","total_conversations":87,"total_messages":12340,"cursor_message_id":"98765","hub_is_default_sms":true,"hub_tunnel_ip":"10.6.0.2"}` |
| Server → client | `hub_status` (extended) | `{"type":"hub_status","is_default_sms":true,"tunnel_ip":"10.6.0.2"}` |

Three channels carry the address, each with a distinct role:

- **mDNS TXT `tip`** (primary, at home): the phone advertises its
  tunnel address in the discovery record, so a scan returns it
  alongside the LAN address. NSD TXT records are fixed at
  registration, so a tunnel change re-registers the service
  (`MdnsAdvertiser.restart`) — a sub-second window in which the
  phone is not advertised.
- **`sync_started.hub_tunnel_ip`**: the desktop learns the current
  tunnel address on every sync start, i.e. on every connect.
  Built in `SyncEngine` from the `SyncMessageSource.tunnelIp()`
  seam.
- **`hub_status.tunnel_ip`**: pushed on change mid-session, exactly
  like the default-SMS flag — so a desktop connected on the LAN
  learns the tunnel address when the tunnel comes up, and can
  reach the phone later when remote.

Change detection mirrors the default-SMS pattern: a
`ConnectivityManager.NetworkCallback` filtered to `TRANSPORT_VPN`,
registered in `SyncService` while the server runs, fires on tunnel
up/down and calls `SyncServer.refreshTunnelAdvertisement()`; the
30 s watchdog tick calls it too, as a safety net. On change only,
the server re-advertises mDNS and pushes `hub_status` with the new
value. Absent on the wire means "no tunnel right now"; the desktop
does NOT delete its stored tunnel address on absent (the tunnel IP
is static per tunnel config — the tunnel may come back with the
same address).

The tunnel address is an RFC1918 address: useless without the
tunnel keys, the router's forwarding, and Relay's own pairing +
Ed25519 auth. It is visible to anyone on the LAN the phone is on
(the TXT record) — it reveals that a VPN exists and its subnet,
nothing more. The address never reaches the log (only whether one
is present). Needs `ACCESS_NETWORK_STATE` (normal-level, granted
at install) — declared explicitly in the manifest, reviewed in
PERMISSIONS.md.

Router requirement (setup, not Relay): the home router must
forward packets between the LAN subnet and the tunnel subnet, in
both directions — otherwise the tunnel dial fails the same way the
LAN dial fails today. The check, from the desktop: `ping
<tunnel-ip>` (the address is in the tunnel app, since Relay never
shows it). If it answers, the path works; if not, the router is
not forwarding.

Dial order is the same on the desktop and on an Android client
(tablet): fresh discovery dials the tunnel first iff the TXT
record carries a `tip`, else LAN only; with no fresh info the
last-known store dials last-working first, then the other known
address. Attempts are sequential, never a race (one device, one
session), 8 s per attempt. A hub refusal (`unknown_device`,
`bad_signature`, `revoked`, `protocol`, a hub error during auth,
or a changed TLS certificate) stops the walk — the phone answered,
so another address would refuse identically; a path failure
(timeout, refused, dropped) tries the next candidate. When every
candidate fails, the error names each attempted address with its
kind and outcome, plus the router hint (ping the tunnel IP) and
the per-app exclusion workaround, failure-only. The reconnect loop
re-scans mDNS after 2 consecutive failed rounds and switches to a
fresh target for the same hub id — a new address, or a tunnel
address that appeared at the same address. No VPN UI on either
client: the status just says Connected, and the winning kind stays
internal (logs + the last-working record).

## Key storage (phone)

Linked-device records in the app's store: device id, kind, display name,
public key (base64url), linked time, last seen. TLS certificate and key in
app-private storage / the AndroidKeyStore.

## Slice 1 scope boundary

Slice 1 carried no message content, contacts, or notifications — Slice
2 (above) adds the sync message types in the same envelope, Slice 3
adds sending from the desktop plus desktop→phone write-back (marking
read on the phone, pin/archive/mute, reactions, pinned message),
Slice 4 adds attachment bytes (download + upload + media sends),
Phase 2 adds scheduled messages from the desktop, and Slice 5 adds
contact sync (names + avatars, snapshot + live delta), and create
conversation adds starting a conversation from the desktop, and round 3
adds setting a contact's photo from the desktop, and live calls add
call signaling (incoming-call + call-state frames) and dialing from
the desktop, all in the same envelope; the protocol version stays 1
until a breaking change needs it. Still out of scope: deleting from
the desktop.

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
   dependency. The screen also owns the runtime permission prompts
   the sync layer cannot show itself (WRITE_CONTACTS for contact
   photos; READ_CALL_LOG + CALL_PHONE for live calls) — the
   background service has no Activity to ask from.
4. **The message-store seam** (`sync/SyncMessageSource.kt`):
   the interface the sync engine calls, plus the wire DTOs
   (`SyncedConversation` / `SyncedMessage` / `SyncedAttachment` /
   `SyncedContact`). The implementation
   (`system/sync/RepositorySyncMessageSource.kt`) lives outside the
   sync package — it is the one file that knows both the wire DTOs
   and the app's repositories — and is bound in Hilt
   (`di/RepositoryModule.kt`) and injected into `SyncServer`, which
   hands it to one `SyncEngine` per session. The sync package
   still imports no app code; a future port re-implements this one
   interface. (Built in Slice 2; listed as "future glue" in
   Slice 1. Slice 5 extends the seam with the contact surface —
   `contacts()` / `openAvatar()` / `contactsReadiness()` — and
   `MainApplication` registers the contacts ContentObserver, which
   lives outside the sync package for exactly this reason.)
5. **The live-calls Hilt bindings** (`di/SyncCallsModule.kt`): the
   observer and dialer implementations live inside the sync package
   (`sync/calls/`, Android-framework imports only, no app code) and
   are bound to their interfaces here, the same shape as the
   wakelock binding — so a future port re-binds two interfaces and
   lifts the package as-is.

A future port re-wires those five points and lifts the `sync` package
as-is.
