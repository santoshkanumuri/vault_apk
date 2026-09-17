# Private Vault 1.5.0

## Navigation and groups

Five destinations: Home, Cards, Passwords, Codes, More. More contains Groups, Security questions, Notes, and Settings. Groups now support search, compact overview cards, editing names/notes, and managing linked entries without changing their other group memberships. Group detail shows full card faces and live authenticator codes.

## Authenticator

Standard TOTP accounts support manual Base32 keys, in-app camera QR scanning, and local QR image import. Camera frames are not saved. Imported source images remain outside the vault. HOTP, Google Authenticator migration QR codes, push approvals, and SMS are not supported.

Uses java-otp 1.0.0 (MIT) for RFC 6238 code generation, ZXing core 3.5.3 (Apache-2.0) for QR decoding, and CameraX 1.3.4 (Apache-2.0) for the camera. No Internet permission. Secrets remain in SQLCipher storage and authenticated encrypted backups, and are excluded from search. Codes use Unix time, with SHA1/SHA256/SHA512, 6–8 digits, and intervals of 1–300 seconds. The phone's clock must be correct. Copy recomputes the current code and uses the existing sensitive-clipboard handling. Codes stop displaying when the activity pauses and are removed with vault content on lock.

Sources: https://github.com/jchambers/java-otp, https://github.com/zxing/zxing, https://developer.android.com/media/camera/camerax, https://www.rfc-editor.org/rfc/rfc6238

## Data transfer

Database migration 4 to 5 adds authenticator settings and a settings table. Editing entries and groups now uses upsert so existing photo links and group memberships survive.

Backup metadata version 2 includes all record fields, authenticator secrets/parameters, groups and memberships, notes, favorites, order, timestamps, photo cover selection, light mode, and NFC preference. Version 1 files remain readable; they contain no authenticator parameters or appearance preferences. Full photo bytes are exported; encrypted thumbnails are regenerated on restore.

Restore decrypts photo streams directly into new encrypted files with random names. It authenticates the full encrypted stream, checks metadata and links, verifies all expected photos, and decodes thumbnails before offering confirmation. Database replacement and restored settings share a transaction. Existing photo files are removed only after that transaction commits. Cancelled/failed preparations delete their staging files. Abandoned encrypted restore files are cleaned after a later successful unlock. A interrupted commit cannot delete staged files before its database result is known.

On another phone, create a vault and restore via More → Settings. The destination vault retains its master password; the backup password decrypts the archive. Fingerprint enrollment and its 24-hour session must be enabled again on the destination device and are not transferred. NFC requires compatible hardware. Previously exported files still need their original backup password.

## Appearance

Saved light-mode switch; dark mode retains pure black. Delete actions use muted red with white text. Launcher uses the supplied logo.jpg.

## Acceptance checks

Automated coverage includes RFC reference vectors, QR parsing/decoding, code intervals, original-photo preservation, full restore with a different vault key, wrong passwords, corrupted/truncated/unsupported backups, cancelled restore, transaction rollback, settings, and group/photo preservation during edits. Real fingerprint behavior and camera scanning on the Samsung phone still require device checks. Keep an independently accessible copy of each service's recovery codes.
