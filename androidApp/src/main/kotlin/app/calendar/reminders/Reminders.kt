package app.calendar.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.calendar.MainActivity
import app.calendar.R
import app.calendar.data.EventCache
import app.calendar.model.EventTiming
import app.calendar.model.UpcomingEvent
import app.calendar.platform.AndroidGoogleAuth
import app.calendar.platform.DirectoryCache
import app.calendar.platform.SharedPrefsStore
import app.calendar.state.AppSettings
import app.calendar.ui.createGoogleProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Notifications before events. Each reminder is an exact alarm; the list is rebuilt whenever
 * the app's upcoming events change, hourly in the background, and after a reboot.
 */
object Reminders {
    private const val PREFS = "calendar_prefs"
    private const val KEY_CODES = "reminders.codes"
    private const val KEY_UPCOMING = "reminders.upcoming"
    private const val KEY_FIRED = "reminders.fired"
    const val CHANNEL = "upcoming"
    const val ACTION = "app.calendar.REMINDER"
    const val EXTRA_OPEN_EVENT = "openEvent"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun minutesBefore(context: Context): Int = AppSettings(SharedPrefsStore(context)).reminderMinutes

    private fun code(e: UpcomingEvent) = (e.key + "@" + e.start.toEpochMilliseconds()).hashCode()

    private fun alarmIntent(context: Context, e: UpcomingEvent?) = Intent(context, ReminderReceiver::class.java).setAction(ACTION).apply {
        if (e != null) {
            putExtra("key", e.key)
            putExtra("title", e.title)
            putExtra("start", e.start.toEpochMilliseconds())
            putExtra("end", e.end.toEpochMilliseconds())
            putExtra("color", e.color)
            e.location?.let { putExtra("location", it) }
        }
    }

    /** Replaces all scheduled reminders with ones for [events] (demo events are skipped). */
    fun schedule(context: Context, events: List<UpcomingEvent>) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val alarms = app.getSystemService(AlarmManager::class.java)
        prefs.getStringSet(KEY_CODES, emptySet()).orEmpty().forEach { c ->
            PendingIntent.getBroadcast(app, c.toInt(), alarmIntent(app, null), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { alarms.cancel(it); it.cancel() }
        }
        val real = events.filter { !it.key.startsWith("demo|") }
        prefs.edit().putString(KEY_UPCOMING, encode(real)).apply()
        val minutes = minutesBefore(app)
        if (minutes <= 0) {
            prefs.edit().putStringSet(KEY_CODES, emptySet()).apply()
            return
        }
        val now = Clock.System.now()
        val fired = prefs.getStringSet(KEY_FIRED, emptySet()).orEmpty()
        val codes = HashSet<String>()
        for (e in real) {
            if (e.start <= now) continue
            val c = code(e)
            if (c.toString() in fired) continue
            // If the reminder time has just passed (e.g. event added 5 minutes before it starts), notify now.
            val trigger = maxOf(e.start.toEpochMilliseconds() - minutes * 60_000L, now.toEpochMilliseconds() + 1_000)
            val pi = PendingIntent.getBroadcast(app, c, alarmIntent(app, e), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
            codes += c.toString()
        }
        prefs.edit().putStringSet(KEY_CODES, codes).apply()
    }

    /** Re-arms reminders from the last saved list (after a reboot or a time change). */
    fun rescheduleFromSaved(context: Context) {
        val saved = prefs(context).getString(KEY_UPCOMING, null) ?: return
        schedule(context, decode(saved))
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Upcoming events", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A notification shortly before each event"
                },
            )
        }
    }

    fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun show(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val title = intent.getStringExtra("title").orEmpty()
        val start = intent.getLongExtra("start", 0)
        val end = intent.getLongExtra("end", 0)
        val location = intent.getStringExtra("location")
        val code = (key + "@" + start).hashCode()
        val prefs = prefs(context)
        val fired = (prefs.getStringSet(KEY_FIRED, emptySet()).orEmpty() + code.toString()).toList().takeLast(300).toSet()
        prefs.edit().putStringSet(KEY_FIRED, fired).apply()
        if (!canNotify(context)) return
        ensureChannel(context)

        val minutesLeft = ((start - System.currentTimeMillis()) / 60_000.0).let { kotlin.math.ceil(it).toLong() }
        val whenText = when {
            minutesLeft <= 0 -> "Now"
            minutesLeft < 60 -> "In $minutesLeft min"
            else -> "In ${minutesLeft / 60} h ${minutesLeft % 60} min".replace(" 0 min", "")
        }
        val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        val range = "${fmt.format(Date(start))} – ${fmt.format(Date(end))}"
        val text = listOfNotNull("$whenText · $range", location).joinToString("\n")
        val open = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_EVENT, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(intent.getLongExtra("color", 0xFFEB5757L).toInt())
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(start)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(code, notification)
    }

    private fun encode(events: List<UpcomingEvent>): String = JSONArray().apply {
        events.forEach { e ->
            put(JSONObject().put("k", e.key).put("t", e.title).put("s", e.start.toEpochMilliseconds())
                .put("e", e.end.toEpochMilliseconds()).put("c", e.color).put("l", e.location ?: JSONObject.NULL))
        }
    }.toString()

    private fun decode(text: String): List<UpcomingEvent> = runCatching {
        val arr = JSONArray(text)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            UpcomingEvent(o.getString("k"), o.getString("t"), Instant.fromEpochMilliseconds(o.getLong("s")),
                Instant.fromEpochMilliseconds(o.getLong("e")), o.getLong("c"), o.optString("l").takeIf { !o.isNull("l") })
        }
    }.getOrDefault(emptyList())
}

/** Fires at reminder time and shows the notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Reminders.ACTION) Reminders.show(context, intent)
    }
}

/** Alarms don't survive a reboot or a clock change; set them again and restart the background sync. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleFromSaved(context)
        ReminderSync.enqueue(context)
    }
}

/** Hourly background fetch of the next two days, so reminders are right even if the app isn't opened. */
object ReminderSync {
    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class ReminderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SharedPrefsStore(ctx)
        val settings = AppSettings(store)
        if (settings.reminderMinutes <= 0) return Result.success()
        val provider = createGoogleProvider(AndroidGoogleAuth(ctx), store)
        val accounts = provider.accounts().map { it.id }.toSet()
        if (accounts.isEmpty()) return Result.success()
        val hidden = settings.hiddenCalendars
        val calendars = EventCache(DirectoryCache(File(ctx.filesDir, "event-cache"))).loadCalendars()
            .filter { it.accountId in accounts && it.key !in hidden }
        val now = Clock.System.now()
        val upcoming = ArrayList<UpcomingEvent>()
        for (cal in calendars) {
            val events = try {
                provider.events(cal, now - 1.hours, now + 48.hours)
            } catch (e: Exception) {
                return Result.retry()
            }
            events.filter { it.timing is EventTiming.Timed }.mapTo(upcoming) {
                val t = it.timing as EventTiming.Timed
                UpcomingEvent(it.key, it.title, t.start, t.end, it.colorOverride ?: cal.color, it.location)
            }
        }
        Reminders.schedule(ctx, upcoming.sortedBy { it.start })
        return Result.success()
    }
}
