package app.calendar

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.calendar.data.DemoProvider
import app.calendar.model.ViewMode
import app.calendar.model.ZoneEntry
import app.calendar.platform.InMemoryStore
import app.calendar.state.AppSettings
import app.calendar.state.CalendarController
import app.calendar.ui.CalendarScreen
import app.calendar.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.io.File
import kotlin.test.Test

/**
 * Renders the app offscreen with demo data to PNGs under shared/build/screenshots,
 * for checking the look without a display. Not an assertion test.
 */
class ScreenshotTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val fixedNow = LocalDateTime(2026, 11, 11, 10, 42).toInstant(zone)

    private fun controller(mode: ViewMode): CalendarController {
        val store = InMemoryStore()
        val settings = AppSettings(store).apply {
            zones = listOf(ZoneEntry("Asia/Kolkata"), ZoneEntry("America/Los_Angeles"))
            demoEnabled = true
            viewMode = mode
        }
        lateinit var c: CalendarController
        val demo = DemoProvider(zone = { zone }, latencyMs = 0)
        c = CalendarController(listOf(demo), settings, CoroutineScope(Dispatchers.Unconfined), clock = { fixedNow })
        kotlinx.coroutines.runBlocking { c.reloadAccounts() }
        c.ensureLoaded()
        return c
    }

    private fun shoot(name: String, mode: ViewMode, width: Int, height: Int, dark: Boolean, density: Float = 1f, editor: Boolean = false, zoom: Float? = null, text: Float? = null) {
        val c = controller(mode)
        zoom?.let { c.setZoom(it) }
        text?.let { c.changeTextScale(it) }
        if (editor) c.openEditor(c.eventsBetween(c.today(), c.today().let { kotlinx.datetime.LocalDate.fromEpochDays(it.toEpochDays() + 1) }).first { it.title == "Lunch" }).also {
            c.updateDraft { d ->
                d.copy(
                    attendees = listOf(
                        app.calendar.model.Attendee("me@example.com", "You", "accepted", organizer = true, self = true),
                        app.calendar.model.Attendee("sam@example.com", "Sam Lee", "accepted"),
                        app.calendar.model.Attendee("priya@example.com", "Priya", "tentative"),
                        app.calendar.model.Attendee("alex@example.com"),
                    ),
                    conference = app.calendar.model.Conference("Google Meet", "https://meet.google.com/abc-defg-hij"),
                    reminders = app.calendar.model.EventReminders(false, listOf(10, 60)),
                )
            }
        }
        ImageComposeScene(width, height, Density(density)) {
            AppTheme(dark = dark) { CalendarScreen(c) }
        }.use { scene ->
            scene.render(0)
            scene.render(16_000_000)
            scene.render(400_000_000)
            val img = scene.render(1_000_000_000)
            val out = File("build/screenshots/$name.png")
            out.parentFile.mkdirs()
            out.writeBytes(img.encodeToData()!!.bytes)
        }
    }

    @Test
    fun renderScreens() {
        shoot("desktop-week-light", ViewMode.WEEK, 1280, 840, dark = false)
        shoot("desktop-week-dark", ViewMode.WEEK, 1280, 840, dark = true)
        shoot("desktop-day-light", ViewMode.DAY, 1280, 840, dark = false)
        shoot("desktop-week-zoom150", ViewMode.WEEK, 1280, 840, dark = false, zoom = 72f)
        shoot("desktop-week-text-larger", ViewMode.WEEK, 1280, 840, dark = false, text = 1.3f)
        shoot("phone-3day-text-larger", ViewMode.THREE_DAY, 412 * 2, 900 * 2, dark = false, density = 2f, text = 1.3f)
        shoot("desktop-editor-light", ViewMode.WEEK, 1280, 1000, dark = false, editor = true)
        shoot("desktop-month-light", ViewMode.MONTH, 1280, 840, dark = false)
        shoot("desktop-month-dark", ViewMode.MONTH, 1280, 840, dark = true)
        shoot("phone-3day-dark", ViewMode.THREE_DAY, 412 * 2, 900 * 2, dark = true, density = 2f)
        shoot("phone-month-light", ViewMode.MONTH, 412 * 2, 900 * 2, dark = false, density = 2f)
    }
}
