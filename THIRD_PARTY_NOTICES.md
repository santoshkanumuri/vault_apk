# Third-party notices

These are the main libraries used by Private Vault. Their code remains subject to their respective licenses; transitive dependencies may have additional notices.

| Library | Purpose | License |
| --- | --- | --- |
| Kotlin | Language and runtime | Apache-2.0 |
| AndroidX, Compose, Room, CameraX | UI, database access, biometrics, camera | Apache-2.0 |
| SQLCipher Community Edition | Encrypted database | BSD-style SQLCipher license |
| Bouncy Castle | Argon2id and Base32 | Bouncy Castle license |
| Google Tink | Streaming backup encryption | Apache-2.0 |
| Apache Commons CSV, IO and Codec | Browser password CSV parsing | Apache-2.0 |
| Gson | Bitwarden JSON parsing | Apache-2.0 |
| java-otp | TOTP generation | MIT |
| ZXing | QR decoding | Apache-2.0 |
| EMV NFC Paycard Enrollment | Contactless card reading | Apache-2.0 |
| SLF4J | Logging facade with a no-op binding | MIT |
| WebAuthn4J | Passkey authenticator and attestation encoding | Apache-2.0 |
| Lucide icons (vendored path data in `ui/NuvoriIcons.kt`) | Interface icons shared with Nuvori for Windows | ISC (portions MIT, from Feather) |
| Jackson | WebAuthn4J JSON and CBOR encoding | Apache-2.0 |

AndroidX Autofill and Credentials provide keyboard suggestions and Android Credential Manager integration under Apache-2.0.

Versions are in [app/build.gradle.kts](app/build.gradle.kts). Original license texts and notices in upstream distributions still apply.

## Artwork

- Payment-network vectors are adapted from [svg-credit-card-payment-icons](https://github.com/aaronfagan/svg-credit-card-payment-icons). Its [Apache-2.0 license](docs/licenses/payment-icons-LICENSE.txt) is included.
- The RuPay logo comes from [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:RuPay.svg), which credits NPCI and marks the artwork as a public-domain text logo. RuPay remains an NPCI trademark.
- Colors were sampled from [Color Hunt's retro palettes](https://colorhunt.co/palettes/retro), with additional neutral colors. Source palette IDs are in the [1.4.0 notes](docs/RELEASE-1.4.0.md).
- Launcher artwork is the project owner's supplied `logo.jpg`.

Network names and logos identify stored cards. They do not imply affiliation or endorsement.

## Lucide

The interface icons in `app/src/main/java/com/privatevault/app/ui/NuvoriIcons.kt` reproduce SVG path data from
[Lucide](https://lucide.dev) 0.544.0, the same set the Windows app uses through `lucide-react`.

ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2023 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2025.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

The MIT License (MIT) (for portions derived from Feather)

Copyright (c) 2013-2023 Cole Bemis

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
