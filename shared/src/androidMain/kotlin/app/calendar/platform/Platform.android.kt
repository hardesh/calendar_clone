package app.calendar.platform

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon

actual val ResizeVerticalPointerIcon: PointerIcon = PointerIcon.Default

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled, onBack)
}

/** SharedPreferences-backed store; a future home-screen widget can read the same file. */
class SharedPrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("calendar_prefs", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}
