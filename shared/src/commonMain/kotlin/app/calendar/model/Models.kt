package app.calendar.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Account(
    val id: String,
    val email: String,
    val name: String? = null,
    val pictureUrl: String? = null,
    /** Which provider owns this account: "google" or "demo". */
    val provider: String,
)

data class CalendarInfo(
    val accountId: String,
    val id: String,
    val name: String,
    /** ARGB colour. */
    val color: Long,
    val readOnly: Boolean,
    val primary: Boolean,
    /** Google's own "show this calendar" flag; used as the initial visibility. */
    val selectedByDefault: Boolean = true,
) {
    val key: String get() = calendarKey(accountId, id)
}

fun calendarKey(accountId: String, calendarId: String) = "$accountId|$calendarId"

sealed interface EventTiming {
    data class Timed(val start: Instant, val end: Instant) : EventTiming

    /** [endExclusive] is the day after the last day, as in Google's API. */
    data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : EventTiming
}

data class CalEvent(
    val accountId: String,
    val calendarId: String,
    val id: String,
    val title: String,
    val timing: EventTiming,
    val location: String? = null,
    val description: String? = null,
    val recurringEventId: String? = null,
    /** ARGB colour set on the event itself; null means "use the calendar colour". */
    val colorOverride: Long? = null,
) {
    val key: String get() = "$accountId|$calendarId|$id"
    val calendarKey: String get() = calendarKey(accountId, calendarId)
    val isAllDay: Boolean get() = timing is EventTiming.AllDay

    fun startIn(zone: TimeZone): Instant = when (val t = timing) {
        is EventTiming.Timed -> t.start
        is EventTiming.AllDay -> t.start.atStartOfDayIn(zone)
    }

    fun endIn(zone: TimeZone): Instant = when (val t = timing) {
        is EventTiming.Timed -> t.end
        is EventTiming.AllDay -> t.endExclusive.atStartOfDayIn(zone)
    }
}

enum class ViewMode(val dayCount: Int, val label: String, val shortLabel: String) {
    DAY(1, "Day", "1D"),
    THREE_DAY(3, "3 days", "3D"),
    WEEK(7, "Week", "7D"),
    MONTH(0, "Month", "M"),
}

@Serializable
data class ZoneEntry(val id: String, val label: String? = null)

/** The fields the event editor works with; used for create and update. */
data class EventFields(
    val title: String,
    val timing: EventTiming,
    val location: String? = null,
    val description: String? = null,
)

fun CalEvent.fields() = EventFields(title, timing, location, description)

fun CalEvent.withFields(f: EventFields) =
    copy(title = f.title, timing = f.timing, location = f.location, description = f.description)
