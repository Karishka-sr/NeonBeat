package com.ryxens.neonbeat.ui

import android.view.View
import com.ryxens.neonbeat.MainActivity
import com.ryxens.neonbeat.audio.AudioLoader
import com.ryxens.neonbeat.audio.Pcm
import com.ryxens.neonbeat.Env
import com.ryxens.neonbeat.core.GameEngine
import com.ryxens.neonbeat.core.PlayChart
import com.ryxens.neonbeat.core.Record

/** Экран игры: оболочка вокруг GameView. */
class GameScreen(act: MainActivity, val chart: PlayChart, private val pcm: Pcm?) : Screen(act) {
    private val gv = GameView(act, this, chart, pcm)
    override val view: View get() = gv

    override fun onBack(): Boolean {
        gv.onBackKey()
        return true
    }

    override fun onPause() { gv.pause() }

    override fun onDestroy() { gv.destroy() }

    fun quit() { act.back() }

    fun restart() { act.replace(GameScreen(act, chart, pcm)) }

    fun finished(engine: GameEngine) {
        val st = Env.settings
        var newRecord = false
        if (!st.autoPlay && !st.noFail && !engine.failed) {
            val r = Record(engine.score(), engine.rank(), engine.finalAccuracy(), engine.maxCombo, engine.fullCombo)
            newRecord = Env.records.submit(chart.id, r)
            if (newRecord) Env.saveRecords()
        }
        act.replace(ResultsScreen(act, chart, pcm, engine, newRecord))
    }
}

/** Запуск карты: сначала готовим музыку в фоне, потом открываем игру. */
object Launcher {
    fun play(act: MainActivity, chart: PlayChart, replace: Boolean = false) {
        val dlg = act.loading(if (chart.audio.isEmpty()) "Подготовка…" else "Готовим музыку…")
        act.background({
            try { AudioLoader.load(chart.audio) } catch (e: Throwable) { null }
        }, { pcm ->
            dlg.dismiss()
            if (pcm == null && chart.audio.isNotEmpty()) {
                act.toast("Музыка не найдена — играем без звука")
            }
            val s = GameScreen(act, chart, pcm)
            if (replace) act.replace(s) else act.show(s)
        })
    }
}
