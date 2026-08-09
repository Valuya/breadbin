package be.valuya.breadbin.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import be.valuya.breadbin.data.MediaLibrary
import be.valuya.breadbin.data.RomStore
import be.valuya.breadbin.data.Settings
import be.valuya.breadbin.data.SettingsRepository
import be.valuya.breadbin.emu.SessionHolder
import kotlinx.coroutines.launch
import be.valuya.breadbin.ui.emulator.EmulatorScreen
import be.valuya.breadbin.ui.library.LibraryScreen
import be.valuya.breadbin.ui.settings.SettingsScreen
import be.valuya.breadbin.ui.setup.SetupScreen
import java.net.URLDecoder
import java.net.URLEncoder

private const val LIBRARY = "library"
private const val SETUP = "setup"
private const val SETTINGS = "settings"
private const val EMULATOR = "emulator"

@Composable
fun BreadbinApp(
    romStore: RomStore,
    library: MediaLibrary,
    settingsRepository: SettingsRepository,
    opened: Uri?,
    onOpenedHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val settings by settingsRepository.settings.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()

    // There are always ROMs now: the free ones ship with the app. What varies is whether the user
    // has read the explanation of what they cost.
    var welcomed by remember(settings.welcomed) { mutableStateOf(settings.welcomed) }

    // Which ROM set is in use has to be state rather than a lookup, or the settings screen goes on
    // reporting the old answer after the user has changed it.
    var romSource by remember { mutableStateOf(romStore.source) }

    // The running machine lives here rather than in the emulator screen, so that going to the
    // settings and back does not restart the game.
    val sessionHolder = remember { SessionHolder() }
    DisposableEffect(sessionHolder) {
        onDispose { sessionHolder.stop() }
    }

    // A file opened from outside the app is added to the library and started. If the ROMs are not
    // in place yet the file is kept, not dropped: this runs again once setup has finished, and the
    // user gets the thing they actually tapped on.
    LaunchedEffect(opened) {
        val uri = opened ?: return@LaunchedEffect
        onOpenedHandled()
        val item = library.add(uri) ?: return@LaunchedEffect
        sessionHolder.stop()
        navController.navigate("$EMULATOR/${encode(item.file.name)}")
    }

    NavHost(
        navController = navController,
        startDestination = if (welcomed) LIBRARY else SETUP,
    ) {
        composable(SETUP) {
            SetupScreen(
                romStore = romStore,
                onReady = {
                    welcomed = true
                    romSource = romStore.source
                    scope.launch { settingsRepository.setWelcomed(true) }
                    // Clear the stack rather than just this screen: reached from the settings, the
                    // settings are still underneath, and Back from the library would reopen them.
                    navController.navigate(LIBRARY) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(LIBRARY) {
            LibraryScreen(
                library = library,
                onOpen = { item ->
                    navController.navigate("$EMULATOR/${encode(item.file.name)}")
                },
                onBasic = { navController.navigate("$EMULATOR/-") },
                onSettings = { navController.navigate(SETTINGS) },
            )
        }

        composable(SETTINGS) {
            SettingsScreen(
                settings = settings,
                repository = settingsRepository,
                scope = scope,
                romSource = romSource,
                onManageRoms = {
                    sessionHolder.stop()
                    navController.navigate(SETUP)
                },
                onUseFreeRoms = {
                    romStore.clear()
                    romSource = romStore.source
                    sessionHolder.stop()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable("$EMULATOR/{file}") { entry ->
            val file = entry.arguments?.getString("file").orEmpty()
            EmulatorScreen(
                item = if (file == "-") null else library.find(decode(file)),
                // Told apart from an empty machine on purpose: "-" is the user asking for BASIC,
                // and anything else that comes back with nothing is a file that has gone missing,
                // which they should hear about rather than watch boot to a bare prompt.
                missing = file != "-" && library.find(decode(file)) == null,
                library = library,
                romStore = romStore,
                settings = settings,
                holder = sessionHolder,
                onSettings = { navController.navigate(SETTINGS) },
                onBack = {
                    // Leaving for the library is the one thing that really does end the machine.
                    sessionHolder.stop()
                    navController.popBackStack(LIBRARY, inclusive = false)
                },
            )
        }
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
