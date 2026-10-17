package com.ryxens.neonbeat.core

import java.util.Random

/**
 * Синтезатор: превращает Composition (ноты и ударные) в PCM 16 бит, моно, 44100 Гц.
 * Никаких внешних файлов — вся музыка встроенных песен рождается здесь.
 */
object Synth {
    const val RATE = 44100
    private const val TWO_PI = 6.2831853071795864

    // Быстрый «синус» на параболах (вход — фаза 0..1)
    private fun psin(p: Float): Float {
        var x = p * 6.2831855f - 3.1415927f
        val s = 1.2732395f * x - 0.4052847f * Math.abs(x) * x
        return 0.225f * (s * Math.abs(s) - s) + s
    }

    private fun hz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    // ---------- ударные (рисуются один раз) ----------

    private val kickS: FloatArray by lazy {
        val n = (RATE * 0.34).toInt()
        val o = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val f = 42.0 + 120.0 * Math.exp(-t * 32.0)
            ph += TWO_PI * f / RATE
            var v = Math.sin(ph) * Math.exp(-t * 8.5)
            if (t < 0.003) v += (1.0 - t / 0.003) * 0.5
            o[i] = (v * 1.1).toFloat()
        }
        o
    }

    private val snareS: FloatArray by lazy {
        val n = (RATE * 0.24).toInt()
        val o = FloatArray(n)
        val r = Random(7)
        var prev = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val w = r.nextDouble() * 2 - 1
            val hp = w - prev * 0.55
            prev = w
            val v = hp * Math.exp(-t * 20.0) * 0.75 + Math.sin(TWO_PI * 190.0 * t) * Math.exp(-t * 32.0) * 0.55
            o[i] = v.toFloat()
        }
        o
    }

    private val clapS: FloatArray by lazy {
        val n = (RATE * 0.26).toInt()
        val o = FloatArray(n)
        val r = Random(11)
        var prev = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val w = r.nextDouble() * 2 - 1
            val hp = w - prev * 0.5
            prev = w
            var env = 0.0
            for (k in 0..2) {
                val d = t - k * 0.011
                if (d >= 0) env = Math.max(env, Math.exp(-d * 260.0))
            }
            if (t > 0.033) env = Math.max(env, Math.exp(-(t - 0.033) * 24.0) * 0.55)
            o[i] = (hp * env * 0.8).toFloat()
        }
        o
    }

    private val hatS: FloatArray by lazy { hat(0.07, 85.0, 3) }
    private val ohatS: FloatArray by lazy { hat(0.30, 13.0, 5) }

    private fun hat(sec: Double, decay: Double, seed: Long): FloatArray {
        val n = (RATE * sec).toInt()
        val o = FloatArray(n)
        val r = Random(seed)
        var p1 = 0.0
        var p2 = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val w = r.nextDouble() * 2 - 1
            val hp = w - p1 * 0.9 + p2 * 0.3 // грубый фильтр верхних частот
            p2 = p1
            p1 = w
            o[i] = (hp * Math.exp(-t * decay) * 0.35).toFloat()
        }
        return o
    }

    private fun mix(buf: FloatArray, start: Int, s: FloatArray, gain: Float) {
        var i = 0
        val n = Math.min(s.size, buf.size - start)
        while (i < n) {
            buf[start + i] += s[i] * gain
            i++
        }
    }

    // ---------- тональные голоса ----------

    private fun lead(buf: FloatArray, start: Int, len: Int, f: Double, vel: Float, chip: Boolean) {
        val rel = (RATE * 0.07).toInt()
        val n = Math.min(len + rel, buf.size - start)
        val inc1 = (f / RATE).toFloat()
        val inc2 = inc1 * 1.006f
        var p1 = 0f
        var p2 = 0.37f
        var lp = 0f
        val a = 0.32f
        val duty = 0.25f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            var env = Math.min(1f, t / 0.004f)
            env *= if (chip) (0.55f + 0.45f * Math.exp((-t * 5.0f).toDouble()).toFloat())
            else (0.62f + 0.38f * Math.exp((-t * 7.0f).toDouble()).toFloat())
            if (i > len) env *= Math.exp((-(i - len).toFloat() / RATE * 45.0f).toDouble()).toFloat()
            val x: Float
            if (chip) {
                x = if (p1 < duty) 0.5f else -0.5f
            } else {
                val s = (p1 * 2 - 1) + (p2 * 2 - 1)
                lp += (s * 0.5f - lp) * a
                x = lp
            }
            buf[start + i] += x * env * vel * 0.42f
            p1 += inc1; if (p1 >= 1f) p1 -= 1f
            p2 += inc2; if (p2 >= 1f) p2 -= 1f
        }
    }

    private fun bass(buf: FloatArray, start: Int, len: Int, f: Double, vel: Float, chip: Boolean) {
        val rel = (RATE * 0.03).toInt()
        val n = Math.min(len + rel, buf.size - start)
        val inc = (f / RATE).toFloat()
        var p = 0f
        var ps = 0f
        var lp = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            var env = Math.min(1f, t / 0.003f)
            env *= 0.8f + 0.2f * Math.exp((-t * 9.0f).toDouble()).toFloat()
            if (i > len) env *= Math.exp((-(i - len).toFloat() / RATE * 90.0f).toDouble()).toFloat()
            val x: Float
            if (chip) {
                x = if (p < 0.5f) 0.5f else -0.5f
            } else {
                val saw = p * 2 - 1
                lp += (saw - lp) * 0.07f
                x = lp * 1.6f * 0.6f + psin(ps) * 0.7f
            }
            buf[start + i] += x * env * vel * 0.62f
            p += inc; if (p >= 1f) p -= 1f
            ps += inc; if (ps >= 1f) ps -= 1f
        }
    }

    private fun arp(buf: FloatArray, start: Int, len: Int, f: Double, vel: Float, chip: Boolean) {
        val n = Math.min((RATE * 0.16).toInt() + len, buf.size - start)
        val inc = (f / RATE).toFloat()
        var p = 0f
        var lp = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val env = Math.min(1f, t / 0.002f) * Math.exp((-t * 22.0f).toDouble()).toFloat()
            val x: Float
            if (chip) {
                x = if (p < 0.125f) 0.5f else -0.5f
            } else {
                val tri = Math.abs(p * 2 - 1) * 2 - 1
                lp += (tri - lp) * 0.5f
                x = lp
            }
            buf[start + i] += x * env * vel * 0.3f
            p += inc; if (p >= 1f) p -= 1f
        }
    }

    private fun pad(buf: FloatArray, start: Int, len: Int, f: Double, vel: Float) {
        val rel = (RATE * 0.25).toInt()
        val n = Math.min(len + rel, buf.size - start)
        val inc1 = (f / RATE).toFloat()
        val inc2 = inc1 * 1.004f
        var p1 = 0f
        var p2 = 0.5f
        var lp = 0f
        val att = RATE * 0.35f
        for (i in 0 until n) {
            var env = Math.min(1f, i / att)
            if (i > len) env *= 1f - (i - len).toFloat() / rel
            val s = (p1 * 2 - 1) + (p2 * 2 - 1)
            lp += (s - lp) * 0.035f
            buf[start + i] += lp * env * vel * 0.22f
            p1 += inc1; if (p1 >= 1f) p1 -= 1f
            p2 += inc2; if (p2 >= 1f) p2 -= 1f
        }
    }

    /** Рендерит всю композицию. Возвращает моно PCM со звуковым «хвостом» в конце. */
    fun render(c: Composition): ShortArray {
        val stepS = c.stepMs * RATE / 1000.0
        val total = (c.totalSteps * stepS).toInt() + RATE * 2
        val buf = FloatArray(total)
        for (e in c.events) {
            val start = (e.step * stepS).toInt()
            if (start >= total) continue
            val len = Math.max(1, (e.len * stepS * 0.96).toInt())
            when (e.inst) {
                Inst.KICK -> mix(buf, start, kickS, 0.95f * e.vel)
                Inst.SNARE -> mix(buf, start, snareS, 0.7f * e.vel)
                Inst.CLAP -> mix(buf, start, clapS, 0.75f * e.vel)
                Inst.HAT -> mix(buf, start, hatS, 0.8f * e.vel)
                Inst.OHAT -> mix(buf, start, ohatS, 0.7f * e.vel)
                Inst.BASS -> bass(buf, start, len, hz(e.midi), e.vel, c.chip)
                Inst.LEAD -> lead(buf, start, len, hz(e.midi), e.vel, c.chip)
                Inst.ARP -> arp(buf, start, len, hz(e.midi), e.vel, c.chip)
                Inst.PAD -> pad(buf, start, len, hz(e.midi), e.vel)
            }
        }
        val out = ShortArray(total)
        for (i in 0 until total) {
            val v = Math.tanh((buf[i] * 0.95f).toDouble())
            out[i] = (v * 30000.0).toInt().toShort()
        }
        return out
    }

    // ---------- короткие звуки интерфейса ----------

    /** Звук попадания: короткий «тик». */
    fun hitSound(rate: Int = RATE): ShortArray {
        val n = (rate * 0.05).toInt()
        val o = ShortArray(n)
        val r = Random(5)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val v = Math.sin(TWO_PI * 1700.0 * t) * Math.exp(-t * 85.0) * 0.55 +
                (r.nextDouble() * 2 - 1) * Math.exp(-t * 400.0) * 0.4
            o[i] = (v * 22000).toInt().toShort()
        }
        return o
    }

    /** Звук промаха: тусклый «удар». */
    fun missSound(rate: Int = RATE): ShortArray {
        val n = (rate * 0.12).toInt()
        val o = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val v = Math.sin(TWO_PI * (110.0 - 50.0 * t * 4) * t) * Math.exp(-t * 28.0) * 0.6
            o[i] = (v * 20000).toInt().toShort()
        }
        return o
    }

    /** Клик в меню. */
    fun clickSound(rate: Int = RATE): ShortArray {
        val n = (rate * 0.04).toInt()
        val o = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val v = Math.sin(TWO_PI * 900.0 * t) * Math.exp(-t * 110.0) * 0.5
            o[i] = (v * 20000).toInt().toShort()
        }
        return o
    }

    /** Метроном в редакторе: сильная и слабая доли. */
    fun metronome(strong: Boolean, rate: Int = RATE): ShortArray {
        val n = (rate * 0.045).toInt()
        val o = ShortArray(n)
        val f = if (strong) 1500.0 else 1000.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val v = Math.sin(TWO_PI * f * t) * Math.exp(-t * 90.0) * 0.6
            o[i] = (v * 22000).toInt().toShort()
        }
        return o
    }
}
