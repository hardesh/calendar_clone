package app.calendar.platform

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in on Android using Google Play services: the system account picker
 * chooses which Google account to add, then the Authorization API grants calendar
 * scopes and hands out access tokens. Play services keeps the grant, so nothing
 * secret is stored by the app — only the list of connected emails.
 *
 * Requires an OAuth client of type "Android" for this package name and signing
 * certificate in the same Google Cloud project (see README).
 */
class AndroidGoogleAuth(private val activity: ComponentActivity) : GoogleAuth {
    override val isConfigured = true
    override val setupHint = "Register an Android OAuth client for app.calendar in Google Cloud (see README)."

    private val client = Identity.getAuthorizationClient(activity)
    private val scopes = listOf(
        "openid", "email", "profile",
        "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
        "https://www.googleapis.com/auth/calendar.events",
    ).map { Scope(it) }

    private var pendingPick: CompletableDeferred<ActivityResult>? = null
    private var pendingConsent: CompletableDeferred<ActivityResult>? = null

    // Launchers must be registered before the activity is started, i.e. construct this in onCreate.
    private val pickLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingPick?.complete(it)
    }
    private val consentLauncher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pendingConsent?.complete(it)
    }

    private val cache = HashMap<String, Pair<String, Long>>()
    private val mutex = Mutex()

    override suspend fun signIn(): SignInResult {
        val pick = CompletableDeferred<ActivityResult>().also { pendingPick = it }
        pickLauncher.launch(
            AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null),
        )
        val result = pick.await()
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode != Activity.RESULT_OK || email == null) throw SignInCancelledException()
        val token = mutex.withLock { authorize(email) }
        return SignInResult(email, token)
    }

    override suspend fun accessToken(email: String, forceRefresh: Boolean): String = mutex.withLock {
        val cached = cache[email]
        if (!forceRefresh && cached != null && cached.second > System.currentTimeMillis()) return@withLock cached.first
        if (forceRefresh && cached != null) {
            runCatching { client.clearToken(ClearTokenRequest.builder().setToken(cached.first).build()).await() }
            cache.remove(email)
        }
        authorize(email)
    }

    override suspend fun signOut(email: String) {
        mutex.withLock {
            cache.remove(email)?.let { (token, _) ->
                runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
            }
        }
    }

    /** Must be called with [mutex] held. Shows Google's consent screen if needed. */
    private suspend fun authorize(email: String): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .setAccount(Account(email, "com.google"))
            .build()
        var result: AuthorizationResult = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            throw IllegalStateException(describe(e), e)
        }
        if (result.hasResolution()) {
            val consent = CompletableDeferred<ActivityResult>().also { pendingConsent = it }
            consentLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
            val r = consent.await()
            if (r.resultCode != Activity.RESULT_OK) throw ReauthRequiredException(email, "Calendar access wasn't granted")
            result = try {
                client.getAuthorizationResultFromIntent(r.data)
            } catch (e: ApiException) {
                throw IllegalStateException(describe(e), e)
            }
        }
        val token = result.accessToken ?: throw ReauthRequiredException(email, "Google returned no access token")
        // Play services doesn't report expiry; Google access tokens last an hour.
        cache[email] = token to System.currentTimeMillis() + 45 * 60_000L
        return token
    }

    private fun describe(e: ApiException): String = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google sign-in isn't set up for this app: add an Android OAuth client for package app.calendar with this build's SHA-1 (see README)."
        CommonStatusCodes.NETWORK_ERROR -> "No network connection"
        CommonStatusCodes.CANCELED -> "Sign-in cancelled"
        else -> "Google sign-in failed (${CommonStatusCodes.getStatusCodeString(e.statusCode)})"
    }
}
