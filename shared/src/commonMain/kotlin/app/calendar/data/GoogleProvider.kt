package app.calendar.data

import app.calendar.model.Account
import app.calendar.model.CalEvent
import app.calendar.model.CalendarInfo
import app.calendar.model.EventFields
import app.calendar.platform.GoogleAuth
import app.calendar.platform.KeyValueStore
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Instant

/** Google accounts connected on this device, backed by [GoogleAuth] for tokens. */
class GoogleProvider(
    val auth: GoogleAuth,
    private val api: GoogleCalendarApi,
    private val store: KeyValueStore,
) : CalendarProvider {
    override val id = "google"

    private val serializer = ListSerializer(Account.serializer())
    private var cached: List<Account> = load()

    private fun load(): List<Account> = store.get(KEY)?.let {
        runCatching { googleJson.decodeFromString(serializer, it) }.getOrNull()
    } ?: emptyList()

    private fun save(list: List<Account>) {
        cached = list
        store.put(KEY, googleJson.encodeToString(serializer, list))
    }

    override fun accounts(): List<Account> = cached

    override suspend fun addAccount(): Account {
        val result = auth.signIn()
        val info = runCatching { api.userInfo(result.accessToken) }.getOrNull()
        val account = Account(
            id = result.email,
            email = result.email,
            name = info?.name,
            pictureUrl = info?.picture,
            provider = id,
        )
        save(cached.filterNot { it.id == account.id } + account)
        return account
    }

    override suspend fun removeAccount(account: Account) {
        runCatching { auth.signOut(account.email) }
        save(cached.filterNot { it.id == account.id })
    }

    private fun tokenFor(accountId: String): suspend (Boolean) -> String = { force -> auth.accessToken(accountId, force) }

    override suspend fun calendars(account: Account): List<CalendarInfo> =
        api.calendarList(account.id, tokenFor(account.id))

    override suspend fun events(calendar: CalendarInfo, from: Instant, to: Instant): List<CalEvent> =
        api.events(calendar, from, to, tokenFor(calendar.accountId))

    override suspend fun create(calendar: CalendarInfo, fields: EventFields): CalEvent =
        api.insert(calendar, fields, tokenFor(calendar.accountId))

    override suspend fun update(original: CalEvent, fields: EventFields): CalEvent =
        api.patch(original, fields, tokenFor(original.accountId))

    override suspend fun moveToCalendar(event: CalEvent, target: CalendarInfo): CalEvent =
        api.move(event, target.id, tokenFor(event.accountId))

    override suspend fun delete(event: CalEvent) = api.delete(event, tokenFor(event.accountId))

    companion object {
        const val KEY = "google.accounts"
    }
}
