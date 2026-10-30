package lu.boud.gymsync.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gymsync")

data class Credentials(val email: String, val password: String)

data class SyncStatus(
    val lastSuccessAt: Long?,          // epoch millis
    val lastError: String?,
    val lastErrorAt: Long?,
    val sessionsWritten: Int,          // in the last successful run
    val consecutiveFailures: Int,
    val firstRunDone: Boolean,
    val visitsSynced: Int,             // distinct gym visits written so far
)

data class SyncTimes(val h1: Int, val m1: Int, val h2: Int, val m2: Int)

/**
 * Preferences DataStore. Credentials are encrypted with Tink AES-GCM; the keyset is wrapped by an
 * Android Keystore master key. Nothing here is ever logged.
 */
class Store(private val context: Context) {

    private object K {
        val EMAIL = stringPreferencesKey("email_enc")
        val PASSWORD = stringPreferencesKey("password_enc")
        val LAST_SUCCESS = longPreferencesKey("last_success_at")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val LAST_ERROR_AT = longPreferencesKey("last_error_at")
        val WRITTEN = intPreferencesKey("sessions_written")
        val FAILURES = intPreferencesKey("consecutive_failures")
        val FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
        val DONE_IDS = stringSetPreferencesKey("done_idcrs")
        val T1H = intPreferencesKey("t1h"); val T1M = intPreferencesKey("t1m")
        val T2H = intPreferencesKey("t2h"); val T2M = intPreferencesKey("t2m")
        val NOTIFIED_FAILURE = booleanPreferencesKey("notified_failure")
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "gymsync_keyset", "gymsync_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://gymsync_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun enc(s: String): String = Base64.encodeToString(aead.encrypt(s.toByteArray(), AAD), Base64.NO_WRAP)
    private fun dec(s: String): String = String(aead.decrypt(Base64.decode(s, Base64.NO_WRAP), AAD))

    suspend fun saveCredentials(c: Credentials) {
        val e = enc(c.email); val p = enc(c.password)
        context.dataStore.edit { it[K.EMAIL] = e; it[K.PASSWORD] = p }
    }

    suspend fun credentials(): Credentials? {
        val p = context.dataStore.data.first()
        val e = p[K.EMAIL] ?: return null
        val pw = p[K.PASSWORD] ?: return null
        return try { Credentials(dec(e), dec(pw)) } catch (ex: Exception) { null }
    }

    val hasCredentials: Flow<Boolean> = context.dataStore.data.map { it[K.EMAIL] != null && it[K.PASSWORD] != null }

    /** Log out: wipe everything, including sync history and the written-id cache. */
    suspend fun wipe() { context.dataStore.edit { it.clear() } }

    val status: Flow<SyncStatus> = context.dataStore.data.map {
        SyncStatus(it[K.LAST_SUCCESS], it[K.LAST_ERROR], it[K.LAST_ERROR_AT], it[K.WRITTEN] ?: 0, it[K.FAILURES] ?: 0, it[K.FIRST_RUN_DONE] ?: false,
            it[K.DONE_IDS]?.size ?: 0)
    }

    suspend fun statusNow(): SyncStatus = status.first()

    suspend fun recordSuccess(written: Int, newDoneIds: Set<String>, firstRunDone: Boolean) {
        context.dataStore.edit {
            it[K.LAST_SUCCESS] = System.currentTimeMillis()
            it[K.WRITTEN] = written
            it[K.FAILURES] = 0
            it.remove(K.LAST_ERROR); it.remove(K.LAST_ERROR_AT)
            it[K.NOTIFIED_FAILURE] = false
            if (firstRunDone) it[K.FIRST_RUN_DONE] = true
            if (newDoneIds.isNotEmpty()) it[K.DONE_IDS] = (it[K.DONE_IDS] ?: emptySet()) + newDoneIds
        }
    }

    /** Returns the new consecutive-failure count. */
    suspend fun recordFailure(message: String): Int {
        var n = 0
        context.dataStore.edit {
            n = (it[K.FAILURES] ?: 0) + 1
            it[K.FAILURES] = n
            it[K.LAST_ERROR] = message.take(300)
            it[K.LAST_ERROR_AT] = System.currentTimeMillis()
        }
        return n
    }

    suspend fun addDoneIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { it[K.DONE_IDS] = (it[K.DONE_IDS] ?: emptySet()) + ids }
    }

    suspend fun markFailureNotified(): Boolean {
        var already = false
        context.dataStore.edit { already = it[K.NOTIFIED_FAILURE] ?: false; it[K.NOTIFIED_FAILURE] = true }
        return already
    }

    suspend fun doneIds(): Set<String> = context.dataStore.data.first()[K.DONE_IDS] ?: emptySet()

    val syncTimes: Flow<SyncTimes> = context.dataStore.data.map {
        SyncTimes(it[K.T1H] ?: DEFAULT.h1, it[K.T1M] ?: DEFAULT.m1, it[K.T2H] ?: DEFAULT.h2, it[K.T2M] ?: DEFAULT.m2)
    }

    suspend fun syncTimesNow(): SyncTimes = syncTimes.first()

    suspend fun setSyncTimes(t: SyncTimes) {
        context.dataStore.edit { it[K.T1H] = t.h1; it[K.T1M] = t.m1; it[K.T2H] = t.h2; it[K.T2M] = t.m2 }
    }

    companion object {
        private val AAD = "gymsync".toByteArray()
        val DEFAULT = SyncTimes(14, 0, 21, 0)
    }
}
