package io.github.frei0xff.readestwebview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://web.readest.com/"
        private const val KEYBOARD_THRESHOLD = 0.15 // 15% of screen height
    }

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private val handler = Handler(Looper.getMainLooper())
    private var layoutCheckRunnable: Runnable? = null

    // Selection action delegate (suppresses copy/select-all bar)
    inner class NoOpSelectionDelegate : BasicSelectionActionDelegate(this@MainActivity) {
        override fun isActionAvailable(action: String): Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder().build()
        )

        session = GeckoSession()
        session.open(runtime)

        // 1. Suppress the native selection action bar
        session.selectionActionDelegate = NoOpSelectionDelegate()

        // 2. Handle <select> dropdowns
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val choices = prompt.choices
                val items = choices.map { it.label }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setItems(items) { _, which ->
                        result.complete(prompt.confirm(choices[which].id))
                    }
                    .setOnCancelListener {
                        if (prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                            result.complete(prompt.confirm(emptyArray<String>()))
                        } else {
                            result.complete(prompt.confirm(""))
                        }
                    }
                    .show()

                return result
            }
        }

        geckoView = GeckoView(this)
        geckoView.setSession(session)
        setContentView(geckoView)

        session.loadUri(HOME_URL)

        // --- FIX: Detect keyboard hide and restore fullscreen ---
        val rootView = window.decorView.rootView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            // Remove any pending delayed check
            layoutCheckRunnable?.let { handler.removeCallbacks(it) }

            // Create a new runnable that checks after a short delay
            val runnable = Runnable {
                val rect = Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.height
                val keypadHeight = screenHeight - rect.bottom

                // If the keyboard is hidden (height < threshold), restore fullscreen
                if (keypadHeight < screenHeight * KEYBOARD_THRESHOLD) {
                    hideSystemUi()
                }
            }
            layoutCheckRunnable = runnable
            // Post with a 100ms delay to allow layout to settle
            handler.postDelayed(runnable, 100)
        }
        // --------------------------------------------------------

        hideSystemUi() // initial call
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
