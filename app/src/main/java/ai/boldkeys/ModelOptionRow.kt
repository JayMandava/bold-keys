package ai.boldkeys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Selection and file actions are sibling targets so download/cancel/delete never select a model. */
@Composable
internal fun ModelOptionRow(
    entry: ModelEntry, installed: Boolean, effective: Boolean, savedId: String?, busy: Boolean,
    details: @Composable () -> Unit,
    onSelect: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val fallback = effective && savedId != entry.id
    val state = when {
        busy -> "Transfer in progress"
        fallback && savedId != null -> "Saved model unavailable. Using temporarily; tap to save this choice."
        fallback -> "Using by default; tap to save this choice."
        effective -> "Saved choice"
        !installed -> "Not installed"
        else -> "Available"
    }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = effective, enabled = installed && !busy, role = Role.RadioButton, onClick = onSelect)
                .semantics { stateDescription = state },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { RadioButton(selected = effective, enabled = installed && !busy, onClick = null) },
            headlineContent = { Text(entry.name) },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SettingsDescription(state)
            details()
        }
        when {
            busy -> OutlinedButton(onClick = onCancel, modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Cancel ${entry.name}" }) { Text("Cancel") }
            installed -> OutlinedButton(onClick = onDelete, modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Delete ${entry.name}" }) { Text("Remove model") }
            else -> FilledTonalButton(onClick = onDownload, modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Download ${entry.name}" }) { Text("Download") }
        }
    }
}
