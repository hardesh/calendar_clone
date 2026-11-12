package app.calendar.desktop

import app.calendar.model.UpcomingEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class MenuBarTextTest {
    private val now = Instant.parse("2026-11-12T02:35:00Z")
    private fun ev(title: String, startIn: kotlin.time.Duration, length: kotlin.time.Duration = 1.hours) =
        UpcomingEvent("a|c|$title", title, now + startIn, now + startIn + length, 0xFF2F80EDL)

    @Test
    fun nextEventWithRelativeTime() {
        assertEquals("Software Roadmap · in 1h 25m", MenuBarText.label(listOf(ev("Software Roadmap", 85.minutes)), now))
        assertEquals("Standup · in 5m", MenuBarText.label(listOf(ev("Lunch", 3.hours), ev("Standup", 5.minutes)), now))
        assertEquals("Sync · in 2h", MenuBarText.label(listOf(ev("Sync", 2.hours)), now))
    }

    @Test
    fun justStartedShowsNowThenMovesOn() {
        assertEquals("Standup · now", MenuBarText.label(listOf(ev("Standup", (-5).minutes)), now))
        // Started 40 minutes ago: show what's next instead.
        assertEquals("Review · in 20m", MenuBarText.label(listOf(ev("Standup", (-40).minutes), ev("Review", 20.minutes)), now))
    }

    @Test
    fun nothingSoonShowsIconOnly() {
        assertNull(MenuBarText.label(emptyList(), now))
        assertNull(MenuBarText.label(listOf(ev("Tomorrow", 13.hours)), now))
    }

    @Test
    fun longTitlesAreShortened() {
        val label = MenuBarText.label(listOf(ev("Quarterly planning with the whole product team", 10.minutes)), now)!!
        assertEquals("Quarterly planning with t… · in 10m", label)
    }
}
