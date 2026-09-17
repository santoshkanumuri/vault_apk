# Third-party notices

These are the main libraries used by Private Vault. Their code remains subject to their respective licenses; transitive dependencies may have additional notices.

| Library | Purpose | License |
| --- | --- | --- |
| Kotlin | Language and runtime | Apache-2.0 |
| AndroidX, Compose, Room, CameraX | UI, database access, biometrics, camera | Apache-2.0 |
| SQLCipher Community Edition | Encrypted database | BSD-style SQLCipher license |
| Bouncy Castle | Argon2id and Base32 | Bouncy Castle license |
| Google Tink | Streaming backup encryption | Apache-2.0 |
| java-otp | TOTP generation | MIT |
| ZXing | QR decoding | Apache-2.0 |
| EMV NFC Paycard Enrollment | Contactless card reading | Apache-2.0 |
| SLF4J | Logging facade with a no-op binding | MIT |

Versions are in [app/build.gradle.kts](app/build.gradle.kts). Original license texts and notices in upstream distributions still apply.

## Artwork

- Payment-network vectors are adapted from [svg-credit-card-payment-icons](https://github.com/aaronfagan/svg-credit-card-payment-icons). Its [Apache-2.0 license](docs/licenses/payment-icons-LICENSE.txt) is included.
- The RuPay logo comes from [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:RuPay.svg), which credits NPCI and marks the artwork as a public-domain text logo. RuPay remains an NPCI trademark.
- Colors were sampled from [Color Hunt's retro palettes](https://colorhunt.co/palettes/retro), with additional neutral colors. Source palette IDs are in the [1.4.0 notes](docs/RELEASE-1.4.0.md).
- Launcher artwork is the project owner's supplied `logo.jpg`.

Network names and logos identify stored cards. They do not imply affiliation or endorsement.
