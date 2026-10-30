package lu.boud.gymsync.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** All three X-MWAPPS-* values in one place. Technogym can rotate them. */
object ApiConstants {
    const val APP_ID = "EC1D38D7-D359-48D0-A60C-D8C0B8FB9DF9"
    const val CLIENT = "enduserweb"
    const val CLIENT_VERSION = "2.0.1-1021,enduserweb"
    const val CORE_BASE = "https://core.mywellness.com"
    const val SERVICES_BASE = "https://services.mywellness.com"
    const val CULTURE = "en-US"
}

sealed class ApiException(message: String) : Exception(message) {
    class Network(message: String) : ApiException(message)
    class Http(val code: Int, message: String) : ApiException(message)
    class BadCredentials : ApiException("Wrong e-mail or password")
    class MfaRequired : ApiException("Two-factor login isn't supported")
    class TokenNotValid : ApiException("Session token expired")
    class Server(message: String) : ApiException(message)
    class Parse(message: String) : ApiException(message)
}

data class Session(val token: String, val userId: String, val facilityUrl: String)

/**
 * Thin, fail-closed HTTP client for the mywellness API. Every response must parse exactly;
 * anything else throws and the caller writes nothing. Never logs bodies, tokens or passwords.
 */
class MywellnessApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val coreBase: String = ApiConstants.CORE_BASE,
    private val servicesBase: String = ApiConstants.SERVICES_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    @Volatile
    private var token: String? = null

    fun currentToken(): String? = token

    /** Logs in and returns the session. Throws BadCredentials / MfaRequired / others. */
    fun login(email: String, password: String): Session {
        val body = json.encodeToString(LoginRequest(email, password))
        val (code, text) = post("$coreBase/v2/enduser/authentication/login", body, auth = false)
        val resp = parse(text, serializer<LoginResponse>())
        if (code == 401) {
            if (resp.result.equals("MfaRequired", ignoreCase = true)) throw ApiException.MfaRequired()
            throw ApiException.BadCredentials()
        }
        if (code != 200) throw ApiException.Http(code, "Login failed with HTTP $code")
        when (resp.result) {
            "Success" -> Unit
            "MfaRequired", "MfaLastAttempt" -> throw ApiException.MfaRequired()
            else -> throw ApiException.Server("Login result: ${resp.result ?: "missing"}")
        }
        val tok = resp.token ?: throw ApiException.Parse("Login response has no token")
        val userId = resp.userContext?.id ?: throw ApiException.Parse("Login response has no userContext.id")
        val facility = resp.facilities?.firstOrNull { !it.isDemo }
            ?: throw ApiException.Parse("Login response has no facility")
        token = tok
        return Session(tok, userId, facility.url)
    }

    fun activityHistory(session: Session, pager: String? = null): ActivityHistory {
        val url = "$servicesBase/${session.facilityUrl}/Training/User/${session.userId}/ActivityHistory?_c=${ApiConstants.CULTURE}"
        val body = json.encodeToString(ActivityHistoryRequest(pager = pager))
        return call(url, body, serializer<Envelope<ActivityHistory>>())
    }

    fun sessionDetail(session: Session, idCr: Long, partitionDate: Int): SessionDetail {
        val url = "$servicesBase/${session.facilityUrl}/Training/User/${session.userId}/GetPerformedWorkoutSessionByIdCr?_c=${ApiConstants.CULTURE}"
        val body = json.encodeToString(SessionDetailRequest(idCr, partitionDate))
        return call(url, body, serializer<Envelope<SessionDetail>>())
    }

    private fun <T : Any> call(url: String, body: String, ser: KSerializer<Envelope<T>>): T {
        val (code, text) = post(url, body, auth = true)
        if (code == 401) throw ApiException.TokenNotValid()
        if (code == 403) throw ApiException.Http(code, "Permission denied")
        if (code == 429) throw ApiException.Http(code, "Rate limited (429)")
        if (code !in 200..299) throw ApiException.Http(code, "HTTP $code")
        val env = parse(text, ser)
        env.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
            val tokenErr = errs.any { it.type == "Security" && (it.field == "TokenNotValid" || it.details == "TokenNotValid") }
            if (tokenErr) throw ApiException.TokenNotValid()
            throw ApiException.Server(errs.joinToString("; ") { it.errorMessage ?: it.field ?: it.type ?: "error" })
        }
        env.token?.let { token = it }
        return env.data ?: throw ApiException.Parse("Response has no data")
    }

    private fun <T> parse(text: String, ser: KSerializer<T>): T = try {
        json.decodeFromString(ser, text)
    } catch (e: Exception) {
        throw ApiException.Parse("Unexpected response format (${e::class.simpleName})")
    }

    /** Returns (httpCode, bodyText). Network problems become ApiException.Network. */
    private fun post(url: String, body: String, auth: Boolean): Pair<Int, String> {
        val b = Request.Builder().url(url)
            .post(body.toRequestBody(jsonType))
            .header("Content-Type", "application/json")
            .header("X-MWAPPS-APPID", ApiConstants.APP_ID)
            .header("X-MWAPPS-CLIENT", ApiConstants.CLIENT)
            .header("X-MWAPPS-CLIENTVERSION", ApiConstants.CLIENT_VERSION)
        if (auth) {
            val t = token ?: throw ApiException.TokenNotValid()
            b.header("Authorization", "Bearer $t")
        }
        try {
            client.newCall(b.build()).execute().use { r ->
                val text = r.body.string()
                return r.code to text
            }
        } catch (e: IOException) {
            throw ApiException.Network("Network error: ${e::class.simpleName}")
        }
    }
}
