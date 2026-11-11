package app.calendar

import app.calendar.logic.MINUTES_PER_DAY
import app.calendar.logic.Placement
import app.calendar.logic.absMinuteOf
import app.calendar.logic.dateSpan
import app.calendar.logic.formatTime
import app.calendar.logic.formatTimeRange
import app.calendar.logic.hourLabels
import app.calendar.logic.instantAt
import app.calendar.logic.layoutColumns
import app.calendar.logic.layoutLanes
import app.calendar.logic.monthGridStart
import app.calendar.logic.offsetLabel
import app.calendar.logic.pageAnchor
import app.calendar.logic.segmentsFor
import app.calendar.logic.shiftByDays
import app.calendar.logic.showsInAllDayRow
import app.calendar.logic.snap
import app.calendar.logic.snapDown
import app.calendar.logic.visibleDays
import app.calendar.model.CalEvent
import app.calendar.model.EventTiming
import app.calendar.model.ViewMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class TimeMathTest {
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val ny = TimeZone.of("America/New_York")

    private fun timed(start: LocalDateTime, end: LocalDateTime, zone: TimeZone) =
        CalEvent("a", "c", "e", "t", EventTiming.Timed(start.toInstant(zone), end.toInstant(zone)))

    @Test
    fun snapping() {
        assertEquals(0, snap(7))
        assertEquals(15, snap(8))
        assertEquals(60, snap(67))
        assertEquals(45, snapDown(59))
        assertEquals(-15, snapDown(-1))
        assertEquals(30, snapDown(59, 30))
    }

    @Test
    fun visibleRanges() {
        val wed = LocalDate(2026, 11, 11)
        assertEquals(listOf(wed), visibleDays(ViewMode.DAY, wed, DayOfWeek.SUNDAY))
        assertEquals(LocalDate(2026, 11, 13), visibleDays(ViewMode.THREE_DAY, wed, DayOfWeek.SUNDAY).last())
        val month = visibleDays(ViewMode.MONTH, wed, DayOfWeek.SUNDAY)
        assertEquals(42, month.size)
        assertEquals(LocalDate(2026, 11, 1), month.first()) // Nov 1 2026 is a Sunday
        assertEquals(LocalDate(2026, 10, 26), monthGridStart(wed, DayOfWeek.MONDAY))
        assertEquals(LocalDate(2026, 12, 1), pageAnchor(ViewMode.MONTH, LocalDate(2026, 11, 30), 1))
        assertEquals(LocalDate(2026, 11, 14), pageAnchor(ViewMode.THREE_DAY, wed, 1))
    }

    @Test
    fun absoluteMinutesRoundTrip() {
        val origin = LocalDate(2026, 11, 11)
        val i = instantAt(origin, MINUTES_PER_DAY + 90, kolkata)
        assertEquals(LocalDateTime(2026, 11, 12, 1, 30), i.toLocalDateTime(kolkata))
        assertEquals(MINUTES_PER_DAY + 90, absMinuteOf(i, origin, kolkata))
        assertEquals(LocalDateTime(2026, 11, 10, 23, 0), instantAt(origin, -60, kolkata).toLocalDateTime(kolkata))
    }

    @Test
    fun overnightEventSplitsIntoTwoSegments() {
        val days = listOf(LocalDate(2026, 11, 11), LocalDate(2026, 11, 12))
        val e = timed(LocalDateTime(2026, 11, 11, 22, 30), LocalDateTime(2026, 11, 12, 1, 0), kolkata)
        val segs = segmentsFor(e, days, kolkata)
        assertEquals(2, segs.size)
        assertEquals(22 * 60 + 30, segs[0].startMinute)
        assertEquals(MINUTES_PER_DAY, segs[0].endMinute)
        assertTrue(segs[0].continuesAfter)
        assertEquals(0, segs[1].startMinute)
        assertEquals(60, segs[1].endMinute)
        assertTrue(segs[1].continuesBefore)
        assertFalse(showsInAllDayRow(e))
    }

    @Test
    fun eventEndingAtMidnightCoversOneDay() {
        val e = timed(LocalDateTime(2026, 11, 11, 23, 0), LocalDateTime(2026, 11, 12, 0, 0), kolkata)
        assertEquals(LocalDate(2026, 11, 11) to LocalDate(2026, 11, 11), dateSpan(e, kolkata))
        assertEquals(1, segmentsFor(e, listOf(LocalDate(2026, 11, 11), LocalDate(2026, 11, 12)), kolkata).size)
    }

    @Test
    fun shiftKeepsWallClockAcrossDst() {
        // US DST ends on 2026-11-01; moving a 9 AM event across it should stay at 9 AM.
        val e = timed(LocalDateTime(2026, 10, 30, 9, 0), LocalDateTime(2026, 10, 30, 10, 0), ny)
        val moved = shiftByDays(e.timing, 3, ny) as EventTiming.Timed
        assertEquals(LocalDateTime(2026, 11, 2, 9, 0), moved.start.toLocalDateTime(ny))
        val allDay = shiftByDays(EventTiming.AllDay(LocalDate(2026, 11, 11), LocalDate(2026, 11, 13)), -1, ny)
        assertEquals(EventTiming.AllDay(LocalDate(2026, 11, 10), LocalDate(2026, 11, 12)), allDay)
    }

    @Test
    fun longTimedEventsGoToAllDayRow() {
        val start = LocalDateTime(2026, 11, 11, 9, 0).toInstant(kolkata)
        assertTrue(showsInAllDayRow(CalEvent("a", "c", "e", "t", EventTiming.Timed(start, start + 24.hours))))
        assertFalse(showsInAllDayRow(CalEvent("a", "c", "e", "t", EventTiming.Timed(start, start + 23.hours))))
    }

    @Test
    fun timeFormatting() {
        assertEquals("12 AM", formatTime(0, compact = true))
        assertEquals("3 PM", formatTime(15 * 60, compact = true))
        assertEquals("3:30", formatTime(15 * 60 + 30, compact = true))
        assertEquals("9:30 AM", formatTime(9 * 60 + 30))
        assertEquals("10:30 – 11:30 AM", formatTimeRange(10 * 60 + 30, 11 * 60 + 30))
        assertEquals("11:00 AM – 1:00 PM", formatTimeRange(11 * 60, 13 * 60))
    }
}

class EventLayoutTest {
    @Test
    fun nonOverlappingEventsGetFullWidth() {
        val p = layoutColumns(listOf(0..60, 60..120))
        assertEquals(listOf(Placement(0, 1), Placement(0, 1)), p)
    }

    @Test
    fun overlappingEventsShareColumns() {
        // A 9-10:30, B 9:30-10, C 10-11, D 12-13
        val p = layoutColumns(listOf(540..630, 570..600, 600..660, 720..780))
        assertEquals(Placement(0, 2), p[0])
        assertEquals(Placement(1, 2), p[1])
        assertEquals(Placement(1, 2), p[2]) // reuses B's column once B ends
        assertEquals(Placement(0, 1), p[3])
    }

    @Test
    fun threeWayOverlap() {
        val p = layoutColumns(listOf(0..100, 10..100, 20..100))
        assertEquals(setOf(0, 1, 2), p.map { it.column }.toSet())
        assertTrue(p.all { it.columnCount == 3 })
    }

    @Test
    fun lanesForMultiDayBars() {
        val lanes = layoutLanes(listOf(0..2, 1..1, 3..4, 2..3))
        assertEquals(0, lanes[0])
        assertEquals(1, lanes[1])
        assertEquals(0, lanes[2])
        assertEquals(1, lanes[3])
    }
}

class TimeZonesTest {
    @Test
    fun halfHourZoneLabels() {
        val day = LocalDate(2026, 11, 11)
        val labels = hourLabels(day, TimeZone.of("America/Los_Angeles"), TimeZone.of("Asia/Kolkata"))
        // 9 AM PST = 10:30 PM IST
        assertEquals("10:30", labels[9])
        val nepal = hourLabels(day, TimeZone.UTC, TimeZone.of("Asia/Kathmandu"))
        assertEquals("5:45", nepal[0])
    }

    @Test
    fun offsets() {
        val now = LocalDateTime(2026, 11, 11, 12, 0).toInstant(TimeZone.UTC)
        assertEquals("GMT+5:30", offsetLabel(TimeZone.of("Asia/Kolkata"), now))
        assertEquals("GMT-8", offsetLabel(TimeZone.of("America/Los_Angeles"), now))
        assertEquals("GMT", offsetLabel(TimeZone.UTC, now))
    }

    @Test
    fun labelsFollowDstPerDay() {
        val ny = TimeZone.of("America/New_York")
        val utc = TimeZone.UTC
        // Noon UTC is 8 AM EDT before Nov 1 2026, 7 AM EST after.
        assertEquals("8 AM", hourLabels(LocalDate(2026, 10, 30), utc, ny)[12])
        assertEquals("7 AM", hourLabels(LocalDate(2026, 11, 2), utc, ny)[12])
    }
}
