# Private Vault 1.5.2

## Link authenticator accounts to apps

Edit an account in Codes, tap **Linked apps**, select one or more apps, then save the account. You can link several accounts to the same app.

In **More > Settings**, enable **Suggest codes for the previous app**, then allow Usage access in Android Settings. This is optional and off by default. The vault checks recent app activity only when the code picker opens, without storing usage history.

Open another app, tap the Vault codes Quick Settings tile, and authenticate. Matching accounts appear first. **Show all** lets you choose from every account instead. Without a match or Usage access, the picker shows all accounts.

Codes in this picker start masked. Use **Copy** without revealing the code, or **Show** and **Hide** to control visibility. Copy closes the picker so you can paste. Existing fingerprint and password requirements still apply.

Detection is a suggestion, not proof of the destination. Browsers cannot identify the website through Usage access. Split-screen use or missing recent events can prevent a correct suggestion. Detection looks at the last five minutes of activity and falls back to the full list when no linked app is found.

App links are encrypted with the account and included in backups. Database version 6 adds the links without replacing existing entries. Backup metadata version 3 includes these links; update the destination app before restoring a new backup. Usage access and the local detection opt-in must be enabled separately on a new phone. Links to apps not installed there remain available to edit.

## Verification

Passed 25 unit tests and 7 emulator tests, including exact package matching, multiple linked accounts, masked copying, Show/Hide, and backup restoration with app links. Lint reports no errors and 39 warnings. The signed release installed over the emulator's existing vault; its dummy authenticator account and the linked-app selector remained available.

Automatic detection across Samsung apps, split-screen behavior, and real fingerprint prompts still need checking on the S26 Plus. Use a dummy account for the first check.
