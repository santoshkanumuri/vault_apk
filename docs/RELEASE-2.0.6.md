# Nuvori 2.0.6 preview

Phone version code 47. The Wear companion is unchanged. The refreshed phone APK and AAB are in `downloads/`.

- Android devices now shows a compact overview and each paired device's connection stage, last contact, last exchange, and received/applied progress. The notification counts connected, nearby, and paired devices. Nearby devices are discovered on Wi-Fi; their identity is only trusted after authentication.
- Failed discovery, stale addresses, and transfer failures trigger a shorter retry. A manual check waits for an active encrypted transfer to finish. Each device can set its own name; that name is signed and shared with its paired devices.
- Conflict review shows both versions and confirms the selected choice. Two-device groups can stop sharing from the device card. Removal from groups of three or four still needs key rotation.
- Autofill profiles now show their saved fields and a visible form-filling switch. The home summary includes Groups, and the Autofill icon uses a contact-page symbol.

Verification: the debug compile and unit tests passed. Focused phone-emulator tests covered device naming, sync UI, locked exchange, Autofill profile detail, form filling, and the compact home summary. The signed release build, package metadata, signatures, and checksums were checked for the refreshed artifacts. Intermittent Wi-Fi, process restart, interrupted transfers, and release installation still need physical-device testing.
