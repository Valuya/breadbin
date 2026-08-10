package be.valuya.breadbin.ui.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.valuya.breadbin.R
import be.valuya.breadbin.data.RomDownload
import be.valuya.breadbin.data.RomDownloadResult
import be.valuya.breadbin.data.RomStore
import be.valuya.breadbin.engine.mem.RomKind
import kotlinx.coroutines.launch

/**
 * The first thing anybody sees, and an ordinary settings screen rather than a production.
 *
 * It used to be a full-bleed layout in the machine's own colours, which read beautifully in a
 * screenshot and was unreadable on a phone. This is a title bar, some sections, and a list — the
 * same shapes as the settings screen next door, because that is what the platform has already
 * taught everybody how to read.
 *
 * What it says has not changed. The app ships free replacement ROMs and starts on them immediately;
 * Commodore's own are better and there are three ways to bring them in. The address is the user's
 * to supply: an app carrying a pointer to a copy of somebody else's ROMs is distributing them,
 * where one that fetches from an address you type is a tool you pointed somewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(romStore: RomStore, onReady: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val download = remember(romStore) { RomDownload(romStore) }
    var version by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<Message?>(null) }
    var note by remember { mutableStateOf<Message?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var taken = 0
        var rejected: String? = null
        for (uri in uris) {
            val kinds = romStore.acceptAll(uri)
            if (kinds.isEmpty()) {
                rejected = uri.lastPathSegment?.substringAfterLast('/') ?: "That file"
            } else {
                taken += kinds.size
            }
        }
        problem = rejected?.let { Message.Resource(R.string.setup_unrecognised, it) }
        note = if (taken > 0) Message.Resource(R.string.setup_took, taken.toString()) else null
        version++
    }

    val present = remember(version) { RomKind.entries.associateWith { romStore.has(it) } }
    val using = remember(version) { RomKind.entries.associateWith { romStore.usingSupplied(it) } }
    val described = remember(version) { RomKind.entries.associateWith { romStore.describe(it) } }
    val complete = using.filterKeys { it.required }.values.all { it }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.setup_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Body(stringResource(R.string.setup_explanation))
            Body(stringResource(R.string.setup_limit))

            Button(
                onClick = onReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Only offer the free ones when they are what would actually be used: with
                // Commodore's three already here this starts on those, and saying otherwise is a lie.
                Text(stringResource(if (complete) R.string.setup_start else R.string.setup_start_free))
            }

            // Above the fold, not below it. These used to sit at the bottom of the column, past
            // seven list rows, which on a phone is off-screen: a file that was refused was refused
            // silently as far as anybody could tell.
            problem?.let { Body(it.resolve(), MaterialTheme.colorScheme.error) }
            note?.let { Body(it.resolve()) }

            Section(stringResource(R.string.setup_section_roms))
            for (kind in RomKind.entries) {
                RomRow(
                    label = stringResource(
                        when (kind) {
                            RomKind.BASIC -> R.string.setup_basic
                            RomKind.KERNAL -> R.string.setup_kernal
                            RomKind.CHARACTER -> R.string.setup_character
                            // The drive's own ROM is a different computer's, and nothing needs it:
                            // disks load without it. What it buys is fast loaders, which are
                            // programs for the drive's processor and so need there to be one.
                            RomKind.DRIVE -> R.string.setup_drive
                        }
                    ),
                    supplied = present[kind] == true,
                    using = using[kind] == true,
                    detail = described[kind],
                    onToggle = { on ->
                        romStore.setUsingSupplied(kind, on)
                        version++
                    },
                )
            }

            Section(stringResource(R.string.setup_section_add))
            ListItem(
                headlineContent = { Text(stringResource(R.string.setup_pick)) },
                supportingContent = { Text(stringResource(R.string.setup_pick_hint)) },
                leadingContent = { Icon(Icons.Filled.FolderOpen, null) },
                modifier = Modifier.clickable { picker.launch(arrayOf("*/*")) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.setup_download)) },
                supportingContent = { Text(stringResource(R.string.setup_download_hint_short)) },
                leadingContent = { Icon(Icons.Filled.Download, null) },
                modifier = Modifier.clickable {
                    problem = null
                    showDownload = true
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.setup_open_vice)) },
                supportingContent = { Text(stringResource(R.string.setup_where)) },
                leadingContent = { Icon(Icons.Filled.OpenInNew, null) },
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(RomStore.WHERE_TO_GET_ROMS))
                        )
                    } catch (_: ActivityNotFoundException) {
                        problem = Message.Resource(R.string.setup_no_browser)
                    }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDownload) {
        AlertDialog(
            onDismissRequest = { if (!fetching) showDownload = false },
            title = { Text(stringResource(R.string.setup_download)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.setup_download_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        enabled = !fetching,
                        label = { Text(stringResource(R.string.setup_download_field)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (fetching) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.setup_download_working))
                        }
                    }
                    // A failure has to be shown here rather than on the screen behind: the dialog
                    // stays open so the address can be corrected, and it covers everything else.
                    problem?.takeIf { !fetching }?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = it.resolve(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !fetching && address.isNotBlank(),
                    onClick = {
                        fetching = true
                        problem = null
                        note = null
                        scope.launch {
                            when (val result = download.fetch(address)) {
                                is RomDownloadResult.Loaded -> {
                                    note = Message.Resource(
                                        R.string.setup_download_done,
                                        result.kind.name,
                                    )
                                    address = ""
                                    showDownload = false
                                    version++
                                }
                                is RomDownloadResult.Failed -> problem = Message.Text(result.reason)
                            }
                            fetching = false
                        }
                    },
                ) { Text(stringResource(R.string.setup_download_go)) }
            },
            dismissButton = {
                TextButton(enabled = !fetching, onClick = { showDownload = false }) {
                    Text(stringResource(R.string.setup_cancel))
                }
            },
        )
    }
}

/** Prose between the sections, at the same inset as the list rows. */
@Composable
private fun Body(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Something to tell the user, kept as data until it is drawn. Resolving a resource needs to happen
 * in composition, and half of these come from a network call that has never heard of resources.
 */
private sealed interface Message {
    data class Text(val message: String) : Message
    data class Resource(val id: Int, val argument: String? = null) : Message
}

@Composable
private fun Message.resolve(): String = when (this) {
    is Message.Text -> message
    is Message.Resource -> if (argument == null) stringResource(id) else stringResource(id, argument)
}

/**
 * One ROM, and the choice between the user's and the one that came with the app.
 *
 * The switch is only live when there is something to switch to. With nothing supplied there is
 * only ever one answer, and a control that cannot move is worse than no control at all.
 */
@Composable
private fun RomRow(
    label: String,
    supplied: Boolean,
    using: Boolean,
    detail: String?,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = {
            Icon(
                imageVector = if (using) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (using) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        trailingContent = {
            Switch(checked = using, enabled = supplied, onCheckedChange = onToggle)
        },
    )
}
