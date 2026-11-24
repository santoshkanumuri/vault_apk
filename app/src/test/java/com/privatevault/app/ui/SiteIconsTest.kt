package com.privatevault.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SiteIconsTest {
    @Test fun subdomainWalksToRegistrableDomain() {
        assertEquals(listOf("accounts.google.com", "google.com"), SiteIconLogic.lookupKeys("accounts.google.com"))
    }

    @Test fun multiLabelSuffixIsOfferedLastSoItNeverWinsOverTheDomain() {
        assertEquals(listOf("www.bbc.co.uk", "bbc.co.uk", "co.uk"), SiteIconLogic.lookupKeys("https://www.bbc.co.uk/news"))
        val set = SiteIconSet(listOf(SiteIcon("BBC", 0xBB1919, "M0 0")), mapOf("bbc.co.uk" to 0))
        assertEquals("BBC", set.lookup("www.bbc.co.uk")?.title)
        assertNull(set.lookup("co.uk"))
    }

    @Test fun rejectsUnusableHosts() {
        assertNull(SiteIconLogic.hostOf("192.168.1.1"))
        assertNull(SiteIconLogic.hostOf("http://10.0.0.1:8080/x"))
        assertNull(SiteIconLogic.hostOf("android://com.x"))
        assertNull(SiteIconLogic.hostOf("localhost"))
        assertNull(SiteIconLogic.hostOf("intranet"))
        assertNull(SiteIconLogic.hostOf("ftp://example.com"))
        assertNull(SiteIconLogic.hostOf("[::1]:3000"))
        assertNull(SiteIconLogic.hostOf("   "))
        assertNull(SiteIconLogic.hostOf(null))
    }

    @Test fun acceptsBareHostsAndUrls() {
        assertEquals("github.com", SiteIconLogic.hostOf("github.com"))
        assertEquals("github.com", SiteIconLogic.hostOf("https://github.com:443/login?x"))
        assertEquals("github.com", SiteIconLogic.hostOf("HTTP://User@GitHub.com./a#b"))
        assertEquals("github.com", SiteIconLogic.hostOf("github.com/login"))
    }

    @Test fun glyphAndBorderChoices() {
        assertTrue(SiteIconLogic.glyphIsWhite(0x181717))
        assertFalse(SiteIconLogic.glyphIsWhite(0xF7DF1E))
        assertTrue(SiteIconLogic.needsBorder(0xFFFFFF))
        assertFalse(SiteIconLogic.needsBorder(0x181717))
    }

    @Test fun parsesSmallV1File() {
        val json = """{"v":1,"source":"x","icons":[["A \"q\"","FF0000","M0 0h24v24H0z"],["B","00ff00","M1 1"]],"domains":{"a.com":0,"b.io":1}}"""
        val set = SiteIconLogic.parse(json)!!
        assertEquals(2, set.icons.size)
        assertEquals("A \"q\"", set.lookup("https://www.a.com")?.title)
        assertEquals(0xFF0000, set.lookup("a.com")?.rgb)
        assertEquals("B", set.lookup("b.io")?.title)
        assertNull(set.lookup("c.com"))
        assertNull(SiteIconLogic.parse("""{"v":2,"icons":[],"domains":{}}"""))
        assertNull(SiteIconLogic.parse("not json"))
    }

    @Test fun realAssetResolvesKnownSites() {
        val file = listOf("src/main/assets/site_icons.json", "app/src/main/assets/site_icons.json").map(::File).first { it.exists() }
        val set = SiteIconLogic.parse(file.readText())!!
        assertEquals("GitHub", set.lookup("github.com")?.title)
        assertEquals("Google", set.lookup("https://accounts.google.com/signin")?.title)
        assertTrue(set.icons.size >= 800)
    }
}
