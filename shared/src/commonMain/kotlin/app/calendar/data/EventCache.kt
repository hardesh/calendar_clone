package app.calendar.data

import app.calendar.model.CalEvent
import app.calendar.model.CalendarInfo
import app.calendar.model.EventTiming
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Small named-file storage for cached data; platforms back it with a folder on disk. */
interface FileCache {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
    /** Names of stored files, oldest first. */
    fun list(): List<String>
}

class InMemoryFileCache : FileCache {
    private val map = LinkedHashMap<String, String>()
    override fun read(name: String) = map[name]
    override fun write(name: String, text: String) { map.remove(name); map[name] = text }
    override fun delete(name: String) { map.remove(name) }
    override fun list() = map.keys.toList()
}

@Serializable
internal data class CachedEvent(
    val a: String,
    val c: String,
    val id: String,
    val t: String,
    /** Timed: start/end as epoch milliseconds. */
    val s: Long? = null,
    val e: Long? = null,
    /** All-day: ISO dates, end exclusive. */
    val ds: String? = null,
    val de: String? = null,
    val loc: String? = null,
    val desc: String? = null,
    val rec: String? = null,
    val col: Long? = null,
)

@Serializable
private data class CachedWindow(val v: Int = 1, val savedAt: Long, val events: List<CachedEvent>)

@Serializable
private data class CachedCalendar(
    val a: String,
    val id: String,
    val name: String,
    val color: Long,
    val readOnly: Boolean,
    val primary: Boolean,
    val selected: Boolean,
)

@Serializable
private data class CachedCalendars(val v: Int = 1, val calendars: List<CachedCalendar>)

/**
 * Last-known calendars and events, so the app opens instantly and works offline.
 * One file per 6-week window (as the app loads them) plus one for the calendar list.
 * Only real accounts are cached; demo data is regenerated each run.
 */
class EventCache(private val files: FileCache, private val maxWindows: Int = 36) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun windowName(weekStart: DayOfWeek, start: LocalDate) = "events-${weekStart.name.take(3).lowercase()}-$start.json"

    fun loadWindow(weekStart: DayOfWeek, start: LocalDate): List<CalEvent>? = runCatching {
        val text = files.read(windowName(weekStart, start)) ?: return null
        json.decodeFromString(CachedWindow.serializer(), text).events.mapNotNull { it.toEvent() }
    }.getOrNull()

    fun saveWindow(weekStart: DayOfWeek, start: LocalDate, events: List<CalEvent>, now: Instant) {
        val cached = events.filter { it.accountId != DEMO_ACCOUNT }.map { it.toCached() }
        runCatching {
            files.write(windowName(weekStart, start), json.encodeToString(CachedWindow.serializer(), CachedWindow(savedAt = now.toEpochMilliseconds(), events = cached)))
            prune()
        }
    }

    fun loadCalendars(): List<CalendarInfo> = runCatching {
        val text = files.read(CALENDARS) ?: return emptyList()
        json.decodeFromString(CachedCalendars.serializer(), text).calendars.map {
            CalendarInfo(it.a, it.id, it.name, it.color, it.readOnly, it.primary, it.selected)
        }
    }.getOrDefault(emptyList())

    fun saveCalendars(calendars: List<CalendarInfo>) {
        val list = calendars.filter { it.accountId != DEMO_ACCOUNT }.map {
            CachedCalendar(it.accountId, it.id, it.name, it.color, it.readOnly, it.primary, it.selectedByDefault)
        }
        runCatching { files.write(CALENDARS, json.encodeToString(CachedCalendars.serializer(), CachedCalendars(calendars = list))) }
    }

    /** Changes not yet sent to Google (demo-account changes are not kept across restarts). */
    fun loadOutbox(): List<OutboxOp> = runCatching {
        val text = files.read(OUTBOX) ?: return emptyList()
        json.decodeFromString(CachedOutbox.serializer(), text).ops.mapNotNull { it.toOp() }
    }.getOrDefault(emptyList())

    fun saveOutbox(ops: List<OutboxOp>) {
        val list = ops.filter { (it.before ?: it.after)?.accountId != DEMO_ACCOUNT }.map { it.toCached() }
        runCatching {
            if (list.isEmpty()) files.delete(OUTBOX)
            else files.write(OUTBOX, json.encodeToString(CachedOutbox.serializer(), CachedOutbox(ops = list)))
        }
    }

    /** Forget everything, e.g. after an account is removed. */
    fun clear() {
        files.list().forEach { files.delete(it) }
    }

    private fun prune() {
        val windows = files.list().filter { it.startsWith("events-") }
        if (windows.size > maxWindows) windows.take(windows.size - maxWindows).forEach { files.delete(it) }
    }

    companion object {
        private const val CALENDARS = "calendars.json"
        private const val OUTBOX = "outbox.json"
        private const val DEMO_ACCOUNT = "demo"
    }
}

internal fun CalEvent.toCached(): CachedEvent = when (val t = timing) {
        is EventTiming.Timed -> CachedEvent(accountId, calendarId, id, title, s = t.start.toEpochMilliseconds(), e = t.end.toEpochMilliseconds(),
            loc = location, desc = description, rec = recurringEventId, col = colorOverride)
        is EventTiming.AllDay -> CachedEvent(accountId, calendarId, id, title, ds = t.start.toString(), de = t.endExclusive.toString(),
            loc = location, desc = description, rec = recurringEventId, col = colorOverride)
    }

internal fun CachedEvent.toEvent(): CalEvent? {
        val timing = when {
            s != null && e != null -> EventTiming.Timed(Instant.fromEpochMilliseconds(s), Instant.fromEpochMilliseconds(e))
            ds != null && de != null -> EventTiming.AllDay(LocalDate.parse(ds), LocalDate.parse(de))
            else -> return null
        }
        return CalEvent(a, c, id, t, timing, loc, desc, rec, col)
    }

/**
 * One change waiting to be sent to Google. [before] is the event as Google has it
 * (null for a new event); [after] is what the user wants (null for a delete).
 */
data class OutboxOp(val id: Long, val kind: Kind, val before: CalEvent?, val after: CalEvent?) {
    enum class Kind { CREATE, UPDATE, MOVE, DELETE }
}

@Serializable
private data class CachedOp(val id: Long, val k: String, val b: CachedEvent? = null, val a: CachedEvent? = null)

@Serializable
private data class CachedOutbox(val v: Int = 1, val ops: List<CachedOp>)

private fun OutboxOp.toCached() = CachedOp(id, kind.name, before?.toCached(), after?.toCached())

private fun CachedOp.toOp(): OutboxOp? {
    val kind = OutboxOp.Kind.entries.firstOrNull { it.name == k } ?: return null
    return OutboxOp(id, kind, b?.toEvent(), a?.toEvent())
}
