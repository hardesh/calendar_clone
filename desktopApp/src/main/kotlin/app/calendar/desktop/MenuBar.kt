package app.calendar.desktop

import app.calendar.model.UpcomingEvent
import java.awt.Color
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.RoundRectangle2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** What the menu bar shows for a moment in time; pure so it can be tested. */
object MenuBarText {
    private const val MAX_TITLE = 26

    /**
     * "Standup · now" for an event that started up to 15 minutes ago, otherwise the next event
     * within 12 hours as "Design review · in 1h 25m". Null when nothing is coming up.
     */
    fun label(events: List<UpcomingEvent>, now: Instant): String? {
        val justStarted = events.filter { it.start <= now && it.end > now && (now - it.start).inWholeMinutes < 15 }
            .maxByOrNull { it.start }
        if (justStarted != null) return "${shorten(justStarted.title)} · now"
        val next = events.filter { it.start > now }.minByOrNull { it.start } ?: return null
        val minutes = (next.start - now).inWholeSeconds.let { (it + 59) / 60 }
        if (minutes > 12 * 60) return null
        return "${shorten(next.title)} · ${relative(minutes)}"
    }

    fun relative(minutes: Long): String = when {
        minutes <= 0 -> "now"
        minutes < 60 -> "in ${minutes}m"
        minutes % 60 == 0L -> "in ${minutes / 60}h"
        else -> "in ${minutes / 60}h ${minutes % 60}m"
    }

    private fun shorten(title: String) = if (title.length <= MAX_TITLE) title else title.take(MAX_TITLE - 1).trimEnd() + "…"
}

/**
 * The next event in the macOS menu bar (and a tray icon with a tooltip on Linux/Windows).
 * Clicking it lists the rest of today's events; choosing one opens it in the app.
 */
class MenuBarItem(
    private val appIcon: BufferedImage?,
    private val onOpenApp: () -> Unit,
    private val onOpenEvent: (String) -> Unit,
    private val onQuit: () -> Unit,
) {
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private var trayIcon: TrayIcon? = null
    private var lastLabel: String? = "\u0000"
    private var lastMenuKeys: List<String>? = null
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun install() {
        if (!SystemTray.isSupported()) return
        SwingUtilities.invokeLater {
            val icon = TrayIcon(render(null), "Calendar")
            icon.isImageAutoSize = false
            if (!isMac) icon.addActionListener { onOpenApp() }
            runCatching { SystemTray.getSystemTray().add(icon) }.onSuccess { trayIcon = icon }
            buildMenu(emptyList())
        }
    }

    fun remove() {
        SwingUtilities.invokeLater { trayIcon?.let { SystemTray.getSystemTray().remove(it) }; trayIcon = null }
    }

    /** Refreshes the label and menu; cheap when nothing changed. Call on new data and every few seconds. */
    fun update(events: List<UpcomingEvent>) {
        val now = Clock.System.now()
        val label = MenuBarText.label(events, now)
        val zone = ZoneId.systemDefault()
        val today = now.toJavaInstant().atZone(zone).toLocalDate()
        val todays = events.filter { it.end > now && it.start.toJavaInstant().atZone(zone).toLocalDate() == today }
        SwingUtilities.invokeLater {
            val icon = trayIcon ?: return@invokeLater
            if (label != lastLabel) {
                lastLabel = label
                if (isMac) icon.image = render(label)
                icon.toolTip = label ?: "Calendar"
            }
            if (todays.map { it.key + it.start + it.title } != lastMenuKeys) {
                lastMenuKeys = todays.map { it.key + it.start + it.title }
                buildMenu(todays)
            }
        }
    }

    private fun buildMenu(todays: List<UpcomingEvent>) {
        val icon = trayIcon ?: return
        val menu = PopupMenu()
        if (todays.isEmpty()) {
            menu.add(MenuItem("Nothing else today").apply { isEnabled = false })
        } else {
            menu.add(MenuItem("Today").apply { isEnabled = false })
            for (e in todays.take(12)) {
                val time = timeFormat.format(e.start.toJavaInstant().atZone(ZoneId.systemDefault()))
                menu.add(MenuItem("$time   ${e.title}").apply { addActionListener { onOpenEvent(e.key) } })
            }
        }
        menu.addSeparator()
        menu.add(MenuItem("Open Calendar").apply { addActionListener { onOpenApp() } })
        menu.add(MenuItem("Quit Calendar").apply { addActionListener { onQuit() } })
        icon.popupMenu = menu
    }

    /**
     * Draws "▍Title · in 25m" (or the icon alone) as a black-on-transparent template image,
     * which macOS tints to match a light or dark menu bar. 1x and 2x for Retina.
     */
    private fun render(label: String?): java.awt.Image {
        if (!isMac) return appIcon?.let { scaled(it, 22) } ?: blank(22, 22)
        fun draw(scale: Int): BufferedImage {
            val h = 22 * scale
            val font = Font(".AppleSystemUIFont", Font.PLAIN, 13 * scale).let { if (it.family == "Dialog") Font("Helvetica Neue", Font.PLAIN, 13 * scale) else it }
            val measure = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().apply { this.font = font }
            val textW = if (label == null) 0 else measure.fontMetrics.stringWidth(label)
            measure.dispose()
            val glyphW = 16 * scale
            val w = if (label == null) glyphW else 5 * scale + textW + 2 * scale
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.color = Color.BLACK
            if (label == null) {
                // Small calendar glyph: outlined page with a filled header.
                val x = 2f * scale; val y = 4f * scale; val s = 12f * scale
                g.stroke = java.awt.BasicStroke(1.4f * scale)
                g.draw(RoundRectangle2D.Float(x, y, s, s, 3f * scale, 3f * scale))
                g.fill(RoundRectangle2D.Float(x, y, s, 4f * scale, 3f * scale, 3f * scale))
            } else {
                // A short vertical bar like an event block's colour bar, then the text.
                g.fill(RoundRectangle2D.Float(0f, 5f * scale, 2.5f * scale, 12f * scale, 2f * scale, 2f * scale))
                g.font = font
                val fm = g.fontMetrics
                g.drawString(label, 5 * scale, (h - fm.height) / 2 + fm.ascent)
            }
            g.dispose()
            return img
        }
        return BaseMultiResolutionImage(draw(1), draw(2))
    }

    private fun scaled(src: BufferedImage, size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, size, size, null)
        g.dispose()
        return img
    }

    private fun blank(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
}
