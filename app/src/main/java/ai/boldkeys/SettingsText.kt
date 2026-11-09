package ai.boldkeys

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Every Settings explanation shares this typography and alignment, with no per-section opt-in. */
@Composable
internal fun SettingsDescription(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        // Apply the same accessibility rule everywhere: enlarged text must not stretch word gaps.
        textAlign = if (LocalDensity.current.fontScale <= 1.3f) TextAlign.Justify else TextAlign.Start,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun Note(text: String) {
    SettingsDescription(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** Link labels start at the same 16dp content edge as descriptions and section headings. */
@Composable
internal fun SettingsLink(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(modifier),
        // Inset belongs inside the clip; multiline labels must not touch rounded corners.
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        content = { Row(Modifier.fillMaxWidth(), content = content) },
    )
}
