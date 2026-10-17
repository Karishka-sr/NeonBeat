package com.ryxens.neonbeat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.MainActivity
import com.ryxens.neonbeat.core.GameSettings
import com.ryxens.neonbeat.core.RecordBook

/** Настройки игры. */
class SettingsScreen(act: MainActivity) : Screen(act) {
    override val view: View

    init {
        val st = Env.settings
        val col = act.vbox()
        col.setPadding(dpi(16), dpi(34), dpi(16), dpi(28))

        val head = act.hbox()
        head.addv(act.ghostButton("‹ Назад", C.DIM) { act.back() }, WRAP, WRAP)
        head.addv(act.text("Настройки", 22f, C.TEXT, true), 0, WRAP, 1f, 14, 0, 0, 0)
        col.addv(head, MATCH, WRAP, 0f, 0, 0, 0, 12)

        fun save() = Env.saveSettings()

        col.addv(act.sectionTitle("Звук"), MATCH, WRAP, 0f, 4, 0, 0, 6)
        val snd = act.card()
        snd.addv(act.seekRow("Музыка", 0, 100, st.musicVol, { "$it%" }) { st.musicVol = it; save() })
        snd.addv(act.seekRow("Звуки попаданий", 0, 100, st.sfxVol, { "$it%" }) { st.sfxVol = it; save() })
        col.addv(snd, MATCH, WRAP, 0f, 0, 0, 0, 14)

        col.addv(act.sectionTitle("Игра"), MATCH, WRAP, 0f, 4, 0, 0, 6)
        val game = act.card()
        game.addv(act.seekRow("Скорость нот", 1, 10, st.speed, { it.toString() }) { st.speed = it; save() })
        game.addv(act.seekRow("Сдвиг звука", -400, 400, st.offsetMs, { String.format("%+d мс", it) }) { st.offsetMs = it; save() })
        game.addv(act.ghostButton("Калибровка задержки", C.CYAN) { act.show(CalibrationScreen(act)) }, MATCH, WRAP, 0f, 0, 4, 0, 6)
        game.addv(act.toggleRow("Вибрация при попадании", st.vibration) { st.vibration = it; save() })
        game.addv(act.toggleRow("Без проигрыша", st.noFail) { st.noFail = it; save() })
        game.addv(act.toggleRow("Автоигра (демо)", st.autoPlay) { st.autoPlay = it; save() })
        col.addv(game, MATCH, WRAP, 0f, 0, 0, 0, 14)

        col.addv(act.sectionTitle("Внешний вид"), MATCH, WRAP, 0f, 4, 0, 0, 6)
        val look = act.card()
        look.addv(act.seekRow("Затемнение фона", 0, 85, st.bgDim, { "$it%" }) { st.bgDim = it; save() })
        look.addv(act.seekRow("Положение линии", 68, 90, st.hitLine, { "$it%" }) { st.hitLine = it; save() })
        look.addv(act.seekRow("Ширина поля", 60, 100, st.fieldWidth, { "$it%" }) { st.fieldWidth = it; save() })
        look.addv(act.text("Цвет нот", 15f, C.TEXT), MATCH, WRAP, 0f, 0, 10, 0, 6)
        look.addv(act.segmented(NoteColors.names.toList(), st.theme) { st.theme = it; save() })
        look.addv(act.toggleRow("Шкала точности", st.errorBar) { st.errorBar = it; save() }, MATCH, WRAP, 0f, 0, 8, 0, 0)
        look.addv(act.toggleRow("Подписи РАНО / ПОЗДНО", st.earlyLate) { st.earlyLate = it; save() })
        col.addv(look, MATCH, WRAP, 0f, 0, 0, 0, 14)

        col.addv(act.sectionTitle("Данные"), MATCH, WRAP, 0f, 4, 0, 0, 6)
        val data = act.card()
        data.addv(act.ghostButton("Сбросить рекорды", C.PINK) {
            act.confirmDialog("Сбросить рекорды?", "Все лучшие результаты будут удалены.", "Сбросить") {
                Env.records = RecordBook()
                Env.saveRecords()
                act.toast("Рекорды сброшены")
            }
        }, MATCH, WRAP, 0f, 0, 0, 0, 8)
        data.addv(act.ghostButton("Вернуть настройки по умолчанию", C.GOLD) {
            act.confirmDialog("Сбросить настройки?", "Все параметры вернутся к начальным.", "Сбросить") {
                Env.settings = GameSettings()
                Env.saveSettings()
                act.replace(SettingsScreen(act))
            }
        })
        col.addv(data)
        view = act.page(col)
    }
}

/** Экран калибровки: жми в такт щелчкам, игра подберёт сдвиг звука. */
class CalibrationScreen(act: MainActivity) : Screen(act) {
    private val cv = CalView(act)
    override val view: View

    private class CalView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handler = Handler(Looper.getMainLooper())
        private val period = 600L
        private var startUp = 0L
        private var k = 0
        private var running = false
        private var lastClick = 0L
        val deltas = ArrayList<Long>()
        var onChange: (() -> Unit)? = null

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                lastClick = SystemClock.uptimeMillis()
                Env.sfx.metronome(k % 4 == 0, Math.max(0.4f, Env.settings.sfxVol / 100f))
                k++
                handler.postAtTime(this, startUp + k * period)
            }
        }

        fun start() {
            if (running) return
            running = true
            k = 0
            startUp = SystemClock.uptimeMillis() + 800L
            handler.postAtTime(tick, startUp)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
        }

        fun median(): Int? {
            if (deltas.size < 4) return null
            val last = deltas.takeLast(16).sorted()
            return last[last.size / 2].toInt()
        }

        fun reset() { deltas.clear(); onChange?.invoke() }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && running && k > 0) {
                val tap = e.eventTime
                val idx = Math.round((tap - startUp).toDouble() / period).toInt()
                val d = tap - (startUp + idx * period)
                if (idx >= 0 && Math.abs(d) < 300) {
                    deltas.add(d)
                    onChange?.invoke()
                }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.color = C.BG1
            c.drawRect(0f, 0f, w, h, p)
            val age = (SystemClock.uptimeMillis() - lastClick).toFloat()
            val pulse = (1f - age / 260f).coerceIn(0f, 1f)
            p.color = withAlpha(C.CYAN, (40 + pulse * 150).toInt())
            c.drawCircle(w / 2f, h * 0.45f, dp(60f) + pulse * dp(34f), p)
            p.color = C.CYAN
            c.drawCircle(w / 2f, h * 0.45f, dp(26f), p)
            postInvalidateOnAnimation()
        }
    }

    init {
        val col = act.vbox()
        col.setBackgroundColor(C.BG1)
        col.setPadding(dpi(16), dpi(34), dpi(16), dpi(24))
        col.addv(act.text("Калибровка", 22f, C.TEXT, true), MATCH, WRAP, 0f, 0, 0, 0, 6)
        col.addv(act.text("Слушай щелчки и нажимай на круг ровно в такт. Нужно 8–16 нажатий. Лучше в наушниках, если играешь в них.", 14f, C.DIM), MATCH, WRAP, 0f, 0, 0, 0, 8)
        col.addv(cv, MATCH, 0, 1f)
        val info: TextView = act.text("Нажатий: 0", 16f, C.TEXT, true)
        info.gravity = Gravity.CENTER
        col.addv(info, MATCH, WRAP, 0f, 0, 8, 0, 10)
        cv.onChange = {
            val m = cv.median()
            info.text = if (m == null) "Нажатий: ${cv.deltas.size}" else
                "Нажатий: ${cv.deltas.size} · задержка ${String.format("%+d", m)} мс"
        }
        val row = act.hbox()
        row.addv(act.ghostButton("Заново", C.GOLD) { cv.reset() }, 0, WRAP, 1f, 0, 0, 6, 0)
        row.addv(act.button("Применить", C.GREEN) {
            val m = cv.median()
            if (m == null) {
                act.toast("Нужно хотя бы 4 нажатия")
            } else {
                Env.settings.offsetMs = m.coerceIn(-400, 400)
                Env.saveSettings()
                act.toast("Сдвиг звука: ${Env.settings.offsetMs} мс")
                act.back()
            }
        }, 0, WRAP, 1f, 6, 0, 0, 0)
        col.addv(row, MATCH, WRAP, 0f, 0, 0, 0, 6)
        col.addv(act.ghostButton("Отмена", C.DIM) { act.back() })
        view = col
    }

    override fun onResume() { cv.start() }
    override fun onPause() { cv.stop() }
}
