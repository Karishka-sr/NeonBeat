package com.ryxens.neonbeat.core

/*
 * Основные данные игры. Этот файл НЕ зависит от Android —
 * поэтому всё ядро можно проверять обычным компилятором Kotlin.
 */

/** Одна нота. durMs > 0 означает «длинная» нота (холд): её нужно держать. */
class Note(val timeMs: Int, val lane: Int, var durMs: Int = 0) {
    val isHold: Boolean get() = durMs > 0
    val endMs: Int get() = timeMs + durMs
}

/** Оценка попадания. weight — сколько очков точности даёт попадание. */
enum class Judgment(val label: String, val weight: Double) {
    PERFECT("PERFECT", 1.0),
    GREAT("GREAT", 0.8),
    GOOD("GOOD", 0.5),
    MISS("MISS", 0.0)
}

/**
 * Сложности. windowScale — множитель окон попадания (чем меньше, тем строже),
 * missDrain — сколько здоровья отнимает промах, regen — сколько возвращает PERFECT.
 */
enum class Difficulty(
    val label: String,
    val shortName: String,
    val color: Int,
    val windowScale: Double,
    val missDrain: Double,
    val regen: Double
) {
    EASY("Лёгкая", "EASY", 0xFF4CD964.toInt(), 1.25, 3.0, 0.8),
    NORMAL("Нормальная", "NORMAL", 0xFF35C8F0.toInt(), 1.10, 5.0, 0.6),
    HARD("Сложная", "HARD", 0xFFFFB020.toInt(), 1.00, 7.0, 0.5),
    EXPERT("Эксперт", "EXPERT", 0xFFFF4D6D.toInt(), 0.92, 9.0, 0.45),
    INSANE("Безумие", "INSANE", 0xFFB45CFF.toInt(), 0.85, 11.0, 0.4)
}

/**
 * Всё, что нужно, чтобы сыграть одну карту (встроенную или пользовательскую).
 * audio: "builtin:<id песни>" | "file:<имя файла>" | "" (без музыки)
 */
class PlayChart(
    val id: String,
    val title: String,
    val artist: String,
    val bpm: Double,
    val firstBeatMs: Int,
    val keys: Int,
    val notes: List<Note>,
    val audio: String,
    val diff: Difficulty?,
    val level: Int,
    val hue: Float,
    val lengthMs: Int
) {
    /** Время конца последней ноты. */
    val endMs: Int
        get() {
            var m = 0
            for (n in notes) if (n.endMs > m) m = n.endMs
            return m
        }
}

/** Пользовательская карта (то, что делается в редакторе). */
class BeatMap(
    var id: String,
    var title: String,
    var artist: String,
    var bpm: Double,
    var offsetMs: Int,
    var keys: Int,
    var audio: String,
    var lengthMs: Int,
    var notes: MutableList<Note> = ArrayList()
) {
    fun hue(): Float = ((id.hashCode() and 0x7fffffff) % 360).toFloat()

    fun toPlayChart(): PlayChart {
        val sorted = notes.sortedWith(compareBy({ it.timeMs }, { it.lane }))
        val lvl = ChartStats.level(sorted, keys)
        var end = 0
        for (n in sorted) if (n.endMs > end) end = n.endMs
        return PlayChart(
            id = "map:$id", title = title, artist = artist, bpm = bpm, firstBeatMs = offsetMs,
            keys = keys, notes = sorted, audio = audio, diff = null, level = lvl, hue = hue(),
            lengthMs = maxOf(lengthMs, end + 1000)
        )
    }
}

/** Настройки игрока. */
class GameSettings {
    var musicVol = 80          // громкость музыки, %
    var sfxVol = 70            // громкость звуков попаданий, %
    var speed = 7              // скорость падения нот 1..10
    var offsetMs = 0           // сдвиг звука (калибровка), мс
    var vibration = true
    var bgDim = 45             // затемнение фона, %
    var hitLine = 80           // положение линии попадания, % высоты экрана
    var fieldWidth = 94        // ширина поля, % ширины экрана
    var errorBar = true        // шкала точности
    var earlyLate = true       // подписи EARLY / LATE
    var theme = 0              // цветовая схема нот
    var noFail = false         // без проигрыша
    var autoPlay = false       // автоигра (демо)

    /** За сколько мс до момента попадания нота появляется сверху экрана. */
    fun approachMs(): Double = 2300.0 - (speed - 1) * 190.0

    fun clampAll() {
        musicVol = musicVol.coerceIn(0, 100)
        sfxVol = sfxVol.coerceIn(0, 100)
        speed = speed.coerceIn(1, 10)
        offsetMs = offsetMs.coerceIn(-400, 400)
        bgDim = bgDim.coerceIn(0, 85)
        hitLine = hitLine.coerceIn(68, 90)
        fieldWidth = fieldWidth.coerceIn(60, 100)
        theme = theme.coerceIn(0, THEMES - 1)
    }

    fun serialize(): String {
        val sb = StringBuilder()
        fun put(k: String, v: Any) { sb.append(k).append('=').append(v).append(';') }
        put("music", musicVol); put("sfx", sfxVol); put("speed", speed); put("offset", offsetMs)
        put("vib", if (vibration) 1 else 0); put("dim", bgDim); put("line", hitLine)
        put("width", fieldWidth); put("bar", if (errorBar) 1 else 0)
        put("el", if (earlyLate) 1 else 0); put("theme", theme)
        put("nofail", if (noFail) 1 else 0); put("auto", if (autoPlay) 1 else 0)
        return sb.toString()
    }

    companion object {
        const val THEMES = 4

        fun parse(text: String?): GameSettings {
            val s = GameSettings()
            if (text.isNullOrBlank()) return s
            for (part in text.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i)
                val v = part.substring(i + 1).toIntOrNull() ?: continue
                when (k) {
                    "music" -> s.musicVol = v
                    "sfx" -> s.sfxVol = v
                    "speed" -> s.speed = v
                    "offset" -> s.offsetMs = v
                    "vib" -> s.vibration = v != 0
                    "dim" -> s.bgDim = v
                    "line" -> s.hitLine = v
                    "width" -> s.fieldWidth = v
                    "bar" -> s.errorBar = v != 0
                    "el" -> s.earlyLate = v != 0
                    "theme" -> s.theme = v
                    "nofail" -> s.noFail = v != 0
                    "auto" -> s.autoPlay = v != 0
                }
            }
            s.clampAll()
            return s
        }
    }
}

/** Лучший результат на карте. */
class Record(
    val score: Int,
    val rank: String,
    val accuracy: Double,
    val maxCombo: Int,
    val fullCombo: Boolean
)

/** Книга рекордов: id карты -> лучший результат. */
class RecordBook {
    val map = HashMap<String, Record>()

    fun get(id: String): Record? = map[id]

    /** Возвращает true, если это новый рекорд. */
    fun submit(id: String, r: Record): Boolean {
        val old = map[id]
        if (old == null || r.score > old.score) {
            map[id] = r
            return true
        }
        return false
    }

    fun clear() = map.clear()

    fun serialize(): String {
        val sb = StringBuilder()
        for ((k, r) in map) {
            sb.append(k).append('|').append(r.score).append('|').append(r.rank).append('|')
                .append(r.accuracy).append('|').append(r.maxCombo).append('|')
                .append(if (r.fullCombo) 1 else 0).append('\n')
        }
        return sb.toString()
    }

    companion object {
        fun parse(text: String?): RecordBook {
            val b = RecordBook()
            if (text.isNullOrBlank()) return b
            for (line in text.split('\n')) {
                val p = line.split('|')
                if (p.size < 6) continue
                val score = p[1].toIntOrNull() ?: continue
                val acc = p[3].toDoubleOrNull() ?: continue
                val combo = p[4].toIntOrNull() ?: continue
                b.map[p[0]] = Record(score, p[2], acc, combo, p[5] == "1")
            }
            return b
        }
    }
}

/** Простая статистика карты: уровень сложности (число, которое показываем игроку). */
object ChartStats {
    /** Число нот в секунду на «активном» участке карты. */
    fun nps(notes: List<Note>): Double {
        if (notes.size < 2) return 0.0
        var first = Int.MAX_VALUE
        var last = 0
        for (n in notes) {
            if (n.timeMs < first) first = n.timeMs
            if (n.endMs > last) last = n.endMs
        }
        val sec = (last - first) / 1000.0
        return if (sec <= 1.0) 0.0 else notes.size / sec
    }

    fun level(notes: List<Note>, keys: Int): Int {
        if (notes.isEmpty()) return 1
        val nps = nps(notes)
        var chords = 0
        var holds = 0
        var i = 0
        while (i < notes.size) {
            var j = i
            while (j + 1 < notes.size && notes[j + 1].timeMs == notes[i].timeMs) j++
            if (j > i) chords++
            i = j + 1
        }
        for (n in notes) if (n.isHold) holds++
        val chordRatio = chords.toDouble() / notes.size
        val holdRatio = holds.toDouble() / notes.size
        val v = nps * 1.15 + chordRatio * 5.0 + holdRatio * 1.5 + (keys - 4) * 0.5
        return Math.round(v).toInt().coerceIn(1, 20)
    }
}
