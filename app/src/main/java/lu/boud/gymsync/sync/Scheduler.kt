package lu.boud.gymsync.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import lu.boud.gymsync.data.Store
import lu.boud.gymsync.data.SyncTimes
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Two syncs per day at user-chosen local times (default 14:00 and 21:00), implemented as a
 * one-time job that enqueues the next one when it finishes (and on app start / settings change).
 * Two alternating unique names (A/B) so a finishing job never replaces itself.
 * WorkManager may shift a run by some minutes; with battery "unrestricted" it is usually on time.
 */
object Scheduler {
    const val SLOT_A = "gymsync-slot-a"
    const val SLOT_B = "gymsync-slot-b"
    const val NOW = "gymsync-now"
    const val RETRY = "gymsync-retry"
    const val KEY_NAME = "name"
    const val KEY_IS_RETRY = "isRetry"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun nextRun(times: SyncTimes, now: ZonedDateTime): ZonedDateTime {
        val slots = listOf(LocalTime.of(times.h1, times.m1), LocalTime.of(times.h2, times.m2)).sorted()
        val candidates = (0..1).flatMap { d -> slots.map { now.toLocalDate().plusDays(d.toLong()).atTime(it).atZone(now.zone) } }
        return candidates.first { it.isAfter(now.plusSeconds(30)) }
    }

    /** After login or a times change: cancels pending slots and enqueues the next one. */
    suspend fun reschedule(context: Context, store: Store) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(SLOT_A); wm.cancelUniqueWork(SLOT_B)
        enqueueSlot(context, store, SLOT_A)
    }

    /** On process start: enqueue only if no slot is pending (never cancel a job that may be starting right now). */
    fun ensureScheduled(context: Context, store: Store) {
        val wm = WorkManager.getInstance(context)
        val pending = listOf(SLOT_A, SLOT_B).any { name ->
            wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }
        }
        if (!pending) kotlinx.coroutines.runBlocking { enqueueSlot(context, store, SLOT_A) }
    }

    /** Called by a finishing scheduled job: enqueues the other slot name. */
    suspend fun scheduleNext(context: Context, store: Store, finishedName: String?) {
        enqueueSlot(context, store, if (finishedName == SLOT_A) SLOT_B else SLOT_A)
    }

    private suspend fun enqueueSlot(context: Context, store: Store, name: String) {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val delay = Duration.between(now, nextRun(store.syncTimesNow(), now))
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_NAME, name).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    fun syncNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(Data.Builder().putString(KEY_NAME, NOW).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** One extra attempt 30 minutes after a retryable (network/server) failure. Never chains. */
    fun scheduleRetry(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putString(KEY_NAME, RETRY).putBoolean(KEY_IS_RETRY, true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.KEEP, req)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        listOf(SLOT_A, SLOT_B, NOW, RETRY).forEach { wm.cancelUniqueWork(it) }
    }
}
