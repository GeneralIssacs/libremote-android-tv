package app.libremotereceiver

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import app.libremotetvservice.protocol.InstalledApp
import java.io.ByteArrayOutputStream

/**
 * The TV's launchable apps, for paired phones' shortcuts: those with a TV launcher entry, then those with only a
 * phone-style one. The manifest's <queries> makes exactly these visible, without QUERY_ALL_PACKAGES.
 */
object InstalledApps {

    /** Each app with its label and a 128 px PNG icon, by label. Takes a moment: call off the main thread. */
    fun list(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).forEach {
                found.putIfAbsent(it.activityInfo.packageName, it)
            }
        }
        found.remove(context.packageName)
        return found.values.map { info ->
            val label = info.loadLabel(pm).toString().trim().ifEmpty { info.activityInfo.packageName }
            InstalledApp(info.activityInfo.packageName, label, runCatching { png(info.loadIcon(pm)) }.getOrNull())
        }.sortedBy { it.label.lowercase() }
    }

    private fun png(icon: Drawable): ByteArray {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, ICON_PX, ICON_PX)
        icon.draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    private const val ICON_PX = 128
}
