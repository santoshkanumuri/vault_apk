# Private Vault 1.9.7

Version code: 33. Package: `com.application.private_vault`.

Home now uses a compact summary and denser rows for favorites, items needing attention, and recently opened entries. Quick add has moved into the Home toolbar. Passwords, security questions, notes, and folders use the same compact layout while retaining accessible touch targets.

Password import now normalizes spaces, underscores, and hyphens in CSV headings. When an export uses unknown headings, a local mapping screen lets you select the website, username, password, title, and notes columns. It displays headings only and never uses credential values to guess a mapping.

Changed passwords can be handled individually or with bulk Keep saved and Use imported actions. Exact duplicates remain unchanged. Accounts matching more than one saved login are marked ambiguous and cannot be overwritten automatically. The app rechecks every match when saving and commits the whole import in one transaction. If the vault changed after review, no part of that import is applied.

Replacing an imported password changes only the saved password and timestamp. Existing titles, notes, folders, linked apps, autofill signatures, photos, and authenticator links remain intact. Repeated accounts inside one export are consolidated before review.

When NFC card import is enabled, the card editor now shows a compact contactless icon in the Card label field instead of a full-width scan button. The icon uses the current theme and retains a 48 dp touch target and screen-reader label.

The vault and encrypted backup formats are unchanged. Install this update over v1.9.6 with the same signing key.

Checks: unit tests, Android lint, release lint, Android-test compilation, compact-layout UI tests, and encrypted-database import tests passed. Import tests cover explicit keep and replace decisions, field preservation, ambiguous matches, stale previews, and full transaction rollback.
