package be.valuya.breadbin.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import be.valuya.breadbin.R
import be.valuya.breadbin.data.MediaItem
import be.valuya.breadbin.data.MediaLibrary
import be.valuya.breadbin.data.RomSource
import be.valuya.breadbin.data.RomStore
import be.valuya.breadbin.data.Settings
import be.valuya.breadbin.emu.EmulatorSession
import be.valuya.breadbin.emu.SessionHolder
import be.valuya.breadbin.engine.tape.MediaKind
import be.valuya.breadbin.engine.tape.Program

/**
 * A running machine, with as little on top of it as the job allows.
 *
 * Everything that is not the picture is either translucent or out of the way: the controls sit over
 * the border, the menu is one button, and nothing appears between the user and the game unless it
 * was asked for.
 */
@Composable
fun EmulatorScreen(
    item: MediaItem?,
    library: MediaLibrary,
    romStore: RomStore,
    settings: Settings,
    holder: SessionHolder,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val freeRomsDiskNotice = stringResource(R.string.emulator_free_roms_disk)
    val roms = remember { romStore.load() }
    if (roms == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.setup_missing))
        }
        return
    }

    // The session comes from the holder, which lives above the navigation graph, so that opening
    // the settings and coming back finds the same machine still running. Only the things the
    // machine is built from are in the key; changing the sound or the joystick size is not one.
    val session = remember(settings.model, item?.file?.path) {
        holder.obtain(settings.model.name + ":" + item?.file?.path) {
            EmulatorSession(roms, settings) { bytes -> item?.let { library.save(it, bytes) } }
        }
    }

    var showKeyboard by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTape by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf<List<Program>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var port by remember { mutableStateOf(settings.joystickPort) }

    // The holder starts and stops the machine; this screen only decides whether it is running,
    // so that a machine nobody is looking at is not burning battery.
    DisposableEffect(session) {
        session.paused = false
        onDispose {
            session.releaseAllKeys()
            session.paused = true
        }
    }

    LaunchedEffect(session, settings.sound) { session.setSound(settings.sound) }

    LaunchedEffect(session) {
        if (item == null) {
            if (!session.machine.virtualDriveAvailable) notice = null
            return@LaunchedEffect
        }
        if (item.kind == MediaKind.ARCHIVE) {
            val entries = session.archiveEntries(item)
            if (entries.size > 1) {
                archive = entries
                return@LaunchedEffect
            }
        }
        notice = session.open(item, settings.autostart)
        // The free ROMs drive the serial bus themselves, so the emulated drive never hears from
        // them. Better to say that up front than to let the machine sit there searching.
        if (item.kind == MediaKind.DISK && romStore.source == RomSource.BUNDLED) {
            notice = freeRomsDiskNotice
        }
    }

    // Pausing when the app goes away stops a game running in the user's pocket, and stops the
    // audio thread with it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.paused = true
                Lifecycle.Event.ON_START -> session.paused = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {
            Display(
                session = session,
                settings = settings,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            if (showKeyboard) {
                VirtualKeyboard(
                    onPress = session::press,
                    onRelease = session::release,
                    onRestore = session::restore,
                )
            }
        }

        if (!showKeyboard) {
            TouchControls(
                scale = settings.stickSize,
                opacity = settings.opacity,
                onState = { state ->
                    session.joystick(port, state.up, state.down, state.left, state.right, state.fire)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.35f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.emulator_back), tint = Color.White)
            }
            IconButton(onClick = {
                showKeyboard = !showKeyboard
                // A latched SHIFT survives the keyboard going away otherwise, and every key the
                // game reads afterwards comes back shifted.
                if (!showKeyboard) session.releaseAllKeys()
            }) {
                Icon(
                    Icons.Filled.Keyboard,
                    stringResource(R.string.emulator_keyboard),
                    tint = if (showKeyboard) MaterialTheme.colorScheme.secondary else Color.White,
                )
            }
            IconButton(onClick = { session.warp = !session.warp }) {
                Icon(
                    Icons.Filled.FastForward,
                    stringResource(R.string.emulator_warp),
                    tint = if (session.warp) MaterialTheme.colorScheme.secondary else Color.White,
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.emulator_menu), tint = Color.White)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.emulator_swap_port)) },
                        onClick = {
                            session.joystick(port, false, false, false, false, false)
                            port = if (port == 2) 1 else 2
                            showMenu = false
                        },
                    )
                    if (item?.kind == MediaKind.DISK) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.emulator_directory)) },
                            onClick = {
                                session.type("LOAD\"$\",8\rLIST\r")
                                showMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.emulator_load_first)) },
                            onClick = {
                                session.machine.autostartDisk()
                                showMenu = false
                            },
                        )
                    }
                    if (item?.kind == MediaKind.TAPE) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.emulator_tape)) },
                            onClick = { showTape = true; showMenu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.emulator_type)) },
                        onClick = { showType = true; showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.emulator_reset)) },
                        onClick = { session.reset(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.emulator_reset_hard)) },
                        onClick = { session.resetAndUnplug(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_settings)) },
                        onClick = { showMenu = false; onSettings() },
                    )
                }
            }
        }

        if (session.paused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.emulator_paused), color = Color.White)
            }
        }
    }

    if (showTape) TapeDialog(session) { showTape = false }

    if (showType) {
        TypeDialog(
            onType = { session.type(it) },
            onDismiss = { showType = false },
        )
    }

    if (archive.isNotEmpty()) {
        ArchiveDialog(
            programs = archive,
            onPick = { session.run(it, settings.autostart); archive = emptyList() },
            onDismiss = { archive = emptyList() },
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
            text = { Text(message) },
        )
    }

    if (item?.kind == MediaKind.DISK && !session.machine.virtualDriveAvailable) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = stringResource(R.string.emulator_no_drive),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun TapeDialog(session: EmulatorSession, onDismiss: () -> Unit) {
    val position = session.tape.position.toFloat()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emulator_tape)) },
        text = {
            Column {
                Text(stringResource(R.string.emulator_tape_hint))
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { position },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { session.playTape() }) {
                        Text(stringResource(R.string.emulator_tape_play))
                    }
                    Button(onClick = { session.stopTape() }) {
                        Text(stringResource(R.string.emulator_tape_stop))
                    }
                    Button(onClick = { session.rewindTape() }) {
                        Text(stringResource(R.string.emulator_tape_rewind))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun TypeDialog(onType: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emulator_type)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onType(text + "\r")
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ArchiveDialog(
    programs: List<Program>,
    onPick: (Program) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kind_archive)) },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                items(programs) { program ->
                    TextButton(
                        onClick = { onPick(program) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = program.name.ifBlank { "PROGRAM" },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
