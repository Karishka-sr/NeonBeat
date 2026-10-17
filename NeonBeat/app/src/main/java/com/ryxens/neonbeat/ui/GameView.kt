package com.ryxens.neonbeat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.audio.Pcm
import com.ryxens.neonbeat.audio.Transport
import com.ryxens.neonbeat.core.GameEngine
import com.ryxens.neonbeat.core.GameSettings
import com.ryxens.neonbeat.core.Judgment
import com.ryxens.neonbeat.core.PlayChart

/** Цвета нот для разных тем. */
object NoteColors {
    private val THEMES = arrayOf(
        intArrayOf(0xFF35E0FF.toInt(), 0xFFFF4DB8.toInt()),
        intArrayOf(0xFFFFFFFF.toInt(), 0xFFC9D2FF.toInt()),
        intArrayOf(0xFFFF4D6D.toInt(), 0xFFFFA64D.toInt(), 0xFFFFE14D.toInt(), 0xFF4CD964.toInt(),
            0xFF35E0FF.toInt(), 0xFF5C7CFF.toInt(), 0xFFB45CFF.toInt()),
        intArrayOf(0xFFFFC857.toInt(), 0xFF8C6CFF.toInt())
    )
    val names = arrayOf("Неон", "Белые", "Радуга", "Закат")
    fun of(theme: Int, lane: Int): Int {
        val t = THEMES[theme.coerceIn(0, THEMES.size - 1)]
        return t[lane % t.size]
    }
}

/**
 * Игровое поле: рисует ноты, принимает касания, ведёт судейство через GameEngine.
 */
class GameView(ctx: Context, private val owner: GameScreen, val chart: PlayChart, pcm: Pcm?) : View(ctx) {

    private val st: GameSettings = Env.settings
    val engine = GameEngine(chart, st.autoPlay, st.noFail)
    private val transport = Transport()
    private val keys = chart.keys
    private val approach: Double = st.approachMs()
    private val leadIn: Int = Math.max(2500, (approach + 900).toInt())

    private companion object {
        const val S_PLAY = 0
        const val S_PAUSED = 1
        const val S_RESUME = 2
        const val S_DONE = 3
    }

    private var mode = S_PLAY
    private var started = false
    private var resumeAt = 0L
    private var failAt = 0L

    // геометрия
    private var fx0 = 0f
    private var laneW = 0f
    private var hitY = 0f
    private var pxPerMs = 0f
    private var noteH = 0f
    private val pauseBtn = RectF()
    private val ovBtns = Array(3) { RectF() }

    // краски
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var bgShader: Shader? = null

    // состояние эффектов
    private class Fx(val lane: Int, val born: Long, val color: Int, val big: Boolean)
    private class Tick(val delta: Double, val born: Long)

    private val fxs = ArrayList<Fx>()
    private val ticks = ArrayList<Tick>()
    private val beamHold = BooleanArray(keys)
    private val beamUntil = LongArray(keys)
    private val pointerLane = HashMap<Int, Int>()
    private var judgText = ""
    private var judgColor = 0
    private var judgBorn = 0L
    private var judgEL = ""
    private var comboBorn = 0L
    private var startIdx = 0

    private val vib: Vibrator? = try {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } catch (e: Throwable) { null }

    init {
        keepScreenOn = true
        transport.load(pcm, leadIn)
        transport.setVolume(st.musicVol / 100f)
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textAlign = Paint.Align.CENTER
    }

    // ---------------------------------------------------------------- время

    private fun gameTime(): Double = transport.nowMs() - st.offsetMs
    private fun gameTimeAt(nano: Long): Double = transport.timeAtNano(nano) - st.offsetMs

    // ---------------------------------------------------------------- жизненный цикл

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val fw = w * st.fieldWidth / 100f
        fx0 = (w - fw) / 2f
        laneW = fw / keys
        hitY = h * st.hitLine / 100f
        pxPerMs = (hitY / approach).toFloat()
        noteH = Math.min(laneW * 0.34f, dp(28f))
        pauseBtn.set(dp(10f), dp(30f), dp(66f), dp(86f))
        val bw = dp(240f)
        val bh = dp(54f)
        for (i in 0..2) {
            val top = h * 0.36f + i * (bh + dp(14f))
            ovBtns[i].set((w - bw) / 2f, top, (w + bw) / 2f, top + bh)
        }
        val b1 = hsv(chart.hue, 0.55f, 0.28f)
        val b2 = hsv(chart.hue + 35f, 0.65f, 0.10f)
        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), b1, b2, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!started) {
            started = true
            transport.play(-leadIn.toDouble())
        }
    }

    fun destroy() {
        mode = S_DONE
        transport.release()
    }

    fun pause() {
        if (mode == S_PLAY && started) {
            transport.pause()
            mode = S_PAUSED
            for (i in beamHold.indices) beamHold[i] = false
            pointerLane.clear()
        } else if (mode == S_RESUME) {
            mode = S_PAUSED
        }
    }

    private fun beginResume() {
        mode = S_RESUME
        resumeAt = System.nanoTime() + 2_800_000_000L
    }

    fun onBackKey() {
        when (mode) {
            S_PLAY -> pause()
            S_PAUSED -> beginResume()
            S_RESUME -> pause()
        }
    }

    // ---------------------------------------------------------------- ввод

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                up(e.getPointerId(e.actionIndex), e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                val ids = ArrayList(pointerLane.keys)
                for (id in ids) up(id, e.eventTime)
            }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float, eventTime: Long) {
        if (mode == S_PAUSED) {
            when {
                ovBtns[0].contains(x, y) -> beginResume()
                ovBtns[1].contains(x, y) -> post { owner.restart() }
                ovBtns[2].contains(x, y) -> post { owner.quit() }
            }
            return
        }
        if (mode != S_PLAY) return
        if (pauseBtn.contains(x, y)) { pause(); return }
        if (st.autoPlay) return
        val lane = ((x - fx0) / laneW).toInt().coerceIn(0, keys - 1)
        pointerLane[id] = lane
        beamHold[lane] = true
        val t = gameTimeAt(eventTime * 1_000_000L)
        engine.press(lane, t)
        drain()
    }

    private fun up(id: Int, eventTime: Long) {
        val lane = pointerLane.remove(id) ?: return
        var still = false
        for (l in pointerLane.values) if (l == lane) still = true
        if (!still) beamHold[lane] = false
        if (mode != S_PLAY) return
        val t = gameTimeAt(eventTime * 1_000_000L)
        engine.release(lane, t)
        drain()
    }

    // ---------------------------------------------------------------- события судейства

    private fun buzz() {
        if (!st.vibration) return
        val v = vib ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(14, 70))
            else @Suppress("DEPRECATION") v.vibrate(14)
        } catch (e: Throwable) { }
    }

    private fun drain() {
        if (engine.events.isEmpty()) return
        val now = System.nanoTime()
        val sfxVol = st.sfxVol / 100f
        for (ev in engine.events) {
            val j = ev.judgment
            if (j == Judgment.MISS) {
                Env.sfx.miss(sfxVol * 0.8f)
                judgText = "MISS"
                judgColor = 0xFFFF4D6D.toInt()
                judgEL = ""
                judgBorn = now
                comboBorn = now
            } else {
                Env.sfx.hit(sfxVol)
                buzz()
                judgColor = when (j) {
                    Judgment.PERFECT -> 0xFFFFE14D.toInt()
                    Judgment.GREAT -> 0xFF4CD964.toInt()
                    else -> 0xFF35C8F0.toInt()
                }
                judgText = j.label
                judgEL = if (!st.earlyLate || j == Judgment.PERFECT || !ev.head) ""
                else if (ev.deltaMs < 0) "РАНО" else "ПОЗДНО"
                judgBorn = now
                comboBorn = now
                fxs.add(Fx(ev.lane, now, judgColor, j == Judgment.PERFECT))
                beamUntil[ev.lane] = now + 110_000_000L
                if (ev.head) {
                    ticks.add(Tick(ev.deltaMs, now))
                    if (ticks.size > 16) ticks.removeAt(0)
                }
            }
        }
        engine.events.clear()
    }

    // ---------------------------------------------------------------- рисование

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val nano = System.nanoTime()

        var t: Double
        when (mode) {
            S_PLAY -> {
                transport.update()
                t = gameTime()
                engine.update(t)
                drain()
                if (engine.failed && failAt == 0L) {
                    failAt = nano
                    transport.setVolume(st.musicVol / 200f)
                }
                val over = engine.isFinished && t > chart.endMs + 900
                val failedOver = failAt != 0L && nano - failAt > 1_300_000_000L
                if (over || failedOver) {
                    mode = S_DONE
                    transport.release()
                    post { owner.finished(engine) }
                }
            }
            S_RESUME -> {
                t = transport.nowMs() - st.offsetMs
                if (nano >= resumeAt) {
                    transport.resume()
                    mode = S_PLAY
                }
            }
            else -> t = transport.nowMs() - st.offsetMs
        }

        drawBackground(c, w, h, t, nano)
        drawField(c, w, h, nano)
        drawNotes(c, t, nano)
        drawEffects(c, nano)
        drawHud(c, w, h, t, nano)
        if (mode == S_PAUSED) drawPause(c, w, h)
        if (mode == S_RESUME) drawCountdown(c, w, h, nano)
        if (failAt != 0L) drawFailed(c, w, h)

        if (mode != S_DONE) postInvalidateOnAnimation()
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float, t: Double, nano: Long) {
        p.shader = bgShader
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // пульс в такт музыке
        val beatMs = 60000.0 / chart.bpm
        val beat = (t - chart.firstBeatMs) / beatMs
        val frac = (beat - Math.floor(beat)).toFloat()
        val pulse = if (t < 0) 0f else (1f - frac) * (1f - frac)
        val col = hsv(chart.hue, 0.7f, 1f)
        val rg = RadialGradient(w / 2f, hitY, w * 0.9f, withAlpha(col, (pulse * 60).toInt()), 0, Shader.TileMode.CLAMP)
        p.shader = rg
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // плавающие огоньки
        val tt = (t / 1000.0).toFloat()
        for (i in 0 until 7) {
            val x = w * (0.1f + 0.8f * ((i * 0.37f + Math.sin((tt * 0.15f + i).toDouble()).toFloat() * 0.08f) % 1f))
            val y = h - ((tt * (22f + i * 7f) * Env.density + i * 280f) % (h + 80f)) + 40f
            p.color = withAlpha(hsv(chart.hue + i * 18f, 0.5f, 1f), 28)
            c.drawCircle(x, y, dp(10f + i * 3f), p)
        }
        // затемнение
        p.color = (st.bgDim * 255 / 100) shl 24
        c.drawRect(0f, 0f, w, h, p)
    }

    private fun drawField(c: Canvas, w: Float, h: Float, nano: Long) {
        val fw = laneW * keys
        p.color = 0x99000000.toInt()
        c.drawRect(fx0, 0f, fx0 + fw, h, p)
        // разделители
        p.color = 0x33FFFFFF
        for (i in 0..keys) c.drawRect(fx0 + i * laneW - 1f, 0f, fx0 + i * laneW + 1f, h, p)
        // лучи при нажатии
        for (l in 0 until keys) {
            val active = beamHold[l] || nano < beamUntil[l]
            if (!active) continue
            val col = NoteColors.of(st.theme, l)
            val g = LinearGradient(0f, hitY, 0f, hitY - h * 0.4f, withAlpha(col, 110), 0, Shader.TileMode.CLAMP)
            p.shader = g
            c.drawRect(fx0 + l * laneW, hitY - h * 0.4f, fx0 + (l + 1) * laneW, hitY, p)
            p.shader = null
        }
        // линия попадания
        p.color = 0xFFFFFFFF.toInt()
        c.drawRect(fx0, hitY - 2f, fx0 + fw, hitY + 2f, p)
        p.color = 0x33FFFFFF
        c.drawRect(fx0, hitY - dp(6f), fx0 + fw, hitY + dp(6f), p)
        // «приёмники»
        for (l in 0 until keys) {
            val col = NoteColors.of(st.theme, l)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2f)
            p.color = withAlpha(col, 200)
            rect.set(fx0 + l * laneW + laneW * 0.08f, hitY - noteH / 2, fx0 + (l + 1) * laneW - laneW * 0.08f, hitY + noteH / 2)
            c.drawRoundRect(rect, dp(8f), dp(8f), p)
            p.style = Paint.Style.FILL
        }
    }

    private fun drawNotes(c: Canvas, t: Double, nano: Long) {
        val notes = engine.notes
        // сдвигаем начало списка: всё, что давно закончилось, можно не рисовать
        while (startIdx < notes.size && engine.state[startIdx] >= 2 && notes[startIdx].endMs < t - 400) startIdx++
        val pad = laneW * 0.07f
        val r = dp(8f)
        var i = startIdx
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs - t > approach + 250) break
            val s = engine.state[i]
            val col = NoteColors.of(st.theme, n.lane)
            val x0 = fx0 + n.lane * laneW + pad
            val x1 = fx0 + (n.lane + 1) * laneW - pad
            if (n.isHold) {
                val tailY = hitY - ((n.endMs - t) * pxPerMs).toFloat()
                var headY = hitY - ((n.timeMs - t) * pxPerMs).toFloat()
                if (s == 1) headY = hitY
                if (s == 2 || s == 3) {
                    // завершённый холд: если был сорван — тусклый
                    if (n.endMs < t - 100) { i++; continue }
                }
                val dim = s == 3
                val bodyCol = if (dim) 0xFF555B7A.toInt() else col
                val inset = laneW * 0.2f
                rect.set(x0 + inset, tailY, x1 - inset, headY)
                p.color = withAlpha(bodyCol, if (s == 1) 190 else 110)
                c.drawRoundRect(rect, r, r, p)
                // «хвостовая» шапка
                rect.set(x0 + inset * 0.5f, tailY - noteH * 0.25f, x1 - inset * 0.5f, tailY + noteH * 0.25f)
                p.color = withAlpha(bodyCol, if (s == 1) 255 else 170)
                c.drawRoundRect(rect, r, r, p)
                if (s != 2 || n.timeMs > t - 200) drawHead(c, x0, x1, headY, bodyCol, r, dim)
            } else {
                if (s >= 2) { i++; continue }
                val y = hitY - ((n.timeMs - t) * pxPerMs).toFloat()
                if (y > height + noteH) { i++; continue }
                drawHead(c, x0, x1, y, col, r, s == 3)
            }
            i++
        }
    }

    private fun drawHead(c: Canvas, x0: Float, x1: Float, y: Float, col: Int, r: Float, dim: Boolean) {
        val cc = if (dim) 0xFF555B7A.toInt() else col
        rect.set(x0, y - noteH / 2, x1, y + noteH / 2)
        p.color = withAlpha(cc, 70)
        val g = dp(3f)
        rect.inset(-g, -g)
        c.drawRoundRect(rect, r + g, r + g, p)
        rect.inset(g, g)
        p.color = cc
        c.drawRoundRect(rect, r, r, p)
        p.color = 0x66FFFFFF
        rect.set(x0 + dp(4f), y - noteH / 2 + dp(3f), x1 - dp(4f), y - noteH / 2 + dp(3f) + noteH * 0.22f)
        c.drawRoundRect(rect, r, r, p)
    }

    private fun drawEffects(c: Canvas, nano: Long) {
        val it = fxs.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val age = (nano - f.born) / 1e6f
            if (age > 340f) { it.remove(); continue }
            val k = age / 340f
            val cx = fx0 + (f.lane + 0.5f) * laneW
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(3f) * (1f - k) + 1f
            p.color = withAlpha(f.color, ((1f - k) * 255).toInt())
            c.drawCircle(cx, hitY, laneW * (0.25f + 0.65f * k) * (if (f.big) 1.15f else 1f), p)
            p.style = Paint.Style.FILL
            if (f.big) {
                p.color = withAlpha(0xFFFFFFFF.toInt(), ((1f - k) * 200).toInt())
                for (a in 0 until 8) {
                    val ang = a * Math.PI.toFloat() / 4f
                    val r0 = laneW * 0.3f + laneW * 0.5f * k
                    c.drawCircle(cx + Math.cos(ang.toDouble()).toFloat() * r0, hitY + Math.sin(ang.toDouble()).toFloat() * r0, dp(3f) * (1f - k), p)
                }
            }
        }
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, t: Double, nano: Long) {
        // прогресс
        val prog = (t / chart.lengthMs).toFloat().coerceIn(0f, 1f)
        p.color = 0x33FFFFFF
        c.drawRect(0f, 0f, w, dp(3f), p)
        p.color = C.CYAN
        c.drawRect(0f, 0f, w * prog, dp(3f), p)
        // здоровье
        val hp = (engine.health / 100.0).toFloat()
        val hx0 = w * 0.25f
        val hw = w * 0.5f
        p.color = 0x44FFFFFF
        rect.set(hx0, dp(10f), hx0 + hw, dp(16f))
        c.drawRoundRect(rect, dp(3f), dp(3f), p)
        p.color = if (hp > 0.5f) C.GREEN else if (hp > 0.25f) C.GOLD else C.RED
        rect.set(hx0, dp(10f), hx0 + hw * hp, dp(16f))
        c.drawRoundRect(rect, dp(3f), dp(3f), p)

        // кнопка паузы
        p.color = 0x55000000
        c.drawCircle(pauseBtn.centerX(), pauseBtn.centerY(), dp(19f), p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawRect(pauseBtn.centerX() - dp(7f), pauseBtn.centerY() - dp(8f), pauseBtn.centerX() - dp(2.5f), pauseBtn.centerY() + dp(8f), p)
        c.drawRect(pauseBtn.centerX() + dp(2.5f), pauseBtn.centerY() - dp(8f), pauseBtn.centerX() + dp(7f), pauseBtn.centerY() + dp(8f), p)

        // счёт и точность
        tp.color = 0xFFFFFFFF.toInt()
        tp.textSize = dp(26f)
        c.drawText(engine.score().toString().padStart(7, '0'), w / 2f, dp(52f), tp)
        tp.textSize = dp(14f)
        tp.color = C.DIM
        tp.textAlign = Paint.Align.RIGHT
        c.drawText(String.format("%.2f%%", engine.accuracy()), w - dp(14f), dp(50f), tp)
        tp.textAlign = Paint.Align.CENTER
        if (chart.diff != null) {
            tp.color = chart.diff.color
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(chart.diff.shortName, w - dp(14f), dp(70f), tp)
            tp.textAlign = Paint.Align.CENTER
        }

        // комбо
        if (engine.combo >= 2) {
            val age = (nano - comboBorn) / 1e6f
            val pop = if (age < 120f) 1f + 0.25f * (1f - age / 120f) else 1f
            tp.color = 0xFFFFFFFF.toInt()
            tp.textSize = dp(46f) * pop
            c.drawText(engine.combo.toString(), w / 2f, h * 0.36f, tp)
            tp.textSize = dp(12f)
            tp.color = C.DIM
            c.drawText("КОМБО", w / 2f, h * 0.36f + dp(18f), tp)
        }
        // оценка
        if (judgText.isNotEmpty()) {
            val age = (nano - judgBorn) / 1e6f
            if (age < 600f) {
                val a = if (age < 400f) 255 else (255 * (1f - (age - 400f) / 200f)).toInt()
                val sc = if (age < 90f) 0.8f + 0.2f * (age / 90f) else 1f
                tp.color = withAlpha(judgColor, a)
                tp.textSize = dp(26f) * sc
                c.drawText(judgText, w / 2f, h * 0.50f, tp)
                if (judgEL.isNotEmpty()) {
                    tp.textSize = dp(12f)
                    tp.color = withAlpha(if (judgEL == "РАНО") 0xFF6CB8FF.toInt() else 0xFFFFA64D.toInt(), a)
                    c.drawText(judgEL, w / 2f, h * 0.50f + dp(18f), tp)
                }
            }
        }
        // шкала точности
        if (st.errorBar) {
            val bw = w * 0.56f
            val by = h - dp(26f)
            val cx = w / 2f
            val range = (engine.goodW).toFloat()
            fun px(ms: Double) = (ms / range * (bw / 2f)).toFloat()
            p.color = 0x33FFFFFF
            rect.set(cx - bw / 2f, by - dp(3f), cx + bw / 2f, by + dp(3f))
            c.drawRoundRect(rect, dp(3f), dp(3f), p)
            p.color = withAlpha(0xFF4CD964.toInt(), 110)
            rect.set(cx - px(engine.greatW), by - dp(3f), cx + px(engine.greatW), by + dp(3f))
            c.drawRect(rect, p)
            p.color = withAlpha(0xFFFFE14D.toInt(), 160)
            rect.set(cx - px(engine.perfectW), by - dp(3f), cx + px(engine.perfectW), by + dp(3f))
            c.drawRect(rect, p)
            for (tk in ticks) {
                val age = (nano - tk.born) / 1e6f
                if (age > 2500f) continue
                val a = (255 * (1f - age / 2500f)).toInt()
                p.color = withAlpha(0xFFFFFFFF.toInt(), a)
                val x = cx + px(tk.delta).coerceIn(-bw / 2f, bw / 2f)
                c.drawRect(x - dp(1.5f), by - dp(10f), x + dp(1.5f), by + dp(10f), p)
            }
            p.color = 0xFFFFFFFF.toInt()
            c.drawRect(cx - 1.5f, by - dp(8f), cx + 1.5f, by + dp(8f), p)
        }
        // подпись при разгоне
        if (t < 0 && mode == S_PLAY) {
            val a = (255 * Math.min(1.0, -t / 600.0)).toInt()
            tp.color = withAlpha(0xFFFFFFFF.toInt(), a)
            tp.textSize = dp(24f)
            c.drawText(chart.title, w / 2f, h * 0.30f, tp)
            tp.textSize = dp(14f)
            tp.color = withAlpha(C.DIM, a)
            c.drawText(chart.artist, w / 2f, h * 0.30f + dp(22f), tp)
            if (st.autoPlay) {
                tp.color = withAlpha(C.GOLD, a)
                c.drawText("АВТОИГРА", w / 2f, h * 0.30f + dp(46f), tp)
            }
        }
    }

    private fun drawPause(c: Canvas, w: Float, h: Float) {
        p.color = 0xCC05060F.toInt()
        c.drawRect(0f, 0f, w, h, p)
        tp.color = 0xFFFFFFFF.toInt()
        tp.textSize = dp(30f)
        c.drawText("ПАУЗА", w / 2f, h * 0.28f, tp)
        val names = arrayOf("Продолжить", "Заново", "Выйти")
        val cols = intArrayOf(C.CYAN, C.GOLD, C.PINK)
        for (i in 0..2) {
            p.color = cols[i]
            c.drawRoundRect(ovBtns[i], dp(14f), dp(14f), p)
            tp.color = C.BG0
            tp.textSize = dp(18f)
            c.drawText(names[i], ovBtns[i].centerX(), ovBtns[i].centerY() + dp(6f), tp)
        }
    }

    private fun drawCountdown(c: Canvas, w: Float, h: Float, nano: Long) {
        p.color = 0x88000000.toInt()
        c.drawRect(0f, 0f, w, h, p)
        val left = (resumeAt - nano) / 1e9f
        val n = Math.ceil(left.toDouble()).toInt().coerceIn(1, 3)
        tp.color = 0xFFFFFFFF.toInt()
        tp.textSize = dp(96f)
        c.drawText(n.toString(), w / 2f, h * 0.45f, tp)
    }

    private fun drawFailed(c: Canvas, w: Float, h: Float) {
        p.color = 0x99300010.toInt()
        c.drawRect(0f, 0f, w, h, p)
        tp.color = C.RED
        tp.textSize = dp(44f)
        c.drawText("ПРОВАЛ", w / 2f, h * 0.45f, tp)
    }
}
