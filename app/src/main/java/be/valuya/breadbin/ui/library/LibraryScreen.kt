package be.valuya.breadbin.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.valuya.breadbin.R
import be.valuya.breadbin.data.MediaItem
import be.valuya.breadbin.data.MediaLibrary
import be.valuya.breadbin.engine.tape.MediaKind
import kotlinx.coroutines.launch

/**
 * Everything the user has added, and the way in to a machine with nothing in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    library: MediaLibrary,
    onOpen: (MediaItem) -> Unit,
    onBasic: () -> Unit,
    onSettings: () -> Unit,
) {
    var items by remember { mutableStateOf(library.list()) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var added = 0
        var rejected: String? = null
        for (uri in uris) {
            // A zip can hold several games, so this counts what came out rather than what went in.
            val taken = library.addAll(uri)
            if (taken.isEmpty()) rejected = uri.lastPathSegment.orEmpty() else added += taken.size
        }
        items = library.list()
        scope.launch {
            if (rejected != null) {
                snackbars.showSnackbar(rejected.substringAfterLast('/'))
            } else if (added > 0) {
                snackbars.showSnackbar("Added $added")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onBasic) {
                        Icon(Icons.Filled.Terminal, stringResource(R.string.library_basic))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.library_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.library_add)) },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.library_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            items(items, key = { it.file.path }) { item ->
                ListItem(
                    headlineContent = { Text(item.title) },
                    supportingContent = { Text(kindLabel(item.kind)) },
                    leadingContent = { Icon(iconFor(item.kind), null, Modifier.size(28.dp)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                library.remove(item)
                                items = library.list()
                                scope.launch { snackbars.showSnackbar("${item.title} removed") }
                            }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.library_remove))
                            }
                            Icon(Icons.Filled.PlayArrow, null)
                        }
                    },
                    modifier = Modifier.clickable { onOpen(item) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun kindLabel(kind: MediaKind) = stringResource(
    when (kind) {
        MediaKind.DISK -> R.string.kind_disk
        MediaKind.TAPE -> R.string.kind_tape
        MediaKind.CARTRIDGE -> R.string.kind_cartridge
        MediaKind.ARCHIVE -> R.string.kind_archive
        else -> R.string.kind_program
    }
)

private fun iconFor(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.DISK -> Icons.Filled.Album
    MediaKind.TAPE -> Icons.Filled.Memory
    MediaKind.CARTRIDGE -> Icons.Filled.Memory
    MediaKind.ARCHIVE -> Icons.Filled.FolderZip
    else -> Icons.Filled.Code
}
