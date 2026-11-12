package app.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.calendar.platform.AndroidGoogleAuth
import app.calendar.platform.DirectoryCache
import app.calendar.platform.PlatformServices
import app.calendar.platform.SharedPrefsStore
import app.calendar.ui.CalendarApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val platform = PlatformServices(
            store = SharedPrefsStore(this),
            googleAuth = AndroidGoogleAuth(this),
            // filesDir (not cacheDir) so Android doesn't clear it, and a future widget can read it.
            cache = DirectoryCache(java.io.File(filesDir, "event-cache")),
            // `adb shell am start -n app.calendar/.MainActivity --ez demo true` turns demo data on.
            forceDemo = intent?.getBooleanExtra("demo", false) == true,
        )
        setContent { CalendarApp(platform) }
    }
}
