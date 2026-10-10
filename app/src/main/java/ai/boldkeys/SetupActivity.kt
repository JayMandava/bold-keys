@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.boldkeys

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BoldTheme { SettingsApp() } }
    }
}

private fun imeState(context: Context): Pair<Boolean, Boolean> {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(context.packageName + "/") == true
    return enabled to current
}

// ---- the personal dictionary lives in one small file the keyboard reads: word, a tab, how many times it was typed

private fun dictFile(c: Context) = File(c.filesDir, "learned.tsv")

private fun readDictionary(c: Context): List<String> = runCatching {
    dictFile(c).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { l ->
        val p = l.split('\t'); if (p.size == 2 && (p[1].toIntOrNull() ?: 0) >= SpellEngine.LEARN_AFTER) p[0] else null
    }.sorted()
}.getOrDefault(emptyList())

private fun writeDictionary(c: Context, edit: (MutableMap<String, Int>) -> Unit) {
    val map = LinkedHashMap<String, Int>()
    runCatching { dictFile(c).takeIf { it.exists() }?.readLines()?.forEach { l -> l.split('\t').takeIf { it.size == 2 }?.let { map[it[0]] = it[1].toIntOrNull() ?: 0 } } }
    edit(map)
    dictFile(c).writeText(map.entries.joinToString("") { "${it.key}\t${it.value}\n" })
}

private enum class Screen(val title: String) {
    HOME("Bold Keys"), TYPING("Typing"), LOOK("Appearance"), FEEL("Key feel"), SYMBOLS("Symbols & emoji"), DICTIONARY("Personal dictionary"), CONTEXT("Context model"), ABOUT("About"),
}

@Composable
private fun SettingsApp() {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val current = Screen.valueOf(screen)
    BackHandler(enabled = current != Screen.HOME) { screen = Screen.HOME.name }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (current != Screen.HOME) IconButton(onClick = { screen = Screen.HOME.name }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (current) {
                Screen.HOME -> homeItems(context) { screen = it.name }
                Screen.TYPING -> typingItems(context)
                Screen.LOOK -> lookItems(context)
                Screen.FEEL -> feelItems(context)
                Screen.SYMBOLS -> symbolItems(context)
                Screen.DICTIONARY -> dictionaryItems(context)
                Screen.CONTEXT -> contextItems(context)
                Screen.ABOUT -> aboutItems(context)
            }
        }
    }
}

// ---------------------------------------------------------------- home

private fun androidx.compose.foundation.lazy.LazyListScope.homeItems(context: Context, open: (Screen) -> Unit) {
    item(key = "status") { StatusCards() }
    item(key = "try") { TryCard() }
    item(key = "sections") {
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                NavRow("Typing", "Autocorrect, suggestions and capitals") { open(Screen.TYPING) }
                NavRow("Appearance", "Black or white keys, and the backlight") { open(Screen.LOOK) }
                NavRow("Key feel", "How hard each key press buzzes") { open(Screen.FEEL) }
                NavRow("Symbols & emoji", "What the sym key offers") { open(Screen.SYMBOLS) }
                NavRow("Personal dictionary", "${readDictionary(context).size} words you have taught it") { open(Screen.DICTIONARY) }
                NavRow("Context model", if (Prefs.contextModel(context)) "On: judges words by the sentence around them" else "Off: optional, needs a download") { open(Screen.CONTEXT) }
                NavRow("About", "Privacy and credits") { open(Screen.ABOUT) }
            }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun StatusCards() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val (enabled, current) = remember(tick) { imeState(context) }
    val ready = enabled && current
    val bg by animateColorAsState(if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer, label = "hero")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = bg)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                    Text(if (ready) "✓" else "⌨", fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (ready) "All set" else "Almost there", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        when { ready -> "This is your keyboard now. Open any text box and type."; !enabled -> "Turn the keyboard on, then switch to it."; else -> "Switch to it to start typing." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (!ready) Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
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
                    headlineContent = { Text(if (current) "This keyboard is selected" else "Switch to this keyboard") },
                    trailingContent = {
                        if (current) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                        else FilledTonalButton(enabled = enabled, onClick = { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); tick++ }) { Text("Switch") }
                    },
                )
            }
        }
    }
}

@Composable
private fun TryCard() {
    var text by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Try it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(20.dp),
                placeholder = { Text("Type here") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
}

// ---------------------------------------------------------------- small building blocks

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

// ---------------------------------------------------------------- typing

private fun androidx.compose.foundation.lazy.LazyListScope.typingItems(context: Context) {
    item(key = "typing") {
        var correct by remember { mutableStateOf(Prefs.autocorrect(context)) }
        var suggest by remember { mutableStateOf(Prefs.suggestions(context)) }
        var caps by remember { mutableStateOf(Prefs.autoCapitals(context)) }
        var dbl by remember { mutableStateOf(Prefs.doubleSpace(context)) }
        var nextW by remember { mutableStateOf(Prefs.nextWord(context)) }
        var spelling by remember { mutableStateOf(Prefs.spelling(context)) }
        Section("While you type") {
            SwitchRow("Autocorrect", "Fixes a misspelt word when you press space or punctuation. Backspace right after brings your word back.", correct) { correct = it; Prefs.put(context, "autocorrect", it) }
            SwitchRow("Suggestions", "Shows the likely words beside the trackpad.", suggest) { suggest = it; Prefs.put(context, "suggestions", it) }
            SwitchRow("Next-word suggestions", "Offers the likely next word as soon as you finish one. A quick tap on the trackpad takes the first suggestion.", nextW) { nextW = it; Prefs.put(context, "nextWord", it) }
            SwitchRow("Capitalise sentences", "Starts the next letter in capitals after a full stop, and at the start of a field.", caps) { caps = it; Prefs.put(context, "autocaps", it) }
            SwitchRow("Double space for a full stop", "Pressing space twice after a word ends the sentence with a full stop.", dbl) { dbl = it; Prefs.put(context, "doubleSpace", it) }
        }
        Section("English spelling") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                val options = listOf("us" to "American", "uk" to "British")
                options.forEachIndexed { i, (key, name) ->
                    SegmentedButton(selected = spelling == key, onClick = { spelling = key; Prefs.put(context, "spelling", key) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(name) }
                }
            }
            Note("Words like colour/color, realise/realize and centre/center are corrected to the one you choose, and only that one is suggested.")
        }
    }
    item(key = "typing-note") { Note("Passwords, email addresses and web addresses are never corrected or suggested, whatever you choose here.") }
}

// ---------------------------------------------------------------- appearance

private fun androidx.compose.foundation.lazy.LazyListScope.lookItems(context: Context) {
    item(key = "look") { AppearanceContent(context) }
}

@Composable
private fun AppearanceContent(context: Context) {
    var theme by remember { mutableStateOf(Prefs.theme(context)) }
    var mode by remember { mutableStateOf(Prefs.backlightMode(context)) }
    var color by remember { mutableStateOf(Prefs.backlightColor(context)) }
    var level by remember { mutableStateOf(Prefs.backlightLevel(context)) }
    var version by remember { mutableIntStateOf(0) }
    val dark = Prefs.isDark(context)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)),
                factory = { c ->
                    KeyboardView(c, Feedback(c), object : KeyboardView.Listener {
                        override fun onText(text: String) = Unit
                        override fun onDelete() = Unit
                        override fun onEnter() = Unit
                    })
                },
                update = { v -> version; v.applySettings() },
            )
        }
        Section("Keys") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                val options = listOf("dark" to "Black", "light" to "White", "auto" to "Match phone")
                options.forEachIndexed { i, (key, name) ->
                    SegmentedButton(
                        selected = theme == key, onClick = { theme = key; Prefs.put(context, "theme", key); version++ },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(name) }
                }
            }
            Note("Match phone follows the system's light or dark setting.")
        }
        Section("Backlight") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                val options = listOf("off" to "Off", "on" to "On", "auto" to "Auto")
                options.forEachIndexed { i, (key, name) ->
                    SegmentedButton(
                        selected = mode == key, onClick = { mode = key; Prefs.put(context, "backlight", key); version++ },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(name) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((key, rgb) in Prefs.BACKLIGHT_COLORS) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(rgb))
                            .clickable { color = key; Prefs.put(context, "backlightColor", key); version++ },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (color == key) Box(Modifier.size(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)))
                    }
                }
            }
            Text("Brightness", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Slider(
                value = level, onValueChange = { level = it; Prefs.put(context, "backlightLevel", it); version++ },
                enabled = mode != "off", modifier = Modifier.padding(horizontal = 16.dp),
            )
            Note(
                when {
                    !dark -> "The backlight glows on the black keys. Switch to Black to see it."
                    mode == "auto" -> "Glows in the dark and fades in bright light, using the phone's light sensor."
                    mode == "on" -> "Glows all the time."
                    else -> "The letters and the gaps between keys stay unlit."
                },
            )
        }
    }
}

// ---------------------------------------------------------------- key feel

private fun androidx.compose.foundation.lazy.LazyListScope.feelItems(context: Context) {
    item(key = "feel") {
        val fb = remember { Feedback(context) }
        var pattern by remember { mutableStateOf(fb.pattern) }
        val amplitude = remember { fb.vibrator.hasAmplitudeControl() }
        Section("The click") {
            Note(if (amplitude) "This phone's motor can change strength." else "This phone's motor is on or off only, so the feel comes from how long it buzzes.")
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, name) in Feedback.PATTERNS) ToggleButton(checked = pattern == key, onCheckedChange = { pattern = key; fb.pattern = key; fb.buzz(key) }) { Text(name) }
            }
            FilledTonalButton(onClick = { fb.key() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Try the click") }
        }
    }
}

// ---------------------------------------------------------------- symbols and emoji

private fun androidx.compose.foundation.lazy.LazyListScope.symbolItems(context: Context) {
    item(key = "symbols") {
        var emoji by remember { mutableStateOf(Prefs.emojiPages(context)) }
        var emoticons by remember { mutableStateOf(Prefs.emoticons(context)) }
        Section("Sym key") {
            SwitchRow("Emoji pages", "After the symbol pages, \"more\" goes on to three pages of emoji. Off keeps only symbols.", emoji) { emoji = it; Prefs.put(context, "emojiPages", it) }
            SwitchRow("Turn text faces into emoji", "Typing :) gives 🙂 and :D then a space gives 😀. Backspace gives your text back.", emoticons) { emoticons = it; Prefs.put(context, "emoticons", it) }
        }
    }
}

// ---------------------------------------------------------------- personal dictionary

private fun androidx.compose.foundation.lazy.LazyListScope.dictionaryItems(context: Context) {
    item(key = "dict") { DictionaryContent(context) }
}

@Composable
private fun DictionaryContent(context: Context) {
    var words by remember { mutableStateOf(readDictionary(context)) }
    var field by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    fun refresh() { words = readDictionary(context) }
    val clean = field.trim().lowercase()
    val valid = clean.length in 2..24 && clean.all { it.isLetter() || it == '\'' }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("Add a word") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = field, onValueChange = { field = it }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                    placeholder = { Text("A name, a place, slang…") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(enabled = valid, onClick = { writeDictionary(context) { it[clean] = 5 }; field = ""; refresh() }) {
                    Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Add")
                }
            }
            Note("Words you add are never corrected, and they show up as suggestions. Anything goes: what you add is up to you. Words you type twice are added on their own.")
        }
        Section(if (words.isEmpty()) "Your words" else "Your words (${words.size})") {
            if (words.isEmpty()) Note("Nothing yet. The keyboard learns words you type twice that it did not know.")
            else words.forEach { w ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(w) },
                    trailingContent = { IconButton(onClick = { writeDictionary(context) { it.remove(w) }; refresh() }) { Icon(Icons.Default.Close, contentDescription = "Remove $w") } },
                )
            }
        }
        if (words.isNotEmpty()) {
            if (!confirmClear) OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all words") }
            else Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Remove all ${words.size} words?", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { writeDictionary(context) { it.clear() }; confirmClear = false; refresh() }) { Text("Remove all") }
                        OutlinedButton(onClick = { confirmClear = false }) { Text("Keep them") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- about

private fun androidx.compose.foundation.lazy.LazyListScope.aboutItems(context: Context) {
    item(key = "about") {
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
        Section("Version $version") {
            Note("Everything happens on this phone. Typing, learning and the optional context model never use the network and send nothing anywhere; what the keyboard learns stays in this app's private storage. The only thing that touches the internet is the Download button for a context model, and only when you tap it.")
            Note("Word frequencies come from the OpenSubtitles-based FrequencyWords list (CC BY-SA 4.0). The key legends use the Nunito typeface (SIL Open Font License).")
            Note("Inspired by the classic BlackBerry Bold 9900 keyboard. BlackBerry is a trademark of its owner; this app is independent and is not affiliated with or endorsed by BlackBerry.")
        }
    }
}


// ---------------------------------------------------------------- context model

private fun androidx.compose.foundation.lazy.LazyListScope.contextItems(context: Context) {
    item(key = "context") { ContextContent(context) }
}

private fun human(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"

@Composable
private fun ContextContent(context: Context) {
    ContextModels.init(context)
    var on by remember { mutableStateOf(Prefs.contextModel(context)) }
    var wifiOnly by remember { mutableStateOf(Prefs.bool(context, "modelWifiOnly", true)) }
    var tick by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: "model.gguf"
            importing = true
            Thread {
                note = if (name.endsWith(".gguf", true)) runCatching { ContextModels.import(uri, name); null }.getOrElse { "Could not import that file: ${it.message}" } else "Pick a .gguf model file."
                importing = false; tick++
            }.start()
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val chosen = ContextModels.selected()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("Smarter corrections") {
            SwitchRow("Use a context model", "Checks each word against the sentence around it: picks the right fix for a misspelling, and catches real words used for another (their/there, your/you're).", on) {
                on = it; Prefs.put(context, "contextModel", it)
            }
            Note("A small language model on this phone scores the words the dictionary suggests. It never writes text and nothing leaves the phone. Switch it off any time to go back to dictionary-only correction.")
            if (on && chosen == null) Note("Download or import a model below to start using it.")
        }
        Section("Models") {
            ContextModels.entries().forEach { e ->
                val installed = ContextModels.installed(e)
                val st = ContextModels.status[e.id]
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { RadioButton(selected = chosen?.id == e.id, enabled = installed, onClick = { ContextModels.select(e); tick++ }) },
                    headlineContent = { Text(e.name) },
                    supportingContent = {
                        Column {
                            Text(if (e.custom) "Imported from a file." else "${e.note} ${human(e.bytes)}.")
                            when (st) {
                                is Transfer.Downloading -> {
                                    androidx.compose.material3.LinearProgressIndicator(progress = { if (st.total > 0) st.done.toFloat() / st.total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                    Text("${human(st.done)} of ${human(st.total)}", style = MaterialTheme.typography.bodySmall)
                                }
                                Transfer.Verifying -> Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
                                is Transfer.Failed -> Text(st.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                null -> Unit
                            }
                        }
                    },
                    trailingContent = {
                        when {
                            ContextModels.active(e) -> OutlinedButton(onClick = { ContextModels.cancel(e) }) { Text("Cancel") }
                            installed -> IconButton(onClick = { ContextModels.delete(e); tick++ }) { Icon(Icons.Default.Close, contentDescription = "Delete ${e.name}") }
                            else -> FilledTonalButton(onClick = { ContextModels.download(e, wifiOnly) { tick++ } }) { Text("Download") }
                        }
                    },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                OutlinedButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (importing) "Importing…" else "Import a .gguf file") }
            }
            note?.let { Note(it) }
        }
        Section("Downloads") {
            SwitchRow("Wi-Fi only", "Models are a few hundred megabytes. Downloads are checked against a fixed checksum, and a file that does not match is thrown away.", wifiOnly) { wifiOnly = it; Prefs.put(context, "modelWifiOnly", it) }
            Note("Downloading is the only thing in this app that uses the internet, and only when you tap Download.")
        }
    }
}
