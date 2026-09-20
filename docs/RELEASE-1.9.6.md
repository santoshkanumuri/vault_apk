# Private Vault 1.9.6

Version code: 32. Package: `com.application.private_vault`.

Bitwarden CSV import now accepts `login_url`, including exports that put spaces before some header names. Version 1.9.5 accepted Bitwarden's documented `login_uri` spelling but rejected the `login_url` variant.

CSV and Bitwarden JSON imports now skip passwordless login rows. A passkey-only or username-only row no longer prevents valid password rows in the same file from reaching the review screen. Files with no usable password records are still rejected.

The vault and encrypted backup formats are unchanged. Install this update over v1.9.5 with the same signing key.

Checks: 43 unit tests cover the reported Bitwarden header and passwordless Brave rows, along with the existing CSV, JSON, duplicate, size-limit, and secret-safe error cases. Android lint and signed-release checks also passed.
