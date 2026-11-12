package com.privatevault.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone answers a held sync lock with `busy1`; Windows up to 2.1.6 answers with `busy`. */
class MembershipWireBusyTest {
    @Test fun busyRepliesFromAndroidAndWindowsAreRecognized() {
        assertTrue(MembershipWire.isBusyReply("busy1".toByteArray(Charsets.US_ASCII)))
        assertTrue(MembershipWire.isBusyReply("busy".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun aChallengeOrAnotherControlWordIsNotBusy() {
        assertFalse(MembershipWire.isBusyReply(ByteArray(32)))
        assertFalse(MembershipWire.isBusyReply("ok".toByteArray(Charsets.US_ASCII)))
        assertFalse(MembershipWire.isBusyReply("busy2".toByteArray(Charsets.US_ASCII)))
        assertFalse(MembershipWire.isBusyReply(ByteArray(0)))
    }
}
