package app.calendar.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.calendar.data.CalendarProvider
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
import app.calendar.model.EventTiming
import app.calendar.model.ViewMode
import app.calendar.model.ZoneEntry
import app.calendar.model.fields
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
import kotlin.time.Duration.Companion.minutes
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
    private val clock: () -> Instant = { Clock.System.now() },
    forceDemo: Boolean = false,
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
    var loadingCount by mutableIntStateOf(0)
        private set

    /** Mutations not yet confirmed by the server; re-applied over freshly loaded windows. */
    private val pending = LinkedHashMap<Long, Pair<CalEvent?, CalEvent?>>()
    private var nextPendingId = 0L

    // ---- UI chrome ----------------------------------------------------------------
    val toasts = mutableStateListOf<Toast>()
    private var nextToastId = 0L
    var editor by mutableStateOf<EditorState?>(null)
        private set
    /** Bumped to cancel an in-progress drag (Esc). Gesture code compares against its start value. */
    var dragCancelToken by mutableIntStateOf(0)
        private set

    init {
        if (forceDemo && !settings.demoEnabled) settings.demoEnabled = true
    }

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
        scope.launch {
            reloadAccounts()
            ensureLoaded()
            while (true) {
                delay(5.minutes)
                refreshEvents(showErrors = false)
            }
        }
    }

    suspend fun reloadAccounts() {
        val list = activeProviders().flatMap { it.accounts() }
        accounts = list
        val cals = ArrayList<CalendarInfo>()
        for (account in list) {
            val provider = providerFor(account.id) ?: continue
            try {
                cals += provider.calendars(account)
                accountErrors.remove(account.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                accountErrors[account.id] = errorText(e)
                if (e is ReauthRequiredException) toast("${account.email}: sign in again to keep syncing")
                else toast("Couldn't load calendars for ${account.email}: ${errorText(e)}")
            }
        }
        applyDefaultVisibility(cals)
        calendars = cals
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
        windows.keys.filter { it !in keep }.forEach { windows.remove(it) }
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
        windows.clear()
        ensureLoaded()
    }

    fun changeDemoEnabled(enabled: Boolean) {
        demoEnabled = enabled
        settings.demoEnabled = enabled
        windows.clear()
        scope.launch {
            reloadAccounts()
            ensureLoaded()
        }
    }

    // ---- time zones ------------------------------------------------------------
    fun updateZones(list: List<ZoneEntry>) {
        if (list.isEmpty()) return
        val primaryChanged = list.first().id != zones.firstOrNull()?.id
        zones = list
        settings.zones = list
        if (primaryChanged) {
            windows.clear()
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

    fun canEdit(event: CalEvent): Boolean = calendarOf(event)?.readOnly == false

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
            windows.clear()
            reloadAccounts()
            ensureLoaded()
        }
    }

    // ---- loading ----------------------------------------------------------------
    private fun windowStartFor(date: LocalDate) = monthGridStart(date, weekStart)

    /** Window starts needed for what is on screen (a 3-day view can cross a month boundary). */
    private fun currentWindows(): Set<LocalDate> {
        val days = visibleDays()
        return setOf(windowStartFor(days.first()), windowStartFor(days.last())).let { set ->
            if (viewMode == ViewMode.MONTH) setOf(monthGridStart(anchor, weekStart)) else set
        }
    }

    fun ensureLoaded() {
        for (w in currentWindows()) {
            if (w !in windows && loadingWindows[w]?.isActive != true) {
                loadingWindows[w] = scope.launch { loadWindow(w, force = false, showErrors = true) }
            }
        }
    }

    private suspend fun loadWindow(start: LocalDate, force: Boolean, showErrors: Boolean) {
        if (!force && start in windows) return
        loadingCount++
        try {
            val zone = primaryZone
            val from = start.atStartOfDayIn(zone)
            val to = start.plusDays(MONTH_GRID_DAYS).atStartOfDayIn(zone)
            val cals = calendars
            val errors = ArrayList<String>()
            val events = coroutineScope {
                cals.map { cal ->
                    async {
                        try {
                            providerFor(cal.accountId)?.events(cal, from, to).orEmpty()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is ReauthRequiredException) accountErrors[cal.accountId] = errorText(e)
                            errors += "${cal.name}: ${errorText(e)}"
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            var list = events
            for ((old, new) in pending.values) list = applyEdit(list, old, new, start)
            windows[start] = list
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
        for (k in windows.keys.toList()) windows[k] = applyEdit(windows[k].orEmpty(), old, new, k)
    }

    /**
     * Shows [optimistic] at once, runs [remote], then swaps in the server's version.
     * On failure restores [original] and shows a toast.
     */
    private fun mutate(original: CalEvent?, optimistic: CalEvent?, remote: suspend () -> CalEvent?) {
        applyLocal(original, optimistic)
        val id = nextPendingId++
        pending[id] = original to optimistic
        scope.launch {
            try {
                val saved = remote()
                pending.remove(id)
                applyLocal(optimistic, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending.remove(id)
                applyLocal(optimistic, original)
                toast("Couldn't save: ${errorText(e)}")
            }
        }
    }

    /** Called when a drag finishes. */
    fun changeTiming(event: CalEvent, timing: EventTiming) {
        if (timing == event.timing || !canEdit(event)) return
        val provider = providerFor(event.accountId) ?: return
        val updated = event.copy(timing = timing)
        mutate(event, updated) { provider.update(event, updated.fields()) }
    }

    fun delete(event: CalEvent) {
        val provider = providerFor(event.accountId) ?: return
        editor = null
        mutate(event, null) {
            provider.delete(event)
            null
        }
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
        editor = null
        settings.defaultCalendar = target.key
        val original = state.original
        when {
            original == null -> {
                val temp = draft.copy(id = "local-${nextPendingId}")
                mutate(null, temp) { provider.create(target, draft.fields()) }
            }
            original.calendarKey == draft.calendarKey -> {
                if (original == draft) return
                mutate(original, draft) { provider.update(original, draft.fields()) }
            }
            original.accountId == draft.accountId -> mutate(original, draft) {
                val moved = provider.moveToCalendar(original, target)
                provider.update(moved, draft.fields())
            }
            else -> {
                val oldProvider = providerFor(original.accountId) ?: return
                mutate(original, draft) {
                    val created = provider.create(target, draft.fields())
                    oldProvider.delete(original)
                    created
                }
            }
        }
    }

    // ---- drag cancel & toasts ---------------------------------------------------------
    fun cancelDrag() {
        dragCancelToken++
    }

    fun toast(message: String) {
        toasts += Toast(nextToastId++, message)
        if (toasts.size > 3) toasts.removeAt(0)
    }

    fun dismissToast(id: Long) {
        toasts.removeAll { it.id == id }
    }

    private fun errorText(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown error"

    companion object {
        const val DRAFT_ID = "__draft__"
    }
}
