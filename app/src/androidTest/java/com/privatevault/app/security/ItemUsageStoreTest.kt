package com.privatevault.app.security

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** The usage record round-trips through the Keystore, counts a keyboard pick once, and holds no names. */
class ItemUsageStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun start() = ItemUsageStore.clear(context)
    @After fun end() = ItemUsageStore.clear(context)

    @Test fun usesSurviveAReadAndAKeyboardPickCountsOnce() {
        assertEquals(0.0, ItemUsageStore.snapshot(context).score("login-a"), 0.0)
        repeat(2) { ItemUsageStore.record(context, "login-a") }
        ItemUsageStore.record(context, "login-b")
        ItemUsageStore.recordKeyboardPick(context, "process:1:login-b", "login-b")
        // Android can report the same pick again at the next fill or save request.
        ItemUsageStore.recordKeyboardPick(context, "process:1:login-b", "login-b")
        ItemUsageStore.recordKeyboardPick(context, "process:2:login-b", "login-b")
        ItemUsageStore.awaitWrites()

        val usage = ItemUsageStore.snapshot(context)
        assertEquals(2.0, usage.score("login-a"), 0.01)
        assertEquals(3.0, usage.score("login-b"), 0.01)
        assertEquals("login-b", usage.mostUsed(listOf("login-a", "login-b")))

        // Sealed on disk: the ids are not readable in the file.
        val bytes = File(context.noBackupFilesDir, "item-usage").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("login-a"))
    }

    @Test fun anUnreadableRecordReadsAsEmptyAndIsReplacedOnTheNextUse() {
        File(context.noBackupFilesDir, "item-usage").writeBytes(ByteArray(64) { 7 })
        assertEquals(0.0, ItemUsageStore.snapshot(context).score("login-a"), 0.0)
        ItemUsageStore.record(context, "login-a")
        ItemUsageStore.awaitWrites()
        assertEquals(1.0, ItemUsageStore.snapshot(context).score("login-a"), 0.01)
    }
}
