package be.valuya.breadbin.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    // The page last asked for, and what the Archive says the whole answer is, which is what decides
    // whether there is any point asking again.
    var page by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val nothingFound = stringResource(R.string.search_nothing)

    /** Asks for one page: the first for a new search, the next when the list is running out. */
    fun load(next: Boolean) {
        if (terms.isBlank() || searching || loadingMore) return
        val wanted = if (next) page + 1 else 1
        if (next) loadingMore = true else searching = true
        if (!next) message = null
        scope.launch {
            when (val outcome = search.search(terms, wanted)) {
                is GameSearchResult.Found -> {
                    // Merged rather than appended: the Archive can return the same row on two pages
                    // when download counts tie, and the list is keyed by identifier.
                    results = if (next) GameSearch.merge(results, outcome.results) else outcome.results
                    total = outcome.total
                    page = wanted
                    message = if (results.isEmpty()) nothingFound else null
                }
                is GameSearchResult.Failed -> {
                    // A failure part way down is not a reason to throw away what is already shown.
                    if (!next) results = emptyList()
                    message = outcome.reason
                }
            }
            searching = false
            loadingMore = false
        }
    }

    fun go() = load(next = false)

    // More of the answer, fetched before the bottom is reached rather than at it, so that scrolling
    // does not stop to wait. The Archive is asked only while it says there are more to come.
    LaunchedEffect(listState, results.size, total) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= results.size - AHEAD && results.isNotEmpty() && results.size < total) {
                    load(next = true)
                }
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

            if (results.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.search_count, results.size, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
                if (loadingMore) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

/**
 * How close to the end of the list to get before asking for the next page. Enough that the next
 * one is usually there by the time the bottom arrives, and not so much that idly opening a search
 * fetches half the collection.
 */
private const val AHEAD = 8
