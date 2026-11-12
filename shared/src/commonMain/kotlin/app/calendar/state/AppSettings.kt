package app.calendar.state

import app.calendar.model.ViewMode
import app.calendar.model.ZoneEntry
import app.calendar.platform.KeyValueStore
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Persisted user preferences. Plain getters/setters over a [KeyValueStore]. */
const val REMINDERS_FROM_GOOGLE = -1

class AppSettings(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())
    private val zoneList = ListSerializer(ZoneEntry.serializer())

    var viewMode: ViewMode?
        get() = store.get("viewMode")?.let { v -> ViewMode.entries.firstOrNull { it.name == v } }
        set(v) = if (v == null) store.remove("viewMode") else store.put("viewMode", v.name)

    /** First entry is the primary zone that drives the grid. */
    var zones: List<ZoneEntry>
        get() = store.get("zones")?.let { runCatching { json.decodeFromString(zoneList, it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(ZoneEntry(TimeZone.currentSystemDefault().id))
        set(v) = store.put("zones", json.encodeToString(zoneList, v))

    var hiddenCalendars: Set<String>
        get() = readSet("hiddenCalendars")
        set(v) = writeSet("hiddenCalendars", v)

    /** Calendars seen before, so Google's "unselected" default is only applied once. */
    var knownCalendars: Set<String>
        get() = readSet("knownCalendars")
        set(v) = writeSet("knownCalendars", v)

    var weekStart: DayOfWeek
        get() = store.get("weekStart")?.let { v -> DayOfWeek.entries.firstOrNull { it.name == v } } ?: DayOfWeek.SUNDAY
        set(v) = store.put("weekStart", v.name)

    var demoEnabled: Boolean
        get() = store.get("demoEnabled") == "true"
        set(v) = store.put("demoEnabled", v.toString())

    /** Hour height in dp for the day/week grids; null means the device's default. */
    var hourHeight: Float?
        get() = store.get("hourHeight")?.toFloatOrNull()
        set(v) = if (v == null) store.remove("hourHeight") else store.put("hourHeight", v.toString())

    /** Phone notifications: -1 = each event's Google reminders, 0 = off, else minutes before every event. */
    var reminderMinutes: Int
        get() = store.get("reminderMinutes")?.toIntOrNull() ?: REMINDERS_FROM_GOOGLE
        set(v) = store.put("reminderMinutes", v.toString())

    /** Text size multiplier for the whole app (1 = default). */
    var textScale: Float
        get() = store.get("textScale")?.toFloatOrNull() ?: 1f
        set(v) = store.put("textScale", v.toString())

    var defaultCalendar: String?
        get() = store.get("defaultCalendar")
        set(v) = if (v == null) store.remove("defaultCalendar") else store.put("defaultCalendar", v)

    private fun readSet(key: String): Set<String> =
        store.get(key)?.let { runCatching { json.decodeFromString(stringList, it).toSet() }.getOrNull() } ?: emptySet()

    private fun writeSet(key: String, v: Set<String>) = store.put(key, json.encodeToString(stringList, v.toList()))
}
