package app.calendar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.calendar.data.DemoProvider
import app.calendar.model.EventTiming
import app.calendar.model.ViewMode
import app.calendar.model.ZoneEntry
import app.calendar.platform.InMemoryStore
import app.calendar.state.AppSettings
import app.calendar.state.CalendarController
import app.calendar.ui.CalendarScreen
import app.calendar.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Drives the real day view with a simulated mouse, as on the Mac. */
@OptIn(ExperimentalTestApi::class)
class MouseDragTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val now = LocalDateTime(2026, 11, 11, 10, 0).toInstant(zone)
    private val day = LocalDate(2026, 11, 11)

    private fun controller(): CalendarController {
        val settings = AppSettings(InMemoryStore()).apply {
            zones = listOf(ZoneEntry("Asia/Kolkata"))
            demoEnabled = true
            viewMode = ViewMode.DAY
        }
        val c = CalendarController(listOf(DemoProvider({ zone }, latencyMs = 0)), settings, CoroutineScope(Dispatchers.Unconfined), clock = { now })
        runBlocking { c.reloadAccounts() }
        c.ensureLoaded()
        return c
    }

    private fun CalendarController.lunch() = eventsBetween(day, LocalDate(2026, 11, 12)).first { it.title == "Lunch" }

    private fun localTime(i: kotlin.time.Instant) = i.toLocalDateTime(zone).time.let { it.hour * 60 + it.minute }

    @Test
    fun dragBodyMovesEventByOneHour() = runComposeUiTest {
        val c = controller()
        setContent { AppTheme(dark = false) { CalendarScreen(c) } }
        waitForIdle()
        val hour = with(density) { 48.dp.toPx() }
        onNodeWithTag("event:Lunch").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(0f, hour / 3))
            moveTo(center + Offset(0f, hour))
            release()
        }
        waitForIdle()
        val t = c.lunch().timing as EventTiming.Timed
        assertEquals(13 * 60 + 30, localTime(t.start)) // 12:30 → 1:30 PM
        assertEquals(14 * 60 + 30, localTime(t.end))
    }

    @Test
    fun dragBottomEdgeResizes() = runComposeUiTest {
        val c = controller()
        setContent { AppTheme(dark = false) { CalendarScreen(c) } }
        waitForIdle()
        val hour = with(density) { 48.dp.toPx() }
        onNodeWithTag("event:Lunch").performMouseInput {
            val edge = bottomCenter - Offset(0f, 2f)
            moveTo(edge)
            press()
            moveTo(edge + Offset(0f, hour / 4))
            moveTo(edge + Offset(0f, hour / 2))
            release()
        }
        waitForIdle()
        val t = c.lunch().timing as EventTiming.Timed
        assertEquals(12 * 60 + 30, localTime(t.start))
        assertEquals(14 * 60, localTime(t.end)) // 1:30 → 2:00 PM
    }

    @Test
    fun clickOpensEditor() = runComposeUiTest {
        val c = controller()
        setContent { AppTheme(dark = false) { CalendarScreen(c) } }
        waitForIdle()
        onNodeWithTag("event:Lunch").performMouseInput { click(center) }
        waitForIdle()
        assertEquals("Lunch", assertNotNull(c.editor).draft.title)
    }
}
