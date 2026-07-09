package io.github.frei0xff.readestwebview

import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://web.readest.com/"
    }

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView

    /**
     * Suppress Android's text-selection ActionMode while leaving Gecko's
     * selection logic untouched.
     */
    inner class NoToolbarSelectionDelegate :
        BasicSelectionActionDelegate(this@MainActivity) {

        override fun onShowActionRequest(
            session: GeckoSession,
            selection: GeckoSession.SelectionActionDelegate.Selection
        ) {
            // Do nothing.
            // Gecko still receives the selection event,
            // but we never create an ActionMode.
        }

        override fun onHideAction(session: GeckoSession, reason: Int) {
            // Do nothing.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder().build()
        )

        session = GeckoSession()
        session.open(runtime)

        session.selectionActionDelegate = NoToolbarSelectionDelegate()

        geckoView = GeckoView(this)
        geckoView.setSession(session)

        setContentView(geckoView)

        session.loadUri(HOME_URL)

        hideSystemUi()
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
