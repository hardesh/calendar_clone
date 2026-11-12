package app.calendar.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon

/** Small string key/value storage for settings and account lists. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class InMemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/** Result of signing in: who the account is and a usable access token. */
data class SignInResult(val email: String, val accessToken: String)

/** Thrown when an account's stored grant no longer works and the user must reconnect. */
class ReauthRequiredException(val accountId: String, message: String) : Exception(message)

/** Thrown when the user backs out of sign-in. */
class SignInCancelledException : Exception("Sign-in cancelled")

/**
 * Google single sign-on for one platform. Implementations keep whatever they need
 * to hand out fresh access tokens for each connected account.
 */
interface GoogleAuth {
    /** False when this build has no OAuth client configured (desktop without local.properties). */
    val isConfigured: Boolean
    val setupHint: String

    /** Lets the user pick/sign in to a Google account and grant calendar access. */
    suspend fun signIn(): SignInResult

    /** A valid access token for [email]. [forceRefresh] discards any cached token first. */
    suspend fun accessToken(email: String, forceRefresh: Boolean = false): String

    suspend fun signOut(email: String)
}

object NoGoogleAuth : GoogleAuth {
    override val isConfigured = false
    override val setupHint = "Google sign-in is not available in this build."
    override suspend fun signIn(): SignInResult = error(setupHint)
    override suspend fun accessToken(email: String, forceRefresh: Boolean): String = error(setupHint)
    override suspend fun signOut(email: String) {}
}

class PlatformServices(
    val store: KeyValueStore,
    val googleAuth: GoogleAuth,
    /** Where last-known calendars and events are kept for instant start and offline use. */
    val cache: app.calendar.data.FileCache = app.calendar.data.InMemoryFileCache(),
    /** Start with demo data on, e.g. from a `--demo` flag. */
    val forceDemo: Boolean = false,
)

/** Mouse cursor for resizing an event vertically; plain arrow where there is no such cursor. */
expect val ResizeVerticalPointerIcon: PointerIcon

/** Android back gesture; no-op on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
