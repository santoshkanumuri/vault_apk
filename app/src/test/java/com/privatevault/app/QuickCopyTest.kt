package com.privatevault.app

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class QuickCopyTest {
    private val code = VaultEntry(id = "code", type = EntryType.AUTHENTICATOR, title = "Mail TOTP", secondaryValue = "JBSWY3DPEHPK3PXP")
    private val login = VaultEntry(id = "login", type = EntryType.PASSWORD, title = "Mail", primaryValue = "user@example.com",
        secondaryValue = "s3cret", linkedAuthenticatorId = "code")
    private val card = VaultEntry(id = "card", type = EntryType.CARD, title = "Visa", primaryValue = "4111 1111 1111 1111",
        secondaryValue = "A Person", tertiaryValue = "8/2029", fourthValue = "123")
    private val question = VaultEntry(id = "q", type = EntryType.QUESTION, title = "Bank", primaryValue = "First pet?", secondaryValue = "Rex")

    @Test fun loginOffersUsernamePasswordAndLinkedCode() {
        val values = quickCopyValues(login, listOf(login, code))
        assertEquals(listOf(QuickField.USERNAME, QuickField.PASSWORD, QuickField.CODE), values.keys.toList())
        assertEquals("s3cret", values.getValue(QuickField.PASSWORD).resolve())
        assertEquals(6, values.getValue(QuickField.CODE).resolve().length)
    }

    @Test fun loginWithoutItsLinkedCodeLoadedOffersNoCode() {
        assertEquals(listOf(QuickField.USERNAME, QuickField.PASSWORD), quickCopyValues(login, listOf(login)).keys.toList())
    }

    @Test fun cardCopiesDigitsOnlyAndNormalizedExpiry() {
        val values = quickCopyValues(card, emptyList())
        assertEquals(listOf(QuickField.CARD_NUMBER, QuickField.EXPIRY, QuickField.NAME, QuickField.CVV), values.keys.toList())
        assertEquals("4111111111111111", values.getValue(QuickField.CARD_NUMBER).resolve())
        assertEquals("08/29", values.getValue(QuickField.EXPIRY).resolve())
        assertEquals("123", values.getValue(QuickField.CVV).resolve())
    }

    @Test fun emptyFieldsAreLeftOut() {
        assertEquals(listOf(QuickField.CARD_NUMBER), quickCopyValues(card.copy(secondaryValue = "", tertiaryValue = "", fourthValue = ""), emptyList()).keys.toList())
        assertEquals(listOf(QuickField.ANSWER), quickCopyValues(question, emptyList()).keys.toList())
        assertTrue(quickCopyValues(question.copy(secondaryValue = ""), emptyList()).isEmpty())
    }

    @Test fun notificationKeepsThreeActionsAndDropsNameFirst() {
        assertEquals(listOf(QuickField.CARD_NUMBER, QuickField.EXPIRY, QuickField.CVV),
            notificationFields(listOf(QuickField.CARD_NUMBER, QuickField.EXPIRY, QuickField.NAME, QuickField.CVV)))
        assertEquals(listOf(QuickField.CARD_NUMBER, QuickField.EXPIRY, QuickField.NAME),
            notificationFields(listOf(QuickField.CARD_NUMBER, QuickField.EXPIRY, QuickField.NAME)))
    }

    @Test fun storeRejectsWrongTokenAndExpiredSession() {
        var now = 1_000L
        val store = QuickCopyStore { now }
        val session = store.start("Visa", quickCopyValues(card, emptyList()), 180_000)
        assertNull(store.session(null))
        assertNull(store.session("forged"))
        assertSame(session, store.session(session.token))
        now += 179_999
        assertSame(session, store.session(session.token))
        now += 1
        assertNull(store.session(session.token))
        assertNull(store.current)
    }

    @Test fun newSessionReplacesOldAndOldTokenCannotEndIt() {
        val store = QuickCopyStore { 0L }
        val first = store.start("A", emptyMap(), 1_000)
        val second = store.start("B", emptyMap(), 1_000)
        assertNull(store.session(first.token))
        store.end(first.token)
        assertSame(second, store.current)
        store.end()
        assertNull(store.current)
    }

    @Test fun sessionIsDoneWhenEveryNotificationFieldIsCopied() {
        val store = QuickCopyStore { 0L }
        val session = store.start("Visa", quickCopyValues(card, emptyList()), QuickCopySession.DURATION_MS)
        assertFalse(store.markCopied(session, QuickField.CARD_NUMBER))
        assertFalse(store.markCopied(session, QuickField.EXPIRY))
        assertFalse(store.markCopied(session, QuickField.EXPIRY))
        // The name is not in the notification, so it never holds a session open.
        assertTrue(store.markCopied(session, QuickField.CVV))
        val loginSession = store.start("Mail", quickCopyValues(login, listOf(login)), QuickCopySession.DURATION_MS)
        assertFalse(store.markCopied(loginSession, QuickField.PASSWORD))
        assertTrue(store.markCopied(loginSession, QuickField.USERNAME))
    }

    @Test fun notificationLastsTwoMinutes() {
        assertEquals(120_000L, QuickCopySession.DURATION_MS)
    }

    @Test fun searchNeverMatchesSecrets() {
        val all = listOf(login, code, card, question)
        assertEquals(listOf(login), quickCopyMatches(all, QuickCategory.PASSWORDS, "example"))
        assertTrue(quickCopyMatches(all, QuickCategory.PASSWORDS, "s3cret").isEmpty())
        assertTrue(quickCopyMatches(all, QuickCategory.QUESTIONS, "Rex").isEmpty())
        assertEquals(listOf(question), quickCopyMatches(all, QuickCategory.QUESTIONS, "pet"))
        assertTrue(quickCopyMatches(all, QuickCategory.CARDS, "123").isEmpty())
        assertEquals(listOf(card), quickCopyMatches(all, QuickCategory.CARDS, "1111"))
    }
}
