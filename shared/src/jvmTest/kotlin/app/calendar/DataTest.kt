package app.calendar

import app.calendar.data.DemoProvider
import app.calendar.data.GoogleCalendarApi
import app.calendar.model.CalEvent
import app.calendar.model.CalendarInfo
import app.calendar.model.EventFields
import app.calendar.model.EventTiming
import app.calendar.model.ZoneEntry
import app.calendar.platform.InMemoryStore
import app.calendar.state.AppSettings
import app.calendar.state.CalendarController
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class GoogleApiTest {
    private val cal = CalendarInfo("me@example.com", "en.indian#holiday@group.v.calendar.google.com", "Me", 0xFF0000FFL, readOnly = false, primary = true)

    private fun api(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): Pair<GoogleCalendarApi, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen += req
            val (status, body) = handler(req)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return GoogleCalendarApi(HttpClient(engine)) to seen
    }

    @Test
    fun parsesEventsAndSkipsCancelled() = runTest {
        val (api, seen) = api {
            HttpStatusCode.OK to """
                {"items":[
                  {"id":"1","summary":"Standup","start":{"dateTime":"2026-11-11T09:30:00+05:30"},"end":{"dateTime":"2026-11-11T09:45:00+05:30"},"colorId":"11"},
                  {"id":"2","summary":"Holiday","start":{"date":"2026-11-12"},"end":{"date":"2026-11-13"}},
                  {"id":"3","status":"cancelled"},
                  {"id":"4","start":{"dateTime":"2026-11-11T10:00:00Z"},"end":{"dateTime":"2026-11-11T11:00:00Z"},"recurringEventId":"r1"}
                ]}
            """.trimIndent()
        }
        val events = api.events(cal, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")) { "tok" }
        assertEquals(3, events.size)
        val standup = events[0]
        assertEquals(EventTiming.Timed(Instant.parse("2026-11-11T04:00:00Z"), Instant.parse("2026-11-11T04:15:00Z")), standup.timing)
        assertEquals(0xFFD50000L, standup.colorOverride)
        assertEquals(EventTiming.AllDay(LocalDate(2026, 11, 12), LocalDate(2026, 11, 13)), events[1].timing)
        assertEquals("(No title)", events[2].title)
        assertEquals("r1", events[2].recurringEventId)

        val url = seen.single().url
        // Calendar ids can contain '#', which must be encoded so the id stays one path segment.
        assertTrue(url.encodedPath.startsWith("/calendar/v3/calendars/en.indian%23holiday@group.v.calendar.google.com/events"), url.encodedPath)
        assertEquals("true", url.parameters["singleEvents"])
        assertEquals("Bearer tok", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun retriesOnceWithFreshTokenAfter401() = runTest {
        var calls = 0
        val (api, seen) = api {
            calls++
            if (calls == 1) HttpStatusCode.Unauthorized to "{}" else HttpStatusCode.OK to """{"items":[]}"""
        }
        val forced = mutableListOf<Boolean>()
        api.calendarList("me") { force -> forced += force; if (force) "new" else "old" }
        assertEquals(listOf(false, true), forced)
        assertEquals("Bearer new", seen[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun patchSendsBothDateFieldsSoAllDayCanBeToggled() = runTest {
        val (api, seen) = api {
            HttpStatusCode.OK to """{"id":"1","summary":"X","start":{"date":"2026-11-11"},"end":{"date":"2026-11-12"}}"""
        }
        val event = CalEvent(cal.accountId, cal.id, "1", "X", EventTiming.Timed(Instant.parse("2026-11-11T04:00:00Z"), Instant.parse("2026-11-11T05:00:00Z")))
        val saved = api.patch(event, EventFields("X", EventTiming.AllDay(LocalDate(2026, 11, 11), LocalDate(2026, 11, 12)))) { "t" }
        assertTrue(saved.isAllDay)
        val req = seen.single()
        assertEquals(HttpMethod.Patch, req.method)
        assertTrue(req.url.encodedPath.endsWith("/events/1"), req.url.encodedPath)
        val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        val start = body.getValue("start").jsonObject
        assertEquals("2026-11-11", start.getValue("date").jsonPrimitive.content)
        assertEquals(JsonNull, start.getValue("dateTime"))
    }

    @Test
    fun deleteToleratesAlreadyGone() = runTest {
        val (api, _) = api { HttpStatusCode.Gone to """{"error":{"message":"Resource has been deleted"}}""" }
        api.delete(CalEvent(cal.accountId, cal.id, "1", "X", EventTiming.AllDay(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2)))) { "t" }
    }

    @Test
    fun surfacesGoogleErrorMessage() = runTest {
        val (api, _) = api { HttpStatusCode.Forbidden to """{"error":{"code":403,"message":"Insufficient Permission"}}""" }
        val e = runCatching { api.calendarList("me") { "t" } }.exceptionOrNull()
        assertEquals("Insufficient Permission", e?.message)
    }
}

class ControllerTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val now = LocalDateTime(2026, 11, 11, 10, 0).toInstant(zone)

    private fun setup(): Pair<CalendarController, DemoProvider> {
        val store = InMemoryStore()
        val settings = AppSettings(store).apply {
            zones = listOf(ZoneEntry("Asia/Kolkata"))
            demoEnabled = true
        }
        val demo = DemoProvider(zone = { zone }, latencyMs = 0)
        val c = CalendarController(listOf(demo), settings, CoroutineScope(Dispatchers.Unconfined), clock = { now })
        runBlocking { c.reloadAccounts() }
        c.ensureLoaded()
        return c to demo
    }

    private fun CalendarController.find(title: String, day: LocalDate) =
        eventsBetween(day, LocalDate.fromEpochDays(day.toEpochDays() + 1)).first { it.title == title }

    @Test
    fun loadsDemoCalendarsAndEvents() {
        val (c, _) = setup()
        assertEquals(3, c.calendars.size)
        assertTrue(c.eventsBetween(LocalDate(2026, 11, 11), LocalDate(2026, 11, 12)).any { it.title == "Standup" })
    }

    @Test
    fun dragMoveIsSaved() {
        val (c, _) = setup()
        val day = LocalDate(2026, 11, 11)
        val lunch = c.find("Lunch", day)
        val t = lunch.timing as EventTiming.Timed
        val newStart = LocalDateTime(2026, 11, 11, 13, 0).toInstant(zone)
        c.changeTiming(lunch, EventTiming.Timed(newStart, newStart + (t.end - t.start)))
        assertEquals(newStart, (c.find("Lunch", day).timing as EventTiming.Timed).start)
        assertTrue(c.toasts.isEmpty())
    }

    @Test
    fun failedMoveRollsBackAndShowsToast() {
        val (c, demo) = setup()
        val day = LocalDate(2026, 11, 11)
        val lunch = c.find("Lunch", day)
        val t = lunch.timing as EventTiming.Timed
        demo.failNextMutation = "Rate limit exceeded"
        val newStart = LocalDateTime(2026, 11, 11, 16, 0).toInstant(zone)
        c.changeTiming(lunch, EventTiming.Timed(newStart, newStart + (t.end - t.start)))
        assertEquals(t.start, (c.find("Lunch", day).timing as EventTiming.Timed).start)
        assertEquals("Couldn't save: Rate limit exceeded", c.toasts.single().message)
    }

    @Test
    fun readOnlyCalendarsCannotBeChanged() {
        val (c, _) = setup()
        val holiday = c.calendars.first { it.readOnly }
        val e = CalEvent(holiday.accountId, holiday.id, "x", "H", EventTiming.AllDay(LocalDate(2026, 11, 11), LocalDate(2026, 11, 12)))
        assertFalse(c.canEdit(e))
    }

    @Test
    fun createHideAndDelete() {
        val (c, _) = setup()
        val day = LocalDate(2026, 11, 20)
        val next = LocalDate(2026, 11, 21)
        val start = LocalDateTime(2026, 11, 20, 7, 0).toInstant(zone)
        assertTrue(c.startNewEvent(EventTiming.Timed(start, start + 1.hours)))
        c.updateDraft { it.copy(title = "Run") }
        c.saveEditor()
        assertNull(c.editor)
        val run = c.find("Run", day)
        val cal = assertNotNull(c.calendarOf(run))
        c.toggleCalendar(cal)
        assertTrue(c.eventsBetween(day, next).none { it.title == "Run" })
        c.toggleCalendar(cal)
        c.delete(c.find("Run", day))
        assertTrue(c.eventsBetween(day, next).none { it.title == "Run" })
    }

    @Test
    fun zonesPersist() {
        val store = InMemoryStore()
        val c = CalendarController(emptyList(), AppSettings(store), CoroutineScope(Dispatchers.Unconfined), clock = { now })
        c.addZone("Asia/Tokyo")
        c.renameZone("Asia/Tokyo", "Tokyo")
        c.moveZone("Asia/Tokyo", -1)
        assertEquals(ZoneEntry("Asia/Tokyo", "Tokyo"), AppSettings(store).zones.first())
    }
}
