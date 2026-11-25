package app.olauncher.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import app.olauncher.R
import app.olauncher.helper.dpToPx

internal class HomeTitleSizing(context: Context) {
    private val prefs = context.getSharedPreferences("personal_home_titles", Context.MODE_PRIVATE)

    fun size(slot: Int) = prefs.getInt("size_$slot", DEFAULT).coerceIn(MIN, MAX)

    fun apply(label: TextView, basePx: Float, slot: Int) = label.setTextSize(
        TypedValue.COMPLEX_UNIT_PX, basePx * size(slot) / DEFAULT,
    )

    fun dialog(label: TextView, basePx: Float, slot: Int, onApply: () -> Unit): AlertDialog {
        val context = label.context
        val preview = TextView(context).apply {
            text = label.text.ifEmpty { label.hint }
            typeface = label.typeface
            gravity = Gravity.CENTER
        }
        val percentage = TextView(context).apply { gravity = Gravity.CENTER }
        val slider = SeekBar(context).apply {
            contentDescription = context.getString(R.string.personal_title_size)
            max = (MAX - MIN) / STEP
            progress = (size(slot) - MIN) / STEP
        }
        fun update() {
            val value = MIN + slider.progress * STEP
            percentage.text = context.getString(R.string.personal_title_percentage, value)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_PX, basePx * value / DEFAULT)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = update()
        })
        update()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 16.dpToPx(), 24.dpToPx(), 8.dpToPx())
            listOf(preview, percentage, slider).forEach { addView(it, -1, -2) }
        }
        return AlertDialog.Builder(context).setTitle(R.string.personal_title_size).setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.personal_reset) { _, _ ->
                prefs.edit { remove("size_$slot") }
                onApply()
            }
            .setPositiveButton(R.string.personal_apply) { _, _ ->
                prefs.edit { putInt("size_$slot", MIN + slider.progress * STEP) }
                onApply()
            }.show()
    }

    private companion object {
        const val MIN = 60
        const val MAX = 160
        const val STEP = 5
        const val DEFAULT = 100
    }
}
