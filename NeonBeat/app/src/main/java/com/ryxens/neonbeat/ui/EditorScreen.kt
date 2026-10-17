package com.ryxens.neonbeat.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.MainActivity
import com.ryxens.neonbeat.audio.AudioLoader
import com.ryxens.neonbeat.audio.Pcm
import com.ryxens.neonbeat.core.AutoMap
import com.ryxens.neonbeat.core.BeatMap
import com.ryxens.neonbeat.core.ChartGen
import com.ryxens.neonbeat.core.Difficulty
import com.ryxens.neonbeat.core.MapCodec
import com.ryxens.neonbeat.core.Note
import com.ryxens.neonbeat.core.OnsetDetector
import com.ryxens.neonbeat.core.Songs

/** Экран редактора: готовит музыку и показывает EditorView. */
class EditorScreen(act: MainActivity, val map: BeatMap) : Screen(act) {
    private val frame = FrameLayout(act)
    override val view: View get() = frame
    private var ev: EditorView? = null
    private var pcm: Pcm? = null
    private val taps = ArrayList<Long>()

    init {
        frame.setBackgroundColor(C.BG0)
        val dlg = act.loading(if (map.audio.isEmpty()) "Открываю редактор…" else "Готовим музыку…")
        act.background({
            try { AudioLoader.load(map.audio) } catch (e: Throwable) { null }
        }, { p ->
            dlg.dismiss()
            pcm = p
            if (p == null && map.audio.isNotEmpty()) {
                act.toast("Музыка не найдена. Выбери файл в меню → Параметры карты")
            }
            startEditor()
        })
    }

    private fun startEditor() {
        ev?.release()
        val v = EditorView(act, this, map, pcm)
        ev = v
        frame.removeAllViews()
        frame.addView(v, FrameLayout.LayoutParams(MainActivity.MATCH_PARENT, MainActivity.MATCH_PARENT))
    }

    private fun save() {
        val v = ev
        if (v != null && map.lengthMs <= 0) map.lengthMs = 1000
        Env.maps.save(map)
    }

    fun exit() {
        ev?.stopPlay()
        save()
        act.back()
    }

    override fun onBack(): Boolean {
        val v = ev
        if (v != null && v.transport.playing) v.stopPlay() else exit()
        return true
    }

    override fun onPause() {
        ev?.stopPlay()
        if (ev != null) save()
    }

    override fun onDestroy() {
        ev?.release()
        ev = null
    }

    fun testPlay() {
        val v = ev ?: return
        v.stopPlay()
        if (map.notes.isEmpty()) {
            act.toast("Сначала поставь хотя бы одну ноту")
            return
        }
        save()
        Launcher.play(act, map.toPlayChart())
    }

    // ---------------------------------------------------------------- меню

    fun showMenu() {
        val v = ev ?: return
        v.stopPlay()
        fun onOff(b: Boolean) = if (b) "ВКЛ" else "ВЫКЛ"
        val items = arrayOf(
            "Параметры карты (название, BPM, сдвиг)",
            "Автозаполнение нотами",
            "Квантизация записи: " + onOff(v.recQuantize),
            "Метроном: " + onOff(v.metroOn),
            "Звук нот при воспроизведении: " + onOff(v.hitsOn),
            "Скорость воспроизведения: " + (v.speed * 100).toInt() + "%",
            "Очистить все ноты",
            "Поделиться картой (текст)",
            "Как пользоваться редактором",
            "Сохранить и выйти"
        )
        AlertDialog.Builder(act).setTitle(map.title).setItems(items) { _, i ->
            when (i) {
                0 -> showParams()
                1 -> autoFill()
                2 -> v.recQuantize = !v.recQuantize
                3 -> v.metroOn = !v.metroOn
                4 -> v.hitsOn = !v.hitsOn
                5 -> v.setSpeed(if (v.speed >= 1f) 0.5f else if (v.speed >= 0.75f) 1f else 0.75f)
                6 -> act.confirmDialog("Очистить карту?", "Все ноты будут удалены (можно отменить кнопкой ↶).", "Очистить") { v.clearAll() }
                7 -> shareMap()
                8 -> showHelp()
                9 -> exit()
            }
        }.show()
    }

    private fun shareMap() {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, MapCodec.encode(map))
            act.startActivity(Intent.createChooser(i, "Поделиться картой"))
        } catch (e: Throwable) {
            act.toast("Не удалось поделиться")
        }
    }

    private fun showHelp() {
        val msg = "• Тап по дорожке — поставить ноту. Тап по ноте — убрать.\n\n" +
            "• Долгое нажатие и протяжка вверх — длинная нота (холд).\n\n" +
            "• Проведи пальцем вверх/вниз — прокрутка. Щипок двумя пальцами — масштаб. Полоса справа — быстрая перемотка.\n\n" +
            "• ▶ — слушать с текущего места. ● REC — включи, нажми ▶ и тапай по дорожкам в такт музыке: ноты запишутся сами. " +
            "Если записал неудачно — кнопка ↶ отменит весь дубль.\n\n" +
            "• Кнопка «сетка» меняет шаг привязки (1/4, 1/8, 1/16…). Чтобы ноты ложились ровно, подбери BPM и сдвиг в «Параметры карты».\n\n" +
            "• «Автозаполнение» расставит ноты по музыке за тебя — потом можно поправить руками.\n\n" +
            "• ▶ Тест — сыграть карту прямо сейчас."
        AlertDialog.Builder(act).setTitle("Редактор").setMessage(msg).setPositiveButton("Понятно", null).show()
    }

    // ---------------------------------------------------------------- параметры

    private fun showParams() {
        val col = act.vbox()
        col.setPadding(dpi(20), dpi(10), dpi(20), dpi(6))
        val title = act.inputField("Название", map.title)
        val artist = act.inputField("Исполнитель", map.artist)
        val bpm = act.inputField("BPM", map.bpm.toString(), true)
        val off = act.inputField("Сдвиг сетки, мс", map.offsetMs.toString(), true)
        col.addv(act.text("Название", 12f, C.DIM), MATCH, WRAP, 0f, 0, 0, 0, 3)
        col.addv(title, MATCH, WRAP, 0f, 0, 0, 0, 8)
        col.addv(act.text("Исполнитель", 12f, C.DIM), MATCH, WRAP, 0f, 0, 0, 0, 3)
        col.addv(artist, MATCH, WRAP, 0f, 0, 0, 0, 8)
        col.addv(act.text("BPM", 12f, C.DIM), MATCH, WRAP, 0f, 0, 0, 0, 3)
        col.addv(bpm, MATCH, WRAP, 0f, 0, 0, 0, 6)
        col.addv(act.ghostButton("Тап-темп: нажимай в такт музыке", C.CYAN, 13f) {
            val now = SystemClock.uptimeMillis()
            if (taps.isNotEmpty() && now - taps[taps.size - 1] > 2000) taps.clear()
            taps.add(now)
            if (taps.size >= 3) {
                val last = taps.takeLast(9)
                val avg = (last[last.size - 1] - last[0]).toDouble() / (last.size - 1)
                val b = Math.round(60000.0 / avg * 10) / 10.0
                if (b in 40.0..300.0) bpm.setText(b.toString())
            }
        }, MATCH, WRAP, 0f, 0, 0, 0, 8)
        col.addv(act.text("Сдвиг сетки, мс (где начинается первая доля)", 12f, C.DIM), MATCH, WRAP, 0f, 0, 0, 0, 3)
        col.addv(off, MATCH, WRAP, 0f, 0, 0, 0, 8)
        if (!map.audio.startsWith("builtin:")) {
            col.addv(act.ghostButton("Выбрать другой аудиофайл…", C.GOLD, 13f) {
                act.pickFile("audio/*") { uri ->
                    if (uri != null) {
                        val dlg = act.loading("Копирую файл…")
                        act.background({
                            val name = Env.importAudio(uri)
                            if (name != null) {
                                AudioLoader.forget()
                                map.audio = "file:$name"
                                map.lengthMs = 0
                                val p = try { AudioLoader.load(map.audio) } catch (e: Throwable) { null }
                                Pair(name, p)
                            } else null
                        }, { r ->
                            dlg.dismiss()
                            if (r == null) act.toast("Не удалось прочитать файл") else {
                                pcm = r.second
                                save()
                                startEditor()
                                act.toast("Музыка заменена: ${r.first}")
                            }
                        })
                    }
                }
            }, MATCH, WRAP)
        }
        val sv = ScrollView(act)
        sv.addView(col)
        AlertDialog.Builder(act).setTitle("Параметры карты").setView(sv)
            .setPositiveButton("Применить") { _, _ ->
                map.title = title.text.toString().trim().ifEmpty { "Без названия" }
                map.artist = artist.text.toString().trim()
                val b = bpm.text.toString().replace(',', '.').toDoubleOrNull()
                if (b != null && b in 40.0..300.0) map.bpm = b else act.toast("BPM должен быть от 40 до 300")
                map.offsetMs = off.text.toString().replace(',', '.').toDoubleOrNull()?.toInt() ?: map.offsetMs
                save()
                ev?.markDirty()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- автозаполнение

    private fun autoFill() {
        val v = ev ?: return
        if (map.audio.isEmpty()) {
            act.toast("Для автозаполнения нужна музыка")
            return
        }
        if (!map.audio.startsWith("builtin:") && pcm == null) {
            act.toast("Музыка не загружена")
            return
        }
        val names = arrayOf("1 · Редко", "2 · Немного", "3 · Средне", "4 · Много", "5 · Очень много")
        AlertDialog.Builder(act).setTitle("Плотность нот").setItems(names) { _, i ->
            val dlg = act.loading("Анализирую музыку…")
            val step = v.snapStepMs()
            act.background({ generate(i + 1, step) }, { notes ->
                dlg.dismiss()
                if (notes == null || notes.isEmpty()) act.toast("Не получилось найти ритм")
                else {
                    v.replaceNotes(notes)
                    act.toast("Расставлено нот: ${notes.size}. Можно править руками")
                }
            })
        }.show()
    }

    private fun generate(density: Int, stepMs: Double): List<Note>? {
        val spec = map.audio
        if (spec.startsWith("builtin:")) {
            val def = Songs.byId(spec.substring(8)) ?: return null
            val diff = Difficulty.values()[density - 1]
            return ChartGen.generate(def, Songs.composition(def), diff, map.keys).notes
        }
        val p = pcm ?: return null
        val on = OnsetDetector.detect(p.data, p.rate, 1.0)
        return AutoMap.generate(on, map.keys, density, stepMs, map.offsetMs, System.nanoTime())
    }
}
