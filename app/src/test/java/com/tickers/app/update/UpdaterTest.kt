package com.tickers.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun versions_compareNumericallyNotLexically() {
        assertTrue(Versions.isNewer("v0.10.0", "0.9.0"))
        assertTrue(Versions.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Versions.isNewer("1.0", "0.9.9"))
        assertTrue(Versions.isNewer("0.1.1", "0.1"))
    }

    @Test
    fun versions_equalOrOlderIsNotNewer() {
        assertFalse(Versions.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.1", "0.1.0"))
        assertFalse(Versions.isNewer("v0.0.9", "0.1.0"))
    }

    @Test
    fun versions_ignoreSuffixes_andRejectGarbage() {
        assertEquals(listOf(1, 2, 3), Versions.parse("v1.2.3-beta+5"))
        assertNull(Versions.parse("latest"))
        assertFalse(Versions.isNewer("latest", "0.1.0"))
    }

    @Test
    fun parsesRealGitHubRelease_andPicksApk() {
        // Real releases/latest response from a public app (saved 2026-11-15).
        val text = javaClass.getResource("/fixtures/github_latest_release.json")!!.readText()
        val release = GitHub.json.decodeFromString<GitHubRelease>(text)
        assertEquals("v1.7.4", release.tagName)
        val apk = release.apk!!
        assertTrue(apk.name.endsWith(".apk"))
        assertTrue(apk.size > 0)
        assertTrue(apk.downloadUrl.startsWith("https://github.com/"))
    }
}
