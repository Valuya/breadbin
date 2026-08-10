package be.valuya.breadbin.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import be.valuya.breadbin.R
import be.valuya.breadbin.data.GameFetchResult
import be.valuya.breadbin.data.GameResult
import be.valuya.breadbin.data.GameSearch
import be.valuya.breadbin.data.GameSearchResult
import kotlinx.coroutines.launch

/**
 * Looking for a game, in the app rather than in a browser and a file manager.
 *
 * Deliberately plain: a box, a list, and a tap that puts the thing in the library. The one piece of
 * cleverness is that tapping a result downloads and adds it rather than opening anything, because
 * every result here is a file somebody wants to play in a moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    search: GameSearch,
    onAdded: (String) -> Unit,
    onBack: () -> Unit,
) {
    var terms by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GameResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val nothingFound = stringResource(R.string.search_nothing)

    fun go() {
        if (terms.isBlank() || searching) return
        searching = true
        message = null
        scope.launch {
            when (val outcome = search.search(terms)) {
                is GameSearchResult.Found -> {
                    results = outcome.results
                    message = if (outcome.results.isEmpty()) nothingFound else null
                }
                is GameSearchResult.Failed -> {
                    results = emptyList()
                    message = outcome.reason
                }
            }
            searched = true
            searching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = terms,
                onValueChange = { terms = it },
                singleLine = true,
                label = { Text(stringResource(R.string.search_field)) },
                trailingIcon = {
                    IconButton(onClick = ::go) { Icon(Icons.Filled.Search, null) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.search_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (searching) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            message?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.identifier }) { result ->
                    ListItem(
                        headlineContent = { Text(result.title) },
                        supportingContent = result.year?.let { { Text(it) } },
                        trailingContent = {
                            if (fetching == result.identifier) {
                                CircularProgressIndicator(Modifier.padding(4.dp))
                            } else {
                                Icon(Icons.Filled.Download, null)
                            }
                        },
                        modifier = Modifier.clickable(enabled = fetching == null) {
                            fetching = result.identifier
                            scope.launch {
                                when (val outcome = search.fetchInto(result)) {
                                    is GameFetchResult.Added -> {
                                        onAdded(outcome.items.first().file.name)
                                        snackbars.showSnackbar(outcome.items.first().title)
                                    }
                                    is GameFetchResult.Failed ->
                                        snackbars.showSnackbar(outcome.reason)
                                }
                                fetching = null
                            }
                        },
                    )
                }
            }
        }
    }
}
