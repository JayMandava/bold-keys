@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.boldkeys

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BoldTheme { SetupScreen() } }
    }
}

private fun imeState(context: Context): Pair<Boolean, Boolean> {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(context.packageName + "/") == true
    return enabled to current
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val fb = remember { Feedback(context) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val (enabled, current) = remember(tick) { imeState(context) }

    Scaffold(
        topBar = {
            // One plain title bar: the app's name appears once, and nothing collapses while the list scrolls.
            TopAppBar(
                title = { Text("Bold Keys", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            // The keyboard's height is applied once: the navigation-bar part the Scaffold already handled is not counted twice.
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "hero") { Hero(enabled, current, Modifier.animateItem()) }
            item(key = "steps") { StepsCard(enabled, current, Modifier.animateItem()) { tick++ } }
            item(key = "try") { TryCard(Modifier.animateItem()) }
            item(key = "feel") { FeelCard(fb, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun Hero(enabled: Boolean, current: Boolean, modifier: Modifier = Modifier) {
    val ready = enabled && current
    val bg by animateColorAsState(if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer, label = "hero")
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = bg)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) { Text(if (ready) "✓" else "⌨", fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(if (ready) "All set" else "Almost there", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when { ready -> "Bold Keys is your keyboard. Open any text box and type."; !enabled -> "Turn the keyboard on, then switch to it."; else -> "Switch to it to start typing." },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StepsCard(enabled: Boolean, current: Boolean, modifier: Modifier = Modifier, changed: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(8.dp)) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                overlineContent = { Text("Step 1") },
                headlineContent = { Text(if (enabled) "Keyboard is on" else "Turn the keyboard on") },
                trailingContent = {
                    if (enabled) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                    else Button(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("Open") }
                },
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                overlineContent = { Text("Step 2") },
                headlineContent = { Text(if (current) "Bold Keys is selected" else "Switch to Bold Keys") },
                trailingContent = {
                    if (current) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                    else FilledTonalButton(
                        enabled = enabled,
                        onClick = { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); changed() },
                    ) { Text("Switch") }
                },
            )
        }
    }
}

@Composable
private fun TryCard(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Try it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(20.dp),
                placeholder = { Text("Type here") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
}

@Composable
private fun FeelCard(fb: Feedback, modifier: Modifier = Modifier) {
    var pattern by remember { mutableStateOf(fb.pattern) }
    var sound by remember { mutableStateOf(fb.soundOn) }
    var volume by remember { mutableStateOf(fb.volume) }
    val amplitude = remember { fb.vibrator.hasAmplitudeControl() }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("The click", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (amplitude) "This phone's motor can change strength." else "This phone's motor is on or off only, so the feel comes from how long it buzzes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, name) in Feedback.PATTERNS) {
                    ToggleButton(checked = pattern == key, onCheckedChange = { pattern = key; fb.pattern = key; fb.buzz(key) }) { Text(name) }
                }
            }
            Spacer(Modifier.height(8.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Key sound") },
                trailingContent = { Switch(checked = sound, onCheckedChange = { sound = it; fb.soundOn = it; if (it) fb.sound() }) },
            )
            Slider(value = volume, onValueChange = { volume = it; fb.volume = it }, onValueChangeFinished = { fb.sound() }, enabled = sound)
            FilledTonalButton(onClick = { fb.key() }, modifier = Modifier.fillMaxWidth()) { Text("Try the click") }
        }
    }
}
