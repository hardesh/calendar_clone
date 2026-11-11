package app.calendar.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.calendar.platform.desktopPlatform
import app.calendar.ui.CalendarApp
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private fun loadIcon(): BufferedImage? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use { ImageIO.read(it) }

fun main(args: Array<String>) {
    // Let the macOS title bar follow light/dark mode, and name the app in the menu bar.
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", "Calendar")
    val icon = loadIcon()
    // When run from Gradle there is no .app bundle, so set the Dock / Cmd+Tab icon here.
    if (icon != null && Taskbar.isTaskbarSupported()) {
        runCatching {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = icon
        }
    }
    val platform = desktopPlatform(args)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Calendar",
            icon = icon?.let { BitmapPainter(it.toComposeImageBitmap()) },
            state = rememberWindowState(width = 1280.dp, height = 840.dp),
        ) {
            CalendarApp(platform)
        }
    }
}
