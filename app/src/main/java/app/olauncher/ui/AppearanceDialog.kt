package app.olauncher.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import app.olauncher.R
import app.olauncher.data.HomeClock
import app.olauncher.data.HomeFont
import app.olauncher.data.HomeHorizontal
import app.olauncher.data.HomeVertical
import app.olauncher.data.PersonalAppearance
import app.olauncher.data.applyHomeText
import app.olauncher.helper.dpToPx
import java.util.Locale

internal fun showAppearanceDialog(context: Context): AlertDialog {
    val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 12.dpToPx())
    }
    fun heading(label: Int) = TextView(context).apply {
        setText(label)
        setPadding(0, 12.dpToPx(), 0, 2.dpToPx())
        controls.addView(this)
    }
    fun dropdown(label: Int, options: Int): Spinner {
        val title = heading(label)
        return Spinner(context).apply {
            id = View.generateViewId()
            title.labelFor = id
            adapter = ArrayAdapter.createFromResource(context, options, android.R.layout.simple_spinner_item)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            controls.addView(this)
        }
    }
    fun color(label: Int): EditText {
        val title = heading(label)
        val field = EditText(context).apply {
            id = View.generateViewId()
            title.labelFor = id
            setSingleLine(true)
            setHint(R.string.personal_hex_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        controls.addView(Button(context).apply {
            setText(R.string.personal_pick_color)
            setOnClickListener {
                showHomeColorPicker(context, PersonalAppearance.colorFromHex(field.text.toString())) { value ->
                    field.setText(value?.let { String.format(Locale.ROOT, "#%06X", it and 0xffffff) }.orEmpty())
                }
            }
        })
        controls.addView(field)
        return field
    }
    val sampleClock = TextView(context).apply { textSize = 34f; controls.addView(this) }
    val sampleApps = TextView(context).apply { textSize = 22f; setText(R.string.personal_app_preview); controls.addView(this) }
    val previewSize = sampleClock.textSize
    val clockColors = sampleClock.textColors
    val appColors = sampleApps.textColors
    val clockTypeface = sampleClock.typeface
    val appTypeface = sampleApps.typeface
    val font = dropdown(R.string.personal_font, R.array.personal_fonts)
    val style = dropdown(R.string.personal_clock_type, R.array.personal_clock_types)
    val appVertical = dropdown(R.string.personal_app_vertical, R.array.personal_vertical_positions)
    val clockVertical = dropdown(R.string.personal_clock_vertical, R.array.personal_vertical_positions)
    val clockHorizontal = dropdown(R.string.personal_clock_horizontal, R.array.personal_horizontal_positions)
    val appColor = color(R.string.personal_app_color)
    val clockColor = color(R.string.personal_clock_color)
    fun selectedFont() = HomeFont.entries[font.selectedItemPosition.coerceAtLeast(0)]
    fun selectedClock() = HomeClock.entries[style.selectedItemPosition.coerceAtLeast(0)]
    fun preview() {
        sampleClock.text = selectedClock().preview
        sampleClock.visibility = if (selectedClock() == HomeClock.HIDDEN) View.GONE else View.VISIBLE
        sampleClock.applyHomeText(selectedFont(), PersonalAppearance.colorFromHex(clockColor.text.toString()), clockTypeface, clockColors)
        selectedClock().style(sampleClock, previewSize)
        sampleApps.applyHomeText(selectedFont(), PersonalAppearance.colorFromHex(appColor.text.toString()), appTypeface, appColors)
    }
    fun Int?.hex() = this?.let { String.format(Locale.ROOT, "#%06X", it and 0xffffff) }.orEmpty()
    fun fill(value: PersonalAppearance) {
        font.setSelection(value.font.ordinal)
        style.setSelection(value.clock.ordinal)
        appVertical.setSelection(value.appVertical.ordinal)
        clockVertical.setSelection(value.clockVertical.ordinal)
        clockHorizontal.setSelection(value.clockHorizontal.ordinal)
        appColor.setText(value.labels.hex())
        clockColor.setText(value.clockColor.hex())
        preview()
    }
    val listener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = preview()
    }
    font.onItemSelectedListener = listener
    style.onItemSelectedListener = listener
    listOf(appColor, clockColor).forEach { field -> field.doAfterTextChanged { field.error = null; preview() } }
    fill(PersonalAppearance.load(context))
    return AlertDialog.Builder(context)
        .setTitle(R.string.personal_appearance)
        .setView(ScrollView(context).apply { addView(controls) })
        .setPositiveButton(R.string.personal_apply, null)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(R.string.personal_reset, null)
        .create().apply {
            setOnShowListener {
                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { fill(PersonalAppearance()) }
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val labels = PersonalAppearance.colorFromHex(appColor.text.toString())
                    val clock = PersonalAppearance.colorFromHex(clockColor.text.toString())
                    var valid = true
                    if (labels == null && appColor.text.isNotBlank()) {
                        appColor.error = context.getString(R.string.personal_hex_error)
                        valid = false
                    }
                    if (clock == null && clockColor.text.isNotBlank()) {
                        clockColor.error = context.getString(R.string.personal_hex_error)
                        valid = false
                    }
                    if (valid) {
                        PersonalAppearance(selectedFont(), labels, clock, selectedClock(),
                            HomeVertical.entries[appVertical.selectedItemPosition],
                            HomeVertical.entries[clockVertical.selectedItemPosition],
                            HomeHorizontal.entries[clockHorizontal.selectedItemPosition],
                        ).save(context)
                        dismiss()
                    }
                }
            }
            show()
        }
}
