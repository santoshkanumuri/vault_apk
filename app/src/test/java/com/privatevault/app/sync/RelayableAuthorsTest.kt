package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** A peer rejects changes signed by a removed device, so they are never offered again. */
class RelayableAuthorsTest {
    private fun member(id: String, status: MemberStatus) =
        SyncMembershipEntity("vault", id, id, "key-$id", status.name, "phone", 1, 1)

    @Test fun onlyActiveMembersAreRelayed() {
        val members = listOf(member("phone", MemberStatus.ACTIVE), member("tablet", MemberStatus.REVOKED),
            member("pc", MemberStatus.ACTIVE))
        assertEquals(setOf("phone", "pc"), relayableAuthors(members))
    }

    @Test fun anEmptyGroupRelaysNothing() {
        assertEquals(emptySet<String>(), relayableAuthors(emptyList()))
    }
}
