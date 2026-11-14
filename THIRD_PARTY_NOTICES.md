# Proprietary Components & Copyright Notice

Copyright © 2026 Relay. All rights reserved.

## 1. Original Work

The following components are original works developed for Relay and are proprietary:

- User interface (UI) design and implementation.
- User experience (UX) design and interaction patterns as expressed in original creative implementations.
- Original layouts, visual styling, and design systems.
- Original icons, illustrations, graphics, and visual assets.
- Branding, logos, product identity, and associated artwork.
- Original documentation, where applicable.

These components are not released under the Apache License 2.0 or any other open-source license unless explicitly stated otherwise.

No permission is granted to copy, reproduce, distribute, modify, or incorporate these proprietary components into other products without prior written permission from the respective copyright holder, except where permitted by applicable law.

## 2. Third-Party Software

This project incorporates and modifies portions of SMS Tech, originally developed by Patrice Haltaya.

The applicable upstream source code remains subject to the Apache License, Version 2.0.

All applicable upstream copyright notices, license terms, and attribution requirements are retained.

See `LICENSE` and `THIRD_PARTY_NOTICES.md`.

## 3. Separate Licensing

The presence of the Apache License 2.0 in this repository does not grant permission to use separately identified proprietary components.

Each component is governed by its applicable license or copyright notice.

For licensing inquiries or commercial permissions concerning proprietary Relay components, contact the copyright holder.

---

# Third-party notices — SMS Relay

SMS Relay (`com.filestech.sms`) is licensed under the Apache License 2.0 ([LICENSE](LICENSE)). It
ships the open-source libraries listed below. The list was read on 2026-09-26 from the resolved
`releaseRuntimeClasspath` of the `:app` module at `v1.28.12`, and each licence from the POM that
the library publishes — not from memory.

**No Google Play Services, no ML Kit, no Firebase, no analytics SDK, no crash reporter.**

## Shipped in the APK

| Library | Version | Publisher | Licence |
|---|---|---|---|
| AndroidX: Core, Activity, Lifecycle, Navigation, SavedState, AppCompat, Emoji2, Fragment, Window, Startup, Tracing, Profile Installer, DocumentFile, Biometric and their support modules | various | Google / AOSP | Apache 2.0 |
| Jetpack Compose: UI, Foundation, Animation, Runtime, Material 3, Material Icons | BOM 2026.09.00 (UI 1.12.1, Material 3 1.4.0) | Google / AOSP | Apache 2.0 |
| Room (`androidx.room`) and `androidx.sqlite` | 2.8.5 / 2.7.0 | Google / AOSP | Apache 2.0 |
| DataStore Preferences | 1.2.1 | Google / AOSP | Apache 2.0 — its embedded copy of Protocol Buffers is BSD-3-Clause, see below |
| WorkManager | 2.11.2 | Google / AOSP | Apache 2.0 |
| Hilt and Dagger (`com.google.dagger`, `androidx.hilt`) | 2.60.1 / 1.4.0 | Google | Apache 2.0 |
| Accompanist Permissions | 0.37.3 | Google | Apache 2.0 |
| Guava `listenablefuture`, JSR-305 annotations | 1.0 / 3.0.2 | Google | Apache 2.0 |
| Kotlin standard library | 2.4.10 | JetBrains | Apache 2.0 |
| kotlinx.coroutines, kotlinx.serialization | 1.11.0 / 1.11.0 | JetBrains | Apache 2.0 |
| JetBrains annotations, JSpecify | 23.0.0 / 1.0.0 | JetBrains / JSpecify | Apache 2.0 |
| `javax.inject`, `jakarta.inject-api` | 1 / 2.0.1 | JSR-330 / Eclipse Foundation | Apache 2.0 |
| Coil (image loading) | 2.7.0 | Coil contributors | Apache 2.0 |
| Coil SVG (`io.coil-kt:coil-svg`, pulls in AndroidSVG) | 2.7.0 | Coil contributors | Apache 2.0 |
| Twemoji graphics (emoji / sticker artwork) | Unicode 17, jdecked/twemoji fork | Twitter, Inc. and contributors | **CC-BY 4.0** — attribution below |
| Ionicons icons (including the schedule, trash, block, and contact icons) | — | Ionic | **MIT** — attribution below |
| OkHttp, Okio (pulled in by Coil) | 4.12.0 / 3.9.1 | Square | Apache 2.0 |
| Timber | 5.0.1 | Jake Wharton | Apache 2.0 |
| Ktor server (`server-core`, `server-cio`, `server-websockets`) | 3.6.1 | JetBrains | Apache 2.0 |
| CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) | 1.6.2 | Google / AOSP | Apache 2.0 |
| ZXing core (`com.google.zxing:core`) | 3.5.4 | ZXing authors | Apache 2.0 |
| BouncyCastle (`bcprov-jdk18on`, `bcpkix-jdk18on`) | 1.87 | Legion of the Bouncy Castle Inc. | MIT |
| **SQLCipher for Android** | 4.19.0 | Zetetic LLC | **BSD-3-Clause** — full text below |

OkHttp is present because Coil depends on it. SMS Relay uses Coil to display contact photos and
attachments stored on the phone; its network uses are MMS transport through your carrier and GIF
search via Klipy (see [PRIVACY.md](PRIVACY.md)).

Twemoji graphics are bundled as SVG/PNG assets under `app/src/main/assets/twemoji/` and rendered
by Coil; the Twemoji code is not used, only the graphics. The bundled set is a curated subset
(241 emojis as SVG, 38 of them also as 72x72 PNG for sending stickers as MMS attachments).

Several icons in this app (including the schedule, trash, block, and contact icons) are from
Ionicons, used under the MIT License. The icons are bundled as vector drawables under
`app/src/main/res/drawable/`; the Ionicons code is not used, only the icon artwork.

SQLCipher bundles **SQLite** and **LibTomCrypt**, both released into the public domain by their
authors.

Ktor, CameraX, ZXing core and BouncyCastle are new in Slice 1, for linked devices: Ktor serves the
TLS WebSocket server on the local network, CameraX shows the camera preview for QR pairing, ZXing
decodes the pairing code on-device, and BouncyCastle verifies Ed25519 signatures and builds the
fallback TLS certificate. None of them contacts any server or collects usage data; CameraX is a
local camera wrapper, not a service, and ZXing is used instead of ML Kit precisely because it
needs no Play Services.

## Licence texts

### Apache License 2.0

Every library marked "Apache 2.0" above is distributed under the same text as SMS Relay itself:
[LICENSE](LICENSE), also at <https://www.apache.org/licenses/LICENSE-2.0>. Of all these libraries,
only `jakarta.inject-api` ships a `NOTICE` file; its attribution is reproduced below.

### Eclipse Jakarta Dependency Injection — NOTICE (Apache 2.0)

> This content is produced and maintained by the Eclipse Jakarta Dependency Injection project.
> Project home: https://projects.eclipse.org/projects/cdi.batch
>
> Jakarta Dependency Injection is a trademark of the Eclipse Foundation.
>
> All content is the property of the respective authors or their employers. For more information
> regarding authorship of content, please consult the listed source code repository logs.
>
> Source code: https://github.com/eclipse-ee4j/injection-api

### SQLCipher for Android — BSD-3-Clause

```
Copyright (c) 2008-2023, ZETETIC LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Protocol Buffers (embedded in DataStore) — BSD-3-Clause

```
Copyright 2008 Google Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Twemoji graphics — Creative Commons Attribution 4.0 International (CC-BY 4.0)

Emoji and sticker artwork: Twemoji (jdecked/twemoji fork, Unicode 17),
Copyright Twitter, Inc. and contributors, licensed under CC-BY 4.0.
Source: https://github.com/jdecked/twemoji — licence text:
https://creativecommons.org/licenses/by/4.0/legalcode

Only the graphics are bundled; the Twemoji code (MIT) is not used.

### Ionicons icons — MIT License

Several icons in this app (including the schedule, trash, block, and
contact icons) are from Ionicons, used under the MIT License.

MIT License

Copyright (c) 2015-present Ionic (http://ionic.io/)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Build and test tools (not shipped in the APK)

Gradle, the Android Gradle Plugin, KSP, the Room and Hilt compilers, R8, detekt, ktlint, JUnit,
Robolectric, MockK, Turbine and the AndroidX test libraries are used to build and test SMS Relay.
They are not part of the APK.
