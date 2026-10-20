package app.libremotetvservice.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import app.libremotetvservice.LibremoteApp
import app.libremotetvservice.data.Shortcut
import app.libremotetvservice.data.Tv
import kotlin.math.min

/** Names and icons of the TV's apps, as the shortcuts show them. */
object AppLabels {
    /** The user's name, else the built-in one, else the TV's label (from Libremote Receiver), else the package. */
    fun name(app: LibremoteApp, tv: Tv, packageName: String, shortcut: Shortcut? = app.shortcuts.get(tv.pin, packageName)) =
        shortcut?.customName ?: defaultName(app, tv, packageName)

    fun defaultName(app: LibremoteApp, tv: Tv, packageName: String) =
        TvApps.nameOf(packageName) ?: tvLabel(app, tv, packageName) ?: packageName

    /** A name for the remote's status line, or null rather than a bare package name. */
    fun statusName(app: LibremoteApp, tv: Tv, packageName: String?): String? {
        packageName ?: return null
        return app.shortcuts.get(tv.pin, packageName)?.customName ?: TvApps.nameOf(packageName) ?: tvLabel(app, tv, packageName)
    }

    private fun tvLabel(app: LibremoteApp, tv: Tv, packageName: String) =
        app.receivers.apps(tv).firstOrNull { it.packageName == packageName }?.label?.takeIf(String::isNotBlank)

    /** The app's icon from the TV when Libremote Receiver shared it, else a coloured tile with its first letter. */
    fun icon(app: LibremoteApp, tv: Tv, packageName: String, name: String): Drawable =
        AppIconDrawable(app.receivers.icon(tv, packageName), letter(name, packageName), tileColor(packageName))

    /** The first letter of the name; for a bare package name, of its last part ("org.xbmc.kodi" → K). */
    fun letter(name: String, packageName: String): String {
        val word = if (name == packageName) packageName.substringAfterLast('.') else name
        return word.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "?"
    }

    /** Dark enough for white letters in both themes; always the same for the same package. */
    fun tileColor(packageName: String) = TILE_COLORS[Math.floorMod(packageName.hashCode(), TILE_COLORS.size)]

    private val TILE_COLORS = intArrayOf(
        0xFFC62828.toInt(), 0xFFAD1457.toInt(), 0xFF6A1B9A.toInt(), 0xFF4527A0.toInt(), 0xFF283593.toInt(),
        0xFF1565C0.toInt(), 0xFF0277BD.toInt(), 0xFF00838F.toInt(), 0xFF00695C.toInt(), 0xFF2E7D32.toInt(),
        0xFF558B2F.toInt(), 0xFFD84315.toInt(), 0xFF4E342E.toInt(), 0xFF37474F.toInt(),
    )
}

/**
 * An app icon in a rounded square: a square icon fills it; a TV banner (wide) is fitted inside on its own edge
 * colour; with no icon, a coloured tile with a letter.
 */
class AppIconDrawable(
    private val bitmap: Bitmap?,
    private val letter: String,
    private val tileColor: Int,
    private val cornerFraction: Float = 0.26f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val clip = Path()
    private val area = RectF()
    private val source = Rect()
    private val target = RectF()

    override fun onBoundsChange(bounds: Rect) {
        area.set(bounds)
        val radius = min(area.width(), area.height()) * cornerFraction
        clip.reset()
        clip.addRoundRect(area, radius, radius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        val image = bitmap
        if (image == null || image.width <= 0 || image.height <= 0) {
            paint.color = tileColor
            canvas.drawRect(area, paint)
            text.textSize = area.height() * 0.46f
            canvas.drawText(letter, area.centerX(), area.centerY() - (text.descent() + text.ascent()) / 2, text)
        } else {
            val aspect = image.width.toFloat() / image.height
            if (aspect in 0.8f..1.25f) {
                // A square-ish icon: crop to the square.
                val side = min(image.width, image.height)
                source.set((image.width - side) / 2, (image.height - side) / 2, (image.width + side) / 2, (image.height + side) / 2)
                target.set(area)
            } else {
                // A banner: show all of it, centred on its edge colour.
                paint.color = runCatching { image.getPixel(0, image.height / 2) }.getOrDefault(Color.DKGRAY) or 0xFF000000.toInt()
                canvas.drawRect(area, paint)
                source.set(0, 0, image.width, image.height)
                val scale = min(area.width() / image.width, area.height() / image.height)
                val w = image.width * scale
                val h = image.height * scale
                target.set(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2)
            }
            paint.color = Color.BLACK
            canvas.drawBitmap(image, source, target, paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
