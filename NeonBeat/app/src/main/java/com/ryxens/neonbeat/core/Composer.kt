package com.ryxens.neonbeat.core

import java.util.Random

/*
 * «Композитор»: из короткого описания песни (темп, тональность, аккорды,
 * структура) строит список музыкальных событий. Потом Synth превращает их
 * в звук, а ChartGen — в ноты игры. Поэтому ноты всегда совпадают с музыкой.
 */

enum class Inst { KICK, SNARE, CLAP, HAT, OHAT, BASS, LEAD, ARP, PAD }

/** step — номер 1/16-доли от начала песни, len — длина в 1/16, midi — высота звука. */
class MusicEvent(val step: Int, val len: Int, val inst: Inst, val midi: Int, val vel: Float)

class Composition(
    val bpm: Double,
    val totalSteps: Int,
    val events: List<MusicEvent>,
    val chip: Boolean
) {
    val stepMs: Double get() = 60000.0 / bpm / 4.0
    val lengthMs: Int get() = (totalSteps * stepMs).toInt()
}

/** Слои, которые играют в секции песни (битовые флаги). */
object Layer {
    const val K = 1     // бочка
    const val S = 2     // малый барабан / клэп
    const val H = 4     // хай-хэты
    const val B = 8     // бас
    const val P = 16    // пэд (аккорды)
    const val L = 32    // мелодия
    const val A = 64    // арпеджио
    const val F = 128   // барабанный fill в последнем такте секции
}

class Section(val bars: Int, val layers: Int)

enum class DrumStyle { FOUR, DNB, BREAK, HALF, CHIP }
enum class BassStyle { OFFBEAT, ROLL, SUB, PUMP, OCTAVE }
enum class LeadStyle { SYNC, LONG, ARPLEAD, CHIP }

class SongDef(
    val id: String,
    val title: String,
    val artist: String,
    val bpm: Double,
    val rootMidi: Int,
    val minor: Boolean,
    val progression: IntArray,      // ступени аккордов по тактам (0 = I, 5 = VI ...)
    val sections: List<Section>,
    val drums: DrumStyle,
    val bass: BassStyle,
    val lead: LeadStyle,
    val seed: Long,
    val hue: Float
)

object Composer {
    private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    // Ритмические шаблоны мелодии на один такт: пары (шаг, длина)
    private val SYNC = arrayOf(
        intArrayOf(0, 3, 3, 3, 6, 2, 8, 4, 12, 4),
        intArrayOf(0, 2, 2, 2, 4, 4, 8, 2, 10, 2, 12, 4),
        intArrayOf(0, 4, 4, 2, 6, 2, 8, 3, 11, 2, 13, 3),
        intArrayOf(0, 2, 3, 1, 4, 2, 7, 1, 8, 4, 14, 2)
    )
    private val LONG = arrayOf(
        intArrayOf(0, 8, 8, 4, 12, 4),
        intArrayOf(0, 12, 12, 4),
        intArrayOf(0, 6, 6, 2, 8, 8),
        intArrayOf(0, 8, 8, 3, 11, 5)
    )
    private val ARPLEAD = arrayOf(
        intArrayOf(0, 2, 2, 2, 4, 2, 6, 2, 8, 2, 10, 2, 12, 2, 14, 2),
        intArrayOf(0, 2, 2, 1, 4, 2, 6, 2, 8, 2, 10, 1, 12, 4)
    )
    private val CHIPR = arrayOf(
        intArrayOf(0, 2, 2, 2, 4, 1, 5, 1, 6, 2, 8, 2, 10, 2, 12, 2, 14, 2),
        intArrayOf(0, 1, 1, 1, 2, 2, 4, 2, 6, 2, 8, 4, 12, 2, 14, 2)
    )

    private class MNote(val step: Int, val len: Int, val off: Int)

    fun compose(def: SongDef): Composition {
        val scale = if (def.minor) MINOR else MAJOR
        fun deg(d: Int): Int = def.rootMidi + scale[Math.floorMod(d, 7)] + 12 * Math.floorDiv(d, 7)

        val ev = ArrayList<MusicEvent>()
        val rng = Random(def.seed)
        var bar = 0

        for (sec in def.sections) {
            val motifA = makeMotif(def.lead, rng)
            val motifB = makeMotif(def.lead, rng)
            for (b in 0 until sec.bars) {
                val base = bar * 16
                val chord = def.progression[b % def.progression.size]
                val last = b == sec.bars - 1
                val L = sec.layers
                val snareAt = BooleanArray(16)

                if (L and Layer.K != 0) {
                    val pat = when (def.drums) {
                        DrumStyle.FOUR -> "x...x...x...x..."
                        DrumStyle.DNB -> "x.........x....."
                        DrumStyle.BREAK -> "x..x......x....."
                        DrumStyle.HALF -> "x.....x...x....."
                        DrumStyle.CHIP -> "x.......x.x....."
                    }
                    put(ev, pat, base, Inst.KICK, 36, 1)
                }
                if (L and Layer.S != 0) {
                    val pat = when (def.drums) {
                        DrumStyle.FOUR -> "....x.......x..."
                        DrumStyle.DNB -> "....x.......x..."
                        DrumStyle.BREAK -> "....x..g.g..x..."
                        DrumStyle.HALF -> "........x......."
                        DrumStyle.CHIP -> "....x.......x..."
                    }
                    val inst = if (def.drums == DrumStyle.FOUR) Inst.CLAP else Inst.SNARE
                    put(ev, pat, base, inst, 38, 1)
                    for (i in 0 until 16) if (pat[i] != '.') snareAt[i] = true
                }
                if (L and Layer.H != 0) {
                    when (def.drums) {
                        DrumStyle.FOUR -> {
                            put(ev, "g...g...g...g...", base, Inst.HAT, 42, 1)
                            put(ev, "..x...x...x...x.", base, Inst.OHAT, 46, 2)
                        }
                        DrumStyle.CHIP -> put(ev, "xgxgxgxgxgxgxgxg", base, Inst.HAT, 42, 1)
                        DrumStyle.HALF -> put(ev, "g.g.g.g.g.g.g.g.", base, Inst.HAT, 42, 1)
                        else -> put(ev, "x.g.x.g.x.g.x.g.", base, Inst.HAT, 42, 1)
                    }
                }
                if (L and Layer.B != 0) bassBar(ev, def.bass, base, deg(chord) - 12)
                if (L and Layer.P != 0) {
                    for (k in intArrayOf(0, 2, 4)) {
                        ev.add(MusicEvent(base, 16, Inst.PAD, deg(chord + k), 0.55f))
                    }
                }
                if (L and Layer.L != 0) {
                    val group = b / 2
                    val motif = if (group % 4 == 3) motifB else motifA
                    val lo = (b % 2) * 16
                    for (m in motif) {
                        if (m.step < lo || m.step >= lo + 16) continue
                        ev.add(MusicEvent(base + (m.step - lo), m.len, Inst.LEAD, deg(chord + 7 + m.off), 0.85f))
                    }
                }
                if (L and Layer.A != 0) {
                    val tones = intArrayOf(0, 2, 4, 7, 4, 2)
                    for (s in 0 until 16) {
                        val v = if (s % 4 == 0) 0.75f else 0.5f
                        ev.add(MusicEvent(base + s, 1, Inst.ARP, deg(chord + 7 + tones[s % 6]), v))
                    }
                }
                if (L and Layer.F != 0 && last) {
                    val steps = intArrayOf(8, 10, 12, 13, 14, 15)
                    for ((i, s) in steps.withIndex()) {
                        if (snareAt[s]) continue
                        ev.add(MusicEvent(base + s, 1, Inst.SNARE, 38, 0.5f + 0.1f * i))
                    }
                }
                bar++
            }
        }
        return Composition(def.bpm, bar * 16, ev, def.lead == LeadStyle.CHIP)
    }

    private fun put(ev: MutableList<MusicEvent>, pat: String, base: Int, inst: Inst, midi: Int, len: Int) {
        for (i in 0 until 16) {
            val vel = when (pat[i]) {
                'X' -> 1.0f
                'x' -> 0.85f
                'g' -> 0.4f
                else -> continue
            }
            ev.add(MusicEvent(base + i, len, inst, midi, vel))
        }
    }

    private fun bassBar(ev: MutableList<MusicEvent>, style: BassStyle, base: Int, root: Int) {
        val fifth = root + 7
        val oct = root + 12
        fun add(step: Int, len: Int, midi: Int, vel: Float = 0.9f) {
            ev.add(MusicEvent(base + step, len, Inst.BASS, midi, vel))
        }
        when (style) {
            BassStyle.OFFBEAT -> {
                add(2, 2, root); add(6, 2, root); add(10, 2, root); add(14, 2, oct)
            }
            BassStyle.ROLL -> {
                val pat = "x.xxx.xxx.xxx.xx"
                for (s in 0 until 16) if (pat[s] == 'x') add(s, 1, if (s % 4 == 2) oct else root, 0.8f)
            }
            BassStyle.SUB -> {
                add(0, 12, root, 1.0f); add(12, 4, fifth, 0.9f)
            }
            BassStyle.PUMP -> {
                add(0, 3, root); add(3, 3, root, 0.8f); add(6, 2, fifth); add(8, 4, root)
                add(12, 2, root, 0.8f); add(14, 2, fifth)
            }
            BassStyle.OCTAVE -> {
                for (s in 0 until 16 step 2) add(s, 2, if ((s / 2) % 2 == 0) root else oct, 0.85f)
            }
        }
    }

    private fun makeMotif(style: LeadStyle, rng: Random): List<MNote> {
        val pool = when (style) {
            LeadStyle.SYNC -> SYNC
            LeadStyle.LONG -> LONG
            LeadStyle.ARPLEAD -> ARPLEAD
            LeadStyle.CHIP -> CHIPR
        }
        val tones = intArrayOf(0, 2, 4, 7)
        val out = ArrayList<MNote>()
        var prev = 4
        for (bar in 0 until 2) {
            val rhythm = pool[rng.nextInt(pool.size)]
            var i = 0
            while (i < rhythm.size) {
                val st = rhythm[i]
                val ln = rhythm[i + 1]
                var off = if (st % 4 == 0) {
                    tones[rng.nextInt(tones.size)]
                } else {
                    (prev + (rng.nextInt(3) - 1) * (1 + rng.nextInt(2))).coerceIn(0, 8)
                }
                if (off == prev && rng.nextInt(3) != 0) off = (off + 2) % 9
                out.add(MNote(bar * 16 + st, ln, off))
                prev = off
                i += 2
            }
        }
        return out
    }
}
