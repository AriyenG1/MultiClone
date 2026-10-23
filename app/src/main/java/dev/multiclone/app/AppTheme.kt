package dev.multiclone.app

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal val destinations = listOf("Create", "Library", "Settings")

@Composable
internal fun MultiCloneTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF80D4D5), onPrimary = Color(0xFF003739),
            primaryContainer = Color(0xFF004F52), onPrimaryContainer = Color(0xFF9CF1F2),
            background = Color(0xFF101415), surface = Color(0xFF101415))
        else -> lightColorScheme(primary = Color(0xFF00696E), onPrimary = Color.White,
            primaryContainer = Color(0xFF9CF1F2), onPrimaryContainer = Color(0xFF002022),
            secondaryContainer = Color(0xFFCCE8E8), onSecondaryContainer = Color(0xFF051F20),
            background = Color(0xFFF6FAFA), surface = Color(0xFFF6FAFA))
    }
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun AppMark() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { DestinationIcon(0) }
    }
}

/** Consistent 24dp line icons, drawn locally without another icon dependency. Labels are on navigation items. */
@Composable
internal fun DestinationIcon(index: Int) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(width = 1.8f * unit)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (index) {
            0 -> {
                drawRoundRect(tint, point(3f, 7f), Size(14f * unit, 14f * unit), CornerRadius(2f * unit), style = stroke)
                drawLine(tint, point(7f, 3f), point(19f, 3f), stroke.width)
                drawLine(tint, point(21f, 5f), point(21f, 17f), stroke.width)
                drawLine(tint, point(7f, 14f), point(13f, 14f), stroke.width)
                drawLine(tint, point(10f, 11f), point(10f, 17f), stroke.width)
            }
            1 -> {
                for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f))
                    drawRoundRect(tint, point(x, y), Size(7f * unit, 7f * unit), CornerRadius(unit), style = stroke)
            }
            else -> {
                for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 8f)) {
                    drawLine(tint, point(3f, y), point(21f, y), stroke.width)
                    drawCircle(tint, 2.5f * unit, point(x, y))
                }
            }
        }
    }
}

@Composable
internal fun StatusMessage(message: String, error: Boolean, onDismiss: () -> Unit) {
    var details by rememberSaveable(message) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { details = true }) { Text("Details") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(if (error) "Action needs attention" else "Activity details") },
        text = { Text(message, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
}
