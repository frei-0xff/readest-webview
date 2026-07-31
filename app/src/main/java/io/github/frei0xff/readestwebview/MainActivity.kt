package io.github.frei0xff.readestwebview

import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://web.readest.com/"
        private const val NEW_TAB_URL = "resource://android/assets/newtab.html"
        private const val KEYBOARD_THRESHOLD = 0.15
        private const val BRIGHTNESS_STEP = 0.01f
        private const val BOTTOM_SWIPE_THRESHOLD = 0.85f // bottom 15% for tab switching
        private const val TOP_SWIPE_THRESHOLD = 0.15f    // top 15% for reload swipe
        private const val MIN_VERTICAL_SWIPE_RATIO = 0.3f // at least 30% of screen height

        private const val PREFS_NAME = "readest_prefs"
        private const val BRIGHTNESS_KEY = "brightness"
    }

    // ---------- Core components ----------
    private lateinit var runtime: GeckoRuntime
    private val handler = Handler(Looper.getMainLooper())
    private var layoutCheckRunnable: Runnable? = null
    private lateinit var container: FrameLayout

    // ---------- Tab management ----------
    private data class Tab(
        val session: GeckoSession,
        val view: GeckoView
    )
    private val tabs = mutableListOf<Tab>()
    private var currentIndex = 0

    // ---------- Brightness control ----------
    private var currentBrightness = 1.0f
    private var isInForeground = false
    private var mainToast: Toast? = null
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

    // ---------- Helpers ----------
    private fun currentTab() = tabs[currentIndex]

    // ---------- Create a GeckoView with gesture listener attached ----------
    private fun createGeckoView(session: GeckoSession): GeckoView {
        val view = GeckoView(this)
        view.setSession(session)
        view.setBackgroundColor(android.graphics.Color.BLACK)
        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // let the view handle the event normally
        }
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawableResource(android.R.color.black)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder().build()
        )

        // Initialize gesture detector before any views are created
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
                // Use dimensions from the view that received the touch
                val view = currentTab().view
                val screenWidth = view.width.toFloat()
                val screenHeight = view.height.toFloat()
                val minVerticalSwipe = screenHeight * MIN_VERTICAL_SWIPE_RATIO

                // ----- New tab: swipe down from top edge (at least 30% of screen height) -----
                if (diffY > minVerticalSwipe &&
                    Math.abs(velocityY) > minVelocity &&
                    e1.y < edgeThreshold) {
                    createNewTab(NEW_TAB_URL)
                    return true
                }

                // ----- Reload: left-to-right swipe in top 15% -----
                if (diffX > 0 &&
                    e1.x < edgeThreshold &&
                    diffX > screenWidth * 0.5f &&
                    Math.abs(diffX) > Math.abs(diffY) * 2 &&
                    Math.abs(velocityX) > minVelocity &&
                    e1.y < screenHeight * TOP_SWIPE_THRESHOLD) {
                    reloadCurrentPage()
                    return true
                }

                // ----- Switch tabs: horizontal swipe from bottom edges -----
                if (e1.y > screenHeight * BOTTOM_SWIPE_THRESHOLD &&
                    Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > minSwipeDistance &&
                    Math.abs(velocityX) > minVelocity) {
                    if (e1.x < edgeThreshold) {
                        switchToNextTab()
                        return true
                    }
                    if (e1.x > screenWidth - edgeThreshold) {
                        switchToPreviousTab()
                        return true
                    }
                }

                // ----- Close tab: upward swipe from bottom edge (at least 30% of screen height) -----
                if (diffY < -minVerticalSwipe &&
                    Math.abs(velocityY) > minVelocity &&
                    e1.y > screenHeight - edgeThreshold) {
                    closeCurrentTab()
                    return true
                }

                return false
            }
        })

        // Create container and set it as content view
        container = FrameLayout(this)
        container.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(container)

        // Create the initial home tab (index 0)
        val session = GeckoSession()
        session.open(runtime)
        setupSessionDelegates(session, isHome = true)

        val view = createGeckoView(session)
        tabs.add(Tab(session, view))
        container.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Ensure only the first tab is visible
        currentIndex = 0
        tabs.forEachIndexed { index, tab ->
            tab.view.visibility = if (index == currentIndex) View.VISIBLE else View.INVISIBLE
        }

        session.loadUri(HOME_URL)
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
    }

    // ---------- Setup delegates for a session ----------
    private fun setupSessionDelegates(session: GeckoSession, isHome: Boolean = false) {
        if (isHome) {
            session.selectionActionDelegate = NoOpSelectionDelegate()
        }
        session.promptDelegate = promptDelegate
    }

    // ---------- Tab management ----------
    private fun createNewTab(url: String) {
        val session = GeckoSession()
        session.open(runtime)
        setupSessionDelegates(session, isHome = false)

        val view = createGeckoView(session)
        tabs.add(Tab(session, view))
        container.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.visibility = View.INVISIBLE // initially hidden

        session.loadUri(url)

        switchToTab(tabs.lastIndex)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return

        // Use INVISIBLE to keep the view attached and avoid layout/recreate overhead
        tabs.forEach { it.view.visibility = View.INVISIBLE }
        tabs[index].view.visibility = View.VISIBLE

        currentIndex = index
        showTabCounter()
    }

    private fun switchToNextTab() {
        if (tabs.size <= 1) {
            showTabCounter()
            return
        }
        val nextIndex = (currentIndex + 1) % tabs.size
        switchToTab(nextIndex)
    }

    private fun switchToPreviousTab() {
        if (tabs.size <= 1) {
            showTabCounter()
            return
        }
        val prevIndex = if (currentIndex - 1 < 0) tabs.size - 1 else currentIndex - 1
        switchToTab(prevIndex)
    }

    private fun closeCurrentTab() {
        if (tabs.size <= 1) {
            showTabCounter()
            return
        }
        if (currentIndex == 0) {
            showTabCounter()
            return
        }

        val tab = tabs.removeAt(currentIndex)
        container.removeView(tab.view)
        tab.session.close()
        // view.releaseSession() is not needed – the view is removed and will be garbage-collected.

        if (currentIndex >= tabs.size) {
            currentIndex = tabs.size - 1
        }
        switchToTab(currentIndex)
    }

    // ---------- Reload current page ----------
    private fun reloadCurrentPage() {
        currentTab().session.reload()
        mainToast?.cancel()
        mainToast = Toast.makeText(this, "⟳ Reloading...", Toast.LENGTH_SHORT)
        mainToast?.show()
    }

    // Show current tab counter
    private fun showTabCounter() {
        mainToast?.cancel()
        mainToast = Toast.makeText(this, "${currentIndex + 1}/${tabs.size}", Toast.LENGTH_SHORT)
        mainToast?.show()
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
        mainToast?.cancel()
        mainToast = Toast.makeText(
            this,
            "$percent%",
            Toast.LENGTH_SHORT
        )
        mainToast?.show()
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
