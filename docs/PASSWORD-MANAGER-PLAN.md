# Password manager plan

## 1. Android login foundation: implemented

- Keep Cards, Passwords, Codes, Groups and Notes. A login can refer to one existing authenticator; never copy its setup key into the login.
- Add a local cryptographic password generator to the login editor.
- Let the user explicitly authorize native apps for each login. Pin package identity and current signing certificates. Code suggestions based on Usage access are not authorization to fill passwords.
- Add an optional Android AutofillService. Show a generic unlock action, authenticate using the existing password/24-hour biometric policy, show the destination and matching logins, and fill only after selection. Do not submit forms.
- Support clear native username/password and single TOTP fields. Reject web content outside the verified-browser path and reject ambiguous forms.
- Native-app saving remains manual. Browser save/update is handled in milestone 2.
- Keep the tile for people who leave Bitwarden selected. Linked authenticators remain usable independently.
- Migrate existing vaults without deleting data. Include new relationships and authorizations in backups. Removing a code clears login links; removing a login preserves the code.

Acceptance: exact package/certificate matching; unsupported web forms rejected; locked/cancelled/expired requests release no values; copied/generated passwords stay out of logs and saved UI state; linking, unlinking, deletion and backup round trips tested. Native autofill must be exercised through Android, not just by testing helper functions.

## 2. Browser autofill and login maintenance: partially implemented

### Next implementation batch

Requested: signup/password-change forms, keyboard suggestions, passkeys, and clipped detail-page actions.

1. Fix the shared detail-page safe area and duplicate top padding. Allow action buttons to grow with text. Verify Edit/Delete on notes, passwords and authenticators on compact screens, in landscape, and with enlarged text.
2. Add a generic keyboard unlock suggestion with the existing authenticated account picker. Keep the dropdown fallback. Verify supported and unsupported keyboards, cancellation, and Android 10 behavior.
3. Recognize explicit new-password fields separately from current-password fields. Generate locally, fill confirmation fields consistently, and require save/update review. Verify signup, password changes, mismatched confirmation, ambiguous fields and origin changes before enabling them.
4. Implement the passkey milestone below, including encrypted backup transfer and sign-in after restoration. Do not advertise passkey support before the provider and restore tests pass.

Current batch status: implemented for v1.8.0. Detail pages use the available safe area, autofill has an icon/name presentation and compatible keyboard suggestions, explicit new-password fields support generation, and the Android 14+ passkey provider includes encrypted backup transfer. Browser tests verify signup, password changes, and passkey sign-in after restoring into a fresh vault with a different encryption key.

- Next priorities requested September 18: browser autofill, Chrome/Brave CSV import, and reorganized Settings and More.
- Establish browser trust, exact HTTPS origin matching, frame handling, domain normalization and phishing tests before filling websites. No title-based or Usage-access-based authorization.
- Import Chrome/Brave password CSV files through the file picker. Parse quoted fields with a maintained CSV library, preview counts and account names without passwords, skip exact duplicates, preserve conflicting records, and commit in one database transaction. Never retain a plaintext copy. Explain that the original export file remains readable outside the vault.
- Give Settings separate Security, Autofill, Backup and import, Appearance, and About pages. Keep More focused on vault sections and clear settings shortcuts.
- Add explicit save/update confirmation, password history with clear retention controls, and safe handling of signup versus change-password forms.
- Test multi-step logins, multiple accounts, unsupported fields, cancellation, accessibility and expiry across several browsers and Android versions.
- Keyboard inline presentations and the separate Credential Manager passkey provider are included in v1.8.0.

Implemented: browser certificate checks for Chrome/Brave release builds, exact HTTPS origin matching, CSV import with preview and transactional duplicate handling, and separate Settings pages. Browser release certificate source: https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json, checked September 18, 2026. No debug certificate or user override is accepted. Certificate changes require a reviewed app update.

Implemented in v1.8.0: browser Save/Update review and username-first filling. Saving submitted credentials requires Android's prompt, vault authentication, and explicit confirmation. Updates require the exact HTTPS origin and username, plus an unchanged entry at commit time. Username-first filling checks each screen independently and requires a separate account selection. Generated passwords use an explicit save-before-fill step because Android can omit its save prompt for unchanged autofilled values. Generation saves a separate login, preserves the old one, and copies groups and the authenticator link from the selected account.

Still deferred: password history, other browsers, and saving credentials spread across multiple pages. Frame nodes, multiple forms, ambiguous password fields, and mixed password/OTP forms are rejected. Signup and change-password support requires explicit new-password hints. Existing code tile behavior is preserved.

## Passkeys: separate acceptance milestone before desktop work

- Implement an Android Credential Manager provider on Android 14 and newer. Android 10-13 keep passwords and TOTP; do not show unsupported passkey actions.
- Start with verified-browser requests. Native-app passkey requests need a separate app-to-website association verification design that respects the offline requirement; never silently trust an app's claimed relying-party ID.
- Create new passkeys in Private Vault, and authenticate before registration or signing. Verify the calling app identity, browser origin, relying-party ID and challenge. Never treat a matching display name as authorization.
- Use established WebAuthn/FIDO components and published test vectors for client data, authenticator data, COSE keys, signatures, user verification and backup flags. Do not invent an authentication protocol.
- Store portable passkey private keys, credential IDs, relying-party IDs, user handles, algorithms and required metadata only inside the encrypted vault. Preserve them in authenticated encrypted backups. Device-bound Keystore private keys cannot provide portable restore; the portable keys must instead be protected by the vault encryption key.
- Prove that a passkey created on one installation signs in after restoring into a clean installation. Test wrong backup passwords, tampering, cancellation, wrong origins/RP IDs, duplicate restore and lock during signing. Do not publish passkey support until those tests pass.
- Password CSV imports cannot transfer existing passkeys. Add support for standardized credential exchange separately where source providers support it. Explain the shared risk of storing passwords, TOTP and passkeys in one unlocked vault.

Implemented in v1.8.0 for verified-browser requests with an exact host match, ES256 and anonymous attestation. Creation and signing require vault authentication and confirmation. Private keys remain encrypted and travel in version 5 backups. The live browser check verifies registration, restore into a fresh vault, and sign-in with the restored key. Native-app association checks, related origins, additional algorithms/extensions, and published-vector coverage remain separate work. This is not an independent security audit. Reference: https://developer.android.com/identity/sign-in/credential-provider

## 3. Desktop vault and browser extension

- Specify a versioned portable encrypted data format and cross-platform cryptographic test vectors.
- Implement a Windows desktop vault first, then macOS, with OS-backed biometric unlock and master-password fallback.
- Use browser Native Messaging with an approved extension identity and authenticated local protocol. Keep the master key and full vault out of the extension. Return only the user-selected credential for the checked destination.
- Treat the browser and filled page as recipients of plaintext. Do not promise protection from a compromised OS or browser.

## 4. Explicit local synchronization

- Pair devices explicitly using cryptographic identities and an authenticated channel. Being on the same Wi-Fi grants no trust.
- Start with a visible Sync now flow. Reconcile record revisions, deletions and attachments; preserve conflicts for review. Never synchronize a live database file directly.
- Test offline edits, interrupted transfers, replay, conflicting deletions and revoked devices. Revocation cannot erase secrets already received.
- Add network permission only when implementing this phase, and update privacy statements. Keep encrypted backups separate from sync.

## 5. Automatic sync and release assurance

- Add background discovery only after manual sync and conflict recovery are dependable. Account for mobile background limits.
- Consider additional peers and optional user-owned transports separately; no relay or cloud service is in the initial scope.
- Obtain an independent security review before marketing the app as a replacement with audited password-manager security.

No desktop, network, autofill-provider change, publishing, or synchronization is enabled merely by installing this plan's Android milestone. Users choose their Autofill provider through Android Settings.

## Verification record

- 31 unit tests passed for the current password/import implementation.
- Device suites passed on Android 14 and Android 16 with 16 KB pages, reporting 16 tests each. The optional live passkey test runs separately. Checks include native username/password and linked-code filling, exact app authorization, one-use/expired/cancelled requests, pending-password cleanup, migration, import rollback and encrypted backup transfer. Browser-update storage checks cover duplicate saves, stale updates, wrong origins, and preserving groups, notes, photos and authenticator links. Compact detail layouts keep Edit, Delete and Camera within a 320 x 420 dp viewport at 1.8 font scale for notes, passwords and authenticators.
- Brave 1.95.102 passed live HTTPS filling and browser password-update checks on the Android 16 emulator with dummy credentials. The update check submitted a dummy login to fill.dev, accepted Android's save prompt, authenticated again, confirmed replacement, and checked the saved password and retained authenticator link. A two-page login also passed: username filling, an empty password field on the next page, then separate authentication and password filling. The official release APK's checksum and signing certificate were verified before testing.
- The browser's Save as new login flow passed separately and left the previous login's password unchanged. No real accounts or credentials were used.
- v1.8.0 live Brave checks passed for brand-new signup accounts, generating for an existing account, and password changes. Saved generated passwords matched the website's submitted values, and existing passwords stayed unchanged. Passkey registration and sign-in passed after an encrypted backup was restored into a fresh vault with a different encryption key. Migration also passed from a database without a passkey table.
- Lint passed with existing warnings. Chrome's current release and real-device fingerprint behavior still need device checks. The emulator's bundled Chrome 133 is too old for the current browser-settings deep link.

Keep: exact destination authorization, saving generated passwords before filling, and testing sign-in with restored passkeys.

Trace: `VaultAutofillService` checks destination and fields, `VaultCodesActivity` authenticates and selects an account, and Android returns the selected values to the recipient. `VaultDao.importLogins` commits browser imports in one transaction.

Try: use a dummy login, link a dummy authenticator, export a backup, restore it into a separate test installation, and confirm both records and their link are intact.
