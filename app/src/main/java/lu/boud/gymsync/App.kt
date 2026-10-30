package lu.boud.gymsync

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lu.boud.gymsync.data.Store
import lu.boud.gymsync.sync.Notifier
import lu.boud.gymsync.sync.Scheduler

class App : Application() {
    val store: Store by lazy { Store(this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        appScope.launch {
            if (store.hasCredentials.first()) Scheduler.ensureScheduled(this@App, store)
        }
    }
}
