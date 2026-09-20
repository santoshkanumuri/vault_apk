# Private Vault 1.9.5

Version code: 31. Package: `com.application.private_vault`.

Password import now accepts common CSV exports from browsers and password managers. Supported column names cover Chrome, Brave, Edge, Firefox, Safari, 1Password, LastPass, Dashlane, KeePass, and Bitwarden exports. Header matching ignores case and accepts each provider's names for the title, website, username, password, and notes fields.

Unencrypted Bitwarden JSON exports can now be imported. Private Vault imports login items and their first saved website. It skips other Bitwarden item types. Encrypted Bitwarden JSON, passkeys, authenticator seeds, cards, identities, and secure notes are not imported.

The file picker now accepts CSV and JSON files. The preview and completion messages refer to password exports instead of assuming every file is a Chrome or Brave CSV. Import limits remain 5,000 logins and four million characters. Error messages do not include source rows or credential values.

Repeated accounts are consolidated before review. Exact matches remain unchanged. When an imported password differs, you can keep the saved password or replace only its password field. Existing names, notes, folders, photos, and linked authenticators remain intact.

The vault and encrypted backup formats are unchanged. Install this update over v1.9.4 with the same signing key.

Checks: 41 unit tests, Android lint, and release lint passed. Tests cover Chrome-style CSV, multiline notes, Bitwarden CSV and JSON, Firefox, Safari, 1Password, LastPass, KeePass, malformed input, encrypted JSON rejection, duplicate handling, and secret-safe errors. A real Chrome CSV export was parsed locally without printing or copying its credential values. The signed release APK has the same certificate as v1.9.4, passes 16 KB native and ZIP alignment checks, has no Internet permission, and is not debuggable.
