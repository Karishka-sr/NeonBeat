package com.ryxens.neonbeat.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.ryxens.neonbeat.Env

/** Цвета интерфейса (неоновая тёмная тема). */
object C {
    const val BG0 = 0xFF080A16.toInt()
    const val BG1 = 0xFF121631.toInt()
    const val CARD = 0xFF1A1F42.toInt()
    const val CARD2 = 0xFF252B5A.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val DIM = 0xFF9BA4CC.toInt()
    const val CYAN = 0xFF35E0FF.toInt()
    const val PINK = 0xFFFF4DB8.toInt()
    const val GOLD = 0xFFFFC857.toInt()
    const val GREEN = 0xFF4CD964.toInt()
    const val RED = 0xFFFF4D6D.toInt()
    const val PURPLE = 0xFFB45CFF.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun dp(v: Float): Float = v * Env.density
fun dpi(v: Int): Int = (v * Env.density + 0.5f).toInt()

fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

fun roundBg(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = dp(radiusDp)
    if (strokeDp > 0f) d.setStroke(dpi(strokeDp.toInt().coerceAtLeast(1)), strokeColor)
    return d
}

fun gradBg(top: Int, bottom: Int): GradientDrawable {
    val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
    return d
}

fun Context.text(s: String, sp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s
    t.setTextColor(color)
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    return t
}

fun Context.button(s: String, color: Int = C.CYAN, sp: Float = 16f, onClick: () -> Unit): TextView {
    val t = text(s, sp, C.BG0, true)
    t.gravity = Gravity.CENTER
    t.setPadding(dpi(16), dpi(12), dpi(16), dpi(12))
    t.background = roundBg(color, 14f)
    t.isClickable = true
    t.setOnClickListener {
        Env.sfx.click(0.5f * Env.settings.sfxVol / 100f)
        onClick()
    }
    return t
}

fun Context.ghostButton(s: String, color: Int = C.CYAN, sp: Float = 14f, onClick: () -> Unit): TextView {
    val t = text(s, sp, color, true)
    t.gravity = Gravity.CENTER
    t.setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
    t.background = roundBg(withAlpha(color, 30), 12f, withAlpha(color, 140), 1f)
    t.isClickable = true
    t.setOnClickListener {
        Env.sfx.click(0.5f * Env.settings.sfxVol / 100f)
        onClick()
    }
    return t
}

fun Context.vbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

fun LinearLayout.addv(
    v: View, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
): View {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(dpi(l), dpi(t), dpi(r), dpi(b))
    addView(v, p)
    return v
}

/** Прокручиваемая страница с тёмным фоном и отступами сверху/снизу. */
fun Context.page(content: View): ScrollView {
    val sv = ScrollView(this)
    sv.background = gradBg(C.BG1, C.BG0)
    sv.isFillViewport = true
    sv.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
    return sv
}

fun Context.card(): LinearLayout {
    val l = vbox()
    l.background = roundBg(C.CARD, 18f)
    l.setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
    return l
}

fun Context.sectionTitle(s: String): TextView {
    val t = text(s.uppercase(), 12f, C.CYAN, true)
    t.letterSpacing = 0.12f
    return t
}

/** Строка с ползунком: подпись, значение справа, SeekBar снизу. */
fun Context.seekRow(
    label: String, min: Int, max: Int, value: Int,
    fmt: (Int) -> String, onChange: (Int) -> Unit
): View {
    val box = vbox()
    box.setPadding(0, dpi(8), 0, dpi(4))
    val top = hbox()
    val name = text(label, 15f, C.TEXT)
    val v = text(fmt(value), 15f, C.CYAN, true)
    top.addv(name, 0, WRAP, 1f)
    top.addv(v, WRAP, WRAP)
    box.addv(top)
    val sb = SeekBar(this)
    sb.max = max - min
    sb.progress = value - min
    sb.progressTintList = ColorStateList.valueOf(C.CYAN)
    sb.thumbTintList = ColorStateList.valueOf(C.CYAN)
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            val x = p + min
            v.text = fmt(x)
            if (fromUser) onChange(x)
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    box.addv(sb, MATCH, WRAP, 0f, 0, 2, 0, 0)
    return box
}

/** Строка-переключатель «подпись ........ [ВКЛ]». */
fun Context.toggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
    val row = hbox()
    row.setPadding(0, dpi(8), 0, dpi(8))
    row.addv(text(label, 15f, C.TEXT), 0, WRAP, 1f)
    var state = value
    val pill = text(if (state) "ВКЛ" else "ВЫКЛ", 13f, C.BG0, true)
    pill.gravity = Gravity.CENTER
    pill.setPadding(dpi(16), dpi(7), dpi(16), dpi(7))
    fun paint() {
        pill.text = if (state) "ВКЛ" else "ВЫКЛ"
        pill.background = roundBg(if (state) C.GREEN else C.DIM, 12f)
    }
    paint()
    pill.setOnClickListener {
        state = !state
        paint()
        onChange(state)
    }
    row.addv(pill, WRAP, WRAP)
    return row
}

fun Context.confirmDialog(title: String, msg: String, yes: String = "Да", onYes: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton("Отмена", null)
        .show()
}

fun Context.inputField(hint: String, value: String, numeric: Boolean = false): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setText(value)
    e.setTextColor(C.TEXT)
    e.setHintTextColor(C.DIM)
    e.setSingleLine(true)
    e.setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
    e.background = roundBg(C.CARD2, 12f)
    if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    return e
}

fun hsv(h: Float, s: Float, v: Float): Int = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))

fun fmtTime(ms: Int): String {
    val t = Math.max(0, ms) / 1000
    return String.format("%d:%02d", t / 60, t % 60)
}

/** Переключатель из нескольких вариантов в одну строку. */
fun Context.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val row = hbox()
    val cells = ArrayList<TextView>()
    var sel = selected
    fun paint() {
        for (i in cells.indices) {
            val on = i == sel
            cells[i].setTextColor(if (on) C.BG0 else C.TEXT)
            cells[i].background = roundBg(if (on) C.CYAN else C.CARD2, 11f)
        }
    }
    for (i in options.indices) {
        val t = text(options[i], 14f, C.TEXT, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dpi(4), dpi(10), dpi(4), dpi(10))
        t.setOnClickListener {
            sel = i
            paint()
            onSelect(i)
        }
        cells.add(t)
        row.addv(t, 0, WRAP, 1f, if (i == 0) 0 else 3, 0, if (i == options.size - 1) 0 else 3, 0)
    }
    paint()
    return row
}
