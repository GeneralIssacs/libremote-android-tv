package app.libremotetvservice.ui

/**
 * What Libremote knows about TV apps without asking the TV: names of popular ones (plain text: no logos or other
 * brand assets), the link scheme an app opens by itself, the starting shortcuts, and the packages that are not
 * worth a shortcut. A wrong or stale entry only gives an app a wrong default name, which the user can rename.
 */
object TvApps {
    /**
     * A known app. [scheme] is a link only that app handles, so the TV opens it without needing to see the app.
     * [suggest]: offered under "Popular apps" when adding a shortcut.
     */
    data class Known(val name: String, val scheme: String? = null, val suggest: Boolean = true)

    /** The shortcuts a TV starts with, in this order. */
    val template = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.spotify.tv.android",
    )

    /**
     * Android TV package names. Add a scheme only once a TV is seen to open the app with it (dumpsys package lists
     * an app's schemes): a scheme nothing handles makes the TV drop the connection.
     */
    private val known = mapOf(
        // vnd.youtube: YouTube 2.x on the Android TV 14 emulator (dumpsys, and the link opened it).
        "com.google.android.youtube.tv" to Known("YouTube", "vnd.youtube://"),
        "com.google.android.youtube.tvkids" to Known("YouTube Kids"),
        "com.google.android.youtube.tvmusic" to Known("YouTube Music"),
        "com.google.android.youtube.tvunplugged" to Known("YouTube TV"),
        "com.netflix.ninja" to Known("Netflix"),
        "com.netflix.mediaclient" to Known("Netflix"),
        "com.amazon.amazonvideo.livingroom" to Known("Prime Video"),
        "com.amazon.avod.thirdpartyclient" to Known("Prime Video"),
        "com.disney.disneyplus" to Known("Disney+"),
        "com.spotify.tv.android" to Known("Spotify"),
        "com.apple.atve.androidtv.appletv" to Known("Apple TV"),
        "com.wbd.stream" to Known("HBO Max"),
        "com.hbo.hbonow" to Known("HBO Max"),
        "com.hulu.livingroomplus" to Known("Hulu"),
        "com.cbs.ott" to Known("Paramount+"),
        "com.peacocktv.peacockandroid" to Known("Peacock"),
        "tv.pluto.android" to Known("Pluto TV"),
        "com.crunchyroll.crunchyroid" to Known("Crunchyroll"),
        "com.dazn" to Known("DAZN"),
        "tv.twitch.android.app" to Known("Twitch"),
        "deezer.android.tv" to Known("Deezer"),
        "com.canal.android.canal" to Known("myCANAL"),
        "fr.francetv.pluzz" to Known("france.tv"),
        "tv.molotov.app" to Known("Molotov"),
        "com.plexapp.android" to Known("Plex"),
        "org.xbmc.kodi" to Known("Kodi"),
        "org.jellyfin.androidtv" to Known("Jellyfin"),
        "org.videolan.vlc" to Known("VLC"),
        "com.mxtech.videoplayer.ad" to Known("MX Player"),
        "com.valvesoftware.steamlink" to Known("Steam Link"),
        "com.nvidia.geforcenow" to Known("GeForce NOW"),
        "com.google.android.play.games" to Known("Play Games"),
        "com.google.android.tv" to Known("Live Channels"),
        "com.android.tv" to Known("Live TV"),
        "app.sofabrowser" to Known("Sofa", suggest = false),
        // Not shortcuts (see ignored), but named for the remote's status line.
        "com.google.android.tvlauncher" to Known("Home"),
        "com.google.android.apps.tv.launcherx" to Known("Home"),
        "com.google.android.leanbacklauncher" to Known("Home"),
        "com.android.tv.settings" to Known("Settings"),
        "com.google.android.katniss" to Known("Search"),
        "com.android.vending" to Known("Play Store"),
    )

    /**
     * Never learned as shortcuts: home screens, settings, system screens, Libremote Receiver itself, and the Play
     * Store, which shows up whenever a launch falls back to an app's store page.
     */
    private val notApps = setOf(
        "android",
        "com.android.systemui",
        "com.android.vending",
        "com.android.intentresolver",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.tv.frameworkpackagestubs",
        "com.google.android.gms",
        "com.google.android.katniss",
        "com.google.android.tvrecommendations",
        "com.google.android.tv.remote.service",
        "com.google.android.apps.mediashell",
        "com.google.android.backdrop",
        "com.google.android.apps.tv.dreamx",
        "com.google.android.inputmethod.latin",
        "com.google.android.leanback.ime",
        "app.libremotereceiver",
        "app.libremotetvservice.tv",
    )

    fun known(packageName: String?): Known? = packageName?.let(known::get)

    /** The built-in name, if the app is a well-known one. */
    fun nameOf(packageName: String?): String? = known(packageName)?.name

    /** Popular apps for the "add a shortcut" list: the named ones that make sense as shortcuts. */
    val popular: List<String> get() = known.keys.filter { !isIgnored(it) && known.getValue(it).suggest }.distinctBy { known.getValue(it).name }

    fun isIgnored(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p in notApps || "launcher" in p || p.endsWith(".settings") || p.endsWith(".tvsettings")
    }

    /** A package name as Android allows them (at least two segments, each starting with a letter). */
    fun isPackageName(text: String) = PACKAGE.matches(text)

    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")
}
