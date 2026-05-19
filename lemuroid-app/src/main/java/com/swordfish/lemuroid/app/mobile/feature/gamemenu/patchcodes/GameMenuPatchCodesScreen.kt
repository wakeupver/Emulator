package com.swordfish.lemuroid.app.mobile.feature.gamemenu.patchcodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.library.db.entity.PatchCode

@Composable
fun GameMenuPatchCodesScreen(viewModel: GameMenuPatchCodesViewModel) {
    val codes by viewModel.patchCodes.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.patch_codes_add),
                )
            }
        },
    ) { innerPadding ->
        if (codes.isEmpty()) {
            EmptyCodesPlaceholder(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(codes, key = { it.id }) { patchCode ->
                    PatchCodeItem(
                        patchCode = patchCode,
                        onToggle = { viewModel.toggleCode(patchCode) },
                        onDelete = { viewModel.deleteCode(patchCode) },
                    )
                }
                item { Spacer(modifier = Modifier.height(72.dp)) } // FAB breathing room
            }
        }
    }

    if (showAddDialog) {
        AddPatchCodeDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { desc, code ->
                val added = viewModel.addCode(desc, code)
                if (added) showAddDialog = false
            },
        )
    }
}

@Composable
private fun PatchCodeItem(
    patchCode: PatchCode,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (patchCode.enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = patchCode.enabled,
                onCheckedChange = { onToggle() },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = patchCode.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
                Text(
                    text = patchCode.code,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.patch_codes_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyCodesPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.patch_codes_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.patch_codes_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddPatchCodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (description: String, code: String) -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.patch_codes_add_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        showError = false
                    },
                    label = { Text(text = stringResource(R.string.patch_codes_field_description)) },
                    singleLine = true,
                    isError = showError && description.isBlank(),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.uppercase()
                        showError = false
                    },
                    label = { Text(text = stringResource(R.string.patch_codes_field_code)) },
                    placeholder = { Text(text = "DEAD BEEF+CAFE 1234") },
                    isError = showError && code.isBlank(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done,
                        ),
                    supportingText = {
                        Text(text = stringResource(R.string.patch_codes_field_code_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.patch_codes_error_empty_fields),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (description.isBlank() || code.isBlank()) {
                        showError = true
                    } else {
                        onConfirm(description, code)
                    }
                },
            ) {
                Text(text = stringResource(R.string.patch_codes_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
