package lu.boud.gymsync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import lu.boud.gymsync.App
import lu.boud.gymsync.api.MywellnessApi

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val store = app.store
        val name = inputData.getString(Scheduler.KEY_NAME)
        val isRetry = inputData.getBoolean(Scheduler.KEY_IS_RETRY, false)
        val creds = store.credentials() ?: return Result.success() // logged out: nothing to do, nothing scheduled
        try {
            val status = store.statusNow()
            val engine = SyncEngine(MywellnessApi(), { HcWriter(applicationContext).write(it) }, { delay(it) })
            val outcome = engine.run(creds, status.firstRunDone, store.doneIds())
            if (outcome.error == null) {
                store.recordSuccess(outcome.sessionsWritten, outcome.newDoneIds, firstRunDone = true)
            } else {
                store.addDoneIds(outcome.newDoneIds)
                val n = store.recordFailure(outcome.error)
                Notifier.maybeNotifyFailures(applicationContext, store, n)
                if (outcome.retryable && !isRetry) Scheduler.scheduleRetry(applicationContext)
            }
        } catch (e: Exception) {
            val n = store.recordFailure("Unexpected: ${e::class.simpleName}: ${e.message}")
            Notifier.maybeNotifyFailures(applicationContext, store, n)
        } finally {
            if (name == Scheduler.SLOT_A || name == Scheduler.SLOT_B) Scheduler.scheduleNext(applicationContext, store, name)
        }
        return Result.success()
    }
}
