package com.ryxens.neonbeat.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.MainActivity
import com.ryxens.neonbeat.core.Difficulty
import com.ryxens.neonbeat.core.Judgment
import com.ryxens.neonbeat.core.Songs

/** Анимированный фон меню: падающие «ноты» в четыре дорожки. */
class BackgroundView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val start = System.nanoTime()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = (System.nanoTime() - start) / 1e9f
        p.color = C.BG0
        c.drawRect(0f, 0f, w, h, p)
        val lanes = 4
        val lw = w / lanes
        for (i in 0 until 26) {
            val lane = (i * 7) % lanes
            val speed = 90f + (i % 5) * 38f
            val len = dp(30f + (i % 4) * 22f)
            val y = ((t * speed * Env.density + i * 211f) % (h + len * 2)) - len
            val col = if (lane % 2 == 0) C.CYAN else C.PINK
            p.color = withAlpha(col, 46)
            rect.set(lane * lw + lw * 0.12f, y, (lane + 1) * lw - lw * 0.12f, y + len)
            c.drawRoundRect(rect, dp(10f), dp(10f), p)
        }
        val pulse = (Math.sin(t * 2.2) * 0.5 + 0.5).toFloat()
        p.color = withAlpha(C.PURPLE, (14 + pulse * 22).toInt())
        c.drawCircle(w / 2f, h * 0.22f, w * (0.55f + pulse * 0.08f), p)
        postInvalidateOnAnimation()
    }
}

/** Главное меню. */
class MenuScreen(act: MainActivity) : Screen(act) {
    override val view: View

    init {
        val root = FrameLayout(act)
        root.addView(BackgroundView(act), FrameLayout.LayoutParams(MATCH, MATCH))

        val col = act.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dpi(28), dpi(60), dpi(28), dpi(28))

        val logo = act.text("NEONBEAT", 44f, C.TEXT, true)
        logo.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        logo.letterSpacing = 0.1f
        logo.setShadowLayer(dp(18f), 0f, 0f, C.CYAN)
        logo.gravity = Gravity.CENTER
        col.addv(logo, MATCH, WRAP, 0f, 0, 40, 0, 4)
        val sub = act.text("РИТМ-ИГРА · СВОИ КАРТЫ", 12f, C.CYAN, true)
        sub.letterSpacing = 0.25f
        sub.gravity = Gravity.CENTER
        col.addv(sub, MATCH, WRAP, 0f, 0, 0, 0, 56)

        col.addv(act.button("▶  ИГРАТЬ", C.CYAN, 20f) { act.show(SongSelectScreen(act)) }, MATCH, WRAP, 0f, 0, 0, 0, 14)
        col.addv(act.button("✎  РЕДАКТОР КАРТ", C.PINK, 18f) { act.show(MapsScreen(act)) }, MATCH, WRAP, 0f, 0, 0, 0, 14)
        col.addv(act.ghostButton("⚙  Настройки", C.GOLD, 16f) { act.show(SettingsScreen(act)) }, MATCH, WRAP, 0f, 0, 0, 0, 12)
        col.addv(act.ghostButton("?  Как играть", C.DIM, 15f) { showHelp(act) }, MATCH, WRAP, 0f, 0, 0, 0, 0)

        val sp = View(act)
        col.addv(sp, MATCH, 0, 1f)
        val foot = act.text("5 песен · 5 сложностей · редактор · импорт своей музыки", 11f, C.DIM)
        foot.gravity = Gravity.CENTER
        col.addv(foot, MATCH, WRAP)

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        view = root
    }

    override fun onBack(): Boolean {
        act.finish()
        return true
    }

    companion object {
        fun showHelp(act: MainActivity) {
            val msg = "• Ноты падают вниз. Нажимай на дорожку, когда нота доходит до белой линии.\n\n" +
                "• Длинная нота: нажми в начале, держи палец до конца и отпусти.\n\n" +
                "• Можно нажимать несколькими пальцами сразу.\n\n" +
                "• Сложности: Лёгкая, Нормальная, Сложная, Эксперт, Безумие — чем выше, тем больше нот и аккордов.\n\n" +
                "• Звук запаздывает? Открой Настройки → Калибровка.\n\n" +
                "• Редактор: тап по дорожке — поставить или убрать ноту. Долгое нажатие и протяжка вверх — длинная нота. " +
                "Кнопка ● REC позволяет играть ноты прямо под музыку. Свою музыку можно загрузить из файла."
            AlertDialog.Builder(act).setTitle("Как играть").setMessage(msg).setPositiveButton("Понятно", null).show()
        }
    }
}

/** Выбор песни и сложности. */
class SongSelectScreen(act: MainActivity) : Screen(act) {
    override val view: View

    init {
        val col = act.vbox()
        col.setPadding(dpi(16), dpi(34), dpi(16), dpi(24))

        val head = act.hbox()
        head.addv(act.ghostButton("‹ Назад", C.DIM) { act.back() }, WRAP, WRAP)
        head.addv(act.text("Выбор песни", 22f, C.TEXT, true), 0, WRAP, 1f, 14, 0, 0, 0)
        col.addv(head, MATCH, WRAP, 0f, 0, 0, 0, 12)

        val st = Env.settings
        val mods = act.card()
        mods.addv(act.toggleRow("Без проигрыша", st.noFail) { st.noFail = it; Env.saveSettings() })
        mods.addv(act.toggleRow("Автоигра (демо, без рекордов)", st.autoPlay) { st.autoPlay = it; Env.saveSettings() })
        col.addv(mods, MATCH, WRAP, 0f, 0, 0, 0, 16)

        col.addv(act.sectionTitle("Встроенные песни"), MATCH, WRAP, 0f, 4, 0, 0, 8)
        for (def in Songs.all) {
            val card = act.card()
            val color = hsv(def.hue, 0.7f, 1f)
            val top = act.hbox()
            val bar = View(act)
            bar.background = roundBg(color, 3f)
            top.addv(bar, dpi(5), dpi(42), 0f, 0, 0, 12, 0)
            val tcol = act.vbox()
            tcol.addv(act.text(def.title, 19f, C.TEXT, true))
            val comp = Songs.composition(def)
            tcol.addv(act.text("${def.artist} · ${def.bpm.toInt()} BPM · ${fmtTime(comp.lengthMs)}", 12f, C.DIM))
            top.addv(tcol, 0, WRAP, 1f)
            card.addv(top, MATCH, WRAP, 0f, 0, 0, 0, 10)

            val row = act.hbox()
            for (d in Difficulty.values()) {
                val chart = Songs.chart(def, d)
                val rec = Env.records.get(chart.id)
                val cell = act.vbox()
                cell.gravity = Gravity.CENTER
                cell.setPadding(dpi(2), dpi(8), dpi(2), dpi(8))
                cell.background = roundBg(withAlpha(d.color, 40), 12f, withAlpha(d.color, 170), 1f)
                cell.addv(act.text(d.shortName, 10f, d.color, true).also { it.gravity = Gravity.CENTER })
                cell.addv(act.text("ур. ${chart.level}", 13f, C.TEXT, true).also { it.gravity = Gravity.CENTER })
                cell.addv(act.text(rec?.rank ?: "—", 12f, if (rec != null) C.GOLD else C.DIM, true).also { it.gravity = Gravity.CENTER })
                cell.isClickable = true
                cell.setOnClickListener { Launcher.play(act, chart) }
                row.addv(cell, 0, WRAP, 1f, 2, 0, 2, 0)
            }
            card.addv(row)
            col.addv(card, MATCH, WRAP, 0f, 0, 0, 0, 10)
        }

        col.addv(act.sectionTitle("Мои карты"), MATCH, WRAP, 0f, 4, 10, 0, 8)
        val maps = Env.maps.list()
        if (maps.isEmpty()) {
            col.addv(act.text("Пока пусто. Создай свою карту в редакторе или импортируй чужую.", 13f, C.DIM), MATCH, WRAP, 0f, 4, 0, 4, 8)
        }
        for (m in maps) {
            val chart = m.toPlayChart()
            val card = act.card()
            val top = act.hbox()
            val tcol = act.vbox()
            tcol.addv(act.text(m.title, 18f, C.TEXT, true))
            val rec = Env.records.get(chart.id)
            tcol.addv(act.text("${m.artist.ifBlank { "—" }} · ${m.bpm.toInt()} BPM · ${m.keys} клав. · ${m.notes.size} нот · ур. ${chart.level}" +
                (if (rec != null) " · ${rec.rank}" else ""), 12f, C.DIM))
            top.addv(tcol, 0, WRAP, 1f)
            top.addv(act.button("▶", C.CYAN, 18f) { Launcher.play(act, chart) }, WRAP, WRAP, 0f, 8, 0, 0, 0)
            card.addv(top)
            col.addv(card, MATCH, WRAP, 0f, 0, 0, 0, 10)
        }
        view = act.page(col)
    }
}

/** Результаты после игры. */
class ResultsScreen(
    act: MainActivity,
    private val chart: com.ryxens.neonbeat.core.PlayChart,
    private val pcm: com.ryxens.neonbeat.audio.Pcm?,
    engine: com.ryxens.neonbeat.core.GameEngine,
    newRecord: Boolean
) : Screen(act) {
    override val view: View

    private class HistView(ctx: Context, val errors: List<Double>, val range: Double) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.color = withAlpha(0xFFFFFFFF.toInt(), 22)
            c.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), p)
            val bins = IntArray(41)
            for (e in errors) {
                val k = ((e / range + 1.0) / 2.0 * 40).toInt().coerceIn(0, 40)
                bins[k]++
            }
            var mx = 1
            for (b in bins) if (b > mx) mx = b
            val bw = w / 41f
            for (i in 0..40) {
                val bh = (h - dp(8f)) * bins[i] / mx
                val dist = Math.abs(i - 20) / 20f
                p.color = if (dist < 0.33f) 0xFFFFE14D.toInt() else if (dist < 0.66f) 0xFF4CD964.toInt() else 0xFF35C8F0.toInt()
                c.drawRect(i * bw + 1f, h - bh - dp(4f), (i + 1) * bw - 1f, h - dp(4f), p)
            }
            p.color = 0xFFFFFFFF.toInt()
            c.drawRect(w / 2f - 1f, 0f, w / 2f + 1f, h, p)
        }
    }

    init {
        val col = act.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dpi(20), dpi(40), dpi(20), dpi(24))

        col.addv(act.text(chart.title, 22f, C.TEXT, true).also { it.gravity = Gravity.CENTER }, MATCH, WRAP)
        val dn = chart.diff?.label ?: "Своя карта"
        col.addv(act.text("$dn · ур. ${chart.level}", 13f, chart.diff?.color ?: C.PINK, true).also { it.gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 2, 0, 10)

        val rank = engine.rank()
        val rankColor = when (rank) {
            "SS" -> 0xFFFFE14D.toInt()
            "S" -> 0xFFFFC857.toInt()
            "A" -> C.GREEN
            "B" -> C.CYAN
            "C" -> 0xFFB45CFF.toInt()
            "D" -> C.PINK
            else -> C.RED
        }
        val rt = act.text(rank, 96f, rankColor, true)
        rt.gravity = Gravity.CENTER
        rt.setShadowLayer(dp(24f), 0f, 0f, rankColor)
        col.addv(rt, MATCH, WRAP)
        if (engine.failed) col.addv(act.text("ПРОВАЛ", 16f, C.RED, true).also { it.gravity = Gravity.CENTER }, MATCH, WRAP)
        if (newRecord) col.addv(act.text("★ НОВЫЙ РЕКОРД ★", 16f, C.GOLD, true).also { it.gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 2, 0, 0)
        if (engine.fullCombo && !engine.failed) col.addv(act.text("FULL COMBO", 14f, C.CYAN, true).also { it.gravity = Gravity.CENTER }, MATCH, WRAP)

        col.addv(act.text(engine.score().toString().padStart(7, '0'), 40f, C.TEXT, true).also { it.gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 6, 0, 0)
        col.addv(act.text(String.format("точность %.2f%%  ·  макс. комбо %d", engine.finalAccuracy(), engine.maxCombo), 14f, C.DIM).also { it.gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 0, 0, 16)

        val card = act.card()
        val names = arrayOf("PERFECT", "GREAT", "GOOD", "MISS")
        val cols = intArrayOf(0xFFFFE14D.toInt(), 0xFF4CD964.toInt(), 0xFF35C8F0.toInt(), 0xFFFF4D6D.toInt())
        for (j in Judgment.values()) {
            val r = act.hbox()
            r.setPadding(0, dpi(4), 0, dpi(4))
            r.addv(act.text(names[j.ordinal], 15f, cols[j.ordinal], true), 0, WRAP, 1f)
            r.addv(act.text(engine.counts[j.ordinal].toString(), 15f, C.TEXT, true), WRAP, WRAP)
            card.addv(r)
        }
        col.addv(card, MATCH, WRAP, 0f, 0, 0, 0, 12)

        if (engine.hitErrors.size >= 5) {
            var sum = 0.0
            for (e in engine.hitErrors) sum += e
            val avg = sum / engine.hitErrors.size
            col.addv(act.text(String.format("Разброс попаданий (в среднем %+.0f мс)", avg), 12f, C.DIM), MATCH, WRAP, 0f, 4, 0, 0, 4)
            col.addv(HistView(act, engine.hitErrors, engine.goodW), MATCH, dpi(70), 0f, 0, 0, 0, 14)
        }

        val row = act.hbox()
        row.addv(act.button("Ещё раз", C.CYAN) { Launcher.play(act, chart, true) }, 0, WRAP, 1f, 0, 0, 6, 0)
        row.addv(act.ghostButton("Назад", C.DIM, 16f) { act.back() }, 0, WRAP, 1f, 6, 0, 0, 0)
        col.addv(row)
        view = act.page(col)
    }
}
