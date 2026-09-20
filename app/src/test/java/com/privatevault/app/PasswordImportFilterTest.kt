package com.privatevault.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordImportFilterTest {
    @Test
    fun filtersMatchExpectedStatuses() {
        PasswordImportStatus.entries.forEach { status ->
            assertTrue(PasswordImportFilter.ALL.matches(status))
        }

        assertTrue(PasswordImportFilter.NEW.matches(PasswordImportStatus.NEW))
        assertFalse(PasswordImportFilter.NEW.matches(PasswordImportStatus.ALREADY_SAVED))

        assertTrue(PasswordImportFilter.ALREADY_SAVED.matches(PasswordImportStatus.ALREADY_SAVED))
        assertFalse(PasswordImportFilter.ALREADY_SAVED.matches(PasswordImportStatus.NEW))

        assertTrue(PasswordImportFilter.NEEDS_DECISION.matches(PasswordImportStatus.PASSWORD_DIFFERS))
        assertTrue(PasswordImportFilter.NEEDS_DECISION.matches(PasswordImportStatus.AMBIGUOUS))
        assertFalse(PasswordImportFilter.NEEDS_DECISION.matches(PasswordImportStatus.NEW))
        assertFalse(PasswordImportFilter.NEEDS_DECISION.matches(PasswordImportStatus.ALREADY_SAVED))
    }
}
