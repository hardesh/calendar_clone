package app.calendar.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.calendar.platform.desktopPlatform
import app.calendar.ui.CalendarApp
import androidx.compose.runtime.mutableStateOf
import app.calendar.model.UpcomingEvent
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private fun loadIcon(): BufferedImage? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use { ImageIO.read(it) }

/**
 * On Linux, docks and taskbars match a window to its .desktop launcher by WM_CLASS,
 * which Java derives from the main class name. Set it to "Calendar" (the launcher
 * is calendar-app-Calendar.desktop, which docks match case-insensitively). Needs --add-opens java.desktop/sun.awt.X11.
 */
private fun setLinuxAppName(name: String) {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, name)
    }
}

fun main(args: Array<String>) {
    // Let the macOS title bar follow light/dark mode, and name the app in the menu bar.
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", "Calendar")
    // Menu-bar images are drawn black-on-transparent; macOS tints them for light/dark menu bars.
    System.setProperty("apple.awt.enableTemplateImages", "true")
    setLinuxAppName("calendar-app-Calendar")
    val icon = loadIcon()
    // When run from Gradle there is no .app bundle, so set the Dock / Cmd+Tab icon here.
    if (icon != null && Taskbar.isTaskbarSupported()) {
        runCatching {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = icon
        }
    }

    var windowRef: java.awt.Window? = null
    val windowVisible = mutableStateOf(true)
    fun showWindow() = SwingUtilities.invokeLater {
        windowVisible.value = true
        windowRef?.let { it.isVisible = true; it.toFront(); it.requestFocus() }
    }

    lateinit var platform: app.calendar.platform.PlatformServices
    var latest: List<UpcomingEvent> = emptyList()
    val menuBar = MenuBarItem(
        appIcon = icon,
        onOpenApp = { showWindow() },
        onOpenEvent = { key -> showWindow(); SwingUtilities.invokeLater { platform.openEvent?.invoke(key) } },
        onQuit = { exitProcess(0) },
    )
    platform = desktopPlatform(args) { events -> latest = events; menuBar.update(events) }
    menuBar.install()
    // Keep the relative time ("in 25m") current.
    Timer(20_000) { menuBar.update(latest) }.apply { isRepeats = true; start() }
    // Clicking the Dock icon brings the window back after it was closed to the menu bar.
    if (Desktop.isDesktopSupported()) {
        runCatching { Desktop.getDesktop().addAppEventListener(AppReopenedListener { showWindow() }) }
    }

    application {
        Window(
            // Closing the window keeps Calendar running in the menu bar; quit from its menu or ⌘Q.
            onCloseRequest = { if (SystemTray.isSupported()) windowVisible.value = false else exitApplication() },
            visible = windowVisible.value,
            title = "Calendar",
            icon = icon?.let { BitmapPainter(it.toComposeImageBitmap()) },
            state = rememberWindowState(width = 1280.dp, height = 840.dp),
        ) {
            windowRef = window
            CalendarApp(platform)
        }
    }
}
