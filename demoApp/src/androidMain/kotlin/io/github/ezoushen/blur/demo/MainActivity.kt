package io.github.ezoushen.blur.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.ezoushen.blur.BlurPerfMonitor
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        demoActivityRef = WeakReference(this)
        enableEdgeToEdge()
        if (intent.getBooleanExtra(EXTRA_EMPTY_CONTENT, false)) return
        setContent {
            Box(Modifier.fillMaxSize()) {
                BlurCmpDemoApp()
                if (BlurPerfMonitor.enabled) {
                    PerfOverlay(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_EMPTY_CONTENT = "io.github.ezoushen.blur.demo.EMPTY_CONTENT"
    }
}
