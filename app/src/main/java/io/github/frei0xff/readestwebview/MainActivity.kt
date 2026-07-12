package io.github.frei0xff.readestwebview

import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://web.readest.com/"
        private const val KEYBOARD_THRESHOLD = 0.15 // 15% of screen height
        private const val BRIGHTNESS_STEP = 0.01f   // 1% per key press

        // SharedPreferences keys
        private const val PREFS_NAME = "readest_prefs"
        private const val BRIGHTNESS_KEY = "brightness"
    }

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private val handler = Handler(Looper.getMainLooper())
    private var layoutCheckRunnable: Runnable? = null

    // ---------- Brightness control ----------
    private var currentBrightness = 1.0f          // Will be initialized
    private var isInForeground = false
    private var brightnessToast: Toast? = null

    // SharedPreferences instance
    private lateinit var prefs: SharedPreferences

    // Selection action delegate (suppresses copy/select-all bar)
    inner class NoOpSelectionDelegate : BasicSelectionActionDelegate(this@MainActivity) {
        override fun isActionAvailable(action: String): Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize SharedPreferences
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

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

        // --- Initialize brightness (respecting stored value if any) ---
        initBrightness()

        // --- FIX: Detect keyboard hide and restore fullscreen ---
        val rootView = window.decorView.rootView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            layoutCheckRunnable?.let { handler.removeCallbacks(it) }

            val runnable = Runnable {
                val rect = Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.height
                val keypadHeight = screenHeight - rect.bottom

                if (keypadHeight < screenHeight * KEYBOARD_THRESHOLD) {
                    hideSystemUi()
                }
            }
            layoutCheckRunnable = runnable
            handler.postDelayed(runnable, 100)
        }

        hideSystemUi() // initial call
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    // ---------- Intercept volume keys for brightness control ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isInForeground) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    adjustBrightness(+BRIGHTNESS_STEP)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    adjustBrightness(-BRIGHTNESS_STEP)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Initialize brightness:
     * 1. If a stored value exists, use that.
     * 2. Else if window has a custom brightness (≥0), use that.
     * 3. Else fall back to system brightness (0‑255 → 0.0‑1.0).
     * 4. Apply the chosen value to the window.
     */
    private fun initBrightness() {
        // Step 1: Check for stored brightness
        val stored = prefs.getFloat(BRIGHTNESS_KEY, -1f)
        if (stored >= 0f) {
            currentBrightness = stored.coerceIn(0f, 1f)
        } else {
            // Step 2: Check window override
            val lp = window.attributes
            currentBrightness = if (lp.screenBrightness >= 0f) {
                lp.screenBrightness
            } else {
                // Step 3: Use system brightness
                try {
                    Settings.System.getInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS
                    ) / 255f
                } catch (_: Exception) {
                    1f // fallback to max
                }
            }
        }

        // Apply to the window
        val lp = window.attributes
        lp.screenBrightness = currentBrightness
        window.attributes = lp
    }

    private fun adjustBrightness(delta: Float) {
        var newBrightness = currentBrightness + delta
        if (newBrightness < 0.0f) newBrightness = 0.0f
        if (newBrightness > 1.0f) newBrightness = 1.0f

        if (newBrightness != currentBrightness) {
            currentBrightness = newBrightness

            // Apply to window
            val lp = window.attributes
            lp.screenBrightness = currentBrightness
            window.attributes = lp

            // Persist the value
            prefs.edit().putFloat(BRIGHTNESS_KEY, currentBrightness).apply()

            // Show Toast with percentage
            val percent = Math.round(currentBrightness * 100)
            showBrightnessToast(percent)
        }
    }

    private fun showBrightnessToast(percent: Int) {
        brightnessToast?.cancel()
        brightnessToast = Toast.makeText(
            this,
            "$percent%",
            Toast.LENGTH_SHORT
        )
        brightnessToast?.show()
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
