package io.github.frei0xff.readestwebview

import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val HOME_URL = "https://web.readest.com/"
        private const val DEEPSEEK_URL = "https://chat.deepseek.com/"
        private const val KEYBOARD_THRESHOLD = 0.15
        private const val BRIGHTNESS_STEP = 0.01f

        private const val PREFS_NAME = "readest_prefs"
        private const val BRIGHTNESS_KEY = "brightness"
    }

    // ---------- Core components ----------
    private lateinit var runtime: GeckoRuntime
    private lateinit var geckoView: GeckoView
    private val handler = Handler(Looper.getMainLooper())
    private var layoutCheckRunnable: Runnable? = null

    // ---------- Tab management ----------
    private val sessions = mutableListOf<GeckoSession>()
    private val isHomeTab = mutableListOf<Boolean>()
    private var currentIndex = 0
    private var pendingSwitchIndex = -1

    // ---------- Brightness control ----------
    private var currentBrightness = 1.0f
    private var isInForeground = false
    private var brightnessToast: Toast? = null
    private lateinit var prefs: SharedPreferences

    // ---------- Gesture detection ----------
    private lateinit var gestureDetector: GestureDetector

    // ---------- Delegates ----------
    inner class NoOpSelectionDelegate : BasicSelectionActionDelegate(this@MainActivity) {
        override fun isActionAvailable(action: String): Boolean = false
    }

    private val promptDelegate = object : GeckoSession.PromptDelegate {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "onCreate started")

            window.setBackgroundDrawableResource(android.R.color.black)

            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            runtime = GeckoRuntime.create(
                this,
                GeckoRuntimeSettings.Builder().build()
            )

            // Create the initial home tab
            val initialSession = GeckoSession()
            initialSession.open(runtime)
            setupSessionDelegates(initialSession)
            sessions.add(initialSession)
            isHomeTab.add(true)
            currentIndex = 0

            geckoView = GeckoView(this)
            geckoView.setSession(initialSession)
            geckoView.setBackgroundColor(android.graphics.Color.BLACK)
            setContentView(geckoView)

            setupGestureDetection()

            initialSession.loadUri(HOME_URL)

            initBrightness()

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

            hideSystemUi()
            Log.d(TAG, "onCreate completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in onCreate", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            throw e
        }
    }

    private fun setupGestureDetection() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                val minSwipeDistance = 80f
                val minVelocity = 80f

                val edgeThreshold = 50f
                val screenWidth = geckoView.width.toFloat()
                val screenHeight = geckoView.height.toFloat()

                // Downward swipe from top edge -> open DeepSeek in new tab
                if (diffY > minSwipeDistance && Math.abs(velocityY) > minVelocity && e1.y < edgeThreshold) {
                    createDeepSeekTab()
                    return true
                }

                // Horizontal swipe from edges -> switch tabs
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > minSwipeDistance && Math.abs(velocityX) > minVelocity) {
                    if (e1.x < edgeThreshold) {
                        switchToNextTab()
                        return true
                    }
                    if (e1.x > screenWidth - edgeThreshold) {
                        switchToPreviousTab()
                        return true
                    }
                }

                // Upward swipe from bottom edge -> close current tab
                if (diffY < -minSwipeDistance && Math.abs(velocityY) > minVelocity && e1.y > screenHeight - edgeThreshold) {
                    closeCurrentTab()
                    return true
                }

                return false
            }
        })

        geckoView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    // ---------- Setup delegates for a session ----------
    private fun setupSessionDelegates(session: GeckoSession) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                return createNewTab(uri)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                return null
            }

            // Correct signature for GeckoView 143
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                isRedirect: Boolean
            ) {
                Log.d(TAG, "onLocationChange: $url")
                // If we have a pending switch for this session, execute it now
                val index = sessions.indexOf(session)
                if (pendingSwitchIndex == index && index >= 0) {
                    pendingSwitchIndex = -1
                    handler.post {
                        switchToTab(index)
                        Toast.makeText(this@MainActivity, "New tab loaded", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        session.selectionActionDelegate = NoOpSelectionDelegate()
        session.promptDelegate = promptDelegate
    }

    // ---------- Tab management ----------
    private fun createNewTab(url: String): GeckoResult<GeckoSession> {
        val newSession = GeckoSession()
        newSession.open(runtime)
        setupSessionDelegates(newSession)
        sessions.add(newSession)
        isHomeTab.add(false)
        val newIndex = sessions.size - 1

        // Set pending switch to this index; onLocationChange will trigger the switch.
        pendingSwitchIndex = newIndex

        // Fallback: if onLocationChange doesn't fire within 500ms, switch anyway.
        handler.postDelayed({
            if (pendingSwitchIndex == newIndex) {
                pendingSwitchIndex = -1
                switchToTab(newIndex)
                Toast.makeText(this, "New tab opened (fallback)", Toast.LENGTH_SHORT).show()
            }
        }, 500)

        // Do NOT call loadUri() – GeckoView will load the URL automatically.
        return GeckoResult.fromValue(newSession)
    }

    private fun createDeepSeekTab() {
        createNewTab(DEEPSEEK_URL)
        Toast.makeText(this, "Opening DeepSeek...", Toast.LENGTH_SHORT).show()
    }

    private fun switchToTab(index: Int) {
        if (index !in 0 until sessions.size) return
        currentIndex = index
        geckoView.setSession(sessions[index])
    }

    private fun switchToNextTab() {
        if (sessions.size <= 1) {
            Toast.makeText(this, "Only one tab", Toast.LENGTH_SHORT).show()
            return
        }
        val nextIndex = (currentIndex + 1) % sessions.size
        switchToTab(nextIndex)
        Toast.makeText(this, "${currentIndex + 1}/${sessions.size}", Toast.LENGTH_SHORT).show()
    }

    private fun switchToPreviousTab() {
        if (sessions.size <= 1) {
            Toast.makeText(this, "Only one tab", Toast.LENGTH_SHORT).show()
            return
        }
        val prevIndex = if (currentIndex - 1 < 0) sessions.size - 1 else currentIndex - 1
        switchToTab(prevIndex)
        Toast.makeText(this, "${currentIndex + 1}/${sessions.size}", Toast.LENGTH_SHORT).show()
    }

    private fun closeCurrentTab() {
        if (sessions.size <= 1) {
            Toast.makeText(this, "Cannot close the last tab", Toast.LENGTH_SHORT).show()
            return
        }
        if (isHomeTab[currentIndex]) {
            Toast.makeText(this, "Cannot close the home tab", Toast.LENGTH_SHORT).show()
            return
        }
        val sessionToClose = sessions[currentIndex]
        sessions.removeAt(currentIndex)
        isHomeTab.removeAt(currentIndex)
        sessionToClose.close()

        if (currentIndex >= sessions.size) {
            currentIndex = sessions.size - 1
        }
        geckoView.setSession(sessions[currentIndex])
        Toast.makeText(this, "Tab closed", Toast.LENGTH_SHORT).show()
    }

    // ---------- Lifecycle ----------
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

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isInForeground && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun initBrightness() {
        val stored = prefs.getFloat(BRIGHTNESS_KEY, -1f)
        if (stored >= 0f) {
            currentBrightness = stored.coerceIn(0f, 1f)
        } else {
            val lp = window.attributes
            currentBrightness = if (lp.screenBrightness >= 0f) {
                lp.screenBrightness
            } else {
                try {
                    Settings.System.getInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS
                    ) / 255f
                } catch (_: Exception) {
                    1f
                }
            }
        }

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

            val lp = window.attributes
            lp.screenBrightness = currentBrightness
            window.attributes = lp

            prefs.edit().putFloat(BRIGHTNESS_KEY, currentBrightness).apply()
        }

        val percent = Math.round(currentBrightness * 100)
        showBrightnessToast(percent)
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
