package com.ryxens.neonbeat.ui

import android.view.View
import com.ryxens.neonbeat.MainActivity

/** Один экран приложения. */
abstract class Screen(val act: MainActivity) {
    abstract val view: View

    /** Кнопка «назад». Вернуть true, если экран сам всё обработал. */
    open fun onBack(): Boolean {
        act.back()
        return true
    }

    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {}
}
