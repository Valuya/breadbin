package be.valuya.breadbin.ui.setup

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
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.valuya.breadbin.R
import be.valuya.breadbin.data.RomStore
import be.valuya.breadbin.engine.mem.RomKind

/**
 * The one thing that has to happen before anything else can: getting the three Commodore ROMs into
 * the app.
 *
 * The screen deliberately does not ask which file is which. Handing over three files and being
 * quizzed about them is the sort of thing that puts people off, and the ROMs identify themselves
 * well enough from their size and contents.
 */
@Composable
fun SetupScreen(romStore: RomStore, onReady: () -> Unit) {
    // Bumped whenever a file lands, to re-read what the store now holds.
    var version by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var rejected: String? = null
        for (uri in uris) {
            if (romStore.accept(uri) == null) {
                rejected = uri.lastPathSegment?.substringAfterLast('/') ?: "That file"
            }
        }
        problem = rejected
        version++
    }

    val present = remember(version) { RomKind.entries.associateWith { romStore.has(it) } }

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
            text = stringResource(R.string.setup_where),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
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
                text = stringResource(R.string.setup_unrecognised, it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))
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

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onReady,
            enabled = present.values.all { it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_start))
        }
    }
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
