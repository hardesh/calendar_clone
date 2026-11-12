package app.calendar.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.calendar.data.CalendarProvider
import app.calendar.data.EventCache
import app.calendar.data.InMemoryFileCache
import app.calendar.data.OutboxOp
import app.calendar.logic.MONTH_GRID_DAYS
import app.calendar.logic.dateSpan
import app.calendar.logic.monthGridStart
import app.calendar.logic.pageAnchor
import app.calendar.logic.plusDays
import app.calendar.logic.visibleDays
import app.calendar.logic.zoneOrNull
import app.calendar.model.Account
import app.calendar.model.CalEvent
import app.calendar.model.CalendarInfo
import app.calendar.model.UpcomingEvent
import app.calendar.model.EventTiming
import app.calendar.model.ViewMode
import app.calendar.model.ZoneEntry
import app.calendar.model.fields
import app.calendar.model.fieldsComparedTo
import app.calendar.model.meetingLink
import app.calendar.platform.ReauthRequiredException
import app.calendar.platform.SignInCancelledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class Toast(val id: Long, val message: String)

/** The event editor's working copy. [original] is null for a new event. */
data class EditorState(val original: CalEvent?, val draft: CalEvent) {
    val isNew: Boolean get() = original == null
}

/**
 * All app state and actions. Compose reads the snapshot-state properties directly;
 * every mutation is applied to the local cache first and rolled back if Google
 * rejects it.
 */
class CalendarController(
    private val providers: List<CalendarProvider>,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** Last-known calendars and events, for instant start and offline use. */
    private val cache: EventCache = EventCache(InMemoryFileCache()),
    private val clock: () -> Instant = { Clock.System.now() },
    forceDemo: Boolean = false,
    /** Whether this device can notify before events (shows the reminder setting). */
    val supportsReminders: Boolean = false,
) {
    // ---- view state ------------------------------------------------------------
    var viewMode by mutableStateOf(settings.viewMode ?: ViewMode.THREE_DAY)
        private set
    var anchor by mutableStateOf(today())
        private set
    var zones by mutableStateOf(settings.zones)
        private set
    var weekStart by mutableStateOf(settings.weekStart)
        private set
    var demoEnabled by mutableStateOf(settings.demoEnabled || forceDemo)
        private set
    /** Minutes before events to notify (0 = off); only used where [supportsReminders]. */
    var reminderMinutes by mutableIntStateOf(settings.reminderMinutes)
        private set

    fun changeReminderMinutes(minutes: Int) {
        reminderMinutes = minutes
        settings.reminderMinutes = minutes
    }

    /** Zoom of the day/week grids as an hour height in dp; null = device default. */
    var hourHeight by mutableStateOf(settings.hourHeight)
        private set

    val primaryZone: TimeZone get() = zones.firstOrNull()?.let { zoneOrNull(it.id) } ?: TimeZone.currentSystemDefault()

    // ---- accounts & calendars ---------------------------------------------------
    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    var calendars by mutableStateOf<List<CalendarInfo>>(emptyList())
        private set
    var hidden by mutableStateOf(settings.hiddenCalendars)
        private set
    /** Accounts whose sign-in needs to be redone, with the reason. */
    val accountErrors = mutableStateMapOf<String, String>()
    var addingAccount by mutableStateOf(false)
        private set

    // ---- events -------------------------------------------------------------------
    /** Loaded events keyed by the first day of a 6-week window. */
    private val windows = mutableStateMapOf<LocalDate, List<CalEvent>>()
    private val loadingWindows = HashMap<LocalDate, Job>()
    /** Windows fetched from the providers this session (others may be showing cached data). */
    private val freshWindows = HashSet<LocalDate>()
    var loadingCount by mutableIntStateOf(0)
        private set
    /** True when the last attempt to reach Google failed for network reasons; cached data is shown. */
    var offline by mutableStateOf(false)
        private set

    /**
     * Changes not yet confirmed by Google, oldest first, saved so they survive restarts and
     * offline periods. Their effect is re-applied over freshly loaded or cached windows.
     */
    private val outbox = ArrayList<OutboxOp>(cache.loadOutbox())
    private var inFlightOp: Long? = null
    private var flushing = false
    private var nextOpId = clock().toEpochMilliseconds() * 1000
    private var warnedOffline = false
    /** Number of changes waiting to be sent to Google. */
    var pendingChanges by mutableIntStateOf(outbox.size)
        private set

    // ---- UI chrome ----------------------------------------------------------------
    val toasts = mutableStateListOf<Toast>()
    private var nextToastId = 0L
    var editor by mutableStateOf<EditorState?>(null)
        private set
    /** Bumped to cancel an in-progress drag (Esc). Gesture code compares against its start value. */
    var dragCancelToken by mutableIntStateOf(0)
        private set

    private val forceDemo = forceDemo

    init {
        if (forceDemo && !settings.demoEnabled) settings.demoEnabled = true
        // Demo data is only for trying the app; once a real account is connected it goes away.
        if (!forceDemo && demoEnabled && hasRealAccount()) {
            demoEnabled = false
            settings.demoEnabled = false
        }
    }

    /** True once any Google account is connected; the demo data switch is hidden then. */
    fun hasRealAccount(): Boolean = providers.any { it.id != "demo" && it.accounts().isNotEmpty() }

    fun today(): LocalDate = clock().toLocalDateTime(primaryZoneOrDefault()).date
    fun now(): Instant = clock()

    private fun primaryZoneOrDefault(): TimeZone =
        runCatching { primaryZone }.getOrDefault(TimeZone.currentSystemDefault())

    private fun activeProviders() = providers.filter { it.id != "demo" || demoEnabled }

    fun providerFor(accountId: String): CalendarProvider? =
        activeProviders().firstOrNull { p -> p.accounts().any { it.id == accountId } }

    val googleProvider: CalendarProvider? get() = providers.firstOrNull { it.id == "google" }
    val googleAuth: app.calendar.platform.GoogleAuth? get() = (googleProvider as? app.calendar.data.GoogleProvider)?.auth

    // ---- lifecycle ---------------------------------------------------------------
    fun start() {
        // Show what we had last time straight away, then refresh from the network.
        restoreCalendarsFromCache()
        restoreCachedWindows()
        scope.launch {
            reloadAccounts()
            ensureLoaded()
            // Every 30 s: while offline, try to reconnect (and then send queued changes);
            // otherwise refresh events every 5 minutes.
            var ticks = 0
            while (true) {
                delay(30.seconds)
                ticks++
                if (offline) {
                    reloadAccounts()
                    if (!offline) refreshEvents(showErrors = false)
                } else if (ticks % 10 == 0) {
                    refreshEvents(showErrors = false)
                }
            }
        }
    }

    private fun restoreCalendarsFromCache() {
        val accountIds = activeProviders().flatMap { it.accounts() }.map { it.id }.toSet()
        if (calendars.isEmpty()) {
            accounts = activeProviders().flatMap { it.accounts() }
            calendars = cache.loadCalendars().filter { it.accountId in accountIds }
        }
    }

    /** Puts cached events into any on-screen window not yet in memory. */
    private fun restoreCachedWindows() {
        for (w in currentWindows()) {
            if (w in windows) continue
            val cached = cache.loadWindow(weekStart, w) ?: continue
            val ids = calendars.map { it.key }.toSet()
            var list = cached.filter { it.calendarKey in ids }
            for (op in outbox) list = applyEdit(list, op.before, op.after, w)
            windows[w] = list
        }
    }

    suspend fun reloadAccounts() {
        val list = activeProviders().flatMap { it.accounts() }
        accounts = list
        val previous = calendars.ifEmpty { cache.loadCalendars() }
        val cals = ArrayList<CalendarInfo>()
        var networkFailures = 0
        var reached = 0
        for (account in list) {
            val provider = providerFor(account.id) ?: continue
            try {
                cals += provider.calendars(account)
                accountErrors.remove(account.id)
                if (provider.id != "demo") reached++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing this account's calendars from before (memory or cache).
                cals += previous.filter { it.accountId == account.id }
                when {
                    isNetworkError(e) -> networkFailures++
                    e is ReauthRequiredException -> {
                        accountErrors[account.id] = errorText(e)
                        toast("${account.email}: sign in again to keep syncing")
                    }
                    else -> {
                        accountErrors[account.id] = errorText(e)
                        toast("Couldn't load calendars for ${account.email}: ${errorText(e)}")
                    }
                }
            }
        }
        if (networkFailures > 0 || reached > 0) offline = networkFailures > 0 && reached == 0
        applyDefaultVisibility(cals)
        calendars = cals
        if (networkFailures == 0) cache.saveCalendars(cals)
        if (!offline) flush()
        println("[calendar] accounts=${list.map { it.provider }} calendars=${cals.size} hidden=${hidden.size} demo=$demoEnabled")
    }

    private fun applyDefaultVisibility(cals: List<CalendarInfo>) {
        val known = settings.knownCalendars
        val newOnes = cals.filter { it.key !in known }
        if (newOnes.isEmpty()) return
        val newlyHidden = newOnes.filter { !it.selectedByDefault }.map { it.key }
        if (newlyHidden.isNotEmpty()) updateHidden(hidden + newlyHidden)
        settings.knownCalendars = known + newOnes.map { it.key }
    }

    fun refresh() {
        scope.launch {
            reloadAccounts()
            refreshEvents(showErrors = true)
        }
    }

    private suspend fun refreshEvents(showErrors: Boolean) {
        val keep = currentWindows()
        windows.keys.filter { it !in keep }.forEach { windows.remove(it); freshWindows.remove(it) }
        keep.map { start -> scope.async { loadWindow(start, force = true, showErrors = showErrors) } }.awaitAll()
    }

    // ---- navigation ----------------------------------------------------------------
    fun setView(mode: ViewMode) {
        viewMode = mode
        settings.viewMode = mode
        ensureLoaded()
    }

    fun goToday() = goTo(today())

    fun goTo(date: LocalDate, mode: ViewMode? = null) {
        if (mode != null) setView(mode)
        anchor = date
        ensureLoaded()
    }

    fun page(direction: Int) {
        anchor = pageAnchor(viewMode, anchor, direction)
        ensureLoaded()
    }

    fun visibleDays(): List<LocalDate> = visibleDays(viewMode, anchor, weekStart)

    fun changeWeekStart(day: DayOfWeek) {
        weekStart = day
        settings.weekStart = day
        clearWindows()
        ensureLoaded()
    }

    fun changeDemoEnabled(enabled: Boolean) {
        demoEnabled = enabled
        settings.demoEnabled = enabled
        clearWindows()
        scope.launch {
            reloadAccounts()
            ensureLoaded()
        }
    }

    // ---- zoom ---------------------------------------------------------------------
    fun defaultHourHeight(compact: Boolean) = if (compact) 52f else 48f

    fun hourHeightFor(compact: Boolean) = hourHeight ?: defaultHourHeight(compact)

    fun setZoom(hourHeightDp: Float?) {
        val v = hourHeightDp?.coerceIn(MIN_HOUR_HEIGHT, MAX_HOUR_HEIGHT)
        hourHeight = v
        settings.hourHeight = v
    }

    /** Multiplies the hour height, e.g. 1.25 to zoom in one step. */
    fun zoomBy(factor: Float, compact: Boolean) = setZoom(hourHeightFor(compact) * factor)

    fun resetZoom() = setZoom(null)

    // ---- text size --------------------------------------------------------------
    /** Text size for the whole app, independent of the grid zoom. */
    var textScale by mutableStateOf(settings.textScale)
        private set

    fun changeTextScale(scale: Float) {
        textScale = scale
        settings.textScale = scale
    }

    // ---- time zones ------------------------------------------------------------
    fun updateZones(list: List<ZoneEntry>) {
        if (list.isEmpty()) return
        val primaryChanged = list.first().id != zones.firstOrNull()?.id
        zones = list
        settings.zones = list
        if (primaryChanged) {
            clearWindows()
            ensureLoaded()
        }
    }

    fun addZone(id: String) {
        if (zones.any { it.id == id }) return
        updateZones(zones + ZoneEntry(id))
    }

    fun removeZone(id: String) = updateZones(zones.filterNot { it.id == id })

    fun moveZone(id: String, delta: Int) {
        val i = zones.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in zones.indices) return
        val list = zones.toMutableList()
        list.add(j, list.removeAt(i))
        updateZones(list)
    }

    fun renameZone(id: String, label: String?) =
        updateZones(zones.map { if (it.id == id) it.copy(label = label?.trim()?.ifEmpty { null }) else it })

    // ---- calendars ----------------------------------------------------------------
    private fun updateHidden(set: Set<String>) {
        hidden = set
        settings.hiddenCalendars = set
    }

    fun toggleCalendar(cal: CalendarInfo) {
        updateHidden(if (cal.key in hidden) hidden - cal.key else hidden + cal.key)
    }

    fun calendarOf(event: CalEvent): CalendarInfo? = calendars.firstOrNull { it.key == event.calendarKey }

    fun colorOf(event: CalEvent): Long = event.colorOverride ?: calendarOf(event)?.color ?: 0xFF9B9B9BL

    /** Whether the event's time and details can be changed (and it can be dragged). */
    fun canEdit(event: CalEvent): Boolean = calendarOf(event)?.readOnly == false && event.organizerSelf

    /** Whether your own reply, colour and reminders can be changed (also for invitations). */
    fun canEditPersonal(event: CalEvent): Boolean = calendarOf(event)?.readOnly == false

    fun writableCalendars(): List<CalendarInfo> = calendars.filter { !it.readOnly }

    fun defaultCalendar(): CalendarInfo? {
        val writable = writableCalendars()
        return writable.firstOrNull { it.key == settings.defaultCalendar && it.key !in hidden }
            ?: writable.firstOrNull { it.primary && it.key !in hidden }
            ?: writable.firstOrNull { it.key !in hidden }
            ?: writable.firstOrNull()
    }

    // ---- accounts -------------------------------------------------------------------
    fun addGoogleAccount() {
        val google = googleProvider ?: return
        if (addingAccount) return
        addingAccount = true
        scope.launch {
            try {
                val account = google.addAccount()
                accountErrors.remove(account.id)
                if (demoEnabled && !forceDemo) {
                    demoEnabled = false
                    settings.demoEnabled = false
                    clearWindows()
                }
                reloadAccounts()
                refreshEvents(showErrors = true)
                toast("Connected ${account.email}")
            } catch (_: SignInCancelledException) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            } finally {
                addingAccount = false
            }
        }
    }

    fun removeAccount(account: Account) {
        scope.launch {
            providerFor(account.id)?.removeAccount(account)
            accountErrors.remove(account.id)
            outbox.removeAll { (it.before ?: it.after)?.accountId == account.id }
            cache.clear()
            saveOutbox()
            clearWindows()
            calendars = emptyList()
            reloadAccounts()
            ensureLoaded()
        }
    }

    // ---- loading ----------------------------------------------------------------
    private fun windowStartFor(date: LocalDate) = monthGridStart(date, weekStart)

    /**
     * Window starts to keep loaded: what is on screen (a 3-day or week view can cross a month
     * boundary), plus today and tomorrow for the menu bar and reminders.
     */
    private fun currentWindows(): Set<LocalDate> {
        val days = visibleDays()
        val onScreen = if (viewMode == ViewMode.MONTH) setOf(monthGridStart(anchor, weekStart))
        else setOf(windowStartFor(days.first()), windowStartFor(days.last()))
        val today = today()
        return onScreen + windowStartFor(today) + windowStartFor(today.plusDays(1))
    }

    /** Timed events from an hour ago to two days ahead, soonest first, from visible calendars. */
    fun upcomingEvents(): List<UpcomingEvent> {
        val now = clock()
        val zone = primaryZone
        val today = today()
        return eventsBetween(today.plusDays(-1), today.plusDays(3))
            .filter { it.timing is EventTiming.Timed && it.id != DRAFT_ID }
            .filter { it.endIn(zone) > now - 1.hours && it.startIn(zone) < now + 48.hours }
            .map {
                UpcomingEvent(
                    it.key, it.title, it.startIn(zone), it.endIn(zone), colorOf(it), it.location,
                    reminderMinutes = reminderMinutesFor(it),
                    conferenceUrl = it.meetingLink()?.url,
                )
            }
            .sortedBy { it.start }
    }

    /** When Google would notify for [event]: its own reminders, or its calendar's defaults. */
    fun reminderMinutesFor(event: CalEvent): List<Int> {
        val r = event.reminders
        return if (r == null || r.useDefault) calendarOf(event)?.defaultReminders.orEmpty() else r.minutes
    }

    /** Opens the editor for an event shown in the menu bar or a notification, and shows its day. */
    fun openEventByKey(key: String) {
        val event = windows.values.asSequence().flatten().firstOrNull { it.key == key } ?: return
        val day = event.startIn(primaryZone).toLocalDateTime(primaryZone).date
        if (day !in visibleDays()) goTo(day)
        openEditor(event)
    }

    private fun clearWindows() {
        windows.clear()
        freshWindows.clear()
    }

    /**
     * Makes sure every on-screen window has data: cached events appear immediately,
     * and any window not yet fetched this session is refreshed in the background.
     */
    fun ensureLoaded() {
        restoreCachedWindows()
        for (w in currentWindows()) {
            if (w !in freshWindows && loadingWindows[w]?.isActive != true) {
                loadingWindows[w] = scope.launch { loadWindow(w, force = false, showErrors = true) }
            }
        }
    }

    private suspend fun loadWindow(start: LocalDate, force: Boolean, showErrors: Boolean) {
        if (!force && start in freshWindows) return
        loadingCount++
        try {
            val zone = primaryZone
            val from = start.atStartOfDayIn(zone)
            val to = start.plusDays(MONTH_GRID_DAYS).atStartOfDayIn(zone)
            val cals = calendars
            val errors = ArrayList<String>()
            val failed = HashSet<String>()
            var networkFailures = 0
            var reached = 0
            val events = coroutineScope {
                cals.map { cal ->
                    async {
                        val provider = providerFor(cal.accountId) ?: return@async emptyList()
                        try {
                            provider.events(cal, from, to).also { if (provider.id != "demo") reached++ }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed += cal.key
                            if (isNetworkError(e)) {
                                networkFailures++
                            } else {
                                if (e is ReauthRequiredException) accountErrors[cal.accountId] = errorText(e)
                                errors += "${cal.name}: ${errorText(e)}"
                            }
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            // For calendars that couldn't be fetched, keep what we had (memory, else cache).
            val previous = windows[start] ?: cache.loadWindow(weekStart, start).orEmpty()
            var list = events + previous.filter { it.calendarKey in failed }
            for (op in outbox) list = applyEdit(list, op.before, op.after, start)
            windows[start] = list
            if (failed.isEmpty()) freshWindows += start
            if (networkFailures > 0 || reached > 0) offline = networkFailures > 0 && reached == 0
            if (!offline) flush()
            cache.saveWindow(weekStart, start, list, clock())
            println("[calendar] loaded $start: ${list.size} events from ${cals.size} calendars, ${failed.size} failed (${networkFailures} network), offline=$offline")
            errors.forEach { println("[calendar] load error: $it") }
            if (showErrors && errors.isNotEmpty()) toast("Couldn't load ${errors.first()}")
        } finally {
            loadingCount--
        }
    }

    /** Events overlapping [from, toExclusive), excluding hidden calendars, de-duplicated. */
    fun eventsBetween(from: LocalDate, toExclusive: LocalDate): List<CalEvent> {
        val zone = primaryZone
        val seen = HashSet<String>()
        val out = ArrayList<CalEvent>()
        for ((_, list) in windows) {
            for (e in list) {
                if (e.calendarKey in hidden || !seen.add(e.key)) continue
                val (s, l) = dateSpan(e, zone)
                if (s < toExclusive && l >= from) out += e
            }
        }
        return out.sortedWith(compareBy<CalEvent> { it.startIn(zone) }.thenByDescending { it.endIn(zone) })
    }

    // ---- mutations ----------------------------------------------------------------
    private fun windowCovers(start: LocalDate, event: CalEvent): Boolean {
        val (s, l) = dateSpan(event, primaryZone)
        return s < start.plusDays(MONTH_GRID_DAYS) && l >= start
    }

    private fun applyEdit(list: List<CalEvent>, old: CalEvent?, new: CalEvent?, windowStart: LocalDate): List<CalEvent> {
        var out = if (old != null) list.filterNot { it.key == old.key } else list
        if (new != null && windowCovers(windowStart, new)) out = out.filterNot { it.key == new.key } + new
        return out
    }

    private fun applyLocal(old: CalEvent?, new: CalEvent?) {
        for (k in windows.keys.toList()) {
            val before = windows[k].orEmpty()
            val after = applyEdit(before, old, new, k)
            if (after != before) {
                windows[k] = after
                cache.saveWindow(weekStart, k, after, clock())
            }
        }
    }

    private fun newOpId() = nextOpId++

    private fun saveOutbox() {
        cache.saveOutbox(outbox)
        pendingChanges = outbox.size
        if (offline && outbox.isEmpty()) warnedOffline = false
    }

    /**
     * Records a change: shows it at once, queues it (merging with a queued change to the same
     * event) and sends the queue unless offline.
     */
    private fun enqueue(op: OutboxOp) {
        applyLocal(op.before, op.after)
        val key = op.before?.key
        val i = if (key == null) -1 else outbox.indexOfFirst { it.id != inFlightOp && it.after?.key == key }
        if (i >= 0) {
            val merged = merge(outbox[i], op)
            if (merged == null) outbox.removeAt(i) else outbox[i] = merged
        } else {
            outbox += op
        }
        saveOutbox()
        if (offline) {
            if (!warnedOffline) {
                warnedOffline = true
                toast("You're offline. Changes are saved and will sync when you're back online.")
            }
        } else {
            flush()
        }
    }

    /** Combines a queued change with a newer change to the same event; null means "nothing left to send". */
    private fun merge(existing: OutboxOp, new: OutboxOp): OutboxOp? = when (existing.kind) {
        OutboxOp.Kind.CREATE ->
            if (new.kind == OutboxOp.Kind.DELETE) null
            else existing.copy(after = new.after!!.copy(id = existing.after!!.id))
        else -> {
            val server = existing.before!!
            if (new.kind == OutboxOp.Kind.DELETE) {
                OutboxOp(existing.id, OutboxOp.Kind.DELETE, server, null)
            } else {
                val after = new.after!!
                val kind = if (after.calendarKey == server.calendarKey) OutboxOp.Kind.UPDATE else OutboxOp.Kind.MOVE
                OutboxOp(existing.id, kind, server, after)
            }
        }
    }

    /** Sends queued changes in order. Stops (keeping the rest) when the network is unreachable. */
    private fun flush() {
        if (flushing || outbox.isEmpty()) return
        flushing = true
        scope.launch {
            try {
                while (true) {
                    val op = outbox.firstOrNull() ?: break
                    inFlightOp = op.id
                    val saved: CalEvent?
                    try {
                        saved = execute(op)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        inFlightOp = null
                        if (isNetworkError(e)) {
                            offline = true
                            if (!warnedOffline) {
                                warnedOffline = true
                                toast("You're offline. Changes are saved and will sync when you're back online.")
                            }
                            break
                        }
                        // Google refused it: drop it and put the event back the way Google has it.
                        outbox.remove(op)
                        saveOutbox()
                        applyLocal(op.after, op.before)
                        toast("Couldn't save: ${errorText(e)}")
                        continue
                    }
                    inFlightOp = null
                    outbox.remove(op)
                    // Later queued changes may refer to the event as we showed it (e.g. a temporary id).
                    val shown = op.after
                    if (shown != null && saved != null) {
                        for (j in outbox.indices) {
                            val o = outbox[j]
                            outbox[j] = o.copy(
                                before = if (o.before?.key == shown.key) saved else o.before,
                                after = if (o.after?.id == shown.id && o.after.accountId == shown.accountId) o.after.copy(id = saved.id) else o.after,
                            )
                        }
                    }
                    saveOutbox()
                    applyLocal(op.after, saved)
                }
            } finally {
                inFlightOp = null
                flushing = false
            }
        }
    }

    private suspend fun execute(op: OutboxOp): CalEvent? {
        val ref = op.before ?: op.after!!
        val provider = providerFor(ref.accountId) ?: throw IllegalStateException("That account is no longer connected")
        fun calendarFor(e: CalEvent) = calendars.firstOrNull { it.key == e.calendarKey }
            ?: CalendarInfo(e.accountId, e.calendarId, e.calendarId, 0, readOnly = false, primary = false)
        return when (op.kind) {
            OutboxOp.Kind.CREATE -> provider.create(calendarFor(op.after!!), op.after.fields())
            OutboxOp.Kind.UPDATE -> provider.update(op.before!!, op.after!!.fieldsComparedTo(op.before))
            OutboxOp.Kind.MOVE -> {
                val moved = provider.moveToCalendar(op.before!!, calendarFor(op.after!!))
                provider.update(moved, op.after.fieldsComparedTo(op.before))
            }
            OutboxOp.Kind.DELETE -> {
                provider.delete(op.before!!)
                null
            }
        }
    }

    /** Called when a drag finishes. Drags never email guests (their calendars still update). */
    fun changeTiming(event: CalEvent, timing: EventTiming) {
        if (timing == event.timing || !canEdit(event)) return
        enqueue(OutboxOp(newOpId(), OutboxOp.Kind.UPDATE, event, event.copy(timing = timing, notifyGuests = false)))
    }

    /** Your reply to an invitation: "accepted", "declined" or "tentative". The organiser is told. */
    fun respond(event: CalEvent, status: String) {
        val me = event.selfAttendee ?: return
        if (me.status == status || !canEditPersonal(event)) return
        val updated = event.copy(
            attendees = event.attendees.map { if (it.self) it.copy(status = status) else it },
            notifyGuests = true,
        )
        if (editor?.original?.key == event.key) editor = editor?.copy(original = updated, draft = editor!!.draft.copy(attendees = updated.attendees))
        enqueue(OutboxOp(newOpId(), OutboxOp.Kind.UPDATE, event, updated))
    }

    fun delete(event: CalEvent) {
        if (providerFor(event.accountId) == null) return
        editor = null
        enqueue(OutboxOp(newOpId(), OutboxOp.Kind.DELETE, event, null))
    }

    // ---- editor -----------------------------------------------------------------
    fun openEditor(event: CalEvent) {
        editor = EditorState(event, event)
    }

    /** Opens the editor for a new event; the draft is drawn in the grid while the editor is open. */
    fun startNewEvent(timing: EventTiming): Boolean {
        val cal = defaultCalendar()
        if (cal == null) {
            toast("Connect a Google account (or turn on demo data) to create events")
            return false
        }
        editor = EditorState(null, CalEvent(cal.accountId, cal.id, DRAFT_ID, "", timing))
        return true
    }

    fun updateDraft(transform: (CalEvent) -> CalEvent) {
        val e = editor ?: return
        editor = e.copy(draft = transform(e.draft))
    }

    fun closeEditor() {
        editor = null
    }

    fun saveEditor() {
        val state = editor ?: return
        val draft = state.draft.let { if (it.title.isBlank()) it.copy(title = "(No title)") else it }
        val target = calendars.firstOrNull { it.key == draft.calendarKey }
        val provider = providerFor(draft.accountId)
        if (target == null || provider == null || target.readOnly) {
            toast("Choose a calendar you can edit")
            return
        }
        // Invitations: only your reply, colour and reminders can change.
        val original0 = state.original
        if (original0 != null && !original0.organizerSelf) {
            editor = null
            val personal = original0.copy(colorId = draft.colorId, reminders = draft.reminders, attendees = draft.attendees)
            if (personal != original0) enqueue(OutboxOp(newOpId(), OutboxOp.Kind.UPDATE, original0, personal))
            return
        }
        editor = null
        settings.defaultCalendar = target.key
        val original = state.original
        when {
            original == null ->
                enqueue(OutboxOp(newOpId(), OutboxOp.Kind.CREATE, null, draft.copy(id = "local-${newOpId()}")))
            original.calendarKey == draft.calendarKey -> {
                if (original == draft) return
                enqueue(OutboxOp(newOpId(), OutboxOp.Kind.UPDATE, original, draft))
            }
            original.accountId == draft.accountId ->
                enqueue(OutboxOp(newOpId(), OutboxOp.Kind.MOVE, original, draft))
            else -> {
                // Another account: create it there, then delete the original.
                enqueue(OutboxOp(newOpId(), OutboxOp.Kind.CREATE, null, draft.copy(id = "local-${newOpId()}")))
                enqueue(OutboxOp(newOpId(), OutboxOp.Kind.DELETE, original, null))
            }
        }
    }

    // ---- drag cancel & toasts ---------------------------------------------------------
    fun cancelDrag() {
        dragCancelToken++
    }

    fun toast(message: String) {
        // Also goes to stdout (Mac terminal) and logcat (Android, tag System.out) for diagnosis.
        println("[calendar] $message")
        toasts += Toast(nextToastId++, message)
        if (toasts.size > 3) toasts.removeAt(0)
    }

    fun dismissToast(id: Long) {
        toasts.removeAll { it.id == id }
    }

    /** Network trouble (no connection, DNS, timeouts) rather than an error from Google. */
    private fun isNetworkError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val name = t::class.simpleName.orEmpty()
            if (name.contains("IOException") || name.contains("UnknownHost") || name.contains("Connect") ||
                name.contains("Timeout") || name.contains("UnresolvedAddress") || name.contains("NoRouteToHost") ||
                t.message == "No network connection"
            ) return true
            t = t.cause
        }
        return false
    }

    private fun errorText(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown error"

    companion object {
        const val DRAFT_ID = "__draft__"
        const val MIN_HOUR_HEIGHT = 20f
        const val MAX_HOUR_HEIGHT = 180f
        const val ZOOM_STEP = 1.25f
        val TEXT_SIZES = listOf(0.9f to "Small", 1f to "Default", 1.15f to "Large", 1.3f to "Larger", 1.5f to "Largest")
    }
}
