package com.privatevault.app.notes

import com.google.gson.JsonParser
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RichNoteFormatTest {
    private val sample = RichNoteDocument(listOf(
        TextBlock(BlockKind.HEADING, listOf(RichSpan("Trip")), level = 1),
        TextBlock(spans = listOf(RichSpan("Bring "), RichSpan("passport", RichMarks(bold = true, color = NoteColor.RED)),
            RichSpan(" and see ", RichMarks(bg = NoteColor.YELLOW)), RichSpan("the booking", RichMarks(link = "https://example.com/b")))),
        TextBlock(BlockKind.CHECK, listOf(RichSpan("Charger")), checked = true),
        TextBlock(BlockKind.CHECK, listOf(RichSpan("Adapter"))),
        TextBlock(BlockKind.BULLET, listOf(RichSpan("Nested", RichMarks(italic = true))), indent = 1),
        TextBlock(BlockKind.NUMBERED, listOf(RichSpan("First"))),
        TextBlock(BlockKind.NUMBERED, listOf(RichSpan("Second"))),
        TextBlock(BlockKind.NUMBERED, listOf(RichSpan("Inner")), indent = 1),
        TextBlock(BlockKind.NUMBERED, listOf(RichSpan("Third"))),
        TextBlock(BlockKind.QUOTE, listOf(RichSpan("Quoted"))),
        TextBlock(BlockKind.CODE, listOf(RichSpan("ssh admin@10.0.0.1"))),
        DividerBlock,
        TableBlock(true, listOf(listOf(listOf(RichSpan("Account")), listOf(RichSpan("Hint"))),
            listOf(listOf(RichSpan("Bank")), listOf(RichSpan("Birthday", RichMarks(bold = true)))))),
    ))

    @Test fun encodesAndDecodesEveryFeature() {
        val (plain, json) = RichNotes.storageFields(sample)
        val loaded = RichNotes.load(plain, json)
        assertEquals(RichNoteSource.RICH, loaded.source)
        assertEquals(RichNotes.normalize(sample), loaded.document)
    }

    @Test fun plainFallbackIsReadable() {
        assertEquals("""
            Trip
            Bring passport and see the booking
            ☑ Charger
            ☐ Adapter
              • Nested
            1. First
            2. Second
              1. Inner
            3. Third
            > Quoted
            ssh admin@10.0.0.1
            ———
            Account | Hint
            Bank | Birthday
        """.trimIndent(), RichNotes.plainText(sample))
    }

    @Test fun plainNotesStayPlainAndKeepTheirTextExactly() {
        val text = "Wi-Fi: home\n\nGuest network: visitors\n  indented line"
        val document = RichNotes.load(text, "").document
        val (plain, json) = RichNotes.storageFields(document)
        assertEquals(text, plain)
        assertEquals("", json)
        assertEquals(RichNoteSource.PLAIN, RichNotes.load(text, "").source)
    }

    @Test fun formattingAPlainNoteProducesADocument() {
        val entry = VaultEntry(type = EntryType.NOTE, title = "N", notes = "hello")
        val doc = RichNotes.load(entry).document
        val bold = RichNoteDocument(listOf(TextBlock(spans = listOf(RichSpan("hello", RichMarks(bold = true))))))
        val saved = RichNotes.applyTo(entry, bold)
        assertEquals("hello", saved.notes)
        assertTrue(saved.fourthValue.contains("\"bold\":true"))
        assertEquals(RichNoteSource.PLAIN, RichNotes.load(entry).source)
        assertEquals(bold, RichNotes.load(saved).document)
        assertEquals(1, doc.blocks.size)
    }

    @Test fun textEditedByAnOlderAppWins() {
        val (_, json) = RichNotes.storageFields(sample)
        val loaded = RichNotes.load("Changed on an older phone", json)
        assertEquals(RichNoteSource.DIVERGED, loaded.source)
        assertEquals("Changed on an older phone", RichNotes.plainText(loaded.document))
    }

    @Test fun trailingWhitespaceChangesDoNotCountAsDivergence() {
        val (plain, json) = RichNotes.storageFields(sample)
        assertEquals(RichNoteSource.RICH, RichNotes.load("$plain\r\n  ", json).source)
        assertEquals(RichNoteSource.RICH, RichNotes.load(plain.replace("\n", "\r\n"), json).source)
    }

    @Test fun newerVersionsFallBackToText() {
        val json = """{"format":"nuvori.richnote","version":2,"plainHash":"x","blocks":[]}"""
        assertEquals(RichNoteSource.NEWER_VERSION, RichNotes.load("text", json).source)
    }

    @Test fun brokenOrForeignJsonIsIgnored() {
        listOf("{", "[]", "null", """{"format":"other","version":1,"plainHash":"","blocks":[]}""",
            """{"format":"nuvori.richnote","version":"1","plainHash":"","blocks":[]}""", "123").forEach {
            assertEquals(it, RichNoteSource.PLAIN, RichNotes.load("text", it).source)
        }
    }

    @Test fun unknownBlocksMarksAndColorsAreDropped() {
        val plain = "Hi"
        val json = """{"format":"nuvori.richnote","version":1,"plainHash":"${RichNotes.hash(plain)}","blocks":[
            {"type":"video","src":"x"},
            {"type":"paragraph","spans":[{"text":"Hi","bold":true,"blink":true,"color":"chartreuse","bg":"blue"}]}]}"""
        val loaded = RichNotes.load(plain, json)
        assertEquals(RichNoteSource.RICH, loaded.source)
        assertEquals(RichNoteDocument(listOf(TextBlock(spans = listOf(RichSpan("Hi", RichMarks(bold = true, bg = NoteColor.BLUE)))))), loaded.document)
    }

    @Test fun onlySafeLinksSurvive() {
        assertEquals("https://example.com", RichNotes.sanitizeLink("https://example.com"))
        assertEquals("mailto:a@b.c", RichNotes.sanitizeLink("mailto:a@b.c"))
        listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x", "data:text/html,x", "https://a b", "https://",
            "http:example.com", "https://x".padEnd(3000, 'a'), "content://x").forEach { assertNull(it, RichNotes.sanitizeLink(it)) }
        assertEquals("https://example.com", RichNotes.linkFromInput("example.com"))
        assertEquals("mailto:me@example.com", RichNotes.linkFromInput("me@example.com"))
        assertNull(RichNotes.linkFromInput("javascript:alert(1)"))
    }

    @Test fun controlAndDirectionCharactersAreRemoved() {
        assertEquals("abc\tdef", RichNotes.sanitizeText("a\u0000b‮c\tdef⁦"))
        val doc = RichNoteDocument(listOf(TextBlock(spans = listOf(RichSpan("x‮y", RichMarks(bold = true))))))
        assertEquals("xy", RichNotes.normalize(doc).blocks.single().let { (it as TextBlock).text })
    }

    @Test fun textWithLineBreaksBecomesOneBlockPerLine() {
        val doc = RichNotes.normalize(RichNoteDocument(listOf(TextBlock(BlockKind.BULLET,
            listOf(RichSpan("a\nb", RichMarks(bold = true)), RichSpan("c"))))))
        assertEquals(listOf(
            TextBlock(BlockKind.BULLET, listOf(RichSpan("a", RichMarks(bold = true)))),
            TextBlock(BlockKind.BULLET, listOf(RichSpan("b", RichMarks(bold = true)), RichSpan("c")))), doc.blocks)
    }

    @Test fun limitsAreEnforced() {
        val big = RichNoteDocument(listOf(TableBlock(true, List(51) { listOf(listOf(RichSpan("x"))) })))
        assertTrue(runCatching { RichNotes.encode(big) }.exceptionOrNull() is RichNoteTooLargeException)
        val long = RichNoteDocument(listOf(TextBlock(spans = listOf(RichSpan("x".repeat(RichNotes.MAX_TEXT_CHARS + 1), RichMarks(bold = true))))))
        assertTrue(runCatching { RichNotes.encode(long) }.exceptionOrNull() is RichNoteTooLargeException)
        val oversized = """{"format":"nuvori.richnote","version":1,"plainHash":"","blocks":[${List(RichNotes.MAX_BLOCKS + 1) { """{"type":"divider"}""" }.joinToString(",")}]}"""
        assertEquals(RichNoteSource.PLAIN, RichNotes.load("", oversized).source)
    }

    @Test fun mergeKeepsSharedBlocksOnceAndBothSidesEdits() {
        val shared1 = TextBlock(spans = listOf(RichSpan("Start")))
        val shared2 = TextBlock(spans = listOf(RichSpan("End")))
        val mine = TextBlock(BlockKind.CHECK, listOf(RichSpan("Mine")))
        val theirs = TextBlock(BlockKind.CHECK, listOf(RichSpan("Theirs")), checked = true)
        val merged = RichNoteMerge.combine(RichNoteDocument(listOf(shared1, mine, shared2)),
            RichNoteDocument(listOf(shared1, theirs, shared2)))
        assertEquals(listOf(shared1, mine, theirs, shared2), merged.blocks)
        assertEquals(listOf(shared1), RichNoteMerge.combine(RichNoteDocument(listOf(shared1)), RichNoteDocument(listOf(shared1))).blocks)
    }

    @Test fun jsonIsCompactAndOmitsDefaults() {
        val json = RichNotes.encode(RichNoteDocument(listOf(TextBlock(spans = listOf(RichSpan("a", RichMarks(bold = true)))))))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("nuvori.richnote", root["format"].asString)
        assertEquals(1, root["version"].asInt)
        val block = root["blocks"].asJsonArray[0].asJsonObject
        assertFalse(block.has("indent"))
        assertFalse(block["spans"].asJsonArray[0].asJsonObject.has("italic"))
    }

    private fun fixtures() = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/rich-notes/fixtures.json") }.firstOrNull { it.exists() }
        ?.let { JsonParser.parseString(it.readText()).asJsonObject } ?: error("fixtures.json not found")

    /**
     * The same fixtures run in the Windows app's tests (src/lib/richNote.test.mjs), so both apps
     * read the same documents and write byte-identical JSON for them.
     */
    @Test fun sharedFixturesMatch() {
        val fixtures = fixtures()["fixtures"].asJsonArray
        assertTrue(fixtures.size() > 0)
        fixtures.forEach { element ->
            val fixture = element.asJsonObject
            val name = fixture["name"].asString
            val plain = fixture["plain"].asString
            val document = RichNotes.decode(fixture["document"].toString())!!.document
            assertEquals(name, plain, RichNotes.plainText(document))
            assertEquals(name, fixture["hash"].asString, RichNotes.hash(plain))
            assertEquals(name, fixture["encoded"].asString, RichNotes.encode(document))
            val reread = RichNotes.load(plain, fixture["encoded"].asString)
            assertEquals(name, RichNoteSource.RICH, reread.source)
            assertEquals(name, document, reread.document)
            assertEquals(name, plain to fixture["encoded"].asString, RichNotes.storageFields(reread.document))
            if (fixture.has("normalized")) assertEquals(name,
                RichNotes.decode(fixture["normalized"].toString())!!.document, document)
        }
    }

    @Test fun sharedLinkRulesMatch() {
        fixtures()["links"].asJsonArray.forEach {
            val o = it.asJsonObject
            assertEquals(o["input"].asString, o["safe"].takeUnless { v -> v.isJsonNull }?.asString, RichNotes.sanitizeLink(o["input"].asString))
        }
        fixtures()["typedLinks"].asJsonArray.forEach {
            val o = it.asJsonObject
            assertEquals(o["input"].asString, o["link"].takeUnless { v -> v.isJsonNull }?.asString, RichNotes.linkFromInput(o["input"].asString))
        }
    }

    @Test fun keepBothEditsCombinesStoredNotes() {
        val mine = RichNotes.storageFields(RichNoteDocument(listOf(TextBlock(BlockKind.HEADING, listOf(RichSpan("List")), level = 1),
            TextBlock(BlockKind.CHECK, listOf(RichSpan("Milk"))), TextBlock(BlockKind.CHECK, listOf(RichSpan("Eggs", RichMarks(bold = true)))))))
        // The other device is an older app: plain text only.
        val (plain, json) = RichNoteMerge.combineStored(mine.first, mine.second, "List\n☐ Milk\nBread", "")
        val loaded = RichNotes.load(plain, json)
        assertEquals(RichNoteSource.RICH, loaded.source)
        assertEquals(listOf("List", "Milk", "Eggs", "Bread"), loaded.document.blocks.map { (it as TextBlock).text })
        assertEquals(listOf(BlockKind.HEADING, BlockKind.CHECK, BlockKind.CHECK, BlockKind.PARAGRAPH),
            loaded.document.blocks.map { (it as TextBlock).kind })
        assertTrue((loaded.document.blocks[2] as TextBlock).spans.single().marks.bold)
    }

    @Test fun notesTooLargeForASyncPayloadAreRefused() {
        // Under the text and JSON limits on their own, but over the payload budget together.
        val lines = List(100) { TextBlock(spans = listOf(RichSpan("界".repeat(1_600), RichMarks(bold = true)))) }
        assertTrue(RichNotes.encode(RichNoteDocument(lines)).toByteArray().size < RichNotes.MAX_JSON_BYTES)
        assertTrue(runCatching { RichNotes.storageFields(RichNoteDocument(lines)) }.exceptionOrNull() is RichNoteTooLargeException)
        assertTrue(RichNotes.storageFields(RichNoteDocument(lines.take(40))).second.isNotEmpty())
    }
}
