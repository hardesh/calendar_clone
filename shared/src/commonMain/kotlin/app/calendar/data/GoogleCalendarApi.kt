package app.calendar.data

import app.calendar.model.CalEvent
import app.calendar.model.CalendarInfo
import app.calendar.model.EventColors
import app.calendar.model.EventFields
import app.calendar.model.EventTiming
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal val googleJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
internal data class CalendarListResponse(val items: List<CalendarListEntry> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class CalendarListEntry(
    val id: String,
    val summary: String? = null,
    val summaryOverride: String? = null,
    val backgroundColor: String? = null,
    val accessRole: String? = null,
    val primary: Boolean? = null,
    val selected: Boolean? = null,
    val hidden: Boolean? = null,
)

@Serializable
internal data class EventsResponse(val items: List<EventDto> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class EventDto(
    val id: String,
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val colorId: String? = null,
    val recurringEventId: String? = null,
    val start: EventDateTime? = null,
    val end: EventDateTime? = null,
)

@Serializable
internal data class EventDateTime(val date: String? = null, val dateTime: String? = null, val timeZone: String? = null)

@Serializable
internal data class UserInfo(val sub: String, val email: String, val name: String? = null, val picture: String? = null)

internal fun parseHexColor(hex: String?): Long {
    val h = hex?.removePrefix("#") ?: return 0xFF4285F4L
    return (h.toLongOrNull(16) ?: 0x4285F4L) or 0xFF000000L
}

internal fun CalendarListEntry.toCalendarInfo(accountId: String) = CalendarInfo(
    accountId = accountId,
    id = id,
    name = summaryOverride ?: summary ?: id,
    color = parseHexColor(backgroundColor),
    readOnly = accessRole != "owner" && accessRole != "writer",
    primary = primary == true,
    selectedByDefault = selected == true,
)

/** Maps an API event; returns null for cancelled or malformed events. */
internal fun EventDto.toCalEvent(accountId: String, calendarId: String): CalEvent? {
    if (status == "cancelled") return null
    val s = start ?: return null
    val e = end ?: return null
    val timing = when {
        s.date != null && e.date != null -> EventTiming.AllDay(LocalDate.parse(s.date), LocalDate.parse(e.date))
        s.dateTime != null && e.dateTime != null -> EventTiming.Timed(Instant.parse(s.dateTime), Instant.parse(e.dateTime))
        else -> return null
    }
    return CalEvent(
        accountId = accountId,
        calendarId = calendarId,
        id = id,
        title = summary?.takeIf { it.isNotBlank() } ?: "(No title)",
        timing = timing,
        location = location,
        description = description,
        recurringEventId = recurringEventId,
        colorId = colorId?.takeIf { EventColors.argbFor(it) != null },
    )
}

/** Request body for insert/patch. Both date and dateTime are written so switching all-day on/off works with PATCH. */
internal fun EventFields.toJson(): JsonObject = buildJsonObject {
    put("summary", title)
    // Always sent, so choosing "calendar colour" clears a colour set before.
    put("colorId", colorId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
    location?.let { put("location", it) }
    description?.let { put("description", it) }
    when (val t = timing) {
        is EventTiming.AllDay -> {
            put("start", buildJsonObject { put("date", t.start.toString()); put("dateTime", JsonNull) })
            put("end", buildJsonObject { put("date", t.endExclusive.toString()); put("dateTime", JsonNull) })
        }
        is EventTiming.Timed -> {
            put("start", buildJsonObject { put("dateTime", t.start.toString()); put("date", JsonNull) })
            put("end", buildJsonObject { put("dateTime", t.end.toString()); put("date", JsonNull) })
        }
    }
}

/**
 * Thin client for the Calendar REST v3 endpoints the app uses. Every call takes a
 * token provider so a 401 can be retried once with a refreshed token.
 */
class GoogleCalendarApi(
    private val http: HttpClient,
    private val baseUrl: String = "https://www.googleapis.com",
) {
    internal suspend fun userInfo(token: String): UserInfo {
        val r = http.request("https://openidconnect.googleapis.com/v1/userinfo") {
            method = HttpMethod.Get
            bearerAuth(token)
        }
        return googleJson.decodeFromString(r.ensureOk().bodyAsText())
    }

    suspend fun calendarList(accountId: String, token: suspend (Boolean) -> String): List<CalendarInfo> {
        val out = ArrayList<CalendarInfo>()
        var page: String? = null
        do {
            val body = call(HttpMethod.Get, listOf("calendar", "v3", "users", "me", "calendarList"), token) {
                parameter("maxResults", 250)
                page?.let { parameter("pageToken", it) }
            }
            val res = googleJson.decodeFromString<CalendarListResponse>(body)
            res.items.filter { it.hidden != true }.mapTo(out) { it.toCalendarInfo(accountId) }
            page = res.nextPageToken
        } while (page != null)
        return out.sortedWith(compareByDescending<CalendarInfo> { it.primary }.thenBy { it.readOnly }.thenBy { it.name.lowercase() })
    }

    suspend fun events(calendar: CalendarInfo, from: Instant, to: Instant, token: suspend (Boolean) -> String): List<CalEvent> {
        val out = ArrayList<CalEvent>()
        var page: String? = null
        do {
            val body = call(HttpMethod.Get, eventsPath(calendar.id), token) {
                parameter("timeMin", from.toString())
                parameter("timeMax", to.toString())
                parameter("singleEvents", true)
                parameter("orderBy", "startTime")
                parameter("maxResults", 2500)
                page?.let { parameter("pageToken", it) }
            }
            val res = googleJson.decodeFromString<EventsResponse>(body)
            res.items.mapNotNullTo(out) { it.toCalEvent(calendar.accountId, calendar.id) }
            page = res.nextPageToken
        } while (page != null)
        return out
    }

    suspend fun insert(calendar: CalendarInfo, fields: EventFields, token: suspend (Boolean) -> String): CalEvent {
        val body = call(HttpMethod.Post, eventsPath(calendar.id), token) { jsonBody(fields.toJson()) }
        return googleJson.decodeFromString<EventDto>(body).toCalEvent(calendar.accountId, calendar.id)
            ?: throw CalendarApiException(500, "Google returned an unexpected event")
    }

    suspend fun patch(event: CalEvent, fields: EventFields, token: suspend (Boolean) -> String): CalEvent {
        val body = call(HttpMethod.Patch, eventsPath(event.calendarId) + event.id, token) { jsonBody(fields.toJson()) }
        return googleJson.decodeFromString<EventDto>(body).toCalEvent(event.accountId, event.calendarId)
            ?: throw CalendarApiException(500, "Google returned an unexpected event")
    }

    suspend fun move(event: CalEvent, targetCalendarId: String, token: suspend (Boolean) -> String): CalEvent {
        val body = call(HttpMethod.Post, eventsPath(event.calendarId) + listOf(event.id, "move"), token) {
            parameter("destination", targetCalendarId)
        }
        return googleJson.decodeFromString<EventDto>(body).toCalEvent(event.accountId, targetCalendarId)
            ?: throw CalendarApiException(500, "Google returned an unexpected event")
    }

    suspend fun delete(event: CalEvent, token: suspend (Boolean) -> String) {
        try {
            call(HttpMethod.Delete, eventsPath(event.calendarId) + event.id, token) {}
        } catch (e: CalendarApiException) {
            if (e.status != 410 && e.status != 404) throw e // already gone is fine
        }
    }

    private fun eventsPath(calendarId: String) = listOf("calendar", "v3", "calendars", calendarId, "events")

    private fun HttpRequestBuilder.jsonBody(obj: JsonObject) {
        contentType(ContentType.Application.Json)
        setBody(obj.toString())
    }

    private suspend fun call(
        method: HttpMethod,
        path: List<String>,
        token: suspend (Boolean) -> String,
        block: HttpRequestBuilder.() -> Unit,
    ): String {
        var refreshed = false
        while (true) {
            val t = token(refreshed)
            val url = URLBuilder().takeFrom(baseUrl).appendPathSegments(path).build()
            val r = http.request(url) {
                this.method = method
                bearerAuth(t)
                block()
            }
            if (r.status.value == 401 && !refreshed) {
                refreshed = true
                continue
            }
            return r.ensureOk().bodyAsText()
        }
    }
}

private suspend fun HttpResponse.ensureOk(): HttpResponse {
    if (status.isSuccess()) return this
    val text = runCatching { bodyAsText() }.getOrDefault("")
    val message = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
    throw CalendarApiException(status.value, message ?: "Google Calendar error ${status.value}")
}
