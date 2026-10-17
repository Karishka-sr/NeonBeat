package com.ryxens.neonbeat.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Событие попадания — его забирает отрисовка (эффекты, звук, вибрация). */
class HitEvent(
    val lane: Int,
    val judgment: Judgment,
    val deltaMs: Double,   // < 0 — рано, > 0 — поздно
    val atMs: Double,
    val head: Boolean,     // попадание в начало ноты
    val tail: Boolean      // конец длинной ноты
)

/**
 * Игровой движок: судейство, счёт, комбо, здоровье.
 * Время везде в миллисекундах «игрового времени» (уже со сдвигом калибровки).
 */
class GameEngine(val chart: PlayChart, val autoPlay: Boolean, val noFail: Boolean) {

    val keys: Int = chart.keys
    val notes: Array<Note> = chart.notes.sortedWith(compareBy({ it.timeMs }, { it.lane })).toTypedArray()

    // Окна попадания (±мс). Для длинных нот конец судится мягче.
    private val scale: Double = chart.diff?.windowScale ?: 1.0
    val perfectW: Double = 50.0 * scale
    val greatW: Double = 100.0 * scale
    val goodW: Double = 150.0 * scale
    private val tailPerfect: Double = 90.0 * scale
    private val tailGreat: Double = 150.0 * scale
    private val tailGood: Double = 220.0 * scale

    private val drain: Double = chart.diff?.missDrain ?: 6.0
    private val regen: Double = chart.diff?.regen ?: 0.5

    // Состояние каждой ноты: 0 — ждёт, 1 — держится, 2 — готово, 3 — пропущена
    val state = IntArray(notes.size)
    private val laneList: Array<IntArray>
    private val laneNext = IntArray(keys)

    val counts = IntArray(4)               // по ordinal() оценки
    var combo = 0; private set
    var maxCombo = 0; private set
    var health = 100.0; private set
    var failed = false; private set
    private var weighted = 0.0
    private var judged = 0
    val totalUnits: Int

    /** Ошибки попаданий в мс (для гистограммы на экране результатов). */
    val hitErrors = ArrayList<Double>()
    val events = ArrayList<HitEvent>()

    init {
        val lists = Array(keys) { ArrayList<Int>() }
        var units = 0
        for (i in notes.indices) {
            val n = notes[i]
            if (n.lane in 0 until keys) lists[n.lane].add(i)
            units += if (n.isHold) 2 else 1
        }
        totalUnits = units
        laneList = Array(keys) { l -> IntArray(lists[l].size) { lists[l][it] } }
    }

    // ---------------- ввод ----------------

    fun press(lane: Int, now: Double) {
        if (autoPlay || lane !in 0 until keys) return
        update(now)
        val list = laneList[lane]
        val p = laneNext[lane]
        if (p >= list.size) return
        val idx = list[p]
        if (state[idx] != 0) return
        val delta = now - notes[idx].timeMs
        if (delta < -goodW) return // слишком рано — игнорируем нажатие
        val a = abs(delta)
        val j = when {
            a <= perfectW -> Judgment.PERFECT
            a <= greatW -> Judgment.GREAT
            else -> Judgment.GOOD
        }
        hitHead(idx, j, delta, now)
    }

    fun release(lane: Int, now: Double) {
        if (autoPlay || lane !in 0 until keys) return
        val list = laneList[lane]
        val p = laneNext[lane]
        if (p >= list.size) return
        val idx = list[p]
        if (state[idx] != 1) return
        val remain = notes[idx].endMs - now
        if (remain <= tailGood) {
            val j = when {
                remain <= tailPerfect -> Judgment.PERFECT
                remain <= tailGreat -> Judgment.GREAT
                else -> Judgment.GOOD
            }
            finishTail(idx, j, -remain, now)
        } else {
            // отпустил слишком рано — холд сорван
            state[idx] = 2
            laneNext[lane]++
            register(Judgment.MISS, lane, 0.0, now, head = false, tail = true)
        }
    }

    /** Вызывается каждый кадр: фиксирует промахи и завершает удержания. */
    fun update(now: Double) {
        for (l in 0 until keys) {
            val list = laneList[l]
            while (laneNext[l] < list.size) {
                val idx = list[laneNext[l]]
                val n = notes[idx]
                when (state[idx]) {
                    0 -> {
                        if (autoPlay) {
                            if (now >= n.timeMs) hitHead(idx, Judgment.PERFECT, 0.0, now) else break
                        } else if (now - n.timeMs > goodW) {
                            missHead(idx, now)
                        } else break
                    }
                    1 -> {
                        if (now >= n.endMs) finishTail(idx, Judgment.PERFECT, 0.0, now) else break
                    }
                    else -> laneNext[l]++
                }
            }
        }
    }

    // ---------------- внутреннее ----------------

    private fun hitHead(idx: Int, j: Judgment, delta: Double, now: Double) {
        val n = notes[idx]
        if (n.isHold) {
            state[idx] = 1
        } else {
            state[idx] = 2
            laneNext[n.lane]++
        }
        hitErrors.add(delta)
        register(j, n.lane, delta, now, head = true, tail = false)
    }

    private fun missHead(idx: Int, now: Double) {
        val n = notes[idx]
        state[idx] = 3
        laneNext[n.lane]++
        register(Judgment.MISS, n.lane, 0.0, now, head = true, tail = false)
        if (n.isHold) {
            // хвост пропущенной длинной ноты тоже считается промахом (без доп. штрафа)
            counts[Judgment.MISS.ordinal]++
            judged++
        }
    }

    private fun finishTail(idx: Int, j: Judgment, delta: Double, now: Double) {
        val n = notes[idx]
        state[idx] = 2
        laneNext[n.lane]++
        register(j, n.lane, delta, now, head = false, tail = true)
    }

    private fun register(j: Judgment, lane: Int, delta: Double, now: Double, head: Boolean, tail: Boolean) {
        counts[j.ordinal]++
        weighted += j.weight
        judged++
        if (j == Judgment.MISS) {
            combo = 0
            health -= drain
        } else {
            combo++
            maxCombo = max(maxCombo, combo)
            health += when (j) {
                Judgment.PERFECT -> regen
                Judgment.GREAT -> regen * 0.5
                else -> 0.0
            }
        }
        health = health.coerceIn(0.0, 100.0)
        events.add(HitEvent(lane, j, delta, now, head, tail))
        if (health <= 0.0 && !noFail && !autoPlay) failed = true
    }

    // ---------------- результаты ----------------

    val isFinished: Boolean
        get() {
            for (l in 0 until keys) if (laneNext[l] < laneList[l].size) return false
            return true
        }

    /** Точность в процентах от уже сыгранного. */
    fun accuracy(): Double = if (judged == 0) 100.0 else weighted / judged * 100.0

    /** Точность в процентах от ВСЕЙ карты (для финального ранга). */
    fun finalAccuracy(): Double = if (totalUnits == 0) 100.0 else weighted / totalUnits * 100.0

    /** Счёт до 1 000 000: 900k за точность и 100k за комбо. */
    fun score(): Int {
        if (totalUnits == 0) return 0
        val v = 900000.0 * weighted / totalUnits + 100000.0 * maxCombo / totalUnits
        return v.roundToInt()
    }

    val fullCombo: Boolean get() = counts[Judgment.MISS.ordinal] == 0

    fun rank(): String {
        if (failed) return "F"
        val a = finalAccuracy()
        return when {
            a >= 99.0 -> "SS"
            a >= 95.0 -> "S"
            a >= 90.0 -> "A"
            a >= 80.0 -> "B"
            a >= 70.0 -> "C"
            else -> "D"
        }
    }
}
