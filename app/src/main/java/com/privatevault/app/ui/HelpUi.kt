package com.privatevault.app

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private data class HelpAnswer(val question: String, val answer: String,
    val destination: String? = null, val action: String? = null)

private val helpAnswers = listOf(
    HelpAnswer("Which device shows the pairing QR?",
        "Open Android devices on the managing device and choose Create pairing link. It shows the QR. On the new empty device, open Android devices and choose Scan QR.",
        "Android devices", "Open Android devices"),
    HelpAnswer("How do I start a sync group?",
        "Set up an empty vault on the new device with the same master password. Put both devices on the same Wi-Fi, show the QR on the managing device, scan it on the new device, and confirm the matching code on both. The vault copy starts after confirmation.",
        "Android devices", "Open Android devices"),
    HelpAnswer("What if the new device has a different password?",
        "On the empty device, scan the pairing QR and choose Change this empty vault's password. Enter its current password and the password used on the managing device. Nuvori changes the local password before pairing. A populated vault cannot join this way.",
        "Android devices", "Open Android devices"),
    HelpAnswer("Does pairing mean both devices are up to date?",
        "Pairing gives the new device an initial encrypted copy. Later edits may still be waiting. Check each device's status and tap Sync now while both are on the same Wi-Fi.",
        "Android devices", "Check device status"),
    HelpAnswer("Can paired devices sync on a different Wi-Fi later?",
        "Yes. Pairing stays with the devices. If both join another Wi-Fi that allows devices to reach each other, they can discover and sync again without pairing.",
        "Android devices", "Check device status"),
    HelpAnswer("Why does it say Looking for your devices?",
        "Nuvori has no authenticated connection yet. Check that both devices are on the same local Wi-Fi and automatic sync is active. Some guest networks block device-to-device traffic.",
        "Android devices", "Open Android devices"),
    HelpAnswer("What does Changes waiting mean?",
        "Known changes still need delivery or application. The device cards say which side is waiting. The counts in Advanced settings describe the last confirmed contact, so a device may have newer edits that this phone has not seen.",
        "Android devices", "Open Advanced settings"),
    HelpAnswer("What does Syncing mean?",
        "The devices authenticated each other and are exchanging encrypted changes. Keep them on Wi-Fi until the exchange finishes.",
        "Android devices", "Open Android devices"),
    HelpAnswer("What does Checked mean?",
        "The last exchange finished. Check the waiting count and each device card before assuming every change has been applied. A device that was offline may still have unknown edits.",
        "Android devices", "Open Android devices"),
    HelpAnswer("Why is a nearby device not shown as paired?",
        "Nearby means Nuvori found a service on Wi-Fi. Its identity is trusted only after authentication with your sync group. An unrelated device cannot join just by being nearby.",
        "Android devices", "Open Android devices"),
    HelpAnswer("How do I fix a failed sync check?",
        "Connect both devices to the same Wi-Fi, open Android devices on both, and tap Sync now. If discovery still fails, open Advanced settings and use Connection help with the other device's current Wi-Fi IP address.",
        "Android devices", "Open Android devices"),
    HelpAnswer("Can I sync while a vault is locked?",
        "Paired devices can exchange protected encrypted changes while locked. The receiving device applies vault changes after you unlock it. The screen distinguishes received changes from applied changes.",
        "Android devices", "Check waiting changes"),
    HelpAnswer("How do I pause automatic sync?",
        "Open Android devices and tap Pause auto. Sync now still lets you check manually. Tap Resume auto when you want automatic checks again.",
        "Android devices", "Open Android devices"),
    HelpAnswer("Why is the sync notification always visible?",
        "The current automatic sync service keeps a listener running on Wi-Fi so paired devices can connect when they find each other. Android requires a foreground notification while that service runs. Pause automatic sync in Android devices to stop it.",
        "Android devices", "Open Android devices"),
    HelpAnswer("How do I enable password autofill?",
        "Open Autofill and codes, choose Nuvori for Autofill in Android, then enable another autofill service in Chrome or Brave if you use one of those browsers. Website and app matching still protect where a login can be offered.",
        "Autofill and codes", "Open Autofill settings"),
    HelpAnswer("Why is my login missing from Autofill?",
        "Unlock Nuvori and check the login's saved website or authorized app. Browser forms must expose Android autofill fields. A different subdomain or an unverified app will not receive that login.",
        "Autofill and codes", "Open Autofill settings"),
    HelpAnswer("How do I fill and link a login?",
        "Choose Nuvori Autofill from the app's sign-in field. After unlocking, pick a login and confirm the destination. To link an app or authenticator manually, edit that login and open its Autofill apps or linked code options.",
        "Autofill and codes", "Open Autofill settings"),
    HelpAnswer("How do I add the Vault codes tile?",
        "Open Autofill and codes and tap Add Vault codes tile. On older Android versions, open Quick Settings, tap Edit, and drag Vault codes into the tiles. The tile works with any Autofill provider.",
        "Autofill and codes", "Open tile settings"),
    HelpAnswer("How do I search my vault?",
        "Open a category and use its Search field. The Vault codes tile has a separate search for passwords and authenticator accounts after you unlock it.",
        "Vault", "Open vault"),
    HelpAnswer("How do I use a passkey?",
        "On Android 14 or newer, enable Nuvori as a password and passkey provider, then choose it when a supported website asks to create or use a passkey. Passkeys are included in encrypted backups.",
        "Passkeys", "Open Passkeys"),
    HelpAnswer("Why was Nuvori not offered for a passkey?",
        "The app or website may use an unsupported passkey flow, or Nuvori may not be enabled as a provider. Check Passkeys settings. Android 13 and earlier cannot create or use passkeys here.",
        "Passkeys", "Open Passkeys"),
    HelpAnswer("How do fingerprint and unlock timing work?",
        "Security settings control when the vault locks and when the master password is required again. Fingerprint still checks your device each time. Leaving the app never extends the inactivity timer.",
        "Security", "Open Security"),
    HelpAnswer("How do I unlock Nuvori?",
        "Enter this device's master password on the lock screen. If you enabled fingerprint, you can use it while its protected session is valid. After a reboot or session expiry, enter the master password again.",
        "Security", "Open Security"),
    HelpAnswer("Why is the master password important?",
        "It protects this device's vault key. Nuvori cannot reset a forgotten password. Keep an encrypted backup and its password somewhere safe. Paired devices must use the same master password when they join.",
        "Security", "Open Security"),
    HelpAnswer("How do I change my master password?",
        "Open Security and choose Change master password. A paired sync group cannot change only one device's password. On a new empty device, the pairing screen can change its password before joining.",
        "Security", "Open Security"),
    HelpAnswer("How do I change the theme?",
        "Open Appearance and use the Light mode switch. The other setting uses a black background.",
        "Appearance", "Open Appearance"),
    HelpAnswer("What does NFC card import read?",
        "If your phone has NFC, turn on NFC card import in Cards and NFC, then tap the NFC icon in a card's label field. It does not make payments or read a card's CVV. You can always enter card details manually.",
        "Cards and NFC", "Open Cards and NFC"),
    HelpAnswer("How do I restore or import data?",
        "Open Backup and import for encrypted Nuvori backups or a browser password export. Review imported records before saving. A backup needs the password used when it was created.",
        "Backup and import", "Open Backup and import")
)

@Composable
internal fun HelpPage(openPage: (String) -> Unit) {
    var expanded by rememberSaveable { mutableIntStateOf(-1) }
    Text("Help", style = MaterialTheme.typography.titleLarge)
    Text("Tap a question for steps and a shortcut to the right setting.",
        style = MaterialTheme.typography.bodyMedium)
    helpAnswers.forEachIndexed { index, item ->
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
                    Icon(if (expanded == index) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
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
