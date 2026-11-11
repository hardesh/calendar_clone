package app.calendar.logic

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private val abbrevFormatter = DateTimeFormatter.ofPattern("zzz", Locale.US)

actual fun zoneAbbreviation(zoneId: String, at: Instant): String {
    val name = runCatching { abbrevFormatter.format(at.toJavaInstant().atZone(ZoneId.of(zoneId))) }.getOrNull()
    // Zones without a common abbreviation come back as "GMT+05:45"; use the shorter "GMT+5:45".
    if (name == null || name.startsWith("GMT") || name.startsWith("UTC")) {
        return zoneOrNull(zoneId)?.let { offsetLabel(it, at) } ?: zoneId.substringAfterLast('/')
    }
    return name
}
