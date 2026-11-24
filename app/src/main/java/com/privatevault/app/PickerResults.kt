package com.privatevault.app

/**
 * The system screens that Nuvori opens to choose a file, a photo or an account. While one is open,
 * the time counts as time in the app ([com.privatevault.app.security.EXTERNAL_FLOW_LIMIT_MS]).
 * [resumable] actions keep the chosen file when the vault locks meanwhile, and continue after unlock.
 */
internal enum class PickerAction(val resumable: Boolean) {
    EXPORT_BACKUP(true),
    RESTORE_BACKUP(true),
    IMPORT_PASSWORDS(true),
    AUTHENTICATOR_IMAGE(true),
    AUTHENTICATOR_FILE(true),
    /** A photo for an item that was open: the item editor is gone after a lock. */
    PHOTO(false),
    /** Google sign-in, passkey import and other screens that return no file. */
    OTHER(false),
}

/** A file chosen while the vault was locked. Only its address is kept; nothing is read before unlock. */
internal data class PendingPick(val action: PickerAction, val uri: android.net.Uri)

internal object PickerResults {
    /** The settings page that continues [action] after unlock, or null when it cannot continue. */
    fun settingsPage(action: PickerAction): String? = when (action) {
        PickerAction.EXPORT_BACKUP, PickerAction.RESTORE_BACKUP, PickerAction.IMPORT_PASSWORDS -> "Backup and import"
        PickerAction.AUTHENTICATOR_IMAGE, PickerAction.AUTHENTICATOR_FILE -> "Import authenticator codes"
        PickerAction.PHOTO, PickerAction.OTHER -> null
    }

    /** What the lock screen says when a result arrives after the vault locked. */
    fun lockedNotice(action: PickerAction, chosen: Boolean): String = when {
        action.resumable && chosen -> "The vault locked while the picker was open. Unlock to continue with the file you chose."
        action == PickerAction.PHOTO && chosen -> "The vault locked while the picker was open. Unlock, then add the photo again."
        else -> "The vault locked while the picker was open. Unlock, then try again."
    }
}
