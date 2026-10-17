package com.ryxens.neonbeat.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.MainActivity
import com.ryxens.neonbeat.core.BeatMap
import com.ryxens.neonbeat.core.MapCodec
import com.ryxens.neonbeat.core.Songs

/** Список пользовательских карт. */
class MapsScreen(act: MainActivity) : Screen(act) {
    override val view: View
    private val list: LinearLayout = act.vbox()

    init {
        val col = act.vbox()
        col.setPadding(dpi(16), dpi(34), dpi(16), dpi(24))
        val head = act.hbox()
        head.addv(act.ghostButton("‹ Назад", C.DIM) { act.back() }, WRAP, WRAP)
        head.addv(act.text("Редактор карт", 22f, C.TEXT, true), 0, WRAP, 1f, 14, 0, 0, 0)
        col.addv(head, MATCH, WRAP, 0f, 0, 0, 0, 12)

        val row = act.hbox()
        row.addv(act.button("+ Новая карта", C.PINK, 16f) { act.show(NewMapScreen(act)) }, 0, WRAP, 1f, 0, 0, 6, 0)
        row.addv(act.ghostButton("Импорт", C.CYAN, 16f) { importMenu() }, 0, WRAP, 1f, 6, 0, 0, 0)
        col.addv(row, MATCH, WRAP, 0f, 0, 0, 0, 14)
        col.addv(list)
        view = act.page(col)
        refresh()
    }

    override fun onResume() { refresh() }

    private fun refresh() {
        list.removeAllViews()
        val maps = Env.maps.list()
        if (maps.isEmpty()) {
            list.addv(act.text("Карт пока нет.\n\nНажми «Новая карта»: выбери встроенную песню или загрузи свою музыку из файла, " +
                "и расставь ноты. Карту можно отправить другу текстом или файлом.", 14f, C.DIM), MATCH, WRAP, 0f, 4, 8, 4, 0)
            return
        }
        for (m in maps) {
            val card = act.card()
            card.addv(act.text(m.title, 18f, C.TEXT, true))
            val audio = when {
                m.audio.startsWith("builtin:") -> "песня: " + (Songs.byId(m.audio.substring(8))?.title ?: "?")
                m.audio.startsWith("file:") -> "файл: " + m.audio.substring(5)
                else -> "без музыки"
            }
            card.addv(act.text("${m.artist.ifBlank { "—" }} · ${m.bpm.toInt()} BPM · ${m.keys} клав. · ${m.notes.size} нот\n$audio", 12f, C.DIM), MATCH, WRAP, 0f, 0, 2, 0, 10)
            val r = act.hbox()
            r.addv(act.button("▶ Играть", C.CYAN, 14f) { Launcher.play(act, m.toPlayChart()) }, 0, WRAP, 1f, 0, 0, 4, 0)
            r.addv(act.button("✎ Править", C.GOLD, 14f) { act.show(EditorScreen(act, m)) }, 0, WRAP, 1f, 4, 0, 4, 0)
            r.addv(act.ghostButton("⋮", C.DIM, 16f) { moreMenu(m) }, WRAP, WRAP, 0f, 4, 0, 0, 0)
            card.addv(r)
            list.addv(card, MATCH, WRAP, 0f, 0, 0, 0, 10)
        }
    }

    private fun moreMenu(m: BeatMap) {
        val items = arrayOf("Поделиться текстом", "Сохранить в файл", "Дублировать", "Удалить")
        AlertDialog.Builder(act).setTitle(m.title).setItems(items) { _, i ->
            when (i) {
                0 -> share(m)
                1 -> act.createFile(m.title.ifBlank { "map" } + ".nbm") { uri ->
                    if (uri == null) return@createFile
                    act.toast(if (Env.writeText(uri, MapCodec.encode(m))) "Карта сохранена" else "Не удалось сохранить")
                }
                2 -> {
                    val c = MapCodec.decode(MapCodec.encode(m))
                    if (c != null) {
                        c.id = "m" + java.lang.Long.toString(System.nanoTime(), 36)
                        c.title = m.title + " (копия)"
                        Env.maps.save(c)
                        refresh()
                    }
                }
                3 -> act.confirmDialog("Удалить карту?", "«${m.title}» будет удалена без возможности восстановления.", "Удалить") {
                    Env.maps.delete(m)
                    refresh()
                }
            }
        }.show()
    }

    private fun share(m: BeatMap) {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, MapCodec.encode(m))
            act.startActivity(Intent.createChooser(i, "Поделиться картой"))
        } catch (e: Throwable) {
            act.toast("Не удалось поделиться")
        }
    }

    private fun importMenu() {
        val items = arrayOf("Из буфера обмена (текст карты)", "Из файла")
        AlertDialog.Builder(act).setTitle("Импорт карты").setItems(items) { _, i ->
            if (i == 0) {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = try { cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() } catch (e: Throwable) { null }
                finishImport(MapCodec.decode(txt))
            } else {
                act.pickFile("*/*") { uri ->
                    if (uri != null) finishImport(MapCodec.decode(Env.readText(uri)))
                }
            }
        }.show()
    }

    private fun finishImport(m: BeatMap?) {
        if (m == null) {
            act.toast("Это не похоже на карту NeonBeat")
            return
        }
        m.id = "m" + java.lang.Long.toString(System.nanoTime(), 36)
        Env.maps.save(m)
        refresh()
        val missing = m.audio.startsWith("file:") && !java.io.File(Env.audioDir(), m.audio.substring(5)).exists()
        act.toast(if (missing) "Карта добавлена. Музыку выбери в редакторе (Параметры карты)" else "Карта добавлена")
    }
}

/** Создание новой карты. */
class NewMapScreen(act: MainActivity) : Screen(act) {
    override val view: View
    private var audioMode = 1          // 0 — без музыки, 1 — встроенная, 2 — свой файл
    private var songIdx = 0
    private var fileName = ""
    private var keys = 4

    init {
        val col = act.vbox()
        col.setPadding(dpi(16), dpi(34), dpi(16), dpi(24))
        val head = act.hbox()
        head.addv(act.ghostButton("‹ Назад", C.DIM) { act.back() }, WRAP, WRAP)
        head.addv(act.text("Новая карта", 22f, C.TEXT, true), 0, WRAP, 1f, 14, 0, 0, 0)
        col.addv(head, MATCH, WRAP, 0f, 0, 0, 0, 12)

        val title = act.inputField("Название", "Моя карта")
        val artist = act.inputField("Исполнитель", "")
        val bpm = act.inputField("BPM", "120", true)
        val minutes = act.inputField("Длина, минут", "2", true)

        col.addv(act.text("Название", 13f, C.DIM), MATCH, WRAP, 0f, 2, 0, 0, 4)
        col.addv(title, MATCH, WRAP, 0f, 0, 0, 0, 10)
        col.addv(act.text("Исполнитель (необязательно)", 13f, C.DIM), MATCH, WRAP, 0f, 2, 0, 0, 4)
        col.addv(artist, MATCH, WRAP, 0f, 0, 0, 0, 10)

        col.addv(act.text("Количество клавиш", 13f, C.DIM), MATCH, WRAP, 0f, 2, 0, 0, 4)
        col.addv(act.segmented(listOf("4", "5", "6", "7"), 0) { keys = it + 4 }, MATCH, WRAP, 0f, 0, 0, 0, 12)

        col.addv(act.text("Музыка", 13f, C.DIM), MATCH, WRAP, 0f, 2, 0, 0, 4)
        val info: TextView = act.text("", 13f, C.CYAN)
        val pickBtn = act.ghostButton("Выбрать…", C.CYAN) { }
        val bpmBox = act.vbox()
        val minBox = act.vbox()
        fun refreshAudioUi() {
            when (audioMode) {
                0 -> {
                    pickBtn.visibility = View.GONE
                    info.text = "Будет тихий метроном-таймер. Подойдёт, чтобы расставить ноты вручную."
                    bpmBox.visibility = View.VISIBLE
                    minBox.visibility = View.VISIBLE
                }
                1 -> {
                    pickBtn.visibility = View.VISIBLE
                    pickBtn.text = "Песня: " + Songs.all[songIdx].title + "  ▾"
                    info.text = "BPM подставится сам, музыка встроена в игру."
                    bpm.setText(Songs.all[songIdx].bpm.toString())
                    bpmBox.visibility = View.VISIBLE
                    minBox.visibility = View.GONE
                }
                else -> {
                    pickBtn.visibility = View.VISIBLE
                    pickBtn.text = if (fileName.isEmpty()) "Выбрать аудиофайл…" else "Файл: $fileName"
                    info.text = "mp3, ogg, m4a, wav, flac. Узнай BPM песни и введи ниже (потом можно поправить в редакторе)."
                    bpmBox.visibility = View.VISIBLE
                    minBox.visibility = View.GONE
                }
            }
        }
        col.addv(act.segmented(listOf("Без музыки", "Встроенная", "Свой файл"), 1) {
            audioMode = it
            refreshAudioUi()
        }, MATCH, WRAP, 0f, 0, 0, 0, 8)
        col.addv(pickBtn, MATCH, WRAP, 0f, 0, 0, 0, 6)
        col.addv(info, MATCH, WRAP, 0f, 2, 0, 2, 10)

        pickBtn.setOnClickListener {
            if (audioMode == 1) {
                val names = Array(Songs.all.size) { Songs.all[it].title + " (" + Songs.all[it].bpm.toInt() + " BPM)" }
                AlertDialog.Builder(act).setTitle("Встроенная песня").setItems(names) { _, i ->
                    songIdx = i
                    refreshAudioUi()
                }.show()
            } else if (audioMode == 2) {
                act.pickFile("audio/*") { uri ->
                    if (uri != null) {
                        val dlg = act.loading("Копирую файл…")
                        act.background({ Env.importAudio(uri) }, { name ->
                            dlg.dismiss()
                            if (name == null) act.toast("Не удалось прочитать файл") else {
                                fileName = name
                                refreshAudioUi()
                            }
                        })
                    }
                }
            }
        }

        bpmBox.addv(act.text("BPM (ударов в минуту)", 13f, C.DIM), MATCH, WRAP, 0f, 2, 0, 0, 4)
        bpmBox.addv(bpm, MATCH, WRAP, 0f, 0, 0, 0, 10)
        col.addv(bpmBox)
        minBox.addv(act.text("Длина карты, минут", 13f, C.DIM), MATCH, WRAP, 0f, 2, 0, 0, 4)
        minBox.addv(minutes, MATCH, WRAP, 0f, 0, 0, 0, 10)
        col.addv(minBox)

        col.addv(act.button("Создать и открыть редактор", C.PINK, 17f) {
            val b = (bpm.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0)
            if (b < 40.0 || b > 300.0) {
                act.toast("BPM должен быть от 40 до 300")
            } else if (audioMode == 2 && fileName.isEmpty()) {
                act.toast("Выбери аудиофайл")
            } else {
                val m = BeatMap(
                    id = "m" + java.lang.Long.toString(System.nanoTime(), 36),
                    title = title.text.toString().trim().ifEmpty { "Без названия" },
                    artist = artist.text.toString().trim(),
                    bpm = b, offsetMs = 0, keys = keys,
                    audio = when (audioMode) {
                        1 -> "builtin:" + Songs.all[songIdx].id
                        2 -> "file:$fileName"
                        else -> ""
                    },
                    lengthMs = when (audioMode) {
                        1 -> Songs.composition(Songs.all[songIdx]).lengthMs
                        2 -> 0
                        else -> ((minutes.text.toString().replace(',', '.').toDoubleOrNull() ?: 2.0).coerceIn(0.2, 12.0) * 60000).toInt()
                    }
                )
                Env.maps.save(m)
                act.replace(EditorScreen(act, m))
            }
        }, MATCH, WRAP, 0f, 0, 6, 0, 0)
        refreshAudioUi()
        view = act.page(col)
    }
}
