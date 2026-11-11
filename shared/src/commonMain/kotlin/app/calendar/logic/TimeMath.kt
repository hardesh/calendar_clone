package app.calendar.logic

import app.calendar.model.CalEvent
import app.calendar.model.EventTiming
import app.calendar.model.ViewMode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

const val MINUTES_PER_DAY = 24 * 60
const val SNAP_MINUTES = 15

fun LocalDate.plusDays(n: Int): LocalDate = plus(n, DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Int): LocalDate = minus(n, DateTimeUnit.DAY)

fun startOfWeek(date: LocalDate, weekStart: DayOfWeek): LocalDate {
    val diff = (date.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7
    return date.minusDays(diff)
}

/** The 6-week block shown by the month view for the month containing [date]. */
fun monthGridStart(date: LocalDate, weekStart: DayOfWeek): LocalDate =
    startOfWeek(LocalDate(date.year, date.month, 1), weekStart)

const val MONTH_GRID_DAYS = 42

fun visibleDays(mode: ViewMode, anchor: LocalDate, weekStart: DayOfWeek): List<LocalDate> = when (mode) {
    ViewMode.DAY, ViewMode.THREE_DAY -> List(mode.dayCount) { anchor.plusDays(it) }
    ViewMode.MONTH -> {
        val start = monthGridStart(anchor, weekStart)
        List(MONTH_GRID_DAYS) { start.plusDays(it) }
    }
}

fun pageAnchor(mode: ViewMode, anchor: LocalDate, direction: Int): LocalDate = when (mode) {
    ViewMode.DAY -> anchor.plusDays(direction)
    ViewMode.THREE_DAY -> anchor.plusDays(3 * direction)
    ViewMode.MONTH -> {
        val first = LocalDate(anchor.year, anchor.month, 1)
        first.plus(direction, DateTimeUnit.MONTH)
    }
}

fun snap(minutes: Int, step: Int = SNAP_MINUTES): Int = (minutes + step / 2).floorDiv(step) * step
fun snapDown(minutes: Int, step: Int = SNAP_MINUTES): Int = (minutes).floorDiv(step) * step

/** Local minute of day (0..1439) of [instant] in [zone]. */
fun minuteOfDay(instant: Instant, zone: TimeZone): Int {
    val t = instant.toLocalDateTime(zone).time
    return t.hour * 60 + t.minute
}

/**
 * Converts an "absolute minute" relative to midnight of [origin] into an instant.
 * Minutes beyond a day (or negative) roll over into following (or previous) days,
 * so 1440 means midnight at the start of origin+1.
 */
fun instantAt(origin: LocalDate, absMinute: Int, zone: TimeZone): Instant {
    val dayOffset = (absMinute).floorDiv(MINUTES_PER_DAY)
    val minute = (absMinute).mod(MINUTES_PER_DAY)
    val date = origin.plusDays(dayOffset)
    return LocalDateTime(date, LocalTime(minute / 60, minute % 60)).toInstant(zone)
}

/** Inverse of [instantAt]: minutes from midnight of [origin] using local wall-clock time. */
fun absMinuteOf(instant: Instant, origin: LocalDate, zone: TimeZone): Int {
    val ldt = instant.toLocalDateTime(zone)
    return origin.daysUntil(ldt.date) * MINUTES_PER_DAY + ldt.time.hour * 60 + ldt.time.minute
}

/** A piece of a timed event that falls on one visible day. Minutes are 0..1440. */
data class DaySegment(
    val dayIndex: Int,
    val startMinute: Int,
    val endMinute: Int,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
)

fun segmentsForRange(
    startAbs: Int,
    endAbs: Int,
    dayCount: Int,
): List<DaySegment> {
    val out = ArrayList<DaySegment>()
    for (i in 0 until dayCount) {
        val dayStart = i * MINUTES_PER_DAY
        val dayEnd = dayStart + MINUTES_PER_DAY
        val s = maxOf(startAbs, dayStart)
        val e = minOf(endAbs, dayEnd)
        if (e > s || (startAbs == endAbs && startAbs in dayStart until dayEnd)) {
            out += DaySegment(i, s - dayStart, maxOf(e, s) - dayStart, (startAbs < dayStart), (endAbs > dayEnd))
        }
    }
    return out
}

fun segmentsFor(event: CalEvent, days: List<LocalDate>, zone: TimeZone): List<DaySegment> {
    if (days.isEmpty()) return emptyList()
    val t = event.timing as? EventTiming.Timed ?: return emptyList()
    val origin = days.first()
    val visibleEnd = days.last().plusDays(1).atStartOfDayIn(zone)
    if (t.end <= origin.atStartOfDayIn(zone) && t.end != t.start) return emptyList()
    if (t.start >= visibleEnd) return emptyList()
    return segmentsForRange(absMinuteOf(t.start, origin, zone), absMinuteOf(t.end, origin, zone), days.size)
}

/**
 * Timed events of 24 hours or more are shown in the all-day row, as Notion and
 * Google Calendar do.
 */
fun showsInAllDayRow(event: CalEvent): Boolean = when (val t = event.timing) {
    is EventTiming.AllDay -> true
    is EventTiming.Timed -> (t.end - t.start).inWholeMinutes >= MINUTES_PER_DAY
}

/** First and last (inclusive) local dates an event covers. */
fun dateSpan(event: CalEvent, zone: TimeZone): Pair<LocalDate, LocalDate> = when (val t = event.timing) {
    is EventTiming.AllDay -> t.start to maxOf(t.start, t.endExclusive.minusDays(1))
    is EventTiming.Timed -> {
        val s = t.start.toLocalDateTime(zone).date
        val endLdt = t.end.toLocalDateTime(zone)
        // An event ending exactly at midnight does not cover the next day.
        val e = if (t.end > t.start && endLdt.time == LocalTime(0, 0)) endLdt.date.minusDays(1) else endLdt.date
        s to maxOf(s, e)
    }
}

/** Moves an event by whole days, keeping its local wall-clock times. */
fun shiftByDays(timing: EventTiming, days: Int, zone: TimeZone): EventTiming = when (timing) {
    is EventTiming.AllDay -> EventTiming.AllDay(timing.start.plusDays(days), timing.endExclusive.plusDays(days))
    is EventTiming.Timed -> {
        val s = timing.start.toLocalDateTime(zone)
        val e = timing.end.toLocalDateTime(zone)
        EventTiming.Timed(
            LocalDateTime(s.date.plusDays(days), s.time).toInstant(zone),
            LocalDateTime(e.date.plusDays(days), e.time).toInstant(zone),
        )
    }
}

fun formatTime(minuteOfDay: Int, compact: Boolean = false): String {
    val m = (minuteOfDay).mod(MINUTES_PER_DAY)
    val h = m / 60
    val min = m % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val ampm = if (h < 12) "AM" else "PM"
    return when {
        min == 0 && compact -> "$h12 $ampm"
        compact -> "$h12:${min.toString().padStart(2, '0')}"
        else -> "$h12:${min.toString().padStart(2, '0')} $ampm"
    }
}

/** "9:30am", "11am" — Notion's compact style for month chips. */
fun formatShort(minuteOfDay: Int): String {
    val m = minuteOfDay.mod(MINUTES_PER_DAY)
    val h = m / 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val suffix = if (h < 12) "am" else "pm"
    return if (m % 60 == 0) "$h12$suffix" else "$h12:${(m % 60).toString().padStart(2, '0')}$suffix"
}

fun formatTimeRange(startMinute: Int, endMinute: Int): String {
    val sameHalf = ((startMinute).mod(MINUTES_PER_DAY) < 720) ==
        ((endMinute).mod(MINUTES_PER_DAY) < 720)
    val start = formatTime(startMinute)
    val end = formatTime(endMinute)
    return if (sameHalf) "${start.removeSuffix(" AM").removeSuffix(" PM")} – $end" else "$start – $end"
}
