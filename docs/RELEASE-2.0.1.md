# Nuvori 2.0.1 preview

Phone version code 41. The Wear companion remains at 2.0.0. Install the phone APK over an installation signed with the same certificate. The phone AAB is for the mobile Play track.

This update tightens Android device sync:

- In a group with exactly two active Android devices, either device can remove its only peer. Nuvori keeps local vault contents, creates a new sync identity, and generates fresh content and transport keys. The old device keeps its old local copy and cannot receive future changes from the new group. Finish pending sync and resolve conflicts first. Re-pair a watch afterward because its old vault pairing no longer matches.
- A lost final enrollment acknowledgement now reports that enrollment was saved, so the user can sync or re-pair the same empty device according to its state.
- Local transport copies are deleted only after every active peer reports applying the change. Abandoned partial photo files are removed. Unreferenced completed photo ciphertext is eligible for deletion after seven days only when all active peers have applied current heads and no pending photo, queued change, rejected change, or unresolved conflict remains. Current photo references stay.
- A full encrypted queue and insufficient photo storage have distinct sync messages. An entry that arrives before its group or linked authenticator waits for that dependency. A permanent database constraint failure is isolated for review.
- Group conflict details identify changed fields. A test covers concurrent group edits converging after an explicit signed choice.

**Still a preview:** removing one device from a three- or four-device group is disabled. The surviving devices need authenticated delivery of new keys and support for multiple key epochs before that can be safe. Enrollment interruption, disk-full, and three-device behavior still need physical-device testing. The local sync checklist tracks those gaps. Keep an encrypted backup while testing.

Verification: the phone unit suite and 27 focused Android instrumentation tests passed on the available emulator. The tested paths include two-device reset and transport recovery, photo transfer and cleanup, out-of-order dependencies, concurrent group resolution, and enrollment snapshots. The available emulator is a Wear device; phone layout and real Wi-Fi behavior still need physical phone and tablet checks. The minified release build passed Android's vital lint gate.

The 2.0.0 phone and Wear artifacts are unchanged. This release updates the phone only.
