package app.olauncher.data

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.edit

internal enum class HomeFont(val family: String?) {
    DEFAULT(null), SANS("sans-serif"), SERIF("serif"), MONOSPACE("monospace"), CONDENSED("sans-serif-condensed")
}
internal enum class HomeClock { DEFAULT, STACKED, HIDDEN }
internal enum class HomeVertical { DEFAULT, TOP, MIDDLE, BOTTOM }
internal enum class HomeHorizontal { DEFAULT, START, CENTER, END }

internal data class PersonalAppearance(
    val font: HomeFont = HomeFont.DEFAULT,
    val labels: Int? = null,
    val clockColor: Int? = null,
    val clock: HomeClock = HomeClock.DEFAULT,
    val appVertical: HomeVertical = HomeVertical.DEFAULT,
    val clockVertical: HomeVertical = HomeVertical.DEFAULT,
    val clockHorizontal: HomeHorizontal = HomeHorizontal.DEFAULT,
) {
    val customLayout get() = appVertical != HomeVertical.DEFAULT || clockVertical != HomeVertical.DEFAULT

    fun save(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
        putString("font", font.name)
        putString("clock", clock.name)
        putString("apps_vertical", appVertical.name)
        putString("clock_vertical", clockVertical.name)
        putString("clock_horizontal", clockHorizontal.name)
        if (labels == null) remove("labels") else putInt("labels", labels)
        if (clockColor == null) remove("clock_color") else putInt("clock_color", clockColor)
    }

    companion object {
        private const val FILE = "personal_appearance"

        fun load(context: Context): PersonalAppearance {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return PersonalAppearance(
                font = readEnum(prefs.getString("font", null), HomeFont.DEFAULT),
                labels = if (prefs.contains("labels")) prefs.getInt("labels", Color.WHITE) else null,
                clockColor = if (prefs.contains("clock_color")) prefs.getInt("clock_color", Color.WHITE) else null,
                clock = readEnum(prefs.getString("clock", null), HomeClock.DEFAULT),
                appVertical = readEnum(prefs.getString("apps_vertical", null), HomeVertical.DEFAULT),
                clockVertical = readEnum(prefs.getString("clock_vertical", null), HomeVertical.DEFAULT),
                clockHorizontal = readEnum(prefs.getString("clock_horizontal", null), HomeHorizontal.DEFAULT),
            )
        }

        private inline fun <reified T : Enum<T>> readEnum(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default

        fun colorFromHex(value: String): Int? {
            val hex = value.trim().removePrefix("#")
            if (hex.length != 6) return null
            return hex.toIntOrNull(16)?.takeIf { it >= 0 && hex.all { c -> c in "0123456789abcdefABCDEF" } }
                ?.or(0xff000000.toInt())
        }
    }
}

internal fun TextView.applyHomeText(
    font: HomeFont, color: Int?, originalTypeface: Typeface?, originalColors: ColorStateList,
    bold: Boolean = false,
) {
    val style = if (bold) Typeface.BOLD else originalTypeface?.style ?: Typeface.NORMAL
    typeface = font.family?.let { Typeface.create(it, style) } ?: originalTypeface
    setTextColor(color?.let(ColorStateList::valueOf) ?: originalColors)
}
