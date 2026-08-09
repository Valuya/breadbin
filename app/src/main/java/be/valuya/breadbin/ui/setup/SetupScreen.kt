package be.valuya.breadbin.ui.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The first thing anybody sees, and deliberately not a wall.
 *
 * The app ships free replacement ROMs and can start on them immediately, so this screen explains
 * what that costs — disks — and offers the ways out rather than demanding one: a link to the page
 * that has the files, the file picker, or an address to fetch from.
 *
 * The address is the user's to supply. Breadbin does not come with one, and it does not go looking:
 * an app carrying a pointer to a copy of somebody else's ROMs is distributing them, where one that
 * can fetch a file from an address you type is a tool you pointed somewhere.
 */
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
        var rejected: String? = null
        for (uri in uris) {
            if (romStore.accept(uri) == null) {
                rejected = uri.lastPathSegment?.substringAfterLast('/') ?: "That file"
            }
        }
        problem = rejected?.let { Message.Resource(R.string.setup_unrecognised, it) }
        version++
    }

    val present = remember(version) { RomKind.entries.associateWith { romStore.has(it) } }
    val complete = present.values.all { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_explanation), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.setup_limit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onReady, modifier = Modifier.fillMaxWidth()) {
            // Only offer the free ones when they are what would actually be used: with Commodore's
            // three already here this button starts on those, and saying otherwise would be a lie.
            Text(stringResource(if (complete) R.string.setup_start else R.string.setup_start_free))
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.settings_replace_roms),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_where),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(RomStore.WHERE_TO_GET_ROMS))
                )
            } catch (_: ActivityNotFoundException) {
                problem = Message.Resource(R.string.setup_no_browser)
            }
        }) {
            Icon(Icons.Filled.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_open_vice))
        }

        TextButton(onClick = {
            problem = null
            showDownload = true
        }) {
            Icon(Icons.Filled.Download, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_download))
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RomRow(stringResource(R.string.setup_basic), present[RomKind.BASIC] == true)
                RomRow(stringResource(R.string.setup_kernal), present[RomKind.KERNAL] == true)
                RomRow(stringResource(R.string.setup_character), present[RomKind.CHARACTER] == true)
            }
        }

        problem?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_pick))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_pick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        note?.let {
            Spacer(Modifier.height(12.dp))
            Text(it.resolve(), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showDownload) {
        AlertDialog(
            onDismissRequest = { if (!fetching) showDownload = false },
            title = { Text(stringResource(R.string.setup_download)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.setup_download_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text("Cancel")
                }
            },
        )
    }
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

@Composable
private fun RomRow(label: String, present: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (present) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(if (present) R.string.setup_loaded else R.string.setup_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
