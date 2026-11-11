package app.calendar.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.calendar.platform.desktopPlatform
import app.calendar.ui.CalendarApp

fun main(args: Array<String>) {
    // Let the macOS title bar follow light/dark mode.
    System.setProperty("apple.awt.application.appearance", "system")
    val platform = desktopPlatform(args)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Calendar",
            state = rememberWindowState(width = 1280.dp, height = 840.dp),
        ) {
            CalendarApp(platform)
        }
    }
}
