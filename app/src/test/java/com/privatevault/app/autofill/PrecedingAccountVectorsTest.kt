package com.privatevault.app.autofill

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared with the browser extension (`extension/fixtures/preceding-account-v1.json` in the Windows
 * repository), so the phone and the browser read the box before a lone password the same way.
 */
class PrecedingAccountVectorsTest {
    @Test fun theBoxBeforeALonePasswordIsReadAsTheSharedVectorsSay() {
        val text = requireNotNull(javaClass.classLoader?.getResource("autofill-vectors/preceding-account-v1.json")).readText()
        val cases = JsonParser.parseString(text).asJsonObject.getAsJsonArray("cases")
        assertTrue(cases.size() > 0)
        cases.forEach { element ->
            val case = element.asJsonObject
            val name = case.get("name").asString
            val form = webPage(htmlForm(htmlInput(0, name = name), htmlInput(1, type = "password")))
            val fields = classifyLoginForm(listOf(form), browser = true)
            assertEquals(name, 1, fields?.password)
            assertEquals(name, if (case.get("username").asBoolean) 0 else null, fields?.username)
        }
    }
}
