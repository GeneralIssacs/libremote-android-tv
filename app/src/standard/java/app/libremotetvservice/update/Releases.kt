package app.libremotetvservice.update

/**
 * Android-free helpers for reading Libremote's GitHub releases; kept pure so they can be unit-tested on the JVM.
 * A release `v<version>` carries `libremote-<version>.apk` (this app) and `libremote-receiver-<version>.apk`
 * (Libremote Receiver for the TV), whose version may be an older one when the receiver didn't change.
 */
object Releases {

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    /** The receiver's APK in a release, and its version, read from the asset's name. */
    data class ReceiverAsset(val version: String, val asset: Asset)

    private val RECEIVER_NAME = Regex("""libremote-receiver-(\d+\.\d+\.\d+)\.apk""", RegexOption.IGNORE_CASE)

    /** The numeric parts of a version or tag: "v1.2.10" is [1, 2, 10]. A suffix such as "-beta" is ignored. */
    fun parts(version: String): List<Int> =
        Regex("""\d+(?:\.\d+)*""").find(version)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()

    /** Whether [candidate] is a later version than [current]; missing parts count as 0, so 1.1 equals 1.1.0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Both apps' versionCode for [version]: MAJOR * 10000 + MINOR * 100 + PATCH, so 1.2.3 is 10203. Null when it
     * isn't such a version (no parts, or a minor or patch over 99).
     */
    fun versionCode(version: String): Long? {
        val p = parts(version)
        if (p.isEmpty() || p.size > 3 || p.drop(1).any { it > 99 }) return null
        return p[0] * 10000L + p.getOrElse(1) { 0 } * 100L + p.getOrElse(2) { 0 }
    }

    /** The version a versionCode stands for: 10203 is "1.2.3". */
    fun versionName(code: Long): String = "${code / 10000}.${code / 100 % 100}.${code % 100}"

    /** This app's APK in the release tagged [version]: "libremote-<version>.apk". */
    fun pickApp(assets: List<Asset>, version: String): Asset? =
        assets.firstOrNull { it.name.equals("libremote-$version.apk", ignoreCase = true) }

    /** Libremote Receiver's APK in a release, "libremote-receiver-<version>.apk"; the newest if there are several. */
    fun pickReceiver(assets: List<Asset>): ReceiverAsset? =
        assets.mapNotNull { asset -> RECEIVER_NAME.matchEntire(asset.name)?.let { ReceiverAsset(it.groupValues[1], asset) } }
            .maxByOrNull { versionCode(it.version) ?: -1 }

    /**
     * Whether a TV whose receiver reported [tvVersionCode] should be offered [release]'s receiver. A receiver that
     * didn't say (older than 1.0.0, or not seen yet) gets no offer.
     */
    fun receiverIsNewer(release: ReceiverAsset?, tvVersionCode: Long?): Boolean {
        val code = release?.let { versionCode(it.version) } ?: return false
        return tvVersionCode != null && code > tvVersionCode
    }

    /** GitHub's "sha256:<hex>" asset digest as lowercase hex; null when missing or of another kind. */
    fun sha256(digest: String?): String? =
        digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    /** Release notes as plain text for a dialog: Markdown marks dropped, list items as bullets, at most [max] chars. */
    fun plainNotes(markdown: String?, max: Int = 1000): String {
        val text = (markdown ?: "").replace("\r\n", "\n").lines().joinToString("\n") { line ->
            line.trimEnd()
                .replace(Regex("""^#{1,6}\s+"""), "")
                .replace(Regex("""^\s*[-*+]\s+"""), "• ")
                .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                .replace(Regex("""__(.+?)__"""), "$1")
                .replace(Regex("""`([^`]*)`"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        }.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length <= max) text else text.take(max).trimEnd() + "…"
    }
}
