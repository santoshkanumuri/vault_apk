# Private Vault 1.4.0

## Card colors

30 choices including white, with the other 29 sampled from Color Hunt's retro collection:
https://colorhunt.co/palettes/retro

Source palette IDs include `f5ebddf2765e315b8c413333`, `ff7f50ffd16606d6a0118ab2`,
`3e0f8d9564dde4da72eeeeee`, `722f99c5c1c1fee7c8ff9292`,
`df301cff9100fff1d100b7cd`, `007dccffb900d10056b2054c`,
`d90000ffea938db355000000`, `0b1849124d1ce4b028ebede3`, and
`ff9e20215e611d2128f4f2f2`.

The form counts only saved cards. Unused colors come first, followed by colors
with increasing usage counts. Ties retain palette order. Editing a card preserves
its saved color until another swatch is chosen, including colors outside this palette.

## NFC import

Uses EMV NFC Paycard Enrollment 3.2.0, Apache-2.0:
https://github.com/devnied/EMV-NFC-Paycard-Enrollment

- Off by default; setup opt-in and Settings switch.
- Optional NFC hardware. Devices without it retain manual card entry.
- Foreground reader mode only after an explicit Scan card action in an unlocked vault.
- Cancels on pause, lock, screen-off, editor disposal, preference off, or timeout.
- Stale reader results are rejected using a session generation and current authorization.
- NFC permission is an Android install-time permission. The switch gates the app's
  reader registration and exchanges; it does not toggle the phone's NFC hardware or revoke that permission.
- Only SELECT, GET PROCESSING OPTIONS, READ RECORD, and GET RESPONSE reach the card.
  PIN verification, cryptogram generation, writes, transaction-history reading,
  and optional data queries are blocked or disabled.
- 30-second scanning window; 15-second read budget, 2-second exchange timeout,
  at most 80 exchanges, and a bounded response size.
- SLF4J no-op binding suppresses parser logs in every build. No INTERNET permission.
- Number, expiry, network, and name if supplied become an editable draft. CVV is
  always left blank. Check the result against the physical card before saving.
- Card networks do not reliably establish credit versus debit. The user selects that.
- Real-card compatibility and Samsung NFC positioning require physical-device testing.
  Some cards expose no usable details or a number different from the printed number.

## Lock behavior

Ordinary app switches get up to 10 seconds, never extending the existing inactivity
deadline. App-launched camera and file pickers retain the existing one-minute
inactivity deadline. The external-flow exception is consumed once so it cannot
carry into a later ordinary app switch. Screen-off locks immediately. A monotonic
deadline is checked on return even if Android delayed the background timer.

Successful photo import and backup export no longer force an extra lock. Restore
still locks after replacing vault contents. Encryption and screenshot restrictions
are unchanged. A grace period leaves the session accessible for that short interval.

## Physical-device acceptance

1. With NFC import off, tap a card while browsing and editing; no scan starts.
2. Enable it, open Add card, and tap Scan card. Cancel and background the app;
   both must stop scanning. Lock the screen during a read; no result may appear.
3. Read a supported Visa, Mastercard, and Discover card if available. Review the
   number/expiry/network against the card and verify CVV remains blank.
4. Save, reopen, and perform an encrypted backup round trip; network must persist.
5. Switch apps and return before and after 10 seconds. Screen-off must lock immediately.
6. Capture or import a dummy photo and export a dummy backup within the inactivity
   limit. Verify no forced lock on successful completion.

Automated checks cover number validation, network ranges, command allowlisting,
synthetic EMV parsing, disabled parser logging, palette ordering, lock deadlines,
crop coordinates, and password cryptography. Emulator checks cannot establish
physical NFC compatibility or constitute a full security audit.
