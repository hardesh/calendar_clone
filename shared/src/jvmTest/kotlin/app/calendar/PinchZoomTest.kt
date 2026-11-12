package app.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two-finger pinch on the phone layout should change the hour height. */
@OptIn(ExperimentalTestApi::class)
class PinchZoomTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val now = LocalDateTime(2026, 11, 11, 10, 0).toInstant(zone)

    @Test
    fun pinchOutZoomsIn() = runComposeUiTest {
        val settings = AppSettings(InMemoryStore()).apply {
            zones = listOf(ZoneEntry("Asia/Kolkata")); demoEnabled = true; viewMode = ViewMode.THREE_DAY
        }
        val c = CalendarController(listOf(DemoProvider({ zone }, latencyMs = 0)), settings, CoroutineScope(Dispatchers.Unconfined), clock = { now })
        runBlocking { c.reloadAccounts() }
        c.ensureLoaded()
        setContent { Box(Modifier.size(400.dp, 800.dp)) { AppTheme(dark = false) { CalendarScreen(c) } } }
        waitForIdle()
        val before = onAllNodesWithTag("event:Lunch", useUnmergedTree = true).fetchSemanticsNodes().first().size.height
        onNodeWithTag("time-grid").performTouchInput {
            pinch(
                start0 = center + Offset(0f, -40f), end0 = center + Offset(0f, -160f),
                start1 = center + Offset(0f, 40f), end1 = center + Offset(0f, 160f),
            )
        }
        waitForIdle()
        val h = assertNotNull(c.hourHeight, "pinch didn't change zoom")
        assertTrue(h > c.defaultHourHeight(compact = true), "zoomed to $h")
        val after = onAllNodesWithTag("event:Lunch", useUnmergedTree = true).fetchSemanticsNodes().first().size.height
        assertTrue(after > before * 1.2f, "event block grew on screen: $before -> $after (zoom $h)")
    }
}
