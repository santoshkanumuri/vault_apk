# Personal device sync

Decision revised October 5, 2026.

Nuvori is a private vault for one owner using personal devices. The usual setup is a primary phone, perhaps a second phone and tablet, a Windows computer, and a watch for codes. A group supports six active mobile devices and two Windows PCs, including the managing device. Version 2.1.0 added Windows pairing and an initial vault copy over the encrypted connection. Version 2.1.1 syncs later edits and photos with a paired Windows PC over the local network; Version 2.1.2 adds Windows discovery and an incoming sync listener so either device can initiate an exchange. Version 2.1.3 corrects physical Wi-Fi selection, remembered peer routes, busy replies, and failure counting. Version 2.1.4 keeps pairing open while the other device connects, shows the phone's reason when a vault copy fails, and fixes photo sync with Windows in release builds. The watch is a companion rather than an Android vault member.

## Daily behavior

One device manages enrollment and removal. It can transfer that role to another paired device while both are reachable and the recipient accepts. Every enrolled device can read and edit its local vault offline and exchange signed changes directly with another enrolled device. A change does not need a global number assigned by the managing phone. Concurrent changes must retain enough history for conflict review.

The vault ID identifies a sync group. Each installation has its own device ID and signed author chain inside that group. If a device stops sharing, it keeps its local records and photos, creates a new vault ID, and starts a new chain with fresh content and transport keys. Its old chain belongs to the old group and is not reused. Other members remain on the old vault ID and can continue exchanging ordinary changes.

The interface must distinguish saved locally, received by a peer, and applied by a peer. A missing managing phone blocks membership changes and transfer, but does not block ordinary edits or peer sync. A notification and Android's background restrictions still affect automatic delivery.

## Passwords and pairing

### Wi-Fi and hotspot connections in 2.1.5

Pairing and later sync both select a local route for the peer's address. A phone hosting a hotspot can use its hotspot interface even while it has an upstream Wi-Fi connection. The sync listener accepts connections across local interfaces and does not depend on a Wi-Fi client callback. Discovery failures leave the listener and saved-address checks running.

Matching-code confirmation has its own two-minute window on Android and Windows. Expiry produces a visible failure. A connection timeout and a timeout after the secure handshake starts now have different messages.

The reported initial pairing succeeded after switching to a hotspot. That points to reachability restrictions on the original Wi-Fi, but does not establish which router setting caused them. The later hotspot sync report exposed the app's Wi-Fi-only routing assumption. [Release 2.1.5 checks](RELEASE-2.1.5.md) distinguish automated verification from the physical hotspot check still needed.

The product target is one user-facing master password across the owner's devices, while each installation keeps a separate random local vault key and salt. Current installations have independent passwords. This release slice does not silently change those passwords or claim they are already synchronized.

Before showing a pairing QR, the managing device asks for its current master password and verifies it locally. It retains the existing temporary invitation, J-PAKE exchange, and matching-code confirmation. A secondary cannot host enrollment, even if the UI is bypassed. The protocol checks authority before opening the listener and again when authorizing admission.

Pairing now checks password equality with a password-based handshake before the vault copy. Each device first verifies the entered password against its own local vault. To complete the common-password experience:

1. Review and test the pairing proof against incorrect passwords and interrupted handshakes. Keep the password out of invitations, logs, and persistent sync operations.
2. Design password changes for offline devices. Show which devices still need a local password update; do not claim that disconnected devices can immediately unlock with the replacement.
3. Keep password changes separate from device revocation. A lost device may already hold decrypted data or usable old keys. Updating a password does not remove its membership.

## Transfer and recovery

A planned authority transfer needs a signed handoff from the current manager and explicit acceptance from the paired recipient while they can connect. Verify that the two participants have applied the agreed state, including photos. Persist a recoverable handoff and acknowledgement. A crash or missing acknowledgement must not make the UI report success prematurely. Other offline members catch up later.

The Android source now records a signed offer, recipient acceptance, and manager commit in the membership chain. Each participant checks the peer's reported applied heads and local waiting work before its action. A recipient can manage enrollment after it receives and applies the final event. A final receipt acknowledgement, physical interruption tests, and recovery after a missing final event remain open release checks.

Emergency recovery uses a surviving unlocked device's contents to start a fresh sync identity and fresh group keys. The current implementation binds encrypted changes, transport state, and history to `vaultId`; create a new value for the new group. Keep the local records and photos. Do not reuse the old trust history under the new identity.

For a lost primary phone with one surviving tablet, the tablet stops sharing, becomes manager of its new group, and keeps its local vault copy. The replacement phone starts with an empty vault and pairs with the tablet using the same master password. Changes that reached the tablet before the split are available to the new phone. Changes only on the lost phone are not recoverable from the tablet. The lost phone keeps its old copy; the new group ID and keys prevent it from joining the tablet's new chain without a new enrollment.

Each remaining device must explicitly rejoin. Before replacing its old sync state, preserve and reconcile its local changes, including deletions and incomplete photo transfers. The current empty-destination enrollment flow is not sufficient for this recovery experience. A recovery wizard must never clear a populated device just to make pairing pass.

The lost phone retains its old contents. Devices that still belong to the old group can continue communicating until they move to the new one. Updates made only on the lost phone cannot be recovered from another device.

## Leaving and removal

Any active Android member can choose "Stop sharing on this device". It keeps its contents and creates a fresh independent group, and disables the old watch relationship. The old group does not learn a signed removal from this local action. Its manager must remove the departed identity later when group removal is implemented. If the manager leaves without transferring first, the others can still edit and sync but cannot manage membership in the old group. A survivor can also start a fresh group from its local copy and enroll a new empty phone. With two members, the existing peer-removal button uses the same local split.

For groups with three or more members, the manager must be able to remove another device while keeping the survivors together. This requires a signed removal, fresh random group keys, authenticated delivery only to survivors, and rejection of removed identities. Updated peers must apply membership changes before exchanging more record data. Offline survivors need a defined path for publishing legitimate edits made under an older key epoch. The manager should transfer authority before leaving if the old group must continue accepting membership changes.

Self-detach and committed group removal are different states. If the user erases local data before a leave request reaches the manager, the app cannot promise to send it later using credentials it has erased. Either acknowledge removal first or explain that the owner must remove the device on the manager. Retaining a signed leave request requires an explicit bounded outbox design.

Use inactivity as a review reminder. Do not automatically remove a tablet after 30 or 45 days. Removal stops future authorized sharing once peers learn it; it cannot erase existing remote copies.

## Implementation order and verification

| Work | Status | Required evidence |
| --- | --- | --- |
| Show managing role and require local password before hosting | Implemented in this slice | Wrong password creates no invitation; secondary hosting fails before listening; secondary editing remains available |
| Tablet create/edit pane and direct sidebar destinations | Implemented in this slice | Editor stays in right pane; draft survives resizing; navigation asks before discarding; narrow layout remains usable |
| Vault codes tile with Passwords and TOTP tabs | Implemented in this slice | Search each category; never render or search password contents; copy only the selected field; retain private authenticated activity and sensitive clipboard marking |
| Safe planned management transfer | Signed offer, acceptance, and commit implemented in source; release checks pending | Final receipt, interruption, old-manager restart, and physical peer tests |
| Recovery into a fresh group | Local split available on any active Android member; populated survivor rejoining pending | Keep records and photos; reconcile another surviving device's unsynced changes before it joins |
| Manager removes another while survivors stay together | Pending | Removed device gets no new keys; offline survivors keep edits |
| Common-password enrollment and update flow | Pairing equality check implemented; update flow pending | Incorrect-password and interruption tests, offline update, and rollback behavior reviewed and tested |
| Verified checkpoints and cleanup | Pending | Preserve conflicts, deletion knowledge, photo references, and offline edits through compaction |
| Physical-device release matrix | Pending | Pairing interruption, locked sync, photo interruption, storage pressure, and three-device convergence |

The UI work and enrollment gate do not change the current preview release status. [The execution checklist](../sync-steps.md) remains the release gate.

The Vault codes tile supports search and copy after its own unlock. It does not receive Android Autofill's authenticated request or field identifiers. Previous-app usage detection only suggests TOTP accounts. Fill and Link remain in the existing Autofill picker, which checks the destination app identity or exact website. See [Android's Autofill authentication contract](https://developer.android.com/reference/android/service/autofill/Dataset.Builder#setAuthentication(android.content.IntentSender)).

## Autofill coverage

Reviewed September 25, 2026 after a report about an IRS in-app login. That exact device and login have not been reproduced. IRS2Go links to account sign-in, but its public description does not establish which rendering path the user's version uses.

| Destination | Current behavior and limits |
| --- | --- |
| Native app login | Match the installed package and signing certificate. Recognized password or separate OTP forms can request unlock. A native username-only screen still has no suggestion. |
| Chrome and Brave HTTPS page | Verify the browser certificate and exact HTTPS origin. A different subdomain needs its own link. The browser must expose Android Autofill and use the selected external provider. |
| Custom Tab or Auth Tab | Browser settings and the provider that owns the tab matter. A browser-powered in-app page is not automatically an embedded WebView. Test the actual provider and version. |
| Embedded WebView | Accept recognized forms as belonging to the containing app. Never treat its reported web domain as a verified website. Explain app access before unlocking. Account suggestions require the app's package and certificate link. Username-first WebView screens can offer account selection. |
| Complex web form | Multiple candidate passwords/usernames, multiple forms/origins, frames, mixed OTP/login forms, and explicit insecure schemes are rejected. A hidden field is not a fill target. |
| Unverified browser | Rejected; adding browser support needs certificate and origin handling verification. |
| Custom widgets or app disables Autofill | No suggestion when Android provides no usable fields. Use the tile to copy or open the page in a supported browser. |
| Credential Manager | A separate provider setting controls password/passkey requests on supported Android versions. Enabling Autofill alone does not enable all Credential Manager flows. |

Autofill settings offer Keyboard suggestions, the default, or Account picker. Keyboard mode returns matching accounts after response-level authentication, with a final Choose another login suggestion. The keyboard controls available slots; Nuvori reserves one for the picker and returns at most eight accounts. Keyboards without inline support use Android's suggestion menu. The app does not request an automatic fill dialog. Codes and new-password generation still use the picker.

Autofill details are separate vault entries for name, email, mobile number and postal address fields. Their vault entries use the existing encrypted backup and signed Android sync path. An opt-in setting creates a separate Android Keystore encrypted copy on each device so these details can appear in keyboard or system suggestions without vault unlock. The copy excludes passwords and TOTP secrets. Turning the setting off removes the local copy. Changes received while this device's vault is locked reach the copy after this device unlocks and applies them. Nuvori fills only recognized fields; generic number fields are not treated as one-time codes. A recognized one-time-code field can show an unlock action in the keyboard, then opens the code picker and generates the TOTP when the user chooses it so an expired code is not left in a keyboard dataset.

Account picker mode stays open after unlocking. Rows show the username, saved title, and fixed-length password dots. Search and link require the existing destination confirmation. The optional picker suggestion starts a separate unlock; it does not keep a vault key in a shared cache. New-password fields expose generation; an ordinary sign-in field does not. The management action opens Nuvori with its normal vault lock rules. Picker dismissal is idempotent, so pause/stop callbacks cannot send an Autofill result twice.

Save requests use Android's form submission signals. A native test app that keeps its form visible must explicitly commit its Autofill context for Android to offer saving. For a two-step HTTPS browser login, the first username response delays saving; the password step can use the earlier field only when browser package, signing identity, and exact origin match. Nuvori still asks for confirmation and vault unlock before storing a new login. A site that hides Autofill fields or never commits its form can suppress Android's save prompt.

Brave's own password manager can also offer to save a login. A Brave prompt does not mean Android sent Nuvori a save request. The two-step save path has a focused Android state test, but a real Google sign-in through Brave has not been verified; test that flow on a physical phone before treating its save prompt as reliable.

WebView verification: an Android 16 emulator test loads a local dummy HTTPS-origin form in a separate app, rejects a wrong vault password, shows the embedded-app explanation, excludes an unlinked website-only login from authorized suggestions, and verifies the exact filled dummy username and password through JavaScript. This is not a verification of the IRS login or every WebView provider.

For Chrome, select Nuvori in Android's Autofill settings, then Chrome Settings > Autofill services > Autofill using another service, and restart Chrome. Seeing Google's suggestions does not prove that Chrome sent an Autofill request to Nuvori.

References: [Chrome provider settings](https://support.google.com/chrome/answer/142893?co=GENIE.Platform%3DAndroid&hl=en), [Android embedded browsers](https://developer.android.com/develop/ui/views/layout/webapps/in-app-browsing-embedded-web), [WebView form metadata](https://developer.android.com/reference/android/webkit/WebView), [Autofill presentations](https://developer.android.com/reference/android/service/autofill/Dataset.Builder), [IRS2Go](https://www.irs.gov/help/app).
