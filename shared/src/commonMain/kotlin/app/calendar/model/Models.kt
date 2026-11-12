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
    /** The calendar's default notification times (minutes before), used by events with "default" reminders. */
    val defaultReminders: List<Int> = emptyList(),
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
    /** Google event colour id ("1".."11") set on the event itself; null means "use the calendar colour". */
    val colorId: String? = null,
    /** Guests, including you if you were invited (no guests = an event just for you). */
    val attendees: List<Attendee> = emptyList(),
    /** Video call to join (Google Meet, or Zoom/Teams added through Google). */
    val conference: Conference? = null,
    /** This event's reminders; null when not known (e.g. demo data). */
    val reminders: EventReminders? = null,
    /** False when someone else organises this event; then only your reply, colour and reminders can change. */
    val organizerSelf: Boolean = true,
    /** Editor request: create a Google Meet link when saving. */
    val requestMeet: Boolean = false,
    /** Editor choice: have Google email guests about this change. */
    val notifyGuests: Boolean = false,
) {
    /** ARGB colour from [colorId], or null to use the calendar's colour. */
    val colorOverride: Long? get() = EventColors.argbFor(colorId)

    /** You, if you are a guest on this event. */
    val selfAttendee: Attendee? get() = attendees.firstOrNull { it.self }

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

data class Attendee(
    val email: String,
    val name: String? = null,
    /** Google's responseStatus: "accepted", "declined", "tentative" or "needsAction". */
    val status: String = "needsAction",
    val organizer: Boolean = false,
    val self: Boolean = false,
    val optional: Boolean = false,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: email
}

data class Conference(val name: String, val url: String)

/** Notifications for one event: the calendar's defaults, or its own list of minutes before. */
data class EventReminders(val useDefault: Boolean, val minutes: List<Int> = emptyList())

/** A timed event coming up soon, for the Mac menu bar and phone reminders. */
data class UpcomingEvent(
    val key: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    /** ARGB colour as shown in the app. */
    val color: Long,
    val location: String? = null,
    /** When Google would notify for this event (minutes before), from its own or its calendar's reminders. */
    val reminderMinutes: List<Int> = emptyList(),
    val conferenceUrl: String? = null,
)

/** The fields the event editor works with; used for create and update. */
data class EventFields(
    val title: String,
    val timing: EventTiming,
    val location: String? = null,
    val description: String? = null,
    val colorId: String? = null,
    /** Full guest list to save, or null to leave guests as they are. */
    val attendees: List<Attendee>? = null,
    /** Reminders to save, or null to leave them as they are. */
    val reminders: EventReminders? = null,
    /** Create a Google Meet link. */
    val requestMeet: Boolean = false,
    /** Have Google email guests about this change. */
    val notifyGuests: Boolean = false,
)

/** Everything this event says, for creating it. */
fun CalEvent.fields() = fieldsComparedTo(null)

/**
 * The fields to send for turning [before] into this event: guests and reminders are only
 * sent when they changed, so drags never rewrite them.
 */
fun CalEvent.fieldsComparedTo(before: CalEvent?) = EventFields(
    title = title,
    timing = timing,
    location = location,
    description = description,
    colorId = colorId,
    attendees = if (before == null) attendees.ifEmpty { null } else attendees.takeIf { it != before.attendees },
    reminders = if (before == null) reminders else reminders.takeIf { it != before.reminders },
    requestMeet = requestMeet && conference == null,
    notifyGuests = notifyGuests,
)

fun CalEvent.withFields(f: EventFields) = copy(
    title = f.title, timing = f.timing, location = f.location, description = f.description, colorId = f.colorId,
    attendees = f.attendees ?: attendees,
    reminders = f.reminders ?: reminders,
    requestMeet = false,
    notifyGuests = false,
)

/** A meeting link found in the event's location or description (Zoom, Meet, Teams, Webex…). */
fun CalEvent.meetingLink(): Conference? {
    conference?.let { return it }
    val text = listOfNotNull(location, description).joinToString(" ")
    val match = Regex("https://[\\w.-]*(zoom\\.us|meet\\.google\\.com|teams\\.microsoft\\.com|teams\\.live\\.com|webex\\.com|whereby\\.com|around\\.co)/[^\\s\"<>)]+")
        .find(text) ?: return null
    val name = when {
        "zoom" in match.groupValues[1] -> "Zoom"
        "meet.google" in match.groupValues[1] -> "Google Meet"
        "teams" in match.groupValues[1] -> "Microsoft Teams"
        "webex" in match.groupValues[1] -> "Webex"
        else -> "video call"
    }
    return Conference(name, match.value)
}

/** Google Calendar's fixed event colours, with the names Google Calendar shows. */
object EventColors {
    data class EventColor(val id: String, val name: String, val argb: Long)

    val all = listOf(
        EventColor("11", "Tomato", 0xFFD50000L),
        EventColor("4", "Flamingo", 0xFFE67C73L),
        EventColor("6", "Tangerine", 0xFFF4511EL),
        EventColor("5", "Banana", 0xFFF6BF26L),
        EventColor("2", "Sage", 0xFF33B679L),
        EventColor("10", "Basil", 0xFF0B8043L),
        EventColor("7", "Peacock", 0xFF039BE5L),
        EventColor("9", "Blueberry", 0xFF3F51B5L),
        EventColor("1", "Lavender", 0xFF7986CBL),
        EventColor("3", "Grape", 0xFF8E24AAL),
        EventColor("8", "Graphite", 0xFF616161L),
    )

    fun argbFor(id: String?): Long? = id?.let { i -> all.firstOrNull { it.id == i }?.argb }
}
