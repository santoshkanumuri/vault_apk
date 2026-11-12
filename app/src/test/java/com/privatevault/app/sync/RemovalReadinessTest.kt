package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RemovalReadinessTest {
    private val hash = "a".repeat(64)
    private fun member(id: String, status: MemberStatus = MemberStatus.ACTIVE) =
        SyncMembershipEntity("vault", id, id, "key-$id", status.name, "phone", 1, 1)
    private fun received(vararg heads: Pair<String, Long>) =
        SyncProgress(heads.associate { it.first to SyncChainHead(it.second, hash) }, emptyMap())
    private val members = listOf(member("phone"), member("tablet"), member("pc"), member("old", MemberStatus.REVOKED))
    private val local = mapOf("tablet" to SyncChainHead(5, hash))

    @Test fun aPeerThatLacksTheRemovedDevicesChangesIsNamed() {
        val behind = RemovalReadiness.peersBehind("tablet", "phone", members, local, mapOf("pc" to received("tablet" to 3)))
        assertEquals(listOf("pc"), behind.map { it.deviceId })
    }

    @Test fun aPeerThatNeverReportedCountsAsBehind() {
        assertEquals(listOf("pc"), RemovalReadiness.peersBehind("tablet", "phone", members, local, emptyMap()).map { it.deviceId })
    }

    @Test fun nothingIsNamedWhenEveryPeerIsCaughtUpOrThereIsNothingToReceive() {
        assertEquals(emptyList<String>(), RemovalReadiness.peersBehind("tablet", "phone", members, local,
            mapOf("pc" to received("tablet" to 5))).map { it.deviceId })
        assertEquals(emptyList<String>(), RemovalReadiness.peersBehind("tablet", "phone", members, emptyMap(), emptyMap())
            .map { it.deviceId })
    }
}
