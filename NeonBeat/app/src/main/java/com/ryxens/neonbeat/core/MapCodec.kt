package com.ryxens.neonbeat.core

/**
 * Формат файла карты (простой текст, одна карта = один файл .nbm).
 * Его можно отправить другу в Telegram — достаточно импортировать файл в игре.
 *
 *   NEONBEAT-MAP 1
 *   title=...
 *   artist=...
 *   bpm=128.0
 *   offset=0
 *   keys=4
 *   audio=builtin:neon_horizon | file:song.mp3 | (пусто)
 *   length=60000
 *   notes:
 *   1234,0          — обычная нота: время(мс), дорожка
 *   1500,2,400      — длинная нота: время, дорожка, длительность
 */
object MapCodec {
    private const val HEADER = "NEONBEAT-MAP 1"

    fun encode(m: BeatMap): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        sb.append("id=").append(clean(m.id)).append('\n')
        sb.append("title=").append(clean(m.title)).append('\n')
        sb.append("artist=").append(clean(m.artist)).append('\n')
        sb.append("bpm=").append(m.bpm).append('\n')
        sb.append("offset=").append(m.offsetMs).append('\n')
        sb.append("keys=").append(m.keys).append('\n')
        sb.append("audio=").append(clean(m.audio)).append('\n')
        sb.append("length=").append(m.lengthMs).append('\n')
        sb.append("notes:\n")
        for (n in m.notes.sortedWith(compareBy({ it.timeMs }, { it.lane }))) {
            sb.append(n.timeMs).append(',').append(n.lane)
            if (n.isHold) sb.append(',').append(n.durMs)
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Возвращает null, если файл не похож на карту. Плохие строки пропускаются. */
    fun decode(text: String?): BeatMap? {
        if (text == null) return null
        val lines = text.replace("\r", "").split('\n')
        if (lines.isEmpty() || !lines[0].trim().startsWith("NEONBEAT-MAP")) return null
        val m = BeatMap(
            id = "", title = "Без названия", artist = "", bpm = 120.0, offsetMs = 0,
            keys = 4, audio = "", lengthMs = 0
        )
        var inNotes = false
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (!inNotes) {
                if (line == "notes:") { inNotes = true; continue }
                val e = line.indexOf('=')
                if (e <= 0) continue
                val k = line.substring(0, e)
                val v = line.substring(e + 1)
                when (k) {
                    "id" -> m.id = v
                    "title" -> m.title = v
                    "artist" -> m.artist = v
                    "bpm" -> m.bpm = (v.toDoubleOrNull() ?: 120.0).coerceIn(40.0, 300.0)
                    "offset" -> m.offsetMs = v.toIntOrNull() ?: 0
                    "keys" -> m.keys = (v.toIntOrNull() ?: 4).coerceIn(4, 7)
                    "audio" -> m.audio = v
                    "length" -> m.lengthMs = v.toIntOrNull() ?: 0
                }
            } else {
                val p = line.split(',')
                if (p.size < 2) continue
                val t = p[0].toIntOrNull() ?: continue
                val l = p[1].toIntOrNull() ?: continue
                val d = if (p.size >= 3) (p[2].toIntOrNull() ?: 0) else 0
                if (t < 0 || l < 0 || l >= m.keys) continue
                m.notes.add(Note(t, l, if (d >= 120) d else 0))
            }
        }
        if (m.id.isBlank()) m.id = "m" + java.lang.Long.toString(System.nanoTime(), 36)
        m.notes.sortWith(compareBy({ it.timeMs }, { it.lane }))
        if (m.lengthMs <= 0) {
            var end = 0
            for (n in m.notes) if (n.endMs > end) end = n.endMs
            m.lengthMs = end + 2000
        }
        return m
    }

    private fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ')
}
