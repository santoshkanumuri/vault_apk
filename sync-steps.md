# Android sync implementation steps

Updated: September 24, 2026.

Status: implementation plan. The remaining work described here has not been completed by writing this document.

Implementation update, September 24, 2026: The Android implementation now limits active membership to four devices, uses a separate group content key and transport credential, carries a signed membership history, and has an emulator test for direct sync between a second and third phone. The LAN exchange now persists separate received and applied heads. Locked receivers acknowledge ciphertext only after protected storage, and the device page shows the peer's reported progress for this phone's changes. Password-only conflicts are visible in review without displaying the secret. Automatic sync has Pause, Resume, Sync now, and retries after Wi-Fi address or NSD failures. These are implementation and emulator results, not completion of the release checklist below.

Pairing test update, September 24, 2026: A tablet and phone reached matching-code confirmation, then stalled at "copying vault." The host did not close `SyncChannelOutput` after streaming its staged snapshot, so the receiver never saw the end marker. The host now closes that stream before waiting for `ready`. A bounded encrypted socket stream test and a complete snapshot enrollment socket test pass. Repeat the physical test with the corrected debug APK on both devices.

Discovery test update, September 24, 2026: A paired tablet and phone initially remained at "Looking for paired phones on Wi-Fi" and a new authenticator did not arrive. The service now holds a Wi-Fi multicast lock during NSD, prefers private IPv4 addresses returned by resolution, and reports discovery, address, and connection stages. It also listens on a stable vault-derived port and can retry a protected last-confirmed peer address. Existing pairs can enter the other device's current Wi-Fi IP address under Android devices without replacing their vaults. The user subsequently confirmed that their tablet and phone exchange edits. The wider physical-device matrix remains pending.

Photo test update, September 24, 2026: Live photo additions, edits, cover choices, and deletions now create signed changes. Photo bytes use separately encrypted, hash-checked files, and interrupted ciphertext transfers resume from a saved offset. A receiver waits for complete photo bytes before applying the photo change; later changes in the same author chain wait behind it. Emulator tests cover transfer, resume, editing, deletion, and a concurrent cover choice resolved explicitly. The user confirmed that photos sync between their tablet and phone. An oversized stale partial now resets on the next exchange. Large-photo limits, multi-device relay, disk-full handling, and safe history/blob cleanup remain pending.

2.0.0 preview update, September 24, 2026: The Android devices page groups pairing, automatic sync, paired-device help, and conflict review. The foreground service has selectable 30-second, 1-minute, 5-minute, and 15-minute check intervals. This changes retry cadence, not Android's background delivery guarantees. The release blockers below still apply.

Still blocking a full Android sync release: device removal with future-key rotation, recoverable enrollment acknowledgement, photo transfer validation across the physical-device matrix, explicit large import batches, typed dependency/error states, complete conflict choices, verified checkpoints and safe cleanup. Keep the current phone artifact labeled preview until those checks pass.

This file covers completion of Android-to-Android vault sync. It is the execution checklist for the current Android work. [The broader sync and recovery plan](docs/SYNC-RECOVERY-PLAN.md) also covers Windows, recovery, and the browser extension; some of its older checkboxes no longer reflect the current source.

## Contents

- [Outcome and scope](#1-outcome-and-scope)
- [Current implementation and gaps](#2-current-implementation-and-gaps)
- [Rules to preserve](#3-rules-that-every-step-must-preserve)
- [File map](#4-file-map)
- [Implementation order](#5-implementation-order-and-dependencies)
  - [Step 0: decisions and inventory](#step-0-record-the-remaining-decisions-and-mutation-inventory)
  - [Step 1: protocol and storage](#step-1-freeze-the-completed-protocol-and-extend-storage)
  - [Step 2: membership and three-device trust](#step-2-signed-membership-and-three-device-trust)
  - [Step 3: removal and key rotation](#step-3-device-removal-and-future-key-rotation)
  - [Step 4: enrollment and snapshots](#step-4-recoverable-enrollment-and-consistent-snapshots)
  - [Step 5: mutation capture](#step-5-capture-every-supported-local-change)
  - [Step 6: photo transfer](#step-6-resumable-encrypted-photos-and-attachments)
  - [Step 7: queues, acknowledgements, and locking](#step-7-durable-queues-acknowledgements-and-lock-ownership)
  - [Step 8: conflict review](#step-8-complete-conflict-review)
  - [Step 9: automatic sync and controls](#step-9-reliable-automatic-sync-and-useful-controls)
  - [Step 10: cleanup](#step-10-safe-history-and-attachment-cleanup)
  - [Step 11: verification and release](#step-11-verification-and-release)
- [Completion checklist](#6-final-completion-checklist)
- [First implementation session](#7-first-implementation-session)

## 1. Outcome and scope

The release is ready when three Android devices can join one logical vault, edit offline, reconnect on the same Wi-Fi, and exchange supported changes without repeated pairing or silent data loss. A locked device can receive encrypted changes, but applying them requires an unlocked vault. A removed device must be excluded from future keys and sessions once the removal is known to the participating devices.

### Required for the Android release

- Initial enrollment into an empty local vault, with explicit confirmation on both phones.
- At most four active Android devices per vault, including the current device. Revoked devices do not occupy a slot.
- Independent local master passwords, biometric settings, and lock settings.
- Two-way sync of entries, authenticators, passkeys, groups, relationships, favorites, ordering, photos, photo edits, and deletions.
- Automatic same-Wi-Fi sync with an ongoing notification, Pause, Resume, and an explicit Sync now action.
- Durable encrypted queues while locked, bounded storage, and recovery from interrupted transfers.
- Signed membership updates, enrollment of a third phone, device removal, and future-key rotation.
- Conflict review that shows enough information to make a choice and propagates that choice to every device.
- Per-device sync status, actionable errors, safe history retention, and verified database migrations.
- Tests on physical Android phones, including a third device and a tablet.

### Follow-up work

- Windows vault and Windows sync.
- Internet relay, cloud hosting, and cross-network discovery.
- Recovery Card creation and Recovery Card restore.
- Browser extension integration.
- Joining two independently populated vaults. The first release requires the joining vault to be empty. Do not silently replace or merge an existing populated vault.

Wear OS code sync remains a separate companion feature. It does not become a full vault peer in this work.

## 2. Current implementation and gaps

The following describes source inspected for this plan. Previous test results are evidence for their named cases, not proof that the complete product works on physical phones.

| Area | What exists | Work still required |
| --- | --- | --- |
| Pairing | J-PAKE, expiring setup link, QR scan, matching confirmation, socket exchange | Durable enrollment state, cancellation/crash recovery, signed membership, physical-device verification |
| Initial copy | Encrypted logical snapshot, staged restore, independent receiving database key | Stable photo snapshot, attachment manifests, bounded decompression, checkpoint support, interrupted commit recovery |
| Record changes | Signed entry, group, and passkey writers; authenticated receiver | Complete write audit, explicit import batches, photo operations, settings policy |
| Locked queues | Keystore-protected transport mirror and encrypted operation files | Separate received/applied progress, membership updates while locked, indexed reads, storage-pressure handling, crash tests |
| Transport | Framing, fresh session keys, pairwise transport secret, operation acknowledgements | Authentication between newly introduced peers, durable peer progress, epoch binding, typed failures |
| Automatic sync | Foreground service, NSD discovery, roughly 30-second retries, notification Pause | Reconnect and permission tests, lifecycle ownership, callback failure handling, retry classification, battery measurements |
| Conflicts | Current/incoming choice, merged version history, signed resolution | Complete field comparison, other entity combinations, photo conflicts, simultaneous resolutions, stale-review tests |
| Membership | Stored member identities and active-state checks | Signed membership history and propagation to every existing peer |
| Keys | Shared content-key state and pairwise secrets | Separate content keys from local database keys, multiple key epochs, recipient-specific key delivery, revocation |
| Storage cleanup | Tombstone, acknowledgement, and attachment tables | Safe checkpointing, acknowledgement policy, history compaction, blob garbage collection |
| UI | Pairing page, generic status, paired-device list, conflict cards | Sync now, per-peer progress, last success, removal, queue/storage state, useful error distinctions |

### Concrete issues to address

1. `SyncContentKey.kt` falls back to a copy of the local database key when shared-key state is missing. Stop using the database key as the long-term shared sync key.
2. `SyncContentKey.kt`, `SyncSnapshot.kt`, and `IncomingEntryChangeApplier.kt` assume key epoch `1`. Rotation cannot work until epoch lookup and validation replace that assumption.
3. `AndroidPairing.kt` stores a direct pairwise transport secret. If A pairs with B, then A pairs with C, B and C do not automatically obtain a safe direct authentication relationship. Do not distribute A-B's secret to C.
4. The host commits membership before receiving the final enrollment acknowledgement. A dropped connection can leave the two devices with different views of whether enrollment finished.
5. `SyncSnapshot.export()` holds a database transaction while streaming to the network. Move slow network I/O outside the transaction while keeping a consistent snapshot.
6. `LockedSyncStore.pending()` repeatedly reads and decrypts the outgoing history. Replace full-history scans with indexed, bounded reads before testing large vaults.
7. `LockedSyncStore.applyQueued()` catches application errors and leaves operations pending without distinguishing missing dependencies from permanent corruption.
8. `LanSyncService` retries several unrelated errors using the same message. Some NSD failure callbacks currently do nothing.
9. Current queue acknowledgements mean ciphertext was saved. They do not mean the receiving vault applied it or that history can be deleted.
10. Conflict summaries omit some entry fields. A password-only change can appear identical in the current review. Provide a proper comparison with controlled secret reveal.
11. Initial enrollment includes current photos through backup restore. Later photo changes have no complete operation-and-blob transfer path.
12. A successful build is not evidence of background discovery, crash safety, revocation, or three-device convergence.

## 3. Rules that every step must preserve

### Vault and lock boundaries

- SQLCipher remains the authoritative store for vault contents and content-decryption keys.
- Transport state outside SQLCipher contains only the minimum connection credentials, signed public membership state, progress metadata, and encrypted payloads needed while locked.
- A transport key must not decrypt a vault record, photo, passkey, or content-key envelope.
- Locking cancels plaintext work, clears pending UI previews and copied session keys, and prevents a late coroutine from repopulating the UI.
- Background sync never calls the user-activity timer or extends the inactivity deadline.
- Notification text, discovery announcements, logs, and diagnostic exports contain no entry titles, accounts, secrets, pairing codes, or content keys.
- Shared preferences may hold local service preferences such as Paused. They must not become the authoritative membership, revocation, or content-key store.

### Integrity and delivery

- Local record mutation and signed operation commit in one database transaction.
- Files must be staged and durable before a committed record refers to them.
- Incoming ciphertext is acknowledged as received only after durable storage.
- Applied progress advances only after the record transaction and required files are complete.
- Per-device sequence and hash-chain continuity must survive restart, replays, and compaction.
- Record version histories determine causal ordering. Wall-clock timestamps are for display only.
- Concurrent edits remain available until an explicit resolution includes the competing histories.
- Unknown versions, invalid signatures, altered keys, and broken chains fail closed. They do not trigger automatic trust replacement or automatic re-pairing.

### Scope and compatibility

- Keep this implementation Android-first and local-network-only.
- Use established cryptographic implementations. Do not implement a new key exchange, KDF, signature scheme, or chunk cipher.
- The current wire protocol is unfinished. Define one completed protocol version and reject unsupported network versions clearly. Do not add a parallel legacy network implementation.
- Preserving existing local vault data through database migration is required. A protocol change is not permission to reset the vault.
- Check which builds actually shipped before deciding how to retire preview pairing state.

## 4. File map

Paths in this table are existing files. Proposed new files in later sections are marked `new`.

| File | Responsibility and planned changes |
| --- | --- |
| [SyncProtocol.kt](app/src/main/java/com/privatevault/app/sync/SyncProtocol.kt) | Canonical operation/control formats, epoch fields, version bounds, fixtures |
| [DeviceIdentity.kt](app/src/main/java/com/privatevault/app/sync/DeviceIdentity.kt) | Device signing identity; bind additional transport and key-envelope public keys |
| [PairingCrypto.kt](app/src/main/java/com/privatevault/app/sync/PairingCrypto.kt) | Keep the enrollment PAKE; bind the complete negotiated enrollment transcript |
| [AndroidPairing.kt](app/src/main/java/com/privatevault/app/sync/AndroidPairing.kt) | Enrollment state machine, confirmation, cancellation, commit recovery |
| [SyncWire.kt](app/src/main/java/com/privatevault/app/sync/SyncWire.kt) | Bounded framing and versioned message decoding; authenticate the completed session contract |
| [SyncChannelStreams.kt](app/src/main/java/com/privatevault/app/sync/SyncChannelStreams.kt) | Bounded snapshot streaming and interruption behavior |
| [SyncSnapshot.kt](app/src/main/java/com/privatevault/app/sync/SyncSnapshot.kt) | Stable snapshot, membership proof, key envelopes, checkpoints, attachment references |
| [SyncContentKey.kt](app/src/main/java/com/privatevault/app/sync/SyncContentKey.kt) | Replace database-key fallback with explicit epoch-key lookup |
| [SyncDatabase.kt](app/src/main/java/com/privatevault/app/data/SyncDatabase.kt) | Membership history, epochs, envelopes, transfer progress, checkpoints, conflict references |
| [VaultDatabase.kt](app/src/main/java/com/privatevault/app/data/VaultDatabase.kt) | Schema migrations and atomic record/relationship/photo writes |
| [LocalEntryChangeWriter.kt](app/src/main/java/com/privatevault/app/sync/LocalEntryChangeWriter.kt) | Complete entry mutation capture and epoch-aware payload encryption |
| [LocalGroupChangeWriter.kt](app/src/main/java/com/privatevault/app/sync/LocalGroupChangeWriter.kt) | Group and relationship behavior, including deletion dependencies |
| [LocalPasskeyChangeWriter.kt](app/src/main/java/com/privatevault/app/sync/LocalPasskeyChangeWriter.kt) | Protected passkey creation/import/delete operations and immutable credential checks |
| [CaptureEntryUpserts.kt](app/src/main/java/com/privatevault/app/sync/CaptureEntryUpserts.kt) | Import and external-save capture with explicit batch boundaries |
| [IncomingEntryChangeApplier.kt](app/src/main/java/com/privatevault/app/sync/IncomingEntryChangeApplier.kt) | Epoch/membership checks, dependency handling, photo application, typed outcomes |
| [SyncTransferPlanner.kt](app/src/main/java/com/privatevault/app/sync/SyncTransferPlanner.kt) | Missing-operation planning, acknowledgement and checkpoint rules |
| [LockedSyncStore.kt](app/src/main/java/com/privatevault/app/sync/LockedSyncStore.kt) | Durable received queue, bounded indexes, atomic security-state updates |
| [LanSyncExchange.kt](app/src/main/java/com/privatevault/app/sync/LanSyncExchange.kt) | Control messages first, operation/blob batches, durable progress, three-device authentication |
| [LanSyncService.kt](app/src/main/java/com/privatevault/app/sync/LanSyncService.kt) | Android service/discovery lifecycle, retries, notification actions, network changes |
| [SyncConflictResolver.kt](app/src/main/java/com/privatevault/app/sync/SyncConflictResolver.kt) | Full review model, stale-choice protection, signed resolutions across entity types |
| [VaultViewModel.kt](app/src/main/java/com/privatevault/app/VaultViewModel.kt) | Unlocked application, mutation entry points, UI state, cancellation on lock |
| [VaultCodesActivity.kt](app/src/main/java/com/privatevault/app/VaultCodesActivity.kt) | Autofill/credential writes and reliable publication after successful save |
| [DeviceSyncUi.kt](app/src/main/java/com/privatevault/app/ui/DeviceSyncUi.kt) | Device list, sync controls, pairing, removal, status, conflict review |
| [EncryptedPhotoStore.kt](app/src/main/java/com/privatevault/app/security/EncryptedPhotoStore.kt) | Immutable staging, local encryption, thumbnail generation, safe file cleanup |
| [VaultBackup.kt](app/src/main/java/com/privatevault/app/backup/VaultBackup.kt) | Snapshot limits, bounded extraction, restore detachment, local-setting preservation |
| [AndroidManifest.xml](app/src/main/AndroidManifest.xml) | Only required service, notification, discovery, and network declarations |
| [app/build.gradle.kts](app/build.gradle.kts) and [proguard-rules.pro](app/proguard-rules.pro) | Reviewed dependencies, completed release version, minified wire-format verification |

## 5. Implementation order and dependencies

Complete each numbered step with its acceptance checks before marking it done.

| Step | Deliverable | Depends on |
| --- | --- | --- |
| 0 | Fixed scope, mutation inventory, membership/transport decisions | Current source audit |
| 1 | Completed protocol contract and schema migration | 0 |
| 2 | Signed membership and direct trust between three devices | 1 |
| 3 | Independent content keys, removal, and key rotation | 2 |
| 4 | Recoverable enrollment and consistent initial copy | 2, 3 |
| 5 | Complete record/import mutation capture | 1, 3 |
| 6 | Resumable encrypted photo transfer | 3, 4, 5 |
| 7 | Durable receipt/application progress and lock-safe lifecycle | 2 through 6 |
| 8 | Complete conflict review and resolution | 5 through 7 |
| 9 | Reliable automatic discovery and manual sync controls | 4, 7, 8 |
| 10 | Checkpoints, history retention, and storage cleanup | 3, 6, 7 |
| 11 | Physical-device validation and release artifacts | All previous steps |

Do not expand into Windows or a relay while these Android release gates remain open.

### Step 0. Record the remaining decisions and mutation inventory

#### Actions

1. List every production DAO write using repository search. Include callers in the main app, credential activity, autofill, imports, restore, photo transforms, and settings.
2. Assign each write one policy: signed record operation, explicit import batch, local-only state, or detached restore baseline.
   The current source inventory and its gaps are recorded in [Android sync write inventory](docs/ANDROID-SYNC-WRITE-INVENTORY.md).
3. Record the exact shipped schema/protocol versions from release artifacts. Source currently declares schema 16, phone version 1.9.11/code 39, and target SDK 36. Do not infer that those source changes shipped.
4. Support at most four active Android devices in one vault, including the current device. Enforce the bound in membership writes, pairing, snapshot parsing, UI, and tests. Retain historical revoked identities and their causal counters beyond this active-device limit. Three devices remain required in the release test matrix.
5. Decide who may enroll and revoke devices before implementing signed membership.
6. Decide how a member introduced through another phone proves possession of its own private transport identity.

#### Proposed membership policy

Use one designated managing device for membership changes in the first Android release. Ordinary record editing and direct data sync remain available on every active device. The managing device is needed to add/remove devices and transfer that management role.

This is a proposed product decision, not an existing user requirement. It gives membership changes one signed order and avoids inventing distributed consensus. The cost is that management operations need that device. Make this visible in the device list.

Provide an explicit role-transfer flow while the old managing device is available. If it is lost, a surviving unlocked device can start a new sync group with a new vault identity and fresh keys, retaining its local records and requiring remaining phones to enroll again. Clearly explain that unsynced data on the lost device cannot be recovered this way. Do not silently elect a new manager for the old group.

Before replacing a remaining phone's old group, preserve its local vault and unsynced changes in an encrypted backup. The new-group flow must include an explicit data-preservation/reconciliation step before the empty-destination enrollment requirement can be satisfied. Do not automatically clear populated phones to make re-enrollment pass.

If management from any offline phone is required instead, design and test concurrent membership and rotation rules before Step 1. Do not choose a winner using timestamps or device IDs.

#### Proposed transport direction

Keep J-PAKE for initial user-approved enrollment. For subsequent direct sessions between any two admitted devices, prefer a standard mutually authenticated transport such as TLS 1.3 with device certificates bound into signed membership.

Run a small Android Keystore/provider feasibility test before committing to that transport change. Check client and server authentication, certificate generation, private-key availability while locked, and future Windows support. The exact certificate and recipient-key suites need documented review. The Android TLS guide is a starting reference, not a complete design for this peer protocol: [Android network security guidance](https://developer.android.com/privacy-and-security/security-ssl).

If the existing PAKE-based transport is retained, document a reviewed way to establish a unique B-C secret using authenticated B and C identities. Sending A-B's reusable secret to C is not acceptable. Do not implement both transports as fallbacks.

#### Acceptance checks

- [ ] Every current DAO write has a recorded policy.
- [ ] Management authority, transfer, loss, and offline behavior are explicit.
- [ ] New-member authentication does not disclose another pair's reusable secret.
- [ ] Prototype demonstrates the selected transport on the minimum supported Android version.
- [ ] No application data or existing vault passwords are reset by the chosen migration.

### Step 1. Freeze the completed protocol and extend storage

#### Files to change

`SyncProtocol.kt`, `SyncWire.kt`, `SyncDatabase.kt`, `VaultDatabase.kt`, `SyncContentKey.kt`, `SyncSnapshot.kt`, and protocol tests/fixtures.

#### Protocol changes

1. Define the completed wire version explicitly. A new epoch-bearing canonical operation must not reuse the old canonical format number with different bytes.
2. Include `keyEpoch`, membership-head reference, optional import-batch identity, and signed attachment references in the operation contract where needed.
3. Define message types for membership history, recipient key envelopes, received/applied frontiers, operation batches, blob requests/chunks, checkpoints, completion, and typed rejection.
4. Bind vault ID, both device identities, protocol version, negotiated capabilities, and membership state to the authenticated session.
5. Use exact integer parsing for sequences, counters, epochs, and lengths. Preserve the fix for Gson converting generic numeric values to `Double`.
6. Require strict field/type validation and bounded collections before constructing objects. Specify unknown-field policy per message version.
7. Share canonical byte fixtures across encoders, decoders, and signing verification. Keep Kotlin fixtures ready for a later Windows implementation.

#### Storage changes

Names below are proposed tables or extensions; reuse current tables where their meaning fits.

| Storage | Required fields or changes |
| --- | --- |
| `sync_membership_events`, new | Vault, control sequence, previous event hash, action, issuer, subject, identity bindings, role, signature, event hash |
| `sync_memberships` | Materialized current membership plus signed event reference and transport/envelope public-key bindings |
| `sync_vault_state` | Active epoch, accepted membership head, managing identity, checkpoint reference |
| `sync_key_epochs`, new | Vault/epoch, content key protected by SQLCipher, creating control event, read/write/retirement state |
| `sync_key_envelopes`, new | Vault/epoch/recipient, reviewed envelope format, authenticated recipient binding, ciphertext, issuer signature |
| `sync_operations` | Completed-format fields, key epoch, batch ID, attachment reference commitment |
| `sync_peer_acknowledgements` | Distinct durable-received and applied positions, source-chain hashes, membership generation |
| `sync_batches`, new | Import identity, type, expected operation count, completion state, resulting frontier |
| `sync_attachment_manifests` | Immutable blob version, owner, epoch, cipher/format version, sizes, chunk hashes/root commitment |
| `sync_blob_transfers`, new | Transfer identity, manifest hash, verified chunk ranges, bytes reserved, state |
| `sync_checkpoints`, new | Snapshot hash, membership head, source sequence/hash anchors, causal state, creation/approval state |
| `sync_peer_status`, new | Last authenticated contact, last successful exchange, applied progress, last error code; no vault contents |

The locked store needs a device-protected index for encrypted queue items and verified public control state. It must not mirror the plaintext epoch-key table.

#### Migration procedure

1. Increment from the actual next unshipped schema version after checking existing artifacts.
2. Add tables and indexes transactionally. Preserve entries, groups, photos, passkeys, security settings, and existing encrypted backup behavior.
3. Generate a separate random shared content key when establishing the completed sync group.
   Existing signed operations may use the local database key through the preview fallback. Preserve and migrate that history through the checkpoint/re-enrollment procedure before removing the fallback; changing the key alone would leave earlier ciphertext unreadable.
4. Convert preview-only sync state through a defined checkpoint/re-enrollment procedure. Preserve historical signatures as historical data; never re-sign another device's old operations as if they originated locally.
5. Require unsupported preview peers to update and explicitly reconnect. Do not maintain a second legacy network decoder.
6. Verify migration interruption leaves a usable vault and never creates two competing local device sequences.

#### Acceptance checks

- [ ] Round-trip and signature fixtures cover every completed message type.
- [ ] Unsupported versions, integer overflow, negative lengths, deep nesting, and oversized collections are rejected.
- [ ] Released database versions migrate without record or photo loss.
- [ ] Release minification does not rename serialized fields or lose numeric precision.
- [ ] A fresh vault and a migrated vault establish equivalent completed sync state.

### Step 2. Signed membership and three-device trust

#### Files to change/add

- Existing: `DeviceIdentity.kt`, `AndroidPairing.kt`, `SyncSnapshot.kt`, `LanSyncExchange.kt`, `LockedSyncStore.kt`, `SyncDatabase.kt`.
- New: `SyncMembershipManager.kt` for control-event validation and application.
- New, if the transport decision selects it: `SyncTransportIdentityStore.kt` and `AuthenticatedSyncConnection.kt`.

#### Implementation

1. Create a signed genesis membership event when the completed sync group is established.
2. Bind each member's device ID, signing public key, transport identity, recipient envelope public key, role, and vault ID in admission records.
3. Verify issuer authority at the preceding membership state. A newly arriving record cannot grant its own issuer authority.
4. Keep immutable signed events and a materialized current-member table. Detect two different events claiming the same control-chain position; stop management updates and report the fork.
5. Exchange missing membership history before ordinary vault operations. Validate it against the locally pinned genesis/control history, not against an identity merely advertised by the remote device.
6. When A admits C, deliver C's admission proof to B. B verifies it, and B/C authenticate their own private identities directly. A's transport credential must not be sufficient for C to impersonate B.
7. Preserve original author signatures when forwarding an operation through another member.
8. Separate a retired member's historical signing key from permission to open new sessions. Old valid history may still need verification after a member is removed.
9. Apply accepted public control events to the locked mirror atomically. Defer encrypted content-key opening until unlock.
10. Bind active connections to a membership generation. Re-check that generation before each batch and invalidate affected sockets when membership changes.

#### Required three-device scenarios

- A pairs with B; A pairs with C; B learns C's admission through A; B and C sync after A disconnects.
- B receives a valid A-authored operation through C and keeps A as its author.
- C presents an altered admission or substituted transport key; B rejects it.
- A member reconnects with an older membership view and catches up before exchanging ordinary records.
- An unknown LAN device cannot obtain record data by advertising the expected service name.
- Device names are editable display labels, never authentication identities.

#### Acceptance gate

Three devices establish direct authenticated sessions without a new manual pairing for each pair, and neither a forged admission nor another member's identity claim is accepted.

### Step 3. Device removal and future-key rotation

#### Files to change/add

- Existing: `SyncContentKey.kt`, all local change writers, `IncomingEntryChangeApplier.kt`, `SyncSnapshot.kt`, `LockedSyncStore.kt`, `LanSyncExchange.kt`, `DeviceSyncUi.kt`.
- New: `SyncKeyManager.kt` and `SyncRevocationManager.kt`.

#### Separate the key roles

| Key | Location | Purpose |
| --- | --- | --- |
| Local database key | Existing password/biometric protection | Opens only this installation's SQLCipher database |
| Device signing key | Existing protected device identity | Signs this device's operations and authorized control changes |
| Device transport private key | Android Keystore or equivalent protected local store | Authenticates connections while locked |
| Recipient envelope private key | Protected so it is usable only through an unlocked vault session | Opens content-key envelopes addressed to this device |
| Content key for epoch N | SQLCipher only while stored | Encrypts record payloads and protects attachment keys for that epoch |
| Session traffic keys | Session memory | Protects one network connection |

Do not use the background transport private key to unwrap content keys. Otherwise possession of background state would cross the vault-lock boundary.

#### Removal sequence

1. Require an unlocked authorized managing device and explicit confirmation naming the device being removed.
2. Create a signed control change that removes the member, identifies the prior membership head, and advances the active key epoch.
3. Generate a fresh random content key using the chosen library. Do not derive it from an old key the removed device already possesses.
4. Create authenticated recipient-specific envelopes for every remaining active device. Use the reviewed public-key envelope suite selected in Step 0.
5. Commit the control event, new key state, and outgoing envelopes transactionally.
6. Update the device-protected membership mirror and close removed-peer sessions before sending more batches. Crash recovery must reconcile this transition before restarting transport.
7. Exchange revocation/control state before record data on reconnect. Locked peers verify the control event, stop serving the removed peer, and queue their unopened envelopes.
8. After unlock, an active device opens its envelope and begins writing only under the current epoch.
9. Keep the keys needed to read retained historical operations, conflicts, and attachments. Retire an old key only after the retention rules in Step 10 allow it.

#### Offline and history policy

- Revocation cannot be learned instantly across a network partition. A phone that has not received the removal may still behave under its older membership state. State this limitation accurately in the UI/help.
- Once a phone learns removal, it must reject new sessions from that device and enforce the signed accepted-history cutoff for its operations.
- Define cutoffs by device sequence and hash, never by wall-clock time. Preserve already accepted historical signatures without accepting a new suffix from the removed author.
- Returning active devices may have legitimate offline work under an old epoch. Define an explicit catch-up path that preserves that work and publishes it under the current policy after unlock. Do not silently discard it, and do not create an unauthenticated exception for removed peers.
- Rejoining after removal requires a fresh admission and fresh credentials. Replaying an old membership event must not restore access.
- Removal cannot erase plaintext or ciphertext already copied to another device.

#### Acceptance checks

- [ ] Removal before a session, during a batch, and while a peer is locked stops further authorized transfer once observed.
- [ ] A removed device cannot open new-epoch envelopes or receive new-epoch data from an updated peer.
- [ ] Restart between database commit and mirror update never resumes using stale admission state.
- [ ] Active offline work is preserved through rotation; removed-device work follows the documented cutoff.
- [ ] Multiple old epochs remain readable until safely retired.
- [ ] Managing-device transfer and the lost-manager new-group flow preserve local vault contents.

### Step 4. Recoverable enrollment and consistent snapshots

Progress: enrollment now stages its encrypted snapshot in app-private cache before writing to the socket. The vault transaction ends before a slow peer can block the send. Photo versions still need to be pinned, and enrollment commit recovery remains pending.

#### Files to change/add

`AndroidPairing.kt`, `SyncSnapshot.kt`, `SyncChannelStreams.kt`, `VaultBackup.kt`, `EncryptedPhotoStore.kt`, and a new `SyncEnrollmentStore.kt`.

#### Enrollment state machine

Use explicit durable states such as `offering`, `confirmed`, `snapshotStaged`, `commitPrepared`, `committed`, `acknowledged`, `cancelled`, and `expired`. Only retain secrets that are needed for bounded recovery, encrypted at rest.

1. Give enrollment one random ID and bind it to both device identities, vault ID, session expiry, and snapshot digest.
2. Persist a pending admission before committing active membership. Define precisely which message makes the membership decision durable.
3. Treat commit requests and acknowledgements as idempotent. A lost final acknowledgement must lead to a status query, not a second enrollment or silent rollback on only one phone.
4. Cancellation before durable commit removes staged content and pending trust.
5. Cancellation after durable commit reports the actual joined state and offers explicit removal if desired. Do not claim that both databases can be rolled back atomically across a failed connection.
6. On app restart, reconcile incomplete enrollment records using authenticated status exchange, with bounded expiry and cleanup.

#### Snapshot changes

1. Capture records, causal state, membership head, key envelopes, attachment references, and source frontiers at one logical point.
2. Use a short transaction to capture that point and pin immutable file versions. Release the transaction before slow socket output.
3. Stream an encrypted staged snapshot. Do not stage plaintext vault exports in shared storage or cache files.
4. Enforce compressed input, expanded output, per-file, total-file-count, metadata-size, and nesting limits before allocating or writing unbounded data.
5. Check archive paths, duplicate IDs, link targets, photo ownership, epoch references, record clocks, head/hash consistency, and membership proofs.
6. Commit the joining database and prepared local photo references only after complete validation. Retain its master password and local security settings.
7. Use checkpoints rather than requiring every historical operation forever in a single metadata JSON object. The current 16 MiB metadata cap must produce a recoverable error until checkpoint support exists.
8. Keep backup restore as a separate new-vault baseline. It must clear stale transport mirrors and require explicit re-enrollment.

#### Acceptance checks

- [ ] Wrong code, expired session, identity substitution, replay, and three failed attempts are rejected.
- [ ] Crash/cancel at every state leaves either no committed enrollment or an identifiable committed enrollment that can resume.
- [ ] Disconnect immediately before and after final acknowledgement does not create duplicate membership.
- [ ] Editing or rotating a photo while a snapshot is being sent cannot mix its old metadata with new bytes.
- [ ] Archive size/path attacks are rejected with staged files removed.
- [ ] Joining preserves local password and security settings and refuses a populated destination.

### Step 5. Capture every supported local change

#### Files to change

The local writers, `CaptureEntryUpserts.kt`, `VaultViewModel.kt`, `VaultCodesActivity.kt`, `VaultDatabase.kt`, and the import callers found in Step 0.

#### Data policy

| Data or action | Policy |
| --- | --- |
| Entry fields, notes, passwords, TOTP configuration | Signed encrypted record operations |
| Passkey private credential material | Encrypted payload only; validate credential structure and identity consistency |
| Groups/folders and entry membership | Signed operations with explicit dependency behavior |
| Favorites, tags, user-selected record ordering | Sync as user-visible vault data |
| Last-opened timestamps, transient selection, search text | Keep local; exclude from conflict-causing synchronized payloads |
| Photos, transforms, cover selection | Signed photo/cover operations referencing immutable encrypted blobs |
| Master password, biometrics, lock intervals | Keep local to each device |
| NFC availability, theme, notification permission, sync pause | Keep local for the first release |
| Watch pairing and watch-sync enablement | Keep local to the phone that manages the watch |
| CSV/authenticator/credential imports | One explicit import batch with deterministic member operations |
| Encrypted backup restore | New local vault identity and detached sync baseline |

#### Implementation details

1. Put each record write, causal-state update, device-head increment, and signed operation in one transaction.
2. Validate the local device is still permitted to author operations before writing. A removed device must not keep creating apparently current-group writes without an explicit detached/local policy.
3. Assign an import batch ID before import. Record the expected members and completion state. Retry must reuse stable mutation identities or recognize the completed batch.
4. Publish only committed batches. A receiver must not report full application while required members or relationships remain missing.
5. Define deletion behavior for linked authenticators, groups, photos, and covers. A missing parent should produce a dependency or conflict outcome, not a skipped operation that stalls the whole author chain forever.
6. Serialize local sequence allocation across main-vault and credential-provider database connections. Never allocate a sequence from an in-memory cached head.
7. Make outbox reconstruction possible from committed database operations after a crash between save and transport publication.
8. A transport publication failure must not report an already saved credential as unsaved. Report saved-with-sync-pending and retry from durable state.
9. Do not create new outgoing operations merely because an incoming record was applied. Preserve the incoming author and mutation ID.

#### Acceptance checks

- [ ] Each audited mutation has a test for its operation count, content, and rollback behavior.
- [ ] Two simultaneous local writers cannot commit the same source sequence.
- [ ] Import interruption/retry does not duplicate entries or expose partial relationships as completed.
- [ ] Local-only settings and last-opened timestamps do not cause cross-device conflicts.
- [ ] Failed outbox publication is recovered without losing the committed mutation.

### Step 6. Resumable encrypted photos and attachments

#### Files to change/add

- Existing: `EncryptedPhotoStore.kt`, photo methods in `VaultViewModel.kt`, `VaultDatabase.kt`, `SyncDatabase.kt`, `SyncSnapshot.kt`, `LanSyncExchange.kt`, `LockedSyncStore.kt`.
- New: `LocalPhotoChangeWriter.kt`, `SyncBlobStore.kt`, and `SyncBlobTransfer.kt`.

#### Representation

- Keep a stable photo ID for the logical attachment and a new immutable blob/version ID for every edited image.
- Keep local encrypted filenames out of the cross-device identity. Each phone has its own locally encrypted image and thumbnail files.
- Use a random per-blob data key and a reviewed streaming/chunk encryption format. Protect that key under the appropriate content epoch.
- Commit chunk ordering, lengths, algorithm/version, epoch, and ciphertext hashes in a signed manifest. Ordinary operations must authenticate the referenced manifest.
- Hash ciphertext for transport addressing. Do not advertise raw-photo hashes, filenames, or thumbnails through discovery.
- Authenticate chunk position and attachment identity so valid chunks cannot be swapped between photos or reordered.
- Choose one bounded chunk size after memory measurements, initially evaluate 256 KiB. Pin final limits in the protocol and test exact boundaries.

#### Write and transfer flow

1. Stage a new immutable image version and encrypted transfer representation.
2. Verify the image can be decoded with bounded dimensions/allocation. Generate a local thumbnail under the local key.
3. Commit photo metadata, manifest, operation, and record state in one transaction after files are durable.
4. Send manifest/operation metadata first, then request only missing chunks.
5. Save chunks in a transfer-specific staging directory, verify hashes, and durably record completion ranges before acknowledging them.
6. Bound per-peer and total staged bytes. Reserve capacity before accepting a chunk; release reservations on cancellation and cleanup.
7. A locked phone may verify public signed manifests and ciphertext hashes and save encrypted chunks. It may not open the blob key or decode an image.
8. After unlock, verify AEAD tags and the complete manifest, create local encrypted files, and transactionally apply photo references.
9. Keep the old visible photo until its replacement is complete. If the parent entry is new, show a clear pending-photo state rather than claiming complete sync.
10. Resume verified ranges after disconnection or process death. Do not restart a large photo from byte zero on every retry.

#### Photo-specific consistency

- Represent cover selection once per parent entry, using a photo ID, to avoid concurrent `isCover` booleans yielding two covers. Materialize the existing booleans locally if needed.
- Transform operations refer to a new blob version; do not modify a blob already referenced by a signed operation or snapshot.
- Entry deletion must retain attachment data needed by unresolved conflicts and offline peers until Step 10 allows cleanup.
- Concurrent photo replacement and deletion must preserve both candidates for review.

#### Acceptance checks

- [ ] Add, rotate, crop, replace, select cover, delete photo, and delete parent entry converge across three devices.
- [ ] Interrupt at every chunk boundary and resume only missing chunks.
- [ ] Corrupted, reordered, duplicated, truncated, or cross-photo chunks are rejected.
- [ ] Disk-full and process-death tests leave the old image readable or a clearly pending new image.
- [ ] No plaintext image is written by the locked/background path.
- [ ] Initial enrollment and later photo changes use compatible manifest/checkpoint rules.

### Step 7. Durable queues, acknowledgements, and lock ownership

#### Files to change/add

`LockedSyncStore.kt`, `LanSyncExchange.kt`, `SyncTransferPlanner.kt`, `IncomingEntryChangeApplier.kt`, `VaultViewModel.kt`, and a new `SyncCoordinator.kt` if needed to centralize ownership.

#### Separate progress meanings

| State | Meaning | What it permits |
| --- | --- | --- |
| Received | Valid signed ciphertext/control metadata is durable | Sender can stop retransmitting that item for this session |
| Waiting | Missing dependency, blob, key epoch, or unlock | Receiver retains it and shows why it cannot yet apply |
| Applied | Vault transaction committed; required files verified | Receiver can advance its applied frontier |
| Checkpoint-covered | A verified checkpoint and retention policy replace old history | Eligible history can be pruned |

Do not use Received as proof that the record is visible or eligible for deletion from every sender.

#### Implementation

1. Add indexed lookup by vault, author, sequence, hash, and state. Read at most one bounded batch per request.
2. Repair the atomic-file/index boundary on startup. Recover `.bak`/staged writes safely; do not discard acknowledged ciphertext because its index was one write behind.
3. Return typed application outcomes: applied, replay, obsolete, conflict, waiting for dependency, waiting for epoch, rejected signature, unsupported version, corrupt payload, storage full.
4. Retry dependency waits when their missing item arrives. Quarantine permanent failures and expose a safe action; do not swallow them into an endless loop.
5. Re-check membership and epoch policy inside the transaction that applies a record.
6. Let one coordinator own publication, incoming application, and lifecycle cancellation. The service owns sockets/ciphertext, while the unlocked session owns content keys and plaintext application.
7. Use an unlock-session generation token. A job started for an older generation cannot update UI or start another transaction after lock/reopen.
8. On lock, cancel and join plaintext jobs at a defined safe boundary, clear previews/keys, and close the database after transactions finish or roll back. Avoid blocking the main thread on arbitrary network I/O.
9. On restore/reset/group replacement, invalidate the old mirror generation, close old sockets, remove old queues, then expose the new state. A late publication job must not recreate the old mirror.
10. Reserve capacity for control messages so an ordinary full inbox cannot prevent revocation updates from being received.

#### Acceptance checks

- [ ] Crash before receipt, after receipt, after database commit, and before mirror refresh recovers without loss or duplicate mutation.
- [ ] Screen-off/manual lock during application clears all vault UI and prevents late repopulation.
- [ ] A full queue gives a bounded, recoverable error and remains able to process essential control state.
- [ ] Missing group/photo/epoch dependencies unblock when supplied.
- [ ] Persistent malformed input does not trigger unlimited CPU work or retries.

### Step 8. Complete conflict review

#### Files to change/add

`SyncConflictResolver.kt`, `IncomingEntryChangeApplier.kt`, `SyncDatabase.kt`, `DeviceSyncUi.kt`; optionally a dedicated `SyncConflictUi.kt` once the review no longer fits the settings page cleanly.

#### Implementation

1. Show entity type, source device, current/incoming change descriptions, and which fields differ.
2. Keep passwords, TOTP seeds, and private credential material concealed by default. Use the app's existing authenticated reveal conventions for fields that may be revealed. Never render raw passkey private-key JSON.
3. Do not silently truncate the information needed to choose a version. Provide a scrollable detail view for long notes and field differences.
4. Support current/incoming choices for entries, groups, relationships, photos, and deletions. Explain when choosing deletion removes an entire record.
5. Preserve immutable passkey credential identity. A conflicting private key under the same credential ID needs a defined reject/recovery policy; do not overwrite it through the generic entry resolver.
6. Resolve using the union of the histories actually reviewed, then add the local author increment and sign one new operation.
7. Validate the displayed current-version token inside the resolution transaction. If the record changed, require a fresh review.
8. Resolve only conflicts causally covered by the new operation. Additional concurrent versions remain unresolved.
9. Two phones may resolve the same conflict differently while offline. Preserve the resulting resolution conflict; never select the later timestamp automatically.
10. Add a conflict indicator in normal vault navigation so users do not have to discover it inside device settings.

#### Acceptance checks

- [ ] Password-only changes are distinguishable without exposing secrets by default.
- [ ] Edit/edit, edit/delete, delete/delete, multiple concurrent branches, and simultaneous resolutions converge after explicit choices.
- [ ] A stale displayed review cannot overwrite a newer unseen edit.
- [ ] Conflict previews and revealed values disappear on lock and rotation through a locked state.
- [ ] Unresolved conflict attachments remain available through storage cleanup.

### Step 9. Reliable automatic sync and useful controls

#### Files to change

`LanSyncService.kt`, `LanSyncExchange.kt`, `VaultViewModel.kt`, `DeviceSyncUi.kt`, `AndroidManifest.xml`, and the coordinator introduced in Step 7.

#### Discovery and lifecycle

1. Keep NSD service advertisements opaque. Device labels appear only after authenticated membership is established.
2. Bind sockets to the selected Wi-Fi network. Test IPv4, IPv6, interface changes, hotspots, VPN coexistence, and Wi-Fi without internet.
3. Serialize discovery/register/resolve state. Handle registration failure, discovery failure, lost services, stale addresses, and late callbacks from an old network generation.
4. The current lexical instance-name rule reduces simultaneous connection attempts but relies on discovery symmetry. Use authenticated device identities plus a bounded collision/backoff rule, and allow recovery when only one side discovers the other.
5. Trigger a check after committed local changes, network reconnection, successful enrollment, and Resume. Periodic retry is the fallback.
6. Use bounded exponential backoff with jitter for transient network failures. Stop automatic retries for invalid signatures, revoked peers, unsupported protocols, and permanent corruption until state or user action changes.
7. Keep one operation pipeline for Sync now and automatic sync. Do not create separate implementations with different validation.
8. Preserve Pause across process restart and app updates. Manual Sync now can run one foreground attempt without silently re-enabling permanent background service.
9. Define reboot behavior explicitly. Proposed first release: resume when Nuvori is next opened after reboot, with a clear paused/not-running status until then. Add boot startup only if required and verified against current Android restrictions.
10. Do not promise an exact 30-second interval or guaranteed operation after force-stop. Record and test actual scheduling behavior.

Android NSD registration, discovery, and cleanup behavior should follow the platform lifecycle guidance: [Network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd).

#### Service and permissions

The current manifest uses a `connectedDevice` foreground service. Verify that the declared type, runtime behavior, permission prerequisites, notification flow, and Play declaration match the shipped implementation. Recheck the platform rules at release time: [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

Keep notification permission denial recoverable. Explain that continuous background sync is paused and leave foreground Sync now available. Test the current target SDK and future target changes against the platform's local-network access rules: [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

#### Settings layout

Provide one Android devices page containing:

- Automatic sync enabled/paused status, with an accessible switch or explicit Pause/Resume controls.
- Sync now and progress for the current attempt.
- Current Wi-Fi availability and permission state.
- Per-device name, identity fingerprint, managing/member role, last authenticated contact, last complete sync, and pending received/applied counts.
- Add device, rename display label, view identity, remove device, and transfer management where authorized.
- A conflict count that opens the dedicated review.
- Queue/storage state with actions such as Unlock to apply, Retry, Update Nuvori, or review a disconnected device.

Use last-complete-sync only after a final exchange confirms relevant applied frontiers and required attachments. A peer being reachable or receiving ciphertext is a different status.

#### Acceptance checks

- [ ] Both apps closed, both locked, one unlocked, and both unlocked behave as documented.
- [ ] Wi-Fi leave/rejoin and process restart recover without another pairing prompt.
- [ ] Notification denial, Pause, Resume, manual Sync now, and reboot follow the documented policy.
- [ ] A protocol/signature failure stops the retry storm and identifies the required action without exposing vault data.
- [ ] Discovery survives name collision, stale addresses, asymmetric discovery, and network changes.
- [ ] Measure idle CPU, wakeups, network traffic, and battery impact on physical hardware before choosing final retry timing.

### Step 10. Safe history and attachment cleanup

#### Files to change/add

`SyncDatabase.kt`, `SyncTransferPlanner.kt`, `SyncSnapshot.kt`, `LockedSyncStore.kt`, `SyncBlobStore.kt`, and a new `SyncCheckpointManager.kt`.

#### Retention rules

1. Track each active peer's durable applied frontier separately from receipt acknowledgements.
2. Never remove an operation solely because one peer acknowledged receiving it or because it is old.
3. Create a consistent encrypted checkpoint containing records, causal versions, tombstones/deletion summaries, unresolved conflicts, attachment references, membership state, and per-author sequence/hash anchors.
4. Authenticate the checkpoint and its anchors through the selected protocol. A hash alone does not establish a trusted baseline.
5. Retain the suffix after each checkpoint anchor. Update snapshot validation so compacted history begins from a verified anchor rather than requiring sequence 1 forever.
6. Prune a prefix only when active-peer progress and the documented checkpoint policy make that prefix unnecessary.
7. An indefinitely offline peer either keeps the required history pinned or is explicitly moved to needs-rebase/removed state. Do not hide that decision inside an age-based cleanup job.
8. Rebase must preserve the returning peer's unsynced work before replacing its old baseline. Stage it and reintroduce valid changes through the current conflict/membership policy.
9. Retain deletion knowledge in checkpoints so an old device cannot resurrect a deleted record by returning an obsolete copy.
10. Delete a blob only when no current record, unresolved conflict, pending transfer, retained operation, or retained checkpoint refers to it.
11. Keep an old epoch key while any retained ciphertext still needs it. Key deletion follows the same reference audit.
12. Run cleanup in bounded batches. Crash after deleting one file must not corrupt the database or invalidate a live reference.

#### Acceptance checks

- [ ] A long-offline device returns after compaction and catches up through a verified checkpoint without losing its local edits.
- [ ] Deleted records remain deleted unless explicitly restored by a newer valid operation.
- [ ] Conflicted/deleted photos needed for review survive cleanup.
- [ ] Broken or untrusted checkpoints do not replace the current vault.
- [ ] Outbox, staged chunks, and retained history have visible limits and a documented response to storage pressure.
- [ ] Crash injection across checkpoint publication and garbage collection leaves a recoverable state.

### Step 11. Verification and release

#### Automated test ownership

| Existing suite | Extend it to cover |
| --- | --- |
| `SyncProtocolTest.kt` | Completed canonical fixtures, epochs, control events, exact counters, malformed bounds |
| `DeviceIdentityTest.kt` / `AndroidDeviceIdentityStoreTest.kt` | Key binding, altered identities, restart, protected transport/envelope roles |
| `PairingCryptoTest.kt` / `SyncWireTest.kt` | Wrong peer, replay, version mismatch, transcript substitution, cancellation, frame limits |
| `SyncTransactionTest.kt` | Every mutation family, simultaneous local writers, rollback and explicit import batches |
| `SyncSnapshotTest.kt` | Independent local passwords, current/old epochs, complete photos, staged commit failures, size limits |
| `LockedSyncExchangeTest.kt` | Received versus applied progress, restart windows, dependency waits, multi-peer forwarding, revocation |
| `VaultMigrationTest.kt` | Every actually released schema to the final schema; local data/settings preserved |

Add focused suites for membership, key rotation, blob transfer, checkpoint cleanup, service lifecycle, and conflict UI. Proposed names: `SyncMembershipTest`, `SyncRevocationTest`, `SyncBlobTransferTest`, `SyncCheckpointTest`, `LanSyncLifecycleTest`, and `SyncConflictUiTest`.

#### Required physical-device matrix

| Scenario | Required result |
| --- | --- |
| A enrolls B, then C | Same supported records and membership on all three |
| A offline, B and C connected | B and C exchange changes directly |
| Each device edits a different record offline | All changes survive and converge |
| Two devices edit the same record | Visible conflict; explicit resolution converges |
| One deletes while another edits | No silent resurrection or silent discarded edit |
| Phone locks during receive/apply | Ciphertext can remain queued; vault content disappears; no late UI leak |
| Both apps closed on the same Wi-Fi | Background behavior matches the documented service policy |
| Wi-Fi drops midway through a photo | Verified chunks survive and only missing chunks resume |
| Receiving storage fills | Existing vault remains usable and pending work is reported |
| Device removed while connected | Updated peers close its sessions and reject later data |
| Active device offline during rotation | Its work survives a controlled catch-up after unlock |
| Process killed at commit/ack boundaries | No lost acknowledged changes, duplicated records, or false completion |
| Reboot, notification denial, force-stop | Clear, truthful paused/restart behavior |
| Old peer returns after cleanup | Verified checkpoint/rebase; no resurrection of deleted records |
| Tablet portrait/landscape, large text, TalkBack | Controls remain usable and conflicts can be reviewed |
| Minified Play-distributed phone build | Pairing, transport, serialization, and service behavior match the tested build |

Run at least one minimum-supported Android configuration and one current target-level configuration. Include different OEM phones for background restrictions. Emulator socket tests remain useful but do not substitute for this matrix.

#### Measurable release evidence

Use synthetic records and test keys for all fixtures. These are proposed minimum release checks, not measurements already achieved:

- Three devices complete 100 edit/sync cycles with equal logical records and compatible source frontiers after every completed cycle.
- A mixed fixture contains at least 1,000 entries, all supported entry types, linked authenticators, groups, passkeys, and 100 photos of varied sizes.
- A separate operation-history fixture exceeds the current 16 MiB enrollment-metadata limit and proves the checkpoint path avoids requiring an unbounded history JSON object.
- Every configured frame, collection, blob, queue, and import limit has tests immediately below, at, and above the limit.
- Inject a crash at each named database/file/acknowledgement boundary at least once, then restart and verify exact record counts, hashes, conflict state, and queued/applied progress.
- Run a physical-device idle/background soak for at least eight hours, including lock/unlock and Wi-Fi loss/rejoin. Record battery change, traffic, wakeups, and whether the OS stopped the service.
- While both apps are running and the test LAN is available, require a single small edit to converge within two configured retry intervals. Record failures rather than relaxing the threshold silently.
- Measure large-attachment throughput and peak memory on the smallest supported test device. Set final chunk size and limits from those results, and rerun the exact-boundary tests.
- Record zero lost acknowledged mutations, zero duplicate logical records from replay, and zero stale vault-content views after the lock test completes.

Keep device model, OS version, build hash, protocol version, fixture size, reproduction steps, and result with each run. Do not put synthetic credentials or real vault contents into general diagnostic logs.

#### Security and failure review

- Attempt malformed/deep messages, oversized manifests, archive expansion, path traversal, altered signatures, duplicate sequence forks, and replayed membership/epoch state.
- Exercise a malicious unpaired LAN peer and a compromised admitted peer. Distinguish possession of a transport credential from authorization to change membership or unwrap content keys.
- Verify device-protected state never contains usable vault keys, plaintext records, photo bytes, or conflict previews.
- Test application/queue rollback detection against stored accepted heads. Document that fully rolled-back local storage and offline peers limit what can be detected without an external trusted anchor.
- Review dependency versions/licenses and cryptographic format choices before release. Treat an independent review as a separate evidence requirement, not something implied by passing unit tests.

#### Build and artifact procedure

1. Record passing test commands, device/OS versions, protocol/schema versions, and unresolved defects.
2. Run the relevant unit suites and connected tests. Example commands from the repository root:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :watchcommon:test
   .\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.package=com.privatevault.app.sync'
   ```

3. Run the new UI/lifecycle suites explicitly if they live outside the sync package. Use `ANDROID_SERIAL` when selecting one attached test device, then repeat across the matrix.
4. Verify the next phone version code exceeds every published phone build. Do not advance or relabel a version just to hide unfinished gates.
5. Build the signed phone artifacts only after the Android release gates pass:

   ```powershell
   .\gradlew.bat :app:assembleRelease :app:bundleRelease
   ```

6. Test the minified release, including a Play internal-test installation. Confirm protocol fields and exact counters survive R8.
7. Place one current phone APK and one current phone AAB in `downloads/`; update `SHA256SUMS.txt` and release notes. Retain only the current watch APK/AAB under the existing artifact policy if a watch update is included.
8. Verify artifact package, certificate, version name/code, and SHA-256 values. Do not delete signing files, environment files, or unrelated user files.
9. For Wear companion checks, use phone and watch installations with matching signatures. The previously observed mixed local-APK/Play-install problem is separate from Android LAN sync.
10. Do not publish to Play automatically as part of writing or implementing this plan. Prepare the concrete tested artifacts for release review.

## 6. Final completion checklist

- [ ] Every supported mutation has a defined sync policy and passes its tests.
- [ ] Three devices authenticate and sync directly after one enrollment per joining device.
- [ ] Membership changes are signed, ordered, persisted, propagated, and enforced while locked.
- [ ] Removed devices cannot obtain future content keys from updated peers.
- [ ] Independent local passwords and lock settings remain intact.
- [ ] Initial enrollment recovers correctly from lost commit acknowledgements.
- [ ] Photo updates resume safely and preserve old visible data until replacement completes.
- [ ] Received, applied, waiting, conflict, and complete statuses have distinct meanings.
- [ ] Locking cancels plaintext work and clears every vault-content view.
- [ ] Conflicts across supported entity types can be reviewed and resolved without hidden field loss.
- [ ] Automatic retry, manual Sync now, Pause/Resume, and reboot behavior match the documented policy.
- [ ] Checkpoints and cleanup preserve offline edits, deletion knowledge, and conflict attachments.
- [ ] Physical-device and minified-release tests pass with recorded evidence.
- [ ] Current APK/AAB files, checksums, release notes, and known limitations agree.

## 7. First implementation session

Start with Step 0. Its concrete output should be the production-write inventory and the membership/transport decision record. Then implement Step 1's schema and protocol fixtures. Do not begin by adding more background timers or a Windows client.

The central distinction to preserve throughout implementation is that receiving encrypted bytes, applying a vault change, and being allowed to delete old history are three different events. Each needs its own durable evidence.
