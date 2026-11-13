# Privacy Policy — SMS Relay

_Last updated: 13 November 2026_ · 🇫🇷 [Français](PRIVACY.fr.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇮🇹 [Italiano](PRIVACY.it.md) · 🇪🇸 [Español](PRIVACY.es.md)

> **Reference versions.** The English and [French](PRIVACY.fr.md) versions of this policy are both
> authoritative. The German, Italian and Spanish translations are provided for information; in case
> of discrepancy, the English and French versions prevail.

SMS Relay (`com.filestech.sms`) is part of the **Files Tech** suite, published by **Patrice
Haltaya**. See also the [terms of use](TERMS.md).

## What we collect

**Nothing.** SMS Relay does not collect, transmit or aggregate any personal data of any kind —
with two exceptions, both user-initiated: when you search for a GIF, the words you type are sent
to Klipy so it can return matching GIFs, and when a message contains a link, Relay fetches that
page once to show a preview card (see [Network use](#network-use) below). Relay itself keeps
nothing from those exchanges beyond the GIF you choose to send and the preview it shows you.
Linking a computer changes nothing about this: the phone and the computer talk directly to each
other on your local Wi-Fi network — no Relay server, no cloud, nothing leaves the network.

There is no analytics SDK, no crash reporter, no telemetry endpoint, no advertising identifier, no
fingerprinting library. The binary contains no third-party tracking code.

## What stays on your device

- SMS & MMS messages, stored in an encrypted Room database (SQLCipher) protected by a key wrapped
  by the AndroidKeyStore.
- Conversation metadata (drafts, pinning, archiving, vault flag, per-conversation overrides).
- Settings, in Android DataStore Preferences.
- A salted PBKDF2-HMAC-SHA512 hash of your app-lock PIN (the PIN itself is never stored).
- Optional MMS attachments, in `<files>/mms_attachments/`.
- Optional locally-generated PDF exports of conversations, in `<files>/exports/`.
- If you set them up, your emergency contacts and the settings of the emergency mode and of Safety
  call.
- If you link a device, its record: the device id, kind (computer, phone or tablet), display name,
  public key and linked date. The matching private key never leaves the linked device, and the
  record is deleted when you remove the device. The phone also keeps its own hub id and the TLS
  certificate and private key the local server presents to linked devices (the private key lives
  in the AndroidKeyStore).

Like any default SMS app, SMS Relay also writes your messages to Android's own message store, which
keeps them independently of the app.

The Android system backup is **disabled** so this data does not get synced to Google Drive or to a
device transfer without your explicit consent.

## Network use

SMS Relay holds the `INTERNET` permission for three uses. (1) GIF search: when you open the
GIF tab and type a search, the words you type are sent to Klipy (`api.klipy.com`), which returns
matching GIFs; tapping one downloads it from Klipy, and the thumbnails in the grid come from
Klipy too. Klipy sees your search words and your IP address, as any service you contact does.
(2) Link previews: when a message contains a URL, Relay fetches that page once, on the device,
and reads its Open Graph title and image for display only — the site you link to sees the request
and your IP address, as with any visit; the preview image is never sent as an attachment.
(3) Linked devices: the phone runs a TLS server on the local network (port 47831, advertised via
mDNS as `_relay._tcp.`) so a computer you linked can find it and connect. The server accepts only
devices whose public key you stored by scanning their pairing code, and only after they prove
they hold the matching private key; the traffic never leaves your Wi-Fi network, and there is no
Relay server or cloud account involved. The server can be turned off in Settings → Linked
devices; the app then works fully standalone. That is the only network use in the app: no update
check, no remote configuration, no analytics ping, no crash report. If the permission is blocked,
the app still works — GIF search just returns nothing, links show no preview, and no computer
can link. (The permission was removed in 1.28.13, when the app made no network call at all; it
returned for GIF search, then for link previews, and now for linked devices.)

MMS are carried by Android's own MMS service, which talks to your carrier's MMSC when you send or
receive one. MMS never needed the `INTERNET` permission and still do not use it.

The messages you send travel through your carrier's network, as with any SMS app. The developer
never receives them.

## Emergency features (off until you turn them on)

- **Emergency mode.** When you hold the emergency button for three seconds, SMS Relay sends an SMS
  to the emergency contacts **you** chose. If you granted the location permission, precise or
  approximate, and left "include my location" on (it is on by default in this mode), the app asks
  Android for **one** position at that moment: it reuses a position less than five minutes old if
  Android has one, otherwise it waits at most eight seconds for a new fix, and failing that reuses
  the last known position if it is less than thirty minutes old. No background or continuous
  tracking. The position is added to the SMS as a `https://maps.google.com/?q=…` link; when it is
  only approximate, Android shifts it by about two kilometres and the SMS says so with "(+/-N km)"
  after the link. If a contact opens that link, their browser contacts Google Maps; the app itself
  sends nothing to Google. If location is refused or unavailable, the SMS says so and leaves without
  coordinates.
- **Safety call.** If you do not use the app before the delay you set, SMS Relay sends a check-in
  SMS to the same contacts. It carries no location.
- **Emergency call.** The call tiles of the Emergency screen offer the emergency numbers of the
  country where your phone is registered on the network (112 is always there). When you tap one,
  the app asks for the call permission (`CALL_PHONE`) and, if you grant it, places the call itself;
  if you refuse, it opens the phone dialer pre-filled, which needs no permission. The lock-screen
  shortcut always opens the dialer.
- None of these run in the decoy session opened by the panic code.
- These messages are ordinary SMS: your carrier may charge them according to your plan.

## Permissions

See [PERMISSIONS.md](PERMISSIONS.md) for the justification of every permission used.

## Your rights

Since no personal data leaves your device, there is nothing for you to access, rectify, transfer or
delete from any remote system. To remove data from SMS Relay, either uninstall the app (Android
wipes its data automatically) or use **Settings → Delete all my data** which performs a panic-wipe
of the encrypted database, Keystore aliases, cached attachments and PDF exports. Messages kept by
Android's own message store are managed from Android or from any SMS app.

## Contact

For any privacy-related question: **contact@files-tech.com**.

The publisher receives no data from the app. Only an e-mail you choose to write to him reaches him:
it is used only to answer you, kept for the time of the exchange, then erased. You can ask for
access to it or its erasure at the same address, and complain to the CNIL, the French data
protection authority (www.cnil.fr), if you think your rights are not respected.
