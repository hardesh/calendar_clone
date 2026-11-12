package app.calendar

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.calendar.platform.AndroidGoogleAuth
import app.calendar.platform.DirectoryCache
import app.calendar.platform.PlatformServices
import app.calendar.platform.SharedPrefsStore
import app.calendar.reminders.ReminderSync
import app.calendar.reminders.Reminders
import app.calendar.ui.CalendarApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var platform: PlatformServices

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        platform = PlatformServices(
            store = SharedPrefsStore(this),
            googleAuth = AndroidGoogleAuth(this),
            // filesDir (not cacheDir) so Android doesn't clear it, and a future widget can read it.
            cache = DirectoryCache(java.io.File(filesDir, "event-cache")),
            // `adb shell am start -n app.calendar/.MainActivity --ez demo true` turns demo data on.
            forceDemo = intent?.getBooleanExtra("demo", false) == true,
            onUpcomingEvents = { events -> Reminders.schedule(applicationContext, events) },
            supportsReminders = true,
        )
        setContent { CalendarApp(platform) }

        Reminders.ensureChannel(this)
        ReminderSync.enqueue(this)
        if (Build.VERSION.SDK_INT >= 33 && Reminders.minutesBefore(this) > 0 && !Reminders.canNotify(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        openEventFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openEventFrom(intent)
    }

    /** A tapped reminder carries the event to open; wait briefly for the app to load it. */
    private fun openEventFrom(intent: Intent?) {
        val key = intent?.getStringExtra(Reminders.EXTRA_OPEN_EVENT) ?: return
        intent.removeExtra(Reminders.EXTRA_OPEN_EVENT)
        lifecycleScope.launch {
            repeat(30) {
                platform.openEvent?.let { open ->
                    delay(300)
                    open(key)
                    return@launch
                }
                delay(100)
            }
        }
    }
}
