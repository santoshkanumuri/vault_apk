package com.privatevault.app

import com.privatevault.app.controls.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private data class HelpAnswer(val question: String, val answer: String,
    val destination: String? = null, val action: String? = null, val section: String? = null)

private val helpAnswers = listOf(
    HelpAnswer("How do I pair a new phone or tablet?",
        "Set up an empty vault on the new device with the same master password. Connect both devices to the same Wi-Fi. On the managing device, open Devices & sync and tap Create QR. On the new device, tap Scan QR, then confirm the matching code on both. The new device receives a vault copy after confirmation.",
        "Android devices", "Open Devices & sync", "Pairing and syncing"),
    HelpAnswer("How do I add a Windows PC?",
        "Create and unlock a local Windows vault. If the PC can reach the phone, create a QR on the managing phone and scan it in Windows Sync. If the phone can reach the PC instead, choose Show QR for phone to scan in Windows Sync, then tap Scan Windows QR on the managing phone. Enter the phone's master password in Windows and confirm the matching code on both devices. Windows keeps existing local items when it receives the vault copy. Later edits do not sync with Windows yet.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Which device creates the pairing QR?",
        "The managing device creates the QR. The new, empty device scans it. A paired device that is not the manager can still sync edits, but cannot invite another device.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Do paired devices need the same master password?",
        "Yes. If the new empty vault uses a different password, scan the QR and choose Change this empty vault's password. Enter its current password and the password used on the managing device. A populated vault cannot join this way.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Does pairing mean both devices are up to date?",
        "Pairing gives the new device an initial encrypted copy. Later edits may still be waiting. Check each device's status and tap Sync now while both are on the same Wi-Fi.",
        "Android devices", "Check device status"),
    HelpAnswer("Can paired devices sync on a different Wi-Fi later?",
        "Yes. Pairing stays with the devices. If both join another Wi-Fi that allows devices to reach each other, they can discover and sync again without pairing.",
        "Android devices", "Check device status"),
    HelpAnswer("Does the managing device need to be online for normal sync?",
        "No. Every device can keep editing locally while the manager is offline. Devices can exchange changes when they can connect on Wi-Fi. The managing role controls invitations and supported device removal.",
        "Android devices", "Open Devices & sync", "Managing devices"),
    HelpAnswer("How do I transfer management to another device?",
        "Keep both devices syncing and finish any waiting changes. On the current manager, open Devices & sync and tap Offer to the other device under Managing role. On the other device, tap Accept managing role. Sync both again, then tap Complete transfer on the old manager. It can still edit and sync afterward. Only an Android phone or tablet can manage the group.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("What if I lose the managing device?",
        "Nobody can add or remove devices in that group any more. On a phone or tablet that has your vault copy, open Devices & sync and tap Remove this device from the group under Leave the group. It keeps its items and starts a new group that it manages. Use Create QR there to pair a new, empty phone. A Windows PC first taps Leave group: choose Start with an empty vault if it has the same items, or they appear twice after it pairs. Only changes that reached a surviving device are kept. If no device has a copy, restore an encrypted backup.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Can this device leave sync without losing its vault?",
        "Yes. In Devices & sync, tap Remove this device from the group under Leave the group. This device keeps its entries and photos and starts a separate sync group. Finish waiting changes first. The managing device removes it from the old group when it gets the request, over Wi-Fi or Google Drive. The managing device itself must transfer the managing role first, or tap Start a separate group on this device instead; the other devices then can't add or remove members until they start a new group.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Can I remove a lost or old paired device?",
        "Yes, from the managing device, in a group of any size. In Devices & sync, tap Remove and the device name on its card, then confirm. The removed device doesn't need to be online or approve. Your other devices learn about it the next time they sync, over Wi-Fi or Google Drive, and refuse its later changes. Removal can't erase data already on that device, and it doesn't change the vault's encryption keys.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Why does it say Looking for your devices?",
        "Nuvori has no authenticated connection yet. Check that both devices are on the same local Wi-Fi and automatic sync is active. Some guest networks block device-to-device traffic.",
        "Android devices", "Open Devices & sync", "Sync status and controls"),
    HelpAnswer("What does Changes waiting mean?",
        "Known changes still need delivery or application. The device cards say which side is waiting. Tap Show connection details for counts from the last contact. An offline device may have edits this device has not seen yet.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("What does Syncing mean?",
        "The devices authenticated each other and are exchanging encrypted changes. Keep them on Wi-Fi until the exchange finishes.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("What does Checked mean?",
        "The last exchange finished. Check the waiting count and each device card before assuming every change has been applied. A device that was offline may still have unknown edits.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Why is a nearby device not shown as paired?",
        "Nearby means Nuvori found a service on Wi-Fi. Its identity is trusted only after authentication with your sync group. An unrelated device cannot join just by being nearby.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("How do I fix a failed sync check?",
        "Connect both devices to the same Wi-Fi, open Devices & sync on both, and tap Sync now. If discovery still fails, tap Show connection details, then Connection help on the device card. Enter that device's current Wi-Fi IP address.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Can I sync while a vault is locked?",
        "Paired devices can exchange protected encrypted changes while locked. The receiving device applies vault changes after you unlock it. The screen distinguishes received changes from applied changes.",
        "Android devices", "Check waiting changes"),
    HelpAnswer("How do I pause automatic sync?",
        "Open Devices & sync and turn off Automatic sync. Sync now still lets you check manually. Turn Automatic sync back on when you want automatic checks again, and use Check every to choose how often. To use Quick Settings, tap Add Auto sync tile there. Tap the tile to pause or resume; hold it to open Devices & sync.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("Why is the sync notification visible?",
        "Automatic sync keeps a listener running on Wi-Fi so paired devices can connect. Android needs one notification while it runs. Its text does not change, so it never pops up again. On Android 13 and later, tap Hide Auto sync notification in Devices & sync: sync keeps running and Nuvori shows under Active apps in Quick Settings. When you pause sync, no notification stays. The Auto sync tile shows On or Paused. Sync now and opening Nuvori check once without a notification.",
        "Android devices", "Open Devices & sync"),
    HelpAnswer("How do I enable password autofill?",
        "Open Autofill and codes. If it says Nuvori is not your Autofill service, tap Choose Nuvori autofill and select Nuvori in Android. If you use Chrome or Brave, set the browser to use an external Autofill service too. Nuvori still checks the saved website or app before offering a login.",
        "Autofill and codes", "Open Autofill settings", "Autofill and codes"),
    HelpAnswer("Why is my login missing from Autofill?",
        "Unlock Nuvori and check the login's saved website or authorized app. Browser forms must expose Android autofill fields. A different subdomain or an unverified app will not receive that login.",
        "Autofill and codes", "Open Autofill settings"),
    HelpAnswer("How do I fill and link a login?",
        "Choose Nuvori Autofill from the app's sign-in field. After unlocking, pick a login and confirm the destination. If the login has a linked authenticator code, Nuvori copies it after you pick the login in the account picker. Turn this off with Copy linked code after filling in Autofill and codes. To link an app or authenticator manually, edit that login and open its Autofill apps or linked code options.",
        "Autofill and codes", "Open Autofill settings"),
    HelpAnswer("How do I add the Quick copy tile?",
        "Open Autofill and codes and tap Add Quick copy tile. On older Android versions, open Quick Settings, tap Edit, and drag Quick copy into the tiles. The tile works with any Autofill provider. It has buttons for Passwords, Codes, Cards and Questions. Tap a field to copy it. The other fields of that item stay in your notifications for 2 minutes, so you can paste them one by one without opening the tile again. The notification closes after the last field is copied.",
        "Autofill and codes", "Open tile settings"),
    HelpAnswer("How do I search my vault?",
        "Use Search vault on Home to find entries and folders, including cards. Search in Notes or Passwords to narrow results. Tap the X to clear the search. The Quick copy tile has its own search after you unlock it.",
        "Vault", "Open vault", "Vault and entries"),
    HelpAnswer("Can I add photos while creating an entry?",
        "Yes. In the new entry form, tap Add photo or Camera. You can preview and remove photos before saving. Tap the checkmark to attach them to the entry; leaving the form discards its photos. If a photo fails to attach, open the saved entry and add it there.",
        "Vault", "Open vault"),
    HelpAnswer("Where are Edit and photo actions on an entry?",
        "Open the entry. On a phone, tap or pull up the bottom bar to show Edit, Add photo, Camera, and other actions. Pull it down to close. On a tablet, use the entry's three-dot menu.",
        "Vault", "Open vault"),
    HelpAnswer("How do I use a passkey?",
        "On Android 14 or newer, enable Nuvori as a password and passkey provider, then choose it when a supported website asks to create or use a passkey. Passkeys are included in encrypted backups.",
        "Passkeys", "Open Passkeys", "Passkeys"),
    HelpAnswer("Why was Nuvori not offered for a passkey?",
        "The app or website may use an unsupported passkey flow, or Nuvori may not be enabled as a provider. Check Passkeys settings. Android 13 and earlier cannot create or use passkeys here.",
        "Passkeys", "Open Passkeys"),
    HelpAnswer("How do fingerprint and unlock timing work?",
        "Security settings control when the vault locks and when the master password is required again. Fingerprint still checks your device each time. Switching apps never extends the inactivity timer. A file or photo picker that Nuvori opens counts as time in the app, for up to 10 minutes. Turning off the screen always locks.",
        "Security", "Open Security", "Security and backup"),
    HelpAnswer("How do I unlock Nuvori?",
        "Enter this device's master password on the lock screen. If you enabled fingerprint, you can use it while its protected session is valid. After a reboot or session expiry, enter the master password again.",
        "Security", "Open Security"),
    HelpAnswer("Why is the master password important?",
        "It protects this device's vault key. Nuvori cannot reset a forgotten password. Keep an encrypted backup and its password somewhere safe. Paired devices must use the same master password when they join.",
        "Security", "Open Security"),
    HelpAnswer("How do I change my master password?",
        "Open Security and choose Change master password. While devices share a vault, this action is unavailable because they must keep the same password. To change only this device's password, stop sharing on this device first; it keeps an independent copy. A new empty device can change its password during pairing.",
        "Security", "Open Security"),
    HelpAnswer("How do I restore or import data?",
        "Open Backup and import for encrypted Nuvori backups or a browser password export. Review imported records before saving. A backup needs the password used when it was created.",
        "Backup and import", "Open Backup and import"),
    HelpAnswer("How do I change the theme?",
        "Open Appearance and use the Light mode switch. With it off, Nuvori uses a black background.",
        "Appearance", "Open Appearance", "Appearance and cards"),
    HelpAnswer("What does NFC card import read?",
        "If your phone has NFC, turn on NFC card import in Cards and NFC, then tap the NFC icon in a card's label field. It does not make payments or read a card's CVV. You can always enter card details manually.",
        "Cards and NFC", "Open Cards and NFC")
)

@Composable
internal fun HelpPage(openPage: (String) -> Unit) {
    var expanded by rememberSaveable { mutableIntStateOf(-1) }
    Text("Choose a question for steps and a shortcut to the right screen.",
        style = MaterialTheme.typography.bodyMedium)
    helpAnswers.forEachIndexed { index, item ->
        item.section?.let { Text(it, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() }) }
        Card(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth().semantics {
                    stateDescription = if (expanded == index) "Expanded" else "Collapsed"
                }.clickable(role = Role.Button) {
                    expanded = if (expanded == index) -1 else index
                }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(item.question, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    Icon(if (expanded == index) NuvoriIcons.ChevronUp else NuvoriIcons.ChevronDown,
                        contentDescription = null)
                }
                if (expanded == index) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(item.answer, style = MaterialTheme.typography.bodyMedium)
                    if (item.destination != null && item.action != null)
                        TextButton(onClick = { openPage(item.destination) }) { Text(item.action) }
                }
            }
        }
    }
}
