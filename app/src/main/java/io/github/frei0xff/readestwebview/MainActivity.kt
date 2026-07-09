package io.github.frei0xff.readestwebview

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://www.w3schools.com/tags/tryit.asp?filename=tryhtml_select"
    }

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .build()
        )

        session = GeckoSession()
        session.open(runtime)

        geckoView = GeckoView(this)
        geckoView.setSession(session)

        session.loadUri(HOME_URL)

        setContentView(geckoView)
    }
}
