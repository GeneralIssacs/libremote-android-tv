package app.libremotetvservice.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    private fun asset(name: String) = Releases.Asset(name, "https://example.org/$name", 1, null)

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Releases.isNewer("v1.0.10", "1.0.9"))
        assertTrue(Releases.isNewer("1.1", "1.0.9"))
        assertTrue(Releases.isNewer("2.0.0", "1.99.99"))
        assertFalse(Releases.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Releases.isNewer("1.1", "1.1.0"))
        assertFalse(Releases.isNewer("0.9.9", "1.0.0"))
        assertFalse(Releases.isNewer("nightly", "1.0.0"))
        assertEquals(listOf(1, 2, 0), Releases.parts("v1.2.0-beta1"))
    }

    @Test
    fun turnsVersionsIntoVersionCodesAndBack() {
        assertEquals(10000L, Releases.versionCode("1.0.0"))
        assertEquals(10001L, Releases.versionCode("v1.0.1"))
        assertEquals(10203L, Releases.versionCode("1.2.3"))
        assertEquals(10100L, Releases.versionCode("1.1"))
        assertEquals(301L, Releases.versionCode("0.3.1"))
        assertNull(Releases.versionCode("1.100.0"))
        assertNull(Releases.versionCode("1.2.3.4"))
        assertNull(Releases.versionCode("latest"))
        assertEquals("1.0.1", Releases.versionName(10001))
        assertEquals("0.3.1", Releases.versionName(301))
        assertEquals("12.34.56", Releases.versionName(123456))
    }

    @Test
    fun picksEachAppsApk() {
        val app = asset("libremote-1.2.0.apk")
        val receiver = asset("libremote-receiver-1.2.0.apk")
        val sums = asset("SHA256SUMS")
        val assets = listOf(receiver, sums, app)
        assertEquals(app, Releases.pickApp(assets, "1.2.0"))
        assertNull(Releases.pickApp(assets, "1.2.1"))
        // The receiver's name is never taken for the phone app's, whatever the order.
        assertNull(Releases.pickApp(listOf(receiver), "1.2.0"))
        assertEquals(Releases.ReceiverAsset("1.2.0", receiver), Releases.pickReceiver(assets))
        assertNull(Releases.pickReceiver(listOf(app, sums)))
    }

    @Test
    fun theReceiverCanBeOlderThanTheRelease() {
        // A release where the receiver didn't change carries its last version.
        val old = asset("libremote-receiver-1.0.0.apk")
        val newer = asset("libremote-receiver-1.0.2.apk")
        assertEquals("1.0.0", Releases.pickReceiver(listOf(asset("libremote-1.1.0.apk"), old))?.version)
        assertEquals(newer, Releases.pickReceiver(listOf(old, newer, asset("libremote-receiver-latest.apk")))?.asset)
    }

    @Test
    fun offersTheReceiverOnlyToAnOlderKnownVersion() {
        val release = Releases.ReceiverAsset("1.0.1", asset("libremote-receiver-1.0.1.apk"))
        assertTrue(Releases.receiverIsNewer(release, 10000))
        assertFalse(Releases.receiverIsNewer(release, 10001))
        assertFalse(Releases.receiverIsNewer(release, 10100))
        // A receiver older than 1.0.0 doesn't say its version: no offer.
        assertFalse(Releases.receiverIsNewer(release, null))
        assertFalse(Releases.receiverIsNewer(null, 10000))
    }

    @Test
    fun readsGithubDigests() {
        val hex = "37d436a201c759fd35f485daab3bbd3f8d54156fea673a23bbcf72a6f38e6ee4"
        assertEquals(hex, Releases.sha256("sha256:" + hex.uppercase()))
        assertNull(Releases.sha256("null"))
        assertNull(Releases.sha256("sha512:abc"))
        assertNull(Releases.sha256("sha256:1234"))
        assertNull(Releases.sha256(null))
    }

    @Test
    fun flattensReleaseNotes() {
        val md = "## What's new\r\n\r\n- **Faster** start\n* Fixed [a bug](https://x/1) in `sending`\n\n\n\nThanks!"
        assertEquals("What's new\n\n• Faster start\n• Fixed a bug in sending\n\nThanks!", Releases.plainNotes(md))
        assertEquals("", Releases.plainNotes(null))
        assertEquals("abcde…", Releases.plainNotes("abcdefgh", max = 5))
    }
}
