package com.privatevault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.CardKind
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class MarketingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onboarding() {
        compose.setContent {
            MaterialTheme(colorScheme = VaultColors) {
                ScreenshotCanvas { OnboardingScreen(0, {}, {}, {}) }
            }
        }
        save("01-nuvori-onboarding-dark.png")
    }

    @Test fun compactHome() {
        val entries = demoEntries()
        compose.setContent {
            MaterialTheme(colorScheme = VaultColors) {
                ScreenshotCanvas {
                    Column(Modifier.fillMaxSize()) {
                        DemoHeader("Home")
                        Dashboard(entries, {}, {}, Modifier.weight(1f))
                    }
                }
            }
        }
        save("02-nuvori-home-dark.png")
    }

    @Test fun passwordFolders() {
        val work = VaultGroup(id = "work", name = "Work", folderType = EntryType.PASSWORD)
        val personal = VaultGroup(id = "personal", name = "Personal", folderType = EntryType.PASSWORD)
        val passwords = listOf(
            demoLogin("Mail", "alex@example.com", work),
            demoLogin("Developer portal", "alex.dev", work),
            demoLogin("Music", "alex@example.com", personal),
            demoLogin("Travel", "traveler@example.com", personal),
            demoLogin("Home router", "admin", null)
        )
        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                ScreenshotCanvas {
                    Column(Modifier.fillMaxSize()) {
                        DemoHeader("Passwords")
                        CategoryCollection(VaultTab.PASSWORDS, passwords, listOf(work, personal), work, "", "Default",
                            {}, {}, {}, {}, null, {}, { _, _ -> }, { it() }, Modifier.weight(1f))
                    }
                }
            }
        }
        save("03-nuvori-passwords-light.png")
    }

    @Test fun cardWallet() {
        val cards = listOf(
            demoCard("Everyday", "4532 1188 9034 2714", "Alex Morgan", "08/29", 0xFF2E7034L),
            demoCard("Travel", "5404 2211 8736 9012", "Alex Morgan", "11/30", 0xFF315B8CL),
            demoCard("Shared", "6011 7342 6609 1840", "Alex Morgan", "03/28", 0xFFF5EBDDL)
        )
        compose.setContent {
            MaterialTheme(colorScheme = VaultColors) {
                ScreenshotCanvas {
                    Column(Modifier.fillMaxSize()) {
                        DemoHeader("Cards")
                        CategoryCollection(VaultTab.CARDS, cards, emptyList(), null, "", "Default",
                            {}, {}, {}, {}, null, {}, { _, _ -> }, { it() }, Modifier.weight(1f))
                    }
                }
            }
        }
        save("04-nuvori-cards-dark.png")
    }

    @Test fun authenticatorCodes() {
        val codes = listOf(
            demoEntry(VaultEntry(type = EntryType.AUTHENTICATOR, title = "Developer account", primaryValue = "alex@example.com", secondaryValue = "JBSWY3DPEHPK3PXP")),
            demoEntry(VaultEntry(type = EntryType.AUTHENTICATOR, title = "Cloud console", primaryValue = "alex.dev", secondaryValue = "KRUGS4ZANFZSAYJA")),
            demoEntry(VaultEntry(type = EntryType.AUTHENTICATOR, title = "Finance", primaryValue = "personal", secondaryValue = "MFRGGZDFMZTWQ2LK"))
        )
        compose.setContent {
            MaterialTheme(colorScheme = VaultColors) {
                ScreenshotCanvas {
                    Column(Modifier.fillMaxSize()) {
                        DemoHeader("Authenticator")
                        CategoryCollection(VaultTab.AUTHENTICATOR, codes, emptyList(), null, "", "Default",
                            {}, {}, {}, {}, null, {}, { _, _ -> }, { it() }, Modifier.weight(1f))
                    }
                }
            }
        }
        save("05-nuvori-authenticator-dark.png")
    }

    @Test fun notes() {
        val notes = listOf(
            demoEntry(VaultEntry(type = EntryType.NOTE, title = "Travel checklist", notes = "Passport, tickets, reservations, and charger")),
            demoEntry(VaultEntry(type = EntryType.NOTE, title = "Wi-Fi setup", notes = "Router placement and guest network details")),
            demoEntry(VaultEntry(type = EntryType.NOTE, title = "Emergency contacts", notes = "People and numbers to keep close")),
            demoEntry(VaultEntry(type = EntryType.NOTE, title = "Project ideas", notes = "Small ideas worth returning to"))
        )
        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                ScreenshotCanvas {
                    Column(Modifier.fillMaxSize()) {
                        DemoHeader("Notes")
                        CategoryCollection(VaultTab.NOTES, notes, emptyList(), null, "", "Default",
                            {}, {}, {}, {}, null, {}, { _, _ -> }, { it() }, Modifier.weight(1f))
                    }
                }
            }
        }
        save("06-nuvori-notes-light.png")
    }

    @Test fun moreAndSettings() {
        val entries = demoEntries()
        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                ScreenshotCanvas {
                    Column(Modifier.fillMaxSize()) {
                        DemoHeader("More")
                        MoreScreen(3, entries, {}, {}, {}, {})
                    }
                }
            }
        }
        save("07-nuvori-more-light.png")
    }

    @Composable
    private fun ScreenshotCanvas(content: @Composable () -> Unit) {
        Surface(
            modifier = Modifier.requiredSize(411.43.dp, 731.43.dp).testTag("marketingShot"),
            color = MaterialTheme.colorScheme.background,
            content = content
        )
    }

    @Composable
    private fun DemoHeader(section: String) {
        Text(section, Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "marketing-screenshots").apply { mkdirs() }
        FileOutputStream(File(directory, name)).use { output ->
            compose.onNodeWithTag("marketingShot").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun demoEntries(): List<EntryWithDetails> {
        val now = System.currentTimeMillis()
        return listOf(
            VaultEntry(type = EntryType.CARD, title = "Everyday card", favorite = true, lastOpenedAt = now),
            VaultEntry(type = EntryType.PASSWORD, title = "Mail", primaryValue = "alex@example.com", secondaryValue = "demo", favorite = true, lastOpenedAt = now - 1),
            VaultEntry(type = EntryType.QUESTION, title = "Recovery question", primaryValue = "First concert?", secondaryValue = "demo", lastOpenedAt = now - 2),
            VaultEntry(type = EntryType.NOTE, title = "Travel checklist", notes = "Passport, tickets, and reservations", lastOpenedAt = now - 3),
            VaultEntry(type = EntryType.AUTHENTICATOR, title = "Developer account", secondaryValue = "JBSWY3DPEHPK3PXP", lastOpenedAt = now - 4)
        ).map { EntryWithDetails(it, emptyList(), emptyList()) }
    }

    private fun demoLogin(title: String, username: String, folder: VaultGroup?) = EntryWithDetails(
        VaultEntry(type = EntryType.PASSWORD, title = title, primaryValue = username, secondaryValue = "demo-password"),
        emptyList(), listOfNotNull(folder)
    )

    private fun demoCard(title: String, number: String, holder: String, expiry: String, color: Long) = demoEntry(
        VaultEntry(type = EntryType.CARD, title = title, primaryValue = number, secondaryValue = holder,
            tertiaryValue = expiry, fourthValue = "123", cardKind = CardKind.CREDIT, network = "VISA", color = color)
    )

    private fun demoEntry(entry: VaultEntry) = EntryWithDetails(entry, emptyList(), emptyList())
}
