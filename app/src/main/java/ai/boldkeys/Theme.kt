@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.boldkeys

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The same Material 3 Expressive setup as Nano Search: wallpaper colours when the phone offers them, a violet scheme otherwise, springy motion.
private val Violet = Color(0xFF6B4EE6)
private val Light = lightColorScheme(primary = Violet, secondary = Color(0xFF5E5C71), tertiary = Color(0xFF00897B))
private val Dark = darkColorScheme(primary = Color(0xFFCBBEFF), secondary = Color(0xFFC9C3DC), tertiary = Color(0xFF6FD9C8))

@Composable
fun BoldTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
