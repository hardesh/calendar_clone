package app.calendar.logic

import app.calendar.model.ZoneEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Short name such as "IST", "PST" or "GMT+5:45". Platform-provided (java.time on both targets). */
expect fun zoneAbbreviation(zoneId: String, at: Instant): String

fun zoneOrNull(id: String): TimeZone? = try {
    TimeZone.of(id)
} catch (_: Exception) {
    null
}

fun ZoneEntry.displayLabel(at: Instant): String =
    label?.takeIf { it.isNotBlank() } ?: zoneAbbreviation(id, at)

/** "GMT+5:30", "GMT-8", "GMT". */
fun offsetLabel(zone: TimeZone, at: Instant): String {
    val seconds = zone.offsetAt(at).totalSeconds
    if (seconds == 0) return "GMT"
    val sign = if (seconds < 0) "-" else "+"
    val abs = kotlin.math.abs(seconds)
    val h = abs / 3600
    val m = (abs % 3600) / 60
    return if (m == 0) "GMT$sign$h" else "GMT$sign$h:${m.toString().padStart(2, '0')}"
}

/**
 * Labels for the hour gutter of a secondary zone: for each hour row of the primary
 * grid on [day], the wall-clock time in [zone]. Whole hours read "3 PM", others "3:30".
 */
fun hourLabels(day: LocalDate, primary: TimeZone, zone: TimeZone): List<String> =
    List(24) { hour ->
        val instant = instantAt(day, hour * 60, primary)
        val t = instant.toLocalDateTime(zone).time
        formatTime(t.hour * 60 + t.minute, compact = true)
    }

/** Whether the secondary zone's day differs from the primary on that hour row (shown as "+1"/"-1"). */
fun dayShift(day: LocalDate, hour: Int, primary: TimeZone, zone: TimeZone): Int {
    val instant = instantAt(day, hour * 60, primary)
    return day.daysUntilSafe(instant.toLocalDateTime(zone).date)
}

private fun LocalDate.daysUntilSafe(other: LocalDate): Int = (other.toEpochDays() - toEpochDays()).toInt()

data class ZoneOption(val id: String, val city: String, val search: String)

/** All selectable zones, with a readable city name ("America/New_York" → "New York"). */
val allZoneOptions: List<ZoneOption> by lazy {
    TimeZone.availableZoneIds
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
        .sorted()
        .map { id ->
            val city = id.substringAfterLast('/').replace('_', ' ')
            ZoneOption(id, city, "$id $city".lowercase())
        } + ZoneOption("UTC", "UTC", "utc gmt")
}

fun searchZones(query: String, limit: Int = 60): List<ZoneOption> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return allZoneOptions.take(limit)
    return allZoneOptions
        .filter { it.search.contains(q) }
        .sortedBy { if (it.city.lowercase().startsWith(q)) 0 else 1 }
        .take(limit)
}
