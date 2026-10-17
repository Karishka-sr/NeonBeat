package com.ryxens.neonbeat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.audio.Pcm
import com.ryxens.neonbeat.audio.Transport
import com.ryxens.neonbeat.core.BeatMap
import com.ryxens.neonbeat.core.Note

/** Редактор карт: нотное поле с прокруткой, сеткой, записью под музыку и волной звука. */
class EditorView(ctx: Context, private val owner: EditorScreen, val map: BeatMap, pcm: Pcm?) : View(ctx) {

    private val st = Env.settings
    val transport = Transport()
    private val keys = map.keys

    var cur = 0.0
    var zoom = 0.30f
    var snapIdx = 1
    var recQuantize = true
    var metroOn = false
    var hitsOn = true
    var rec = false
    var speed = 1f
        private set

    private val divs = intArrayOf(1, 2, 3, 4, 6, 8)
    private val divNames = arrayOf("1/4", "1/8", "1/12", "1/16", "1/24", "1/32")

    private val undoStack = ArrayList<() -> Unit>()
    private val recNotes = HashMap<Int, Note>()
    private val recTake = ArrayList<Note>()

    private var sorted: Array<Note> = emptyArray()
    private var dirty = true
    private var lengthMs = 1000
    private val hist = IntArray(90)
    private var env = FloatArray(0)
    private var lastBeat = 0

    private class Btn(val id: Int, val r: RectF)
    private val btns = ArrayList<Btn>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint()
    private val rect = RectF()
    private var waveLines = FloatArray(0)

    // геометрия
    private var laneAreaW = 0f
    private var laneW = 0f
    private var fieldTop = 0f
    private var fieldBottom = 0f
    private var hitY = 0f
    private var gutterX = 0f

    // жесты
    private var gestureId = -1
    private var gMode = 0         // 1 кнопка, 2 полоса прокрутки, 3 поле, 4 рисуем длинную ноту
    private var btnDown: Btn? = null
    private var downX = 0f
    private var downY = 0f
    private var scrollBase = 0.0
    private var moved = false
    private var holdNote: Note? = null
    private var holdStart = 0
    private val slop = dp(9f)
    private val longPress = Runnable { startHold() }

    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.06f, 1.2f)
            return true
        }
    })

    init {
        keepScreenOn = true
        tp.typeface = Typeface.DEFAULT_BOLD
        if (pcm != null) {
            transport.load(pcm, 0)
            if (map.lengthMs <= 0 || Math.abs(map.lengthMs - pcm.durationMs) > 50) map.lengthMs = pcm.durationMs
            buildEnvelope(pcm)
        }
        transport.setVolume(st.musicVol / 100f)
        wave.color = withAlpha(0xFF6C7CFF.toInt(), 70)
        wave.strokeWidth = 3f
    }

    // ---------------------------------------------------------------- данные

    private fun buildEnvelope(pcm: Pcm) {
        val hop = Math.max(1, pcm.rate / 100)
        val n = pcm.data.size / hop
        val e = FloatArray(n + 1)
        var peak = 1f
        for (i in 0 until n) {
            var m = 0
            val b = i * hop
            for (k in 0 until hop) {
                val a = Math.abs(pcm.data[b + k].toInt())
                if (a > m) m = a
            }
            e[i] = m / 32768f
            if (e[i] > peak) peak = e[i]
        }
        val sortedE = e.copyOf(n).also { it.sort() }
        val ref = if (n > 10) Math.max(0.05f, sortedE[(n * 0.97).toInt().coerceAtMost(n - 1)]) else peak
        for (i in 0 until n) e[i] = Math.min(1f, e[i] / ref)
        env = e
    }

    fun markDirty() { dirty = true }

    private fun rebuild() {
        val arr = map.notes.toTypedArray()
        arr.sortWith(compareBy({ it.timeMs }, { it.lane }))
        sorted = arr
        var m = Math.max(map.lengthMs, 1000)
        for (n in arr) if (n.endMs + 1500 > m) m = n.endMs + 1500
        lengthMs = m
        java.util.Arrays.fill(hist, 0)
        for (n in arr) hist[(n.timeMs.toLong() * (hist.size - 1) / lengthMs).toInt().coerceIn(0, hist.size - 1)]++
        dirty = false
    }

    fun noteCount(): Int = map.notes.size
    fun snapStepMs(): Double = 60000.0 / map.bpm / divs[snapIdx]
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    private fun snapTime(t: Double): Double {
        val step = snapStepMs()
        val off = map.offsetMs.toDouble()
        return off + Math.round((t - off) / step) * step
    }

    private fun pxPerMs(): Float = zoom * Env.density
    private fun yOf(t: Double): Float = hitY - ((t - cur) * pxPerMs()).toFloat()
    private fun timeAtY(y: Float): Double = cur + (hitY - y) / pxPerMs()

    fun undo() {
        if (undoStack.isEmpty()) return
        undoStack.removeAt(undoStack.size - 1).invoke()
        markDirty()
    }

    fun replaceNotes(list: List<Note>) {
        val old = ArrayList(map.notes)
        map.notes.clear()
        map.notes.addAll(list)
        undoStack.add { map.notes.clear(); map.notes.addAll(old) }
        markDirty()
    }

    fun clearAll() {
        if (map.notes.isEmpty()) return
        replaceNotes(emptyList())
    }

    fun setSpeed(s: Float) {
        speed = s
        transport.setSpeed(s)
    }

    // ---------------------------------------------------------------- воспроизведение

    fun togglePlay() { if (transport.playing) stopPlay() else startPlay() }

    private fun startPlay() {
        if (cur >= lengthMs - 100) cur = 0.0
        transport.setSpeed(speed)
        transport.setVolume(st.musicVol / 100f)
        transport.play(cur)
        lastBeat = Math.floor((cur - map.offsetMs) / (60000.0 / map.bpm)).toInt()
    }

    fun stopPlay() {
        if (transport.playing) {
            transport.pause()
            cur = transport.nowMs().coerceIn(0.0, lengthMs.toDouble())
        }
        for ((_, n) in recNotes) n.durMs = 0
        recNotes.clear()
        if (recTake.isNotEmpty()) {
            val take = ArrayList(recTake)
            recTake.clear()
            undoStack.add { map.notes.removeAll(take.toSet()) }
        }
    }

    fun release() {
        stopPlay()
        transport.release()
    }

    private fun seekTo(ms: Double) {
        cur = ms.coerceIn(0.0, lengthMs.toDouble())
        if (transport.playing) {
            transport.play(cur)
            lastBeat = Math.floor((cur - map.offsetMs) / (60000.0 / map.bpm)).toInt()
        }
    }

    // ---------------------------------------------------------------- размеры

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val topInset = dp(26f)
        val barH = dp(48f)
        fieldTop = topInset + barH + dp(6f)
        fieldBottom = h - dp(66f)
        hitY = fieldTop + (fieldBottom - fieldTop) * 0.72f
        val gutter = dp(26f)
        gutterX = w - gutter
        laneAreaW = w - gutter
        laneW = laneAreaW / keys
        waveLines = FloatArray(4 * (((fieldBottom - fieldTop) / 3f).toInt() + 2))

        btns.clear()
        val m = dp(6f)
        val y0 = topInset
        val y1 = topInset + barH
        fun add(id: Int, x0: Float, x1: Float, yy0: Float, yy1: Float) { btns.add(Btn(id, RectF(x0, yy0, x1, yy1))) }
        add(0, m, m + dp(52f), y0, y1)                                  // назад
        add(1, m + dp(58f), m + dp(110f), y0, y1)                       // отмена
        add(8, w - m - dp(176f), w - m - dp(58f), y0, y1)               // тест
        add(2, w - m - dp(52f), w - m, y0, y1)                          // меню
        val b0 = h - dp(60f)
        val b1 = h - dp(8f)
        var x = m
        add(3, x, x + dp(64f), b0, b1); x += dp(70f)                    // сетка
        add(4, x, x + dp(48f), b0, b1); x += dp(54f)                    // −
        add(5, x, x + dp(48f), b0, b1); x += dp(54f)                    // +
        add(7, w - m - dp(176f), w - m - dp(104f), b0, b1)              // REC
        add(6, w - m - dp(98f), w - m, b0, b1)                          // играть
    }

    // ---------------------------------------------------------------- ввод

    private fun inBar(y: Float) = y < fieldTop - dp(2f) || y > fieldBottom + dp(2f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (scale.isInProgress) {
            removeCallbacks(longPress)
            gMode = 0
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                if (transport.playing && rec && !inBar(y) && x < gutterX) recDown(id, x, e.eventTime)
                else if (gestureId == -1) { gestureId = id; begin(x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) if (e.getPointerId(i) == gestureId) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (recNotes.containsKey(id)) recUp(id, e.eventTime)
                else if (id == gestureId) { end(e.getX(i), e.getY(i)); gestureId = -1 }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (gMode == 4) finishHold()
                gMode = 0
                gestureId = -1
                recNotes.clear()
            }
        }
        return true
    }

    private fun begin(x: Float, y: Float) {
        downX = x; downY = y; moved = false
        for (b in btns) if (b.r.contains(x, y)) { gMode = 1; btnDown = b; return }
        if (x >= gutterX && y >= fieldTop && y <= fieldBottom) {
            gMode = 2
            scrubTo(y)
            return
        }
        if (inBar(y)) { gMode = 0; return }
        if (transport.playing && !rec) stopPlay()
        gMode = 3
        scrollBase = cur
        postDelayed(longPress, 380)
    }

    private fun move(x: Float, y: Float) {
        when (gMode) {
            2 -> scrubTo(y)
            3 -> {
                if (!moved && Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > slop) {
                    moved = true
                    removeCallbacks(longPress)
                }
                if (moved) cur = (scrollBase + (y - downY) / pxPerMs()).coerceIn(0.0, lengthMs.toDouble())
            }
            4 -> updateHold(y)
        }
    }

    private fun end(x: Float, y: Float) {
        removeCallbacks(longPress)
        when (gMode) {
            1 -> { val b = btnDown; if (b != null && b.r.contains(x, y)) press(b.id) }
            3 -> if (!moved) tap(x, y)
            4 -> finishHold()
        }
        gMode = 0
        btnDown = null
    }

    private fun scrubTo(y: Float) {
        val f = ((y - fieldTop) / (fieldBottom - fieldTop)).coerceIn(0f, 1f)
        seekTo(f * lengthMs.toDouble())
    }

    private fun press(id: Int) {
        when (id) {
            0 -> post { owner.exit() }
            1 -> undo()
            2 -> owner.showMenu()
            3 -> snapIdx = (snapIdx + 1) % divs.size
            4 -> zoom = (zoom * 0.8f).coerceIn(0.06f, 1.2f)
            5 -> zoom = (zoom * 1.25f).coerceIn(0.06f, 1.2f)
            6 -> togglePlay()
            7 -> rec = !rec
            8 -> post { owner.testPlay() }
        }
    }

    private fun laneAt(x: Float): Int = (x / laneW).toInt().coerceIn(0, keys - 1)

    private fun findNote(lane: Int, t: Double, tol: Double): Note? {
        var best: Note? = null
        var bestD = Double.MAX_VALUE
        for (n in map.notes) {
            if (n.lane != lane) continue
            if (t < n.timeMs - tol || t > n.endMs + tol) continue
            val d = Math.abs(n.timeMs - t)
            if (d < bestD) { bestD = d; best = n }
        }
        return best
    }

    private fun tap(x: Float, y: Float) {
        if (x >= gutterX) return
        val lane = laneAt(x)
        val t = timeAtY(y)
        val tol = Math.max(45.0, (dp(16f) / pxPerMs()).toDouble())
        val ex = findNote(lane, t, tol)
        if (ex != null) {
            map.notes.remove(ex)
            undoStack.add { map.notes.add(ex) }
        } else {
            val ti = Math.round(snapTime(t)).toInt()
            if (ti < 0) return
            val n = Note(ti, lane, 0)
            map.notes.add(n)
            undoStack.add { map.notes.remove(n) }
            Env.sfx.hit(0.6f * st.sfxVol / 100f)
        }
        markDirty()
    }

    private fun startHold() {
        if (gMode != 3 || moved) return
        val lane = laneAt(downX)
        val t0 = Math.round(snapTime(timeAtY(downY))).toInt()
        if (t0 < 0 || findNote(lane, t0.toDouble(), 30.0) != null) return
        val n = Note(t0, lane, 0)
        map.notes.add(n)
        holdNote = n
        holdStart = t0
        gMode = 4
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        markDirty()
    }

    private fun updateHold(y: Float) {
        val n = holdNote ?: return
        val t1 = snapTime(timeAtY(y))
        var dur = (t1 - holdStart).toInt()
        // не залезаем на следующую ноту этой же дорожки
        var next = Int.MAX_VALUE
        for (o in map.notes) if (o !== n && o.lane == n.lane && o.timeMs > holdStart && o.timeMs < next) next = o.timeMs
        if (next != Int.MAX_VALUE && holdStart + dur > next - 40) dur = next - 40 - holdStart
        n.durMs = if (dur >= 120) dur else 0
        markDirty()
    }

    private fun finishHold() {
        val n = holdNote
        holdNote = null
        if (n != null) undoStack.add { map.notes.remove(n) }
        markDirty()
    }

    // ---------------------------------------------------------------- запись

    private fun recDown(id: Int, x: Float, eventTime: Long) {
        val lane = laneAt(x)
        var t = transport.timeAtNano(eventTime * 1_000_000L) - st.offsetMs
        if (recQuantize) t = snapTime(t)
        val ti = Math.max(0, Math.round(t).toInt())
        for (o in map.notes) if (o.lane == lane && Math.abs(o.timeMs - ti) < 40) return
        val n = Note(ti, lane, 0)
        map.notes.add(n)
        recNotes[id] = n
        recTake.add(n)
        Env.sfx.hit(0.7f * st.sfxVol / 100f)
        markDirty()
    }

    private fun recUp(id: Int, eventTime: Long) {
        val n = recNotes.remove(id) ?: return
        val t = transport.timeAtNano(eventTime * 1_000_000L) - st.offsetMs
        var dur = t - n.timeMs
        if (dur >= 280.0) {
            if (recQuantize) dur = snapTime(n.timeMs + dur) - n.timeMs
            n.durMs = Math.max(120, dur.toInt())
        } else n.durMs = 0
        markDirty()
    }

    // ---------------------------------------------------------------- рисование

    private fun btnRect(id: Int): RectF? { for (b in btns) if (b.id == id) return b.r; return null }

    override fun onDraw(c: Canvas) {
        if (dirty) rebuild()
        val w = width.toFloat()
        val h = height.toFloat()

        if (transport.playing) {
            transport.update()
            val prev = cur
            cur = transport.nowMs()
            if (cur >= lengthMs) { stopPlay(); cur = lengthMs.toDouble() }
            else playSounds(prev, cur)
        }

        p.color = C.BG0
        c.drawRect(0f, 0f, w, h, p)

        // дорожки
        for (l in 0 until keys) {
            p.color = if (l % 2 == 0) 0xFF0E1226.toInt() else 0xFF0A0D1D.toInt()
            c.drawRect(l * laneW, fieldTop, (l + 1) * laneW, fieldBottom, p)
        }
        drawWave(c)
        c.save()
        c.clipRect(0f, fieldTop, w, fieldBottom)
        drawGrid(c)
        drawNotes(c)
        // линия «сейчас»
        p.color = 0xFFFFFFFF.toInt()
        c.drawRect(0f, hitY - 2f, laneAreaW, hitY + 2f, p)
        p.color = 0x33FFFFFF
        c.drawRect(0f, hitY - dp(5f), laneAreaW, hitY + dp(5f), p)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(12f)
        tp.color = 0xFFFFFFFF.toInt()
        val beatMs = 60000.0 / map.bpm
        val bi = Math.floor((cur - map.offsetMs) / beatMs).toInt()
        val barNo = Math.floorDiv(bi, 4) + 1
        val ms = cur.toInt()
        c.drawText(String.format("%d:%02d.%03d · такт %d", ms / 60000, (ms / 1000) % 60, ms % 1000, barNo), dp(8f), hitY - dp(10f), tp)
        c.restore()
        // разделители дорожек
        p.color = 0x22FFFFFF
        for (i in 0..keys) c.drawRect(i * laneW - 0.5f, fieldTop, i * laneW + 0.5f, fieldBottom, p)

        drawScrub(c, w)
        drawBars(c, w, h)
        postInvalidateOnAnimation()
    }

    private fun playSounds(prev: Double, now: Double) {
        if (hitsOn && sorted.isNotEmpty()) {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (sorted[mid].timeMs <= prev) lo = mid + 1 else hi = mid }
            var played = 0
            var i = lo
            while (i < sorted.size && sorted[i].timeMs <= now && played < 6) {
                Env.sfx.hit(0.7f * st.sfxVol / 100f)
                played++
                i++
            }
        }
        if (metroOn) {
            val b = Math.floor((now - map.offsetMs) / (60000.0 / map.bpm)).toInt()
            if (b > lastBeat && now >= map.offsetMs - 5) Env.sfx.metronome(Math.floorMod(b, 4) == 0, 0.6f * st.sfxVol / 100f)
            lastBeat = b
        }
    }

    private fun drawWave(c: Canvas) {
        if (env.isEmpty()) return
        val ppm = pxPerMs()
        val cx = laneAreaW / 2f
        var n = 0
        var y = fieldTop
        while (y < fieldBottom && (n + 1) * 4 <= waveLines.size) {
            val t = cur + (hitY - y) / ppm
            val idx = (t / 10.0).toInt()
            if (t >= 0 && idx >= 0 && idx < env.size) {
                val a = env[idx] * laneAreaW * 0.46f
                waveLines[n * 4] = cx - a
                waveLines[n * 4 + 1] = y
                waveLines[n * 4 + 2] = cx + a
                waveLines[n * 4 + 3] = y
                n++
            }
            y += 3f
        }
        c.drawLines(waveLines, 0, n * 4, wave)
    }

    private fun drawGrid(c: Canvas) {
        val ppm = pxPerMs()
        val beatMs = 60000.0 / map.bpm
        val off = map.offsetMs.toDouble()
        val tTop = cur + (hitY - fieldTop) / ppm
        val tBot = cur - (fieldBottom - hitY) / ppm
        val kFrom = Math.ceil((tBot - off) / beatMs).toInt()
        val kTo = Math.floor((tTop - off) / beatMs).toInt()
        val div = divs[snapIdx]
        val stepPx = beatMs / div * ppm
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(10f)
        var count = 0
        for (k in kFrom..kTo) {
            if (count++ > 400) break
            val t = off + k * beatMs
            if (t < -1.0) continue
            val y = yOf(t)
            if (k % 4 == 0) {
                p.color = 0x88FFFFFF.toInt()
                c.drawRect(0f, y - 1.5f, laneAreaW, y + 1.5f, p)
                tp.color = 0xFF9BA4CC.toInt()
                c.drawText((k / 4 + 1).toString(), dp(4f), y - dp(3f), tp)
            } else {
                p.color = 0x44FFFFFF
                c.drawRect(0f, y - 1f, laneAreaW, y + 1f, p)
            }
            if (div > 1 && stepPx >= dp(8f)) {
                p.color = 0x1AFFFFFF
                for (j in 1 until div) {
                    val ys = yOf(t + j * beatMs / div)
                    c.drawRect(0f, ys - 0.5f, laneAreaW, ys + 0.5f, p)
                }
            }
        }
    }

    private fun drawNotes(c: Canvas) {
        val ppm = pxPerMs()
        val nh = Math.min(laneW * 0.32f, dp(24f))
        val pad = laneW * 0.07f
        val r = dp(7f)
        for (n in map.notes) {
            val yh = yOf(n.timeMs.toDouble())
            val yt = if (n.isHold) yOf(n.endMs.toDouble()) else yh
            if (yh < fieldTop - nh && yt < fieldTop - nh) continue
            if (yh > fieldBottom + nh && yt > fieldBottom + nh) continue
            if (yt > fieldBottom + nh) continue
            val col = NoteColors.of(st.theme, n.lane)
            val x0 = n.lane * laneW + pad
            val x1 = (n.lane + 1) * laneW - pad
            if (n.isHold) {
                val inset = laneW * 0.2f
                rect.set(x0 + inset, yt, x1 - inset, yh)
                p.color = withAlpha(col, 120)
                c.drawRoundRect(rect, r, r, p)
                rect.set(x0 + inset * 0.5f, yt - nh * 0.25f, x1 - inset * 0.5f, yt + nh * 0.25f)
                p.color = withAlpha(col, 200)
                c.drawRoundRect(rect, r, r, p)
            }
            rect.set(x0, yh - nh / 2, x1, yh + nh / 2)
            p.color = col
            c.drawRoundRect(rect, r, r, p)
            p.color = 0x66FFFFFF
            rect.set(x0 + dp(3f), yh - nh / 2 + dp(2f), x1 - dp(3f), yh - nh / 2 + dp(2f) + nh * 0.22f)
            c.drawRoundRect(rect, r, r, p)
        }
    }

    private fun drawScrub(c: Canvas, w: Float) {
        p.color = 0xFF10142B.toInt()
        c.drawRect(gutterX, fieldTop, w, fieldBottom, p)
        var mx = 1
        for (v in hist) if (v > mx) mx = v
        val hh = (fieldBottom - fieldTop) / hist.size
        p.color = 0xFF3A4585.toInt()
        for (i in hist.indices) {
            if (hist[i] == 0) continue
            val bw = (w - gutterX - dp(4f)) * hist[i] / mx
            c.drawRect(gutterX + dp(2f), fieldTop + i * hh, gutterX + dp(2f) + bw, fieldTop + (i + 1) * hh - 1f, p)
        }
        val f = (cur / lengthMs).toFloat().coerceIn(0f, 1f)
        val y = fieldTop + f * (fieldBottom - fieldTop)
        p.color = C.CYAN
        c.drawRect(gutterX, y - dp(2f), w, y + dp(2f), p)
    }

    private fun drawBtn(c: Canvas, id: Int, label: String, fill: Int, textColor: Int = 0xFFFFFFFF.toInt(), size: Float = 15f) {
        val r = btnRect(id) ?: return
        p.color = fill
        c.drawRoundRect(r, dp(12f), dp(12f), p)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = dp(size)
        tp.color = textColor
        c.drawText(label, r.centerX(), r.centerY() + dp(size) * 0.35f, tp)
    }

    private fun drawBars(c: Canvas, w: Float, h: Float) {
        // верхняя панель
        p.color = 0xEE0B0E20.toInt()
        c.drawRect(0f, 0f, w, fieldTop, p)
        drawBtn(c, 0, "‹", C.CARD2, size = 22f)
        drawBtn(c, 1, "↶", if (undoStack.isEmpty()) 0xFF161A36.toInt() else C.CARD2,
            if (undoStack.isEmpty()) 0xFF555B7A.toInt() else 0xFFFFFFFF.toInt(), 20f)
        drawBtn(c, 8, "▶ Тест", C.GOLD, C.BG0, 14f)
        drawBtn(c, 2, "☰", C.CARD2, size = 20f)
        tp.textAlign = Paint.Align.CENTER
        tp.color = 0xFFFFFFFF.toInt()
        tp.textSize = dp(13f)
        val b1 = btnRect(1)!!
        val b8 = btnRect(8)!!
        val midX = (b1.right + b8.left) / 2f
        var title = map.title
        val maxW = b8.left - b1.right - dp(8f)
        while (title.length > 3 && tp.measureText(title) > maxW) title = title.substring(0, title.length - 2)
        if (title != map.title) title += "…"
        c.drawText(title, midX, dp(26f) + dp(20f), tp)
        tp.textSize = dp(10f)
        tp.color = 0xFF9BA4CC.toInt()
        c.drawText("${map.notes.size} нот · ${map.bpm} BPM", midX, dp(26f) + dp(36f), tp)

        // нижняя панель
        p.color = 0xEE0B0E20.toInt()
        c.drawRect(0f, fieldBottom, w, h, p)
        drawBtn(c, 3, divNames[snapIdx], C.CARD2, size = 14f)
        drawBtn(c, 4, "−", C.CARD2, size = 22f)
        drawBtn(c, 5, "+", C.CARD2, size = 22f)
        drawBtn(c, 7, "● REC", if (rec) C.RED else C.CARD2, 0xFFFFFFFF.toInt(), 13f)
        drawBtn(c, 6, if (transport.playing) "❚❚" else "▶", C.CYAN, C.BG0, 20f)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(9f)
        tp.color = 0xFF9BA4CC.toInt()
        val sb = btnRect(3)!!
        c.drawText("сетка", sb.left + dp(2f), sb.top - dp(2f), tp)
        if (rec && transport.playing) {
            p.color = C.RED
            c.drawCircle(w / 2f, fieldTop + dp(14f), dp(6f), p)
        }
    }
}
