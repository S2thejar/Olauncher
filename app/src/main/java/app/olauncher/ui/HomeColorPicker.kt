package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.dpToPx
import java.util.Locale
import kotlin.math.roundToInt

internal fun showHomeColorPicker(context: Context, initial: Int?, use: (Int?) -> Unit) {
    val hsv = FloatArray(3)
    Color.colorToHSV(initial ?: Color.WHITE, hsv)
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 8.dpToPx())
    }
    val preview = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        minHeight = 56.dpToPx()
        panel.addView(this, -1, -2)
    }
    val presets = LinearLayout(context).also { panel.addView(it, -1, 48.dpToPx()) }
    val tracks = mutableListOf<View>()
    val sliders = listOf(R.string.personal_color_hue, R.string.personal_color_saturation, R.string.personal_color_brightness)
        .mapIndexed { index, label ->
            val title = TextView(context).apply {
                setText(label)
                setPadding(0, 8.dpToPx(), 0, 0)
                panel.addView(this)
            }
            tracks += View(context).also { panel.addView(it, -1, 10.dpToPx()) }
            SeekBar(context).apply {
                id = View.generateViewId()
                title.labelFor = id
                contentDescription = context.getString(label)
                max = if (index == 0) 359 else 100
                minHeight = 40.dpToPx()
                panel.addView(this, -1, -2)
            }
        }
    fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xffffff)
    fun gradient(colors: IntArray) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
        cornerRadius = 4.dpToPx().toFloat()
    }
    tracks[0].background = gradient(intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED))
    fun update() {
        val color = Color.HSVToColor(hsv)
        preview.setBackgroundColor(color)
        preview.setTextColor(if (ColorUtils.calculateLuminance(color) > .18) Color.BLACK else Color.WHITE)
        preview.text = context.getString(R.string.personal_color_preview, hex(color))
        tracks[1].background = gradient(intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, 1f)), Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))))
        tracks[2].background = gradient(intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))))
    }
    fun sync() {
        sliders.forEachIndexed { index, bar -> bar.progress = (hsv[index] * if (index == 0) 1f else 100f).roundToInt() }
        update()
    }
    sliders.forEachIndexed { index, bar ->
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                hsv[index] = if (index == 0) progress.toFloat() else progress / 100f
                if (index == 0 && (hsv[1] == 0f || hsv[2] == 0f)) {
                    hsv[1] = 1f
                    hsv[2] = 1f
                    sync()
                } else update()
            }
        })
    }
    arrayOf("#FFFFFF", "#101010", "#F29D99", "#F7D68A", "#A7E3BD", "#8BDDF7", "#C6ABFC").forEach { value ->
        val color = Color.parseColor(value)
        presets.addView(Button(context).apply {
            contentDescription = value
            minWidth = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 8.dpToPx().toFloat()
                setStroke(1.dpToPx(), 0xff888888.toInt())
            }
            setOnClickListener { Color.colorToHSV(color, hsv); sync() }
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(2.dpToPx(), 4.dpToPx(), 2.dpToPx(), 4.dpToPx()) })
    }
    sync()
    AlertDialog.Builder(context).setTitle(R.string.personal_pick_color)
        .setView(ScrollView(context).apply { addView(panel) })
        .setPositiveButton(R.string.personal_apply) { _, _ -> use(Color.HSVToColor(hsv)) }
        .setNeutralButton(R.string.personal_color_default) { _, _ -> use(null) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
