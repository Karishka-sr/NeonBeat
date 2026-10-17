package com.ryxens.neonbeat

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.ryxens.neonbeat.ui.C
import com.ryxens.neonbeat.ui.MenuScreen
import com.ryxens.neonbeat.ui.Screen
import com.ryxens.neonbeat.ui.dpi
import com.ryxens.neonbeat.ui.roundBg
import com.ryxens.neonbeat.ui.text

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private val stack = ArrayList<Screen>()
    private var pending: ((Uri?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        root = FrameLayout(this)
        root.setBackgroundColor(C.BG0)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        applyImmersive()
        show(MenuScreen(this))
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val c = window.insetsController
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ---------- навигация между экранами ----------

    fun current(): Screen? = if (stack.isEmpty()) null else stack[stack.size - 1]

    private fun attach(s: Screen) {
        root.removeAllViews()
        root.addView(s.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        s.onResume()
    }

    fun show(s: Screen) {
        current()?.onPause()
        stack.add(s)
        attach(s)
    }

    /** Заменяет текущий экран новым (старый закрывается). */
    fun replace(s: Screen) {
        val old = current()
        if (old != null) {
            old.onPause()
            old.onDestroy()
            stack.removeAt(stack.size - 1)
        }
        stack.add(s)
        attach(s)
    }

    fun back() {
        val old = current() ?: return
        if (stack.size <= 1) {
            finish()
            return
        }
        old.onPause()
        old.onDestroy()
        stack.removeAt(stack.size - 1)
        attach(stack[stack.size - 1])
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = current()
        if (s == null || !s.onBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        current()?.onPause()
    }

    override fun onResume() {
        super.onResume()
        current()?.onResume()
    }

    override fun onDestroy() {
        for (s in stack) s.onDestroy()
        stack.clear()
        super.onDestroy()
    }

    // ---------- выбор файлов ----------

    fun pickFile(mime: String, cb: (Uri?) -> Unit) {
        pending = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 11)
        } catch (e: Throwable) {
            pending = null
            toast("Не удалось открыть выбор файла")
        }
    }

    fun createFile(name: String, cb: (Uri?) -> Unit) {
        pending = cb
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TITLE, name)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 12)
        } catch (e: Throwable) {
            pending = null
            toast("Не удалось создать файл")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val cb = pending
        pending = null
        cb?.invoke(if (resultCode == RESULT_OK) data?.data else null)
    }

    // ---------- мелочи ----------

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Окошко «Загрузка…» на время тяжёлой работы. */
    fun loading(msg: String): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val t = text(msg, 17f, C.TEXT, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dpi(32), dpi(24), dpi(32), dpi(24))
        t.background = roundBg(C.CARD, 18f, C.CYAN, 1f)
        d.setContentView(t)
        d.setCancelable(false)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
        return d
    }

    /** Выполнить в фоне, затем результат — в главном потоке. */
    fun <T> background(work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = work()
            runOnUiThread { if (!isFinishing) done(r) }
        }.start()
    }

    companion object {
        const val MATCH_PARENT = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
