package app.calendar.data

import app.calendar.model.Account
import app.calendar.model.CalEvent
import app.calendar.model.CalendarInfo
import app.calendar.model.EventFields
import kotlin.time.Instant

/** One source of accounts and calendars (Google, or the built-in demo data). */
interface CalendarProvider {
    val id: String

    fun accounts(): List<Account>

    /** Interactive: asks the user to connect an account. */
    suspend fun addAccount(): Account

    suspend fun removeAccount(account: Account)

    suspend fun calendars(account: Account): List<CalendarInfo>

    suspend fun events(calendar: CalendarInfo, from: Instant, to: Instant): List<CalEvent>

    suspend fun create(calendar: CalendarInfo, fields: EventFields): CalEvent

    /** Writes [fields] to [original] in place (same calendar). Returns the saved event. */
    suspend fun update(original: CalEvent, fields: EventFields): CalEvent

    /** Moves [event] to another calendar of the same account. */
    suspend fun moveToCalendar(event: CalEvent, target: CalendarInfo): CalEvent

    suspend fun delete(event: CalEvent)
}

class CalendarApiException(val status: Int, message: String) : Exception(message)
