package be.valuya.breadbin.ui

import android.net.Uri
import androidx.compose.runtime.Composable
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

    // Which file is loaded is app state rather than screen state: it survives going to settings
    // and coming back, which is the point of having settings reachable from a running machine.
    var romsPresent by remember { mutableStateOf(romStore.complete) }

    // A file opened from outside the app is added to the library and started.
    LaunchedEffect(opened, romsPresent) {
        val uri = opened ?: return@LaunchedEffect
        onOpenedHandled()
        if (!romsPresent) return@LaunchedEffect
        val item = library.add(uri) ?: return@LaunchedEffect
        navController.navigate("$EMULATOR/${encode(item.file.name)}")
    }

    NavHost(
        navController = navController,
        startDestination = if (romsPresent) LIBRARY else SETUP,
    ) {
        composable(SETUP) {
            SetupScreen(
                romStore = romStore,
                onReady = {
                    romsPresent = true
                    navController.navigate(LIBRARY) {
                        popUpTo(SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(LIBRARY) {
            LibraryScreen(
                library = library,
                onOpen = { item -> navController.navigate("$EMULATOR/${encode(item.file.name)}") },
                onBasic = { navController.navigate("$EMULATOR/-") },
                onSettings = { navController.navigate(SETTINGS) },
            )
        }

        composable(SETTINGS) {
            SettingsScreen(
                settings = settings,
                repository = settingsRepository,
                scope = scope,
                onReplaceRoms = {
                    romStore.clear()
                    romsPresent = false
                    navController.navigate(SETUP) { popUpTo(LIBRARY) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable("$EMULATOR/{file}") { entry ->
            val file = entry.arguments?.getString("file").orEmpty()
            EmulatorScreen(
                item = if (file == "-") null else library.find(decode(file)),
                library = library,
                romStore = romStore,
                settings = settings,
                onSettings = { navController.navigate(SETTINGS) },
                onBack = { navController.popBackStack(LIBRARY, inclusive = false) },
            )
        }
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
