package app.calendar.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import kotlin.time.Duration.Companion.minutes

/** App data folder: ~/Library/Application Support/CalendarApp on macOS. */
fun desktopDataDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val dir = when {
        os.contains("mac") -> File(home, "Library/Application Support/CalendarApp")
        os.contains("win") -> File(System.getenv("APPDATA") ?: home, "CalendarApp")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "calendar-app")
    }
    dir.mkdirs()
    return dir
}

/** Settings as a small JSON object in a file; written on every change (it is tiny). */
class FileStore(private val file: File) : KeyValueStore {
    private val json = Json { prettyPrint = true }
    private val map: MutableMap<String, String> = runCatching {
        json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String) { map[key] = value; flush() }
    @Synchronized override fun remove(key: String) { if (map.remove(key) != null) flush() }

    private fun flush() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(map.mapValues { JsonPrimitive(it.value) })))
        tmp.renameTo(file)
    }
}

/** Where refresh tokens live: the macOS Keychain, or a 0600 file elsewhere. */
interface SecretStore {
    fun get(account: String): String?
    fun put(account: String, secret: String)
    fun delete(account: String)
}

class MacKeychain(private val service: String = "CalendarApp Google") : SecretStore {
    override fun get(account: String): String? {
        val p = ProcessBuilder("security", "find-generic-password", "-a", account, "-s", service, "-w")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && out.isNotEmpty()) out else null
    }

    override fun put(account: String, secret: String) {
        // Pass the secret through stdin (interactive mode) so it never appears in the process list.
        val p = ProcessBuilder("security", "-i").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use {
            it.write("add-generic-password -U -a ${quote(account)} -s ${quote(service)} -w ${quote(secret)}\n")
        }
        p.inputStream.readAllBytes()
        p.waitFor(10, TimeUnit.SECONDS)
        check(get(account) == secret) { "Couldn't save the Google sign-in to the Keychain" }
    }

    override fun delete(account: String) {
        ProcessBuilder("security", "delete-generic-password", "-a", account, "-s", service)
            .redirectErrorStream(true).start().apply { inputStream.readAllBytes(); waitFor(10, TimeUnit.SECONDS) }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Linux desktop keyring (GNOME Keyring, KWallet via the Secret Service API) through
 * `secret-tool` from libsecret-tools. The secret is passed on stdin, never as an argument.
 */
class LinuxSecretTool(private val service: String = "calendar-app-google") : SecretStore {
    override fun get(account: String): String? {
        val p = ProcessBuilder("secret-tool", "lookup", "service", service, "account", account)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && out.isNotEmpty()) out else null
    }

    override fun put(account: String, secret: String) {
        val p = ProcessBuilder("secret-tool", "store", "--label=Calendar: $account", "service", service, "account", account)
            .redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(secret) }
        val msg = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "Couldn't save the Google sign-in to the keyring: ${msg.trim()}" }
    }

    override fun delete(account: String) {
        ProcessBuilder("secret-tool", "clear", "service", service, "account", account)
            .redirectErrorStream(true).start().apply { inputStream.readAllBytes(); waitFor(10, TimeUnit.SECONDS) }
    }

    companion object {
        /** True when secret-tool is installed and a keyring answers (a lookup of a missing item exits 1, not 127). */
        fun available(): Boolean = runCatching {
            val p = ProcessBuilder("secret-tool", "lookup", "service", "calendar-app-probe")
                .redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() in 0..1 && System.getenv("DBUS_SESSION_BUS_ADDRESS") != null
        }.getOrDefault(false)
    }
}

class FileSecretStore(private val file: File) : SecretStore {
    private val store by lazy {
        if (!file.exists()) {
            file.createNewFile()
            runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        FileStore(file)
    }
    override fun get(account: String) = store.get(account)
    override fun put(account: String, secret: String) = store.put(account, secret)
    override fun delete(account: String) = store.remove(account)
}

/**
 * Google sign-in for an installed desktop app: the system browser shows Google's
 * consent page, the redirect comes back to a one-shot listener on 127.0.0.1, and
 * the code is exchanged with PKCE. Refresh tokens are kept in [secrets].
 */
class DesktopGoogleAuth(
    private val clientId: String?,
    private val clientSecret: String?,
    private val secrets: SecretStore,
) : GoogleAuth {
    override val isConfigured = !clientId.isNullOrBlank()
    override val setupHint =
        "Google sign-in isn't set up: add google.desktop.clientId and google.desktop.clientSecret to local.properties (see README)."

    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, Pair<String, Long>>() // email -> token, expiry epoch ms
    private val mutex = Mutex()
    private val random = SecureRandom()

    private val scopes = listOf(
        "openid", "email", "profile",
        "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
        "https://www.googleapis.com/auth/calendar.events",
    )

    override suspend fun signIn(): SignInResult = withContext(Dispatchers.IO) {
        if (!isConfigured) throw IllegalStateException(setupHint)
        val verifier = randomString(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomString(16)
        val result = CompletableDeferred<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val params = parseQuery(ex.requestURI.rawQuery)
            val ok = params.containsKey("code")
            val page = """
                <html><body style="font-family:-apple-system,sans-serif;text-align:center;padding-top:80px;color:#37352F">
                <h2>${if (ok) "You're signed in" else "Sign-in didn't finish"}</h2>
                <p>You can close this tab and return to Calendar.</p></body></html>
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, page.size.toLong())
            ex.responseBody.use { it.write(page) }
            if (ok || params.containsKey("error")) result.complete(params)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(
                "client_id" to clientId!!,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to scopes.joinToString(" "),
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
                "access_type" to "offline",
                "prompt" to "consent select_account",
            )
            openBrowser(url)
            val params = withTimeout(5.minutes) { result.await() }
            if (params["error"] != null) {
                if (params["error"] == "access_denied") throw SignInCancelledException()
                throw IllegalStateException("Google sign-in failed: ${params["error"]}")
            }
            check(params["state"] == state) { "Sign-in response didn't match (state mismatch)" }
            val token = postToken(
                "grant_type" to "authorization_code",
                "code" to params.getValue("code"),
                "redirect_uri" to redirect,
                "code_verifier" to verifier,
            )
            val email = token["id_token"]?.jsonPrimitive?.content?.let(::emailFromIdToken)
                ?: throw IllegalStateException("Google didn't return the account email")
            val refresh = token["refresh_token"]?.jsonPrimitive?.content
            if (refresh != null) secrets.put(email, refresh)
            else if (secrets.get(email) == null) throw IllegalStateException("Google didn't return a refresh token; remove the app's access at myaccount.google.com and try again")
            val access = token.getValue("access_token").jsonPrimitive.content
            val expires = token["expires_in"]?.jsonPrimitive?.long ?: 3600
            mutex.withLock { cache[email] = access to System.currentTimeMillis() + expires * 1000 }
            SignInResult(email, access)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw SignInCancelledException()
        } finally {
            server.stop(0)
        }
    }

    override suspend fun accessToken(email: String, forceRefresh: Boolean): String = mutex.withLock {
        val cached = cache[email]
        if (!forceRefresh && cached != null && cached.second - 60_000 > System.currentTimeMillis()) return@withLock cached.first
        val refresh = withContext(Dispatchers.IO) { secrets.get(email) }
            ?: throw ReauthRequiredException(email, "Not signed in")
        val token = try {
            withContext(Dispatchers.IO) { postToken("grant_type" to "refresh_token", "refresh_token" to refresh) }
        } catch (e: TokenException) {
            if (e.error == "invalid_grant") throw ReauthRequiredException(email, "Google sign-in expired")
            throw e
        }
        val access = token.getValue("access_token").jsonPrimitive.content
        val expires = token["expires_in"]?.jsonPrimitive?.long ?: 3600
        cache[email] = access to System.currentTimeMillis() + expires * 1000
        access
    }

    override suspend fun signOut(email: String) {
        withContext(Dispatchers.IO) {
            secrets.get(email)?.let { refresh ->
                runCatching {
                    http.send(
                        HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/revoke"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form("token" to refresh))).build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                }
            }
            secrets.delete(email)
        }
        mutex.withLock { cache.remove(email) }
    }

    private class TokenException(val error: String, message: String) : Exception(message)

    private fun postToken(vararg params: Pair<String, String>): JsonObject {
        val body = form(*(params.toList() + listOfNotNull("client_id" to clientId!!, clientSecret?.let { "client_secret" to it })).toTypedArray())
        val res = http.send(
            HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val obj = runCatching { json.parseToJsonElement(res.body()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (res.statusCode() !in 200..299) {
            val err = obj["error"]?.jsonPrimitive?.content ?: "http_${res.statusCode()}"
            val desc = obj["error_description"]?.jsonPrimitive?.content ?: err
            throw TokenException(err, "Google sign-in error: $desc")
        }
        return obj
    }

    private fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(idToken.split('.')[1]))
        json.parseToJsonElement(payload).jsonObject["email"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun openBrowser(url: String) {
        val opened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url)); true
            } else false
        }.getOrDefault(false)
        if (!opened) {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("mac") -> listOf("open", url)
                os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                else -> listOf("xdg-open", url)
            }
            ProcessBuilder(cmd).start()
        }
    }

    private fun randomString(bytes: Int) = b64url(ByteArray(bytes).also { random.nextBytes(it) })
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8) }

    private fun parseQuery(q: String?): Map<String, String> =
        q.orEmpty().split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
}

fun desktopPlatform(args: Array<String>): PlatformServices {
    val dir = desktopDataDir()
    val os = System.getProperty("os.name").lowercase()
    val secrets: SecretStore = when {
        os.contains("mac") -> MacKeychain()
        os.contains("linux") && LinuxSecretTool.available() -> LinuxSecretTool()
        // No keyring (e.g. a bare window manager): a file only this user can read.
        else -> FileSecretStore(File(dir, "secrets.json"))
    }
    // Google client, first match wins: google-oauth.properties in the data folder (lets anyone
    // use their own Google Cloud project with a downloaded build), environment variables,
    // then the client built in from local.properties.
    val fileProps = java.util.Properties().apply {
        val f = File(dir, "google-oauth.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val clientId = fileProps.getProperty("clientId")?.trim()?.ifEmpty { null }
        ?: System.getenv("GOOGLE_CLIENT_ID")
        ?: System.getProperty("google.clientId")
    val clientSecret = fileProps.getProperty("clientSecret")?.trim()?.ifEmpty { null }
        ?: System.getenv("GOOGLE_CLIENT_SECRET")
        ?: System.getProperty("google.clientSecret")
    return PlatformServices(
        store = FileStore(File(dir, "settings.json")),
        googleAuth = DesktopGoogleAuth(clientId, clientSecret, secrets),
        cache = DirectoryCache(File(dir, "cache")),
        forceDemo = "--demo" in args,
    )
}
