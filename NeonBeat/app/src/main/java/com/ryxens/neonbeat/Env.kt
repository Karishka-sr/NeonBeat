package com.ryxens.neonbeat

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.ryxens.neonbeat.audio.Sfx
import com.ryxens.neonbeat.core.BeatMap
import com.ryxens.neonbeat.core.GameSettings
import com.ryxens.neonbeat.core.MapCodec
import com.ryxens.neonbeat.core.RecordBook
import java.io.File

/** Общее состояние приложения: настройки, рекорды, карты, звуки. */
object Env {
    lateinit var ctx: Context
        private set
    lateinit var settings: GameSettings
    lateinit var records: RecordBook
    lateinit var maps: MapStore
    lateinit var sfx: Sfx
    var density = 1f
        private set
    private lateinit var prefs: SharedPreferences
    private var ready = false

    fun init(c: Context) {
        if (ready) return
        ctx = c.applicationContext
        density = ctx.resources.displayMetrics.density
        prefs = ctx.getSharedPreferences("neonbeat", Context.MODE_PRIVATE)
        settings = GameSettings.parse(prefs.getString("settings", null))
        records = RecordBook.parse(prefs.getString("records", null))
        maps = MapStore(File(ctx.filesDir, "maps"))
        sfx = Sfx(ctx)
        ready = true
    }

    fun saveSettings() { prefs.edit().putString("settings", settings.serialize()).apply() }
    fun saveRecords() { prefs.edit().putString("records", records.serialize()).apply() }

    fun audioDir(): File {
        val d = File(ctx.filesDir, "audio")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            val c = ctx.contentResolver.query(uri, null, null, null, null)
            if (c != null) {
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i)
                }
                c.close()
            }
        } catch (e: Throwable) { }
        return name ?: (uri.lastPathSegment ?: "audio")
    }

    /** Копирует выбранный аудиофайл в память игры. Возвращает имя сохранённого файла. */
    fun importAudio(uri: Uri): String? {
        return try {
            var name = displayName(uri).replace(Regex("[^A-Za-z0-9А-Яа-яЁё._ -]"), "_")
            if (name.isBlank()) name = "audio"
            val dest = File(audioDir(), name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            name
        } catch (e: Throwable) { null }
    }

    fun readText(uri: Uri): String? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Throwable) { null }
    }

    fun writeText(uri: Uri, text: String): Boolean {
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Throwable) { false }
    }
}

/** Хранилище пользовательских карт: по файлу .nbm на карту. */
class MapStore(private val dir: File) {
    init { if (!dir.exists()) dir.mkdirs() }

    fun list(): List<BeatMap> {
        val out = ArrayList<BeatMap>()
        val files = dir.listFiles() ?: return out
        for (f in files) {
            if (!f.name.endsWith(".nbm")) continue
            try {
                val m = MapCodec.decode(f.readText(Charsets.UTF_8))
                if (m != null) out.add(m)
            } catch (e: Throwable) { }
        }
        out.sortBy { it.title.lowercase() }
        return out
    }

    fun save(m: BeatMap) {
        try {
            File(dir, safe(m.id) + ".nbm").writeText(MapCodec.encode(m), Charsets.UTF_8)
        } catch (e: Throwable) { }
    }

    fun delete(m: BeatMap) {
        try { File(dir, safe(m.id) + ".nbm").delete() } catch (e: Throwable) { }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
