# Private Vault 1.4.4

- American Express logo area widened from 70 dp to 74 dp.
- Added black, charcoal, graphite, and gray. All 34 colors retain unused-first ordering. Dark cards and color swatches have outlines for visibility on black.
- Added Use fingerprint on the lock screen, including the password-entry view, while strong biometrics and the existing daily session are available. It invokes the same authenticated unlock path as the automatic prompt.
- Expired or invalid sessions fall back to master-password entry. The 24-hour expiry is checked both before the prompt and when authentication completes. Fingerprint retries do not renew the deadline.

Device check: cancel the automatic fingerprint prompt, retry with Use fingerprint, then choose password entry and retry again. Confirm the master password is required after the session expires.
