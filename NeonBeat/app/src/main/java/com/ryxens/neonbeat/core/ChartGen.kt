package com.ryxens.neonbeat.core

import java.util.Random

/**
 * Генератор карт: берёт музыкальные события песни и строит из них ноты
 * под выбранную сложность. Чем выше сложность — тем больше инструментов
 * превращается в ноты, тем плотнее поток, чаще аккорды и длинные ноты.
 */
object ChartGen {

    private class Rule(
        val minStrength: Double,   // какие инструменты вообще становятся нотами
        val grid: Int,             // на какие 1/16-доли можно ставить ноты (кроме очень сильных)
        val strongBypass: Double,  // сила, при которой ограничение сетки не действует
        val minGapMs: Double,      // минимальный интервал между нотами
        val chordProb: Double,     // шанс сделать аккорд из двух нот
        val chordMin: Double,      // минимальная сила второго звука для аккорда
        val holdMinSteps: Int,     // от скольки 1/16 длинный звук становится холдом
        val holdProb: Double,
        val maxSimul: Int,         // сколько клавиш можно держать/нажимать одновременно
        val jackOk: Boolean,       // можно ли повторять одну и ту же дорожку подряд
        val moveWeight: Double     // насколько штрафуем большие «прыжки» между дорожками
    )

    private fun rule(d: Difficulty): Rule = when (d) {
        Difficulty.EASY -> Rule(2.5, 4, 3.5, 300.0, 0.0, 9.0, 8, 1.0, 2, false, 0.9)
        Difficulty.NORMAL -> Rule(2.5, 2, 3.0, 160.0, 0.0, 9.0, 6, 0.9, 2, false, 0.6)
        Difficulty.HARD -> Rule(1.6, 1, 3.0, 150.0, 0.10, 2.4, 4, 0.8, 2, true, 0.35)
        Difficulty.EXPERT -> Rule(1.1, 1, 3.0, 118.0, 0.28, 1.6, 4, 0.7, 3, true, 0.2)
        Difficulty.INSANE -> Rule(0.4, 1, 3.0, 88.0, 0.42, 1.1, 3, 0.7, 3, true, 0.1)
    }

    private class Cand(val strength: Double, val midi: Int, val len: Int, val role: Int)

    private fun weight(e: MusicEvent): Double = when (e.inst) {
        Inst.KICK -> 3.0
        Inst.SNARE, Inst.CLAP -> 3.0
        Inst.LEAD -> 2.6 + (if (e.len >= 6) 0.3 else 0.0)
        Inst.BASS -> 1.7 + (if (e.len >= 8) 0.4 else 0.0)
        Inst.ARP -> if (e.vel >= 0.7f) 1.7 else 1.2
        Inst.OHAT -> 1.1
        Inst.HAT -> if (e.vel >= 0.8f) 0.6 else 0.4
        Inst.PAD -> 0.0
    }

    // роль инструмента: 0 — ударные, 1 — бас, 2 — мелодия/арпеджио
    private fun role(i: Inst): Int = when (i) {
        Inst.KICK, Inst.SNARE, Inst.CLAP, Inst.HAT, Inst.OHAT -> 0
        Inst.BASS -> 1
        else -> 2
    }

    fun generate(def: SongDef, comp: Composition, diff: Difficulty, keys: Int = 4): PlayChart {
        val rule = rule(diff)
        val stepMs = comp.stepMs
        val rng = Random(def.seed * 31 + diff.ordinal * 7919L)

        // 1. Кандидаты по шагам
        val per = arrayOfNulls<ArrayList<Cand>>(comp.totalSteps + 1)
        val pitches = ArrayList<Int>()
        for (e in comp.events) {
            val w = weight(e)
            if (w <= 0.0 || e.step >= comp.totalSteps) continue
            var lst = per[e.step]
            if (lst == null) { lst = ArrayList(); per[e.step] = lst }
            lst.add(Cand(w, e.midi, e.len, role(e.inst)))
            if (e.inst == Inst.LEAD || e.inst == Inst.ARP) pitches.add(e.midi)
        }
        pitches.sort()
        var lo = if (pitches.isEmpty()) 50 else pitches[pitches.size / 10]
        var hi = if (pitches.isEmpty()) 80 else pitches[pitches.size * 9 / 10]
        if (hi <= lo) { lo = 50; hi = 80 }

        // 2. Размещение
        val notes = ArrayList<Note>()
        val busyUntil = IntArray(keys) { -1 }    // до какого мс дорожка занята холдом
        var lastLane = keys / 2
        var lastTime = -100000.0
        var lastStrength = 0.0
        var kickFlip = false
        val usage = IntArray(keys)
        var usedTotal = 0

        for (step in 0 until comp.totalSteps) {
            val list = per[step] ?: continue
            list.sortByDescending { it.strength }
            val top = list[0]
            if (top.strength < rule.minStrength) continue
            if (step % rule.grid != 0 && top.strength < rule.strongBypass) continue
            val t = step * stepMs
            val gap = t - lastTime
            if (gap < rule.minGapMs * 0.98) continue

            // не занимаем место перед явно более сильной нотой
            val nxt = per.getOrNull(step + 1)
            if (nxt != null && nxt.isNotEmpty()) {
                val s2 = nxt.maxOf { it.strength }
                if (s2 >= top.strength + 0.9 && s2 >= rule.minStrength && (stepMs) < rule.minGapMs) continue
            }

            val timeMs = Math.round(t).toInt()
            var activeHolds = 0
            for (l in 0 until keys) if (busyUntil[l] > timeMs) activeHolds++
            if (activeHolds >= rule.maxSimul) continue

            // сколько нот ставим на этот момент
            var count = 1
            if (rule.chordProb > 0.0 && list.size > 1) {
                val sec = list.firstOrNull { it.role != top.role && it.strength >= rule.chordMin }
                if (sec != null && rng.nextDouble() < rule.chordProb && activeHolds + 2 <= rule.maxSimul) count = 2
                if (count == 2 && diff.ordinal >= Difficulty.EXPERT.ordinal && top.strength >= 3.0 &&
                    rng.nextDouble() < 0.12 && activeHolds + 3 <= rule.maxSimul && keys >= 4
                ) count = 3
            }

            // длинная нота?
            var holdSteps = 0
            if (top.len >= rule.holdMinSteps && (top.role == 2 || (top.role == 1 && top.len >= 8)) &&
                rng.nextDouble() < rule.holdProb
            ) holdSteps = top.len

            val used = BooleanArray(keys)
            var firstLane = -1
            for (k in 0 until count) {
                val cand = if (k == 0) top else list.first { it.role != top.role }
                val pref = preferredLane(cand, keys, lo, hi, kickFlip, rng)
                val lane = pickLane(
                    keys, pref, lastLane, used, busyUntil, timeMs, rule, rng,
                    gap, diff, usage, usedTotal
                )
                if (lane < 0) break
                used[lane] = true
                usage[lane]++
                usedTotal++
                if (k == 0) firstLane = lane
                var dur = 0
                if (k == 0 && holdSteps > 0) {
                    dur = Math.round(holdSteps * stepMs - stepMs * 0.6).toInt()
                    if (dur < 160) dur = 0
                    else busyUntil[lane] = timeMs + dur + 60
                }
                notes.add(Note(timeMs, lane, dur))
            }
            if (firstLane < 0) continue
            if (top.role == 0) kickFlip = !kickFlip
            lastLane = firstLane
            lastTime = t
            lastStrength = top.strength
        }

        notes.sortWith(compareBy({ it.timeMs }, { it.lane }))
        val level = ChartStats.level(notes, keys)
        return PlayChart(
            id = "${def.id}:${diff.shortName}", title = def.title, artist = def.artist,
            bpm = def.bpm, firstBeatMs = 0, keys = keys, notes = notes,
            audio = "builtin:${def.id}", diff = diff, level = level, hue = def.hue,
            lengthMs = comp.lengthMs
        )
    }

    private fun preferredLane(c: Cand, keys: Int, lo: Int, hi: Int, flip: Boolean, rng: Random): Double {
        return when (c.role) {
            2 -> ((c.midi - lo).toDouble() / (hi - lo).toDouble()).coerceIn(0.0, 1.0) * (keys - 1)
            1 -> if (flip) 1.0 else (keys - 2).toDouble()
            else -> if (flip) 0.0 else (keys - 1).toDouble()
        }
    }

    private fun pickLane(
        keys: Int, pref: Double, last: Int, used: BooleanArray, busyUntil: IntArray,
        timeMs: Int, rule: Rule, rng: Random, gapMs: Double, diff: Difficulty,
        usage: IntArray, usedTotal: Int
    ): Int {
        var best = -1
        var bestCost = Double.MAX_VALUE
        for (l in 0 until keys) {
            if (used[l]) continue
            if (busyUntil[l] > timeMs) continue
            var cost = Math.abs(l - pref) * 1.0
            cost += Math.abs(l - last) * rule.moveWeight
            if (l == last) {
                if (!rule.jackOk) cost += 6.0
                else cost += if (gapMs < 200) 1.6 else 0.7
            }
            cost += 4.0 * usage[l] / (usedTotal + 8)   // выравниваем нагрузку на дорожки
            cost += rng.nextDouble() * (0.9 + diff.ordinal * 0.15)
            if (cost < bestCost) { bestCost = cost; best = l }
        }
        return best
    }
}
