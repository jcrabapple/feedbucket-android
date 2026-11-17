package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.model.constant.ElevationTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    value: String,
    placeholder: String = "",
    focusRequester: FocusRequester = remember { FocusRequester() },
    onValueChange: (String) -> Unit = {},
    onClose: () -> Unit = {},
    /** S15 — when non-null, a "save search" action is offered for non-blank input. */
    onSaveSearch: ((name: String, query: String) -> Unit)? = null,
    /** S10 — show the honest grammar help action. */
    grammarHelp: Boolean = false,
) {
    val focusManager = LocalFocusManager.current

    var input by remember { mutableStateOf(value) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showGrammarHelp by remember { mutableStateOf(false) }
    var savedSearchName by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp)
            .padding(vertical = 12.dp)
            .height(56.dp)
            .fillMaxWidth(),
        shape = CircleShape,
        tonalElevation = ElevationTokens.Level2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    modifier = Modifier.padding(start = 16.dp),
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = TextFieldDefaults.colors(
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    value = input,
                    onValueChange = {
                        input = it
                        onValueChange(it)
                    },
                    placeholder = {
                        Text(
                            modifier = Modifier.alpha(0.7f),
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                        }
                    )
                )
            }
            if (grammarHelp) {
                IconButton(onClick = { showGrammarHelp = true }) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = stringResource(R.string.search_grammar_help),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onSaveSearch != null && input.isNotBlank()) {
                IconButton(onClick = {
                    savedSearchName = ""
                    showSaveDialog = true
                }) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkAdd,
                        contentDescription = stringResource(R.string.saved_search_save),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onClose() }) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // S15 — save the current query string VERBATIM under a (non-unique) name.
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.saved_search_save)) },
            text = {
                TextField(
                    value = savedSearchName,
                    onValueChange = { savedSearchName = it },
                    placeholder = { Text(stringResource(R.string.saved_search_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = savedSearchName.isNotBlank(),
                    onClick = {
                        onSaveSearch?.invoke(savedSearchName, input)
                        showSaveDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // S10 — honest grammar statement (no regex/boolean claims; the S12a
    // simple-lowercase limitation is stated explicitly).
    if (showGrammarHelp) {
        AlertDialog(
            onDismissRequest = { showGrammarHelp = false },
            title = { Text(stringResource(R.string.search_grammar_help)) },
            text = { Text(stringResource(R.string.search_grammar_help_body)) },
            confirmButton = {
                TextButton(onClick = { showGrammarHelp = false }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}
