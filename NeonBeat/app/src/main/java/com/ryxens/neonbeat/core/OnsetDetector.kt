package com.ryxens.neonbeat.core

import java.util.Random

/** Найденный «удар» в музыке. band: 0 — низкие частоты (бас/бочка), 1 — высокие (хэты/клики). */
class Onset(val timeMs: Int, val strength: Double, val band: Double)

/**
 * Простой поиск ударов в звуке (для автосоздания карты из своей музыки).
 * Считаем «всплески» громкости отдельно для низких и высоких частот и ищем пики.
 */
object OnsetDetector {

    fun detect(pcm: ShortArray, rate: Int, sensitivity: Double = 1.0): List<Onset> {
        val hop = Math.max(1, rate / 100)               // 10 мс
        val frames = pcm.size / hop
        if (frames < 20) return emptyList()
        val low = DoubleArray(frames)
        val high = DoubleArray(frames)
        var lp = 0.0
        var prev = 0.0
        for (f in 0 until frames) {
            var sl = 0.0
            var sh = 0.0
            val base = f * hop
            for (i in 0 until hop) {
                val x = pcm[base + i] / 32768.0
                lp += (x - lp) * 0.08                    // низкие частоты (~600 Гц)
                val hp = x - prev                        // грубый фильтр верхних частот
                prev = x
                sl += Math.abs(lp)
                sh += Math.abs(hp)
            }
            low[f] = sl / hop
            high[f] = sh / hop
        }
        // «всплески»: рост энергии относительно недавнего прошлого
        val fl = DoubleArray(frames)
        val fh = DoubleArray(frames)
        for (f in 1 until frames) {
            var pl = 0.0
            var ph = 0.0
            val from = Math.max(0, f - 4)
            for (k in from until f) { pl += low[k]; ph += high[k] }
            val n = (f - from).toDouble()
            fl[f] = Math.max(0.0, low[f] - pl / n)
            fh[f] = Math.max(0.0, high[f] - ph / n) * 1.6
        }
        val flux = DoubleArray(frames) { fl[it] + fh[it] }

        // адаптивный порог: среднее за ±0.6 с
        val win = 60
        val out = ArrayList<Onset>()
        var lastT = -1000
        val k = 1.0 + 0.9 / Math.max(0.3, sensitivity)
        for (f in 2 until frames - 2) {
            val v = flux[f]
            if (v <= flux[f - 1] || v < flux[f + 1]) continue
            var sum = 0.0
            var cnt = 0
            val a = Math.max(0, f - win)
            val b = Math.min(frames - 1, f + win)
            for (j in a..b) { sum += flux[j]; cnt++ }
            val mean = sum / cnt
            if (v < mean * k + 0.004) continue
            val t = f * 10
            if (t - lastT < 55) {
                // слишком близко: оставляем более сильный
                val last = out[out.size - 1]
                if (v / (mean + 1e-9) > last.strength) {
                    out[out.size - 1] = Onset(t, v / (mean + 1e-9), fh[f] / (flux[f] + 1e-9))
                    lastT = t
                }
                continue
            }
            out.add(Onset(t, v / (mean + 1e-9), fh[f] / (flux[f] + 1e-9)))
            lastT = t
        }
        return out
    }
}

/** Автосоздание карты из найденных ударов. */
object AutoMap {

    /**
     * density 1..5: чем больше, тем больше ударов превращается в ноты.
     * snapStepMs > 0 — выровнять ноты по сетке BPM (начало сетки — gridOffsetMs).
     */
    fun generate(onsets: List<Onset>, keys: Int, density: Int, snapStepMs: Double, gridOffsetMs: Int, seed: Long): List<Note> {
        if (onsets.isEmpty()) return emptyList()
        val d = density.coerceIn(1, 5)
        val minGap = doubleArrayOf(420.0, 300.0, 210.0, 150.0, 105.0)[d - 1]
        val keep = doubleArrayOf(0.30, 0.45, 0.62, 0.80, 1.0)[d - 1]
        val sorted = onsets.map { it.strength }.sorted()
        val thr = sorted[((sorted.size - 1) * (1.0 - keep)).toInt().coerceIn(0, sorted.size - 1)]
        val rng = Random(seed)
        val notes = ArrayList<Note>()
        val usage = IntArray(keys)
        var total = 0
        var lastLane = keys / 2
        var lastT = -100000
        for (o in onsets) {
            if (o.strength < thr) continue
            var t = o.timeMs
            if (snapStepMs > 0) {
                t = Math.round(gridOffsetMs + Math.round((t - gridOffsetMs) / snapStepMs) * snapStepMs).toInt()
            }
            if (t < 0 || t - lastT < minGap) continue
            var best = 0
            var bestCost = Double.MAX_VALUE
            val pref = o.band * (keys - 1)
            for (l in 0 until keys) {
                var c = Math.abs(l - pref) * 0.8 + Math.abs(l - lastLane) * 0.35
                if (l == lastLane && t - lastT < 350) c += 5.0
                c += 4.0 * usage[l] / (total + 8) + rng.nextDouble() * 1.0
                if (c < bestCost) { bestCost = c; best = l }
            }
            notes.add(Note(t, best))
            usage[best]++
            total++
            lastLane = best
            lastT = t
        }
        return notes
    }
}
