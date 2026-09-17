# Private Vault 1.5.1

## Vault codes shortcut

Open **More > Settings > Add Vault codes tile** to add the shortcut beside Wi-Fi and Bluetooth. On Android 10 to 12, add it through the Quick Settings edit screen instead.

While signing into another app, pull down Quick Settings and tap **Vault codes**. Unlock, search for an account, and tap its copy button. The picker closes so you can paste the code into the login form. Bitwarden or another password autofill app can stay selected.

The picker always authenticates its own session. It does not reuse an unlocked main screen. Fingerprint access follows the existing 24-hour deadline. Entering the master password lets you enable a new fingerprint session. No account names or codes appear before authentication.

The picker reads only authenticator records, closes its database connection after loading, blocks screenshots, and clears its account list when closed or backgrounded. Screen-off and one minute of inactivity close it too. It needs no overlay, accessibility, or Internet permission. Android can require device unlock before opening the picker; that does not replace vault authentication.

Codes are generated again when copied. Clipboard items are marked sensitive, and the app attempts to clear its own item after 30 seconds. Android can prevent background clipboard access, so automatic clearing is not guaranteed. The tile never displays codes or account names.

Authenticator records and backup format are unchanged. The tile's placement is managed by Android and needs to be added again on a new phone.

## Device checks

Passed 23 unit tests and 6 Android emulator tests, including encrypted backup restoration, authenticator-only queries, tile permissions, screenshot blocking, and closure on backgrounding. Lint reports no errors and 39 existing warnings. The release signature was verified.

Installed the release over the emulator's existing dummy vault. Checked tile launch, password unlock, display and copy of a dummy account's code, and a fresh authentication prompt when reopening.

Real fingerprint prompts and Samsung Quick Settings behavior still need checking on the S26 Plus. Use dummy authenticator accounts for the first check.
