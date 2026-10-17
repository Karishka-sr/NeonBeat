package com.ryxens.neonbeat.core

/**
 * Пять встроенных песен. Все они сочинены кодом (см. Composer) и
 * озвучиваются синтезатором при первом запуске, поэтому в APK нет аудиофайлов.
 */
object Songs {

    // Короткая запись слоёв: K=бочка, S=снейр, H=хэт, B=бас, P=пэд, L=мелодия, A=арпеджио, F=fill
    private fun layers(s: String): Int {
        var m = 0
        for (c in s) m = m or when (c) {
            'K' -> Layer.K
            'S' -> Layer.S
            'H' -> Layer.H
            'B' -> Layer.B
            'P' -> Layer.P
            'L' -> Layer.L
            'A' -> Layer.A
            'F' -> Layer.F
            else -> 0
        }
        return m
    }

    private fun sec(bars: Int, l: String) = Section(bars, layers(l))

    val all: List<SongDef> = listOf(
        SongDef(
            id = "neon_horizon", title = "Neon Horizon", artist = "NeonBeat Studio",
            bpm = 128.0, rootMidi = 57, minor = true,
            progression = intArrayOf(0, 5, 2, 6),
            sections = listOf(
                sec(4, "HP"), sec(4, "KHBP"), sec(8, "KSHBL"), sec(4, "PL"),
                sec(8, "KSHBPLAF"), sec(8, "KSHBLAF"), sec(4, "KHPL")
            ),
            drums = DrumStyle.FOUR, bass = BassStyle.OFFBEAT, lead = LeadStyle.SYNC,
            seed = 1001L, hue = 190f
        ),
        SongDef(
            id = "pixel_rush", title = "Pixel Rush", artist = "NeonBeat Studio",
            bpm = 150.0, rootMidi = 60, minor = false,
            progression = intArrayOf(0, 4, 5, 3),
            sections = listOf(
                sec(4, "KH"), sec(8, "KSHBL"), sec(8, "KSHBA"), sec(4, "BA"),
                sec(8, "KSHBLAF"), sec(12, "KSHBLA"), sec(4, "KHB")
            ),
            drums = DrumStyle.CHIP, bass = BassStyle.OCTAVE, lead = LeadStyle.CHIP,
            seed = 2002L, hue = 120f
        ),
        SongDef(
            id = "midnight_drive", title = "Midnight Drive", artist = "NeonBeat Studio",
            bpm = 100.0, rootMidi = 50, minor = true,
            progression = intArrayOf(0, 3, 5, 4),
            sections = listOf(
                sec(4, "PH"), sec(4, "KHBP"), sec(8, "KSHBPLA"), sec(4, "PLA"),
                sec(8, "KSHBPLAF"), sec(4, "KHBLA"), sec(4, "P")
            ),
            drums = DrumStyle.HALF, bass = BassStyle.SUB, lead = LeadStyle.LONG,
            seed = 3003L, hue = 260f
        ),
        SongDef(
            id = "gravity_wave", title = "Gravity Wave", artist = "NeonBeat Studio",
            bpm = 140.0, rootMidi = 52, minor = true,
            progression = intArrayOf(0, 6, 5, 6),
            sections = listOf(
                sec(4, "KH"), sec(8, "KSHB"), sec(8, "KSHBA"), sec(4, "PAL"),
                sec(8, "KSHBALF"), sec(8, "KSHBPLAF"), sec(8, "KSHBLF"), sec(0, "")
            ).filter { it.bars > 0 },
            drums = DrumStyle.BREAK, bass = BassStyle.PUMP, lead = LeadStyle.ARPLEAD,
            seed = 4004L, hue = 330f
        ),
        SongDef(
            id = "hyperdrive", title = "Hyperdrive", artist = "NeonBeat Studio",
            bpm = 174.0, rootMidi = 54, minor = true,
            progression = intArrayOf(0, 2, 5, 6),
            sections = listOf(
                sec(4, "HP"), sec(8, "KSHB"), sec(8, "KSHBL"), sec(4, "PL"),
                sec(8, "KSHBLAF"), sec(8, "KSHBA"), sec(16, "KSHBPLAF"), sec(8, "KSHBLF")
            ),
            drums = DrumStyle.DNB, bass = BassStyle.ROLL, lead = LeadStyle.SYNC,
            seed = 5005L, hue = 20f
        )
    )

    fun byId(id: String): SongDef? = all.firstOrNull { it.id == id }

    // Кэш: сочинение каждой песни делается один раз
    private val compCache = HashMap<String, Composition>()

    @Synchronized
    fun composition(def: SongDef): Composition =
        compCache.getOrPut(def.id) { Composer.compose(def) }

    /** Карта для встроенной песни на нужной сложности. */
    fun chart(def: SongDef, diff: Difficulty): PlayChart =
        ChartGen.generate(def, composition(def), diff, 4)
}
