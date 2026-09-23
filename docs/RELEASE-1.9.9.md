# Nuvori 1.9.9

Version code: 35. Package: `com.application.private_vault`.

Nuvori can now search the existing vault when an Android app or verified browser has no exact login match. "Fill and link" requires confirmation, records the verified app identity or HTTPS origin, and makes that login available for the same destination next time.

The Fill and link confirmation now completes from its dialog instead of failing its own window-focus check. Saved-login search reuses the compact password cards from the vault and exposes the Fill or Fill and link action in the row's accessible name.

Autofill now keeps the first Nuvori suggestion generic while the vault is locked. After unlock, regular username and password forms receive one account suggestion per exact app identity or HTTPS origin match. Each suggestion shows the username, a fixed masked-password indicator, and the saved entry title. OTP, password-generation, and no-match flows keep their existing review and search screens.

Password import now handles provider-specific CSV variants and Bitwarden JSON through a review step. It detects exact duplicates, protects ambiguous local matches, keeps saved passwords by default when values differ, supports bulk conflict choices, and rechecks the reviewed matches inside the database transaction.

Chrome-style imports now accept BOM-marked UTF-8 and UTF-16 files, comma, semicolon, or tab delimiters, optional `sep=` preambles, and rows that omit an empty optional trailing field. Rejections now explain whether the file is empty, has no non-empty passwords, has mismatched columns, or uses an unsupported delimiter without exposing credential rows.

Google Password Manager device exports may contain passwordless rows with omitted trailing cells or an extra empty trailing cell. Nuvori now skips those passwordless rows and imports the remaining passwords. A nonempty undeclared field still stops the import to prevent shifted credential data.

Settings now includes an exact duplicate-password review. It compares every saved password field, tags, folder membership, and native-app or browser links while ignoring generated IDs and activity timestamps. Entries with photos are excluded. Nothing is selected by default, one oldest copy always remains, and deletion rechecks every selected copy in one database transaction.

Android Credential Transfer support can request passkeys from another credential provider that offers a compatible CXF export. Nuvori validates imported P-256 credentials, previews duplicates and conflicts, and refuses to overwrite a different passkey with the same credential ID. Chrome, Brave, or another manager appears as a source only when its installed credential provider supports Android's transfer flow.

Passkey requests now support a verified browser origin on a subdomain when the request uses its parent RP ID, such as `api.id.me` with `id.me`. Creation also accepts direct and indirect attestation preferences while returning privacy-preserving none attestation. Enterprise attestation remains unsupported. Native apps must use Android Credential Manager; legacy FIDO APIs cannot discover Nuvori.

This release adds the local cryptographic and database foundation for future Android-to-Windows sync. Network discovery, pairing, remote transfer, and automatic sync remain disabled. Nuvori still has no Internet permission.

The database schema is version 12. Encrypted backup format 8 preserves the vault identity and autofill origin links. Nuvori reads older backups, but releases before 1.9.9 cannot restore a format-8 backup. Export a new encrypted backup after updating and keep the older backup until the new one has been checked.

Release checks: 67 JVM tests passed with no failures or skips. Release lint, R8 shrinking, resource shrinking, APK signing verification, ZIP alignment, and 16 KB native-library alignment passed. The APK is not debuggable, has no Internet permission, and uses the same signing certificate as 1.9.8. Android instrumentation tests compiled, but were not run on a device as part of this build.
