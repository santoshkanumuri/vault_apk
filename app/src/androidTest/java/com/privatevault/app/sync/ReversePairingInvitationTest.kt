package com.privatevault.app.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ReversePairingInvitationTest {
    private fun link(vararg addresses: String): String = "nuvori-pair-reverse://v2/" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(JsonObject().apply {
            add("addresses", JsonArray().apply { addresses.forEach { add(it) } })
            addProperty("port", 20001)
            addProperty("session", "test-session")
            addProperty("code", "123456789012345678901234")
        }.toString().toByteArray(Charsets.UTF_8))

    @Test fun keepsBothVpnAndLanCandidates() {
        assertEquals(listOf("10.10.0.2", "192.168.1.2"),
            ReversePairingInvitation.decode(link("10.10.0.2", "192.168.1.2")).addresses)
    }

    @Test fun rejectsUnboundedEmptyPublicAndDuplicateCandidates() {
        for (value in listOf(link(), link("8.8.8.8"), link("127.0.0.1"),
            link("192.168.1.2", "192.168.1.2"), link(*(1..17).map { "192.168.1.$it" }.toTypedArray()))) {
            assertTrue(runCatching { ReversePairingInvitation.decode(value) }.isFailure)
        }
    }
}
