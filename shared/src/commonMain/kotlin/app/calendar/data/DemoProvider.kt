package app.calendar.data

import app.calendar.logic.plusDays
import app.calendar.model.Account
import app.calendar.model.CalEvent
import app.calendar.model.CalendarInfo
import app.calendar.model.EventFields
import app.calendar.model.EventTiming
import app.calendar.model.withFields
import kotlinx.coroutines.delay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Instant

/**
 * In-memory calendars with generated events, so the app can be tried (and tested)
 * without a Google account. Events are generated per day the first time a day is
 * requested; edits are kept in memory until the app quits.
 */
class DemoProvider(
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val latencyMs: Long = 150,
) : CalendarProvider {
    override val id = "demo"

    val account = Account(id = "demo", email = "Demo calendar", name = "Demo", provider = "demo")

    private val calendars = listOf(
        CalendarInfo("demo", "work", "Work", 0xFF2F80EDL, readOnly = false, primary = true),
        CalendarInfo("demo", "personal", "Personal", 0xFF27AE60L, readOnly = false, primary = false),
        CalendarInfo("demo", "holidays", "Holidays", 0xFFF2994AL, readOnly = true, primary = false),
    )

    private val events = LinkedHashMap<String, CalEvent>()
    private val generatedDays = HashSet<LocalDate>()
    private var nextId = 1

    /** Test hook: when set, the next mutation fails with this message. */
    var failNextMutation: String? = null

    override fun accounts() = listOf(account)
    override suspend fun addAccount() = account
    override suspend fun removeAccount(account: Account) {}
    override suspend fun calendars(account: Account) = calendars

    override suspend fun events(calendar: CalendarInfo, from: Instant, to: Instant): List<CalEvent> {
        delay(latencyMs)
        val z = zone()
        var d = from.toLocalDateTime(z).date.plusDays(-1)
        val last = to.toLocalDateTime(z).date
        while (d <= last) {
            if (generatedDays.add(d)) generate(d, z)
            d = d.plusDays(1)
        }
        return events.values.filter { e ->
            e.calendarId == calendar.id && e.startIn(z) < to && e.endIn(z) > from
        }
    }

    override suspend fun create(calendar: CalendarInfo, fields: EventFields): CalEvent {
        mutation()
        val e = CalEvent("demo", calendar.id, "d${nextId++}", fields.title, fields.timing, fields.location, fields.description)
        events[e.id] = e
        return e
    }

    override suspend fun update(original: CalEvent, fields: EventFields): CalEvent {
        mutation()
        val updated = original.withFields(fields)
        events[original.id] = updated
        return updated
    }

    override suspend fun moveToCalendar(event: CalEvent, target: CalendarInfo): CalEvent {
        mutation()
        val moved = event.copy(calendarId = target.id)
        events[event.id] = moved
        return moved
    }

    override suspend fun delete(event: CalEvent) {
        mutation()
        events.remove(event.id)
    }

    private suspend fun mutation() {
        delay(latencyMs)
        failNextMutation?.let {
            failNextMutation = null
            throw CalendarApiException(500, it)
        }
    }

    private fun timed(cal: String, title: String, date: LocalDate, h: Int, m: Int, durationMin: Int, z: TimeZone, location: String? = null) {
        val start = LocalDateTime(date, LocalTime(h, m)).toInstant(z)
        val e = CalEvent("demo", cal, "d${nextId++}", title, EventTiming.Timed(start, start + durationMin.minutesDuration()), location = location)
        events[e.id] = e
    }

    private fun allDay(cal: String, title: String, date: LocalDate, days: Int = 1) {
        val e = CalEvent("demo", cal, "d${nextId++}", title, EventTiming.AllDay(date, date.plusDays(days)))
        events[e.id] = e
    }

    private fun generate(date: LocalDate, z: TimeZone) {
        val rnd = Random(date.toEpochDays())
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        if (!weekend) {
            timed("work", "Standup", date, 9, 30, 15, z, "Zoom")
            if (rnd.nextInt(3) != 0) timed("work", listOf("Design review", "1:1 with Sam", "Roadmap sync", "Customer call").random(rnd), date, 10 + rnd.nextInt(2), if (rnd.nextBoolean()) 0 else 30, 60, z)
            timed("personal", "Lunch", date, 12, 30, 60, z)
            if (rnd.nextInt(2) == 0) {
                // Two overlapping meetings to show side-by-side layout.
                timed("work", "Planning", date, 14, 0, 90, z)
                timed("work", "Interview", date, 14, 30, 60, z, "Room 4")
            } else {
                timed("work", "Focus time", date, 14, 0, 120, z)
            }
            if (rnd.nextInt(4) == 0) timed("personal", "Gym", date, 18, 0, 60, z)
        } else {
            if (rnd.nextBoolean()) timed("personal", "Brunch", date, 11, 0, 90, z, "Café")
            if (rnd.nextInt(3) == 0) timed("personal", "Movie night", date, 22, 30, 150, z) // crosses midnight
        }
        if (date.dayOfWeek == DayOfWeek.MONDAY && rnd.nextInt(2) == 0) allDay("work", "Offsite", date, 2)
        if (date.day == 1) allDay("holidays", "Month start", date)
        if (date.day == 15) allDay("personal", "Pay rent", date)
    }
}

private fun Int.minutesDuration() = kotlin.time.Duration.parse("${this}m")
