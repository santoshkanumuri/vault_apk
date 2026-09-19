# Private Vault 1.9.3

Version code: 29. Package: `com.application.private_vault`.

The entry-action dock is now 88 dp tall, with a contrasting surface, direction arrow, and a single-line **Pull up to edit** prompt. Pull it upward or tap it to open Add photo, Camera, Edit, Duplicate, and Delete.

Entry headers now include a three-dot action button beside Favorite. It opens or closes the same dock, providing a visible alternative when the pull gesture is not obvious.

Full-screen entry details, Groups, and Settings now draw in the app window. Their former dialog windows did not provide usable system-bar insets on Android 16, which left bottom controls behind the navigation bar. The photo viewer keeps its secure full-screen window and uses the app window's safe insets. Group details keep **Delete group** in a fixed footer above the navigation area. Settings can scroll to the Privacy policy link.

The Cards tab now shows every card in one stack, without folders. Existing card folders become regular Groups when the vault opens; their names, notes, and card links remain. The Amex mark is slightly wider on cards.

The backup format is unchanged. Install this update over v1.9.2 with the same signing key.

Checks: 36 unit tests, lint, and 28 passing Android 16 emulator tests; one optional live-browser passkey test was skipped. Layout tests cover 840 × 1100 and 1080 × 2400 pixels, 1.8 font scale, gesture and three-button navigation, the dock, expanded actions, the group footer, Settings scrolling, editor buttons, photo actions, and cards without folders. A database test checks that card-folder conversion keeps links and notes. The release APK was checked for signing, native alignment, no Internet permission, and debugging disabled. Physical S26 Plus verification is still needed.
