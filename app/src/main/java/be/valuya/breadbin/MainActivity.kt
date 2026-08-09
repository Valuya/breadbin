package be.valuya.breadbin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import be.valuya.breadbin.data.MediaLibrary
import be.valuya.breadbin.data.RomStore
import be.valuya.breadbin.data.SettingsRepository
import be.valuya.breadbin.ui.BreadbinApp
import be.valuya.breadbin.ui.theme.BreadbinTheme

class MainActivity : ComponentActivity() {

    private val opened = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A game being played is a game nobody is touching, and the screen going off in the middle
        // of it is the single most annoying thing an emulator can do.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        opened.value = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

        val romStore = RomStore(applicationContext)
        val library = MediaLibrary(applicationContext)
        val settings = SettingsRepository(applicationContext)

        setContent {
            BreadbinTheme {
                BreadbinApp(
                    romStore = romStore,
                    library = library,
                    settingsRepository = settings,
                    opened = opened.value,
                    onOpenedHandled = { opened.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) opened.value = intent.data
    }
}
