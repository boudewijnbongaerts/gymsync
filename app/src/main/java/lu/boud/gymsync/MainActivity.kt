package lu.boud.gymsync

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lu.boud.gymsync.api.ApiException
import lu.boud.gymsync.api.MywellnessApi
import lu.boud.gymsync.data.Credentials
import lu.boud.gymsync.data.SyncStatus
import lu.boud.gymsync.data.SyncTimes
import lu.boud.gymsync.sync.HcWriter
import lu.boud.gymsync.sync.Scheduler
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val app get() = application as App
    private val resumeTick = mutableStateOf(0)

    override fun onResume() { super.onResume(); resumeTick.value++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GymSyncTheme { Scaffold { pad -> Screen(Modifier.padding(pad)) } } }
    }

    // ---------------------------------------------------------------- screen

    @Composable
    private fun Screen(modifier: Modifier) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val store = app.store
        val hasCreds by store.hasCredentials.collectAsState(initial = null)
        val status by store.status.collectAsState(initial = null)
        val times by store.syncTimes.collectAsState(initial = null)
        val runningFlow = remember(context) { isSyncRunning(context) }
        val running by runningFlow.collectAsState(initial = false)

        var hcAvailable by remember { mutableStateOf(HcWriter.sdkAvailable(context)) }
        var granted by remember { mutableStateOf<Boolean?>(null) }
        var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
        var notifOk by remember { mutableStateOf(hasNotificationPermission(context)) }

        suspend fun refresh() {
            hcAvailable = HcWriter.sdkAvailable(context)
            granted = if (hcAvailable) HcWriter(context).grantedAll() else false
            unrestricted = isBatteryUnrestricted(context)
            notifOk = hasNotificationPermission(context)
        }
        val tick by resumeTick
        LaunchedEffect(tick) { refresh() }

        val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            scope.launch { refresh() }
        }
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }

        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            when {
                !hcAvailable -> InfoCard(Icons.Default.Warning, "Health Connect is not available",
                    "Install or update Health Connect, then reopen GymSync.")
                hasCreds == null || times == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                hasCreds == false -> { WelcomeCard(); LoginCard { email, pw, onError ->
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            try { MywellnessApi().login(email, pw); null } catch (e: ApiException) { e.message ?: "Login failed" }
                        }
                        if (err != null) onError(err) else {
                            store.saveCredentials(Credentials(email, pw))
                            Scheduler.reschedule(context, store)
                            refresh()
                        }
                    }
                } }
                granted == false -> StepCard(
                    icon = Icons.Default.Lock, title = "Connect Google Health",
                    text = "Allow GymSync to add workouts.",
                    button = "Allow", onClick = { hcLauncher.launch(HcWriter.PERMISSIONS) })
                !unrestricted -> StepCard(
                    icon = Icons.Default.Info, title = "Allow background sync",
                    text = "Twice a day, even when you don't open the app.",
                    button = "Allow", onClick = { requestUnrestricted(context) },
                    skip = { unrestricted = true })
                else -> ReadyPanel(
                    status = status, times = times!!, running = running, notifOk = notifOk,
                    onSyncNow = { Scheduler.syncNow(context) },
                    onTimes = { t -> scope.launch { store.setSyncTimes(t); Scheduler.reschedule(context, store) } },
                    onNotif = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onLogout = { scope.launch { Scheduler.cancelAll(context); store.wipe(); refresh() } },
                )
            }
        }
    }

    @Composable
    private fun Header() {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Image(painterResource(R.drawable.logo_art), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(14.dp))
            Text("GymSync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }

    // ---------------------------------------------------------------- cards

    @Composable
    private fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Column { Text(title, fontWeight = FontWeight.SemiBold); Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    @Composable
    private fun StepCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, button: String, onClick: () -> Unit, skip: (() -> Unit)? = null) {
        ElevatedCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
                if (skip != null) TextButton(onClick = skip) { Text("Skip for now") }
            }
        }
    }

    @Composable
    private fun LoginCard(onLogin: (String, String, (String) -> Unit) -> Unit) {
        var email by remember { mutableStateOf("") }
        var pw by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        ElevatedCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect your Technogym account", style = MaterialTheme.typography.titleMedium)
                Text("Your details stay encrypted on this phone and are only used to fetch your workouts.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !busy && email.isNotBlank() && pw.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true; error = null
                    onLogin(email.trim(), pw) { e -> error = e; busy = false }
                }) { Text(if (busy) "Checking…" else "Log in") }
            }
        }
    }

    @Composable
    private fun ReadyPanel(
        status: SyncStatus?, times: SyncTimes, running: Boolean, notifOk: Boolean,
        onSyncNow: () -> Unit, onTimes: (SyncTimes) -> Unit, onNotif: () -> Unit, onLogout: () -> Unit,
    ) {
        val context = LocalContext.current
        val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        val ok = status?.lastError == null && status?.lastSuccessAt != null
        val never = status?.lastSuccessAt == null

        // Status card
        ElevatedCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        ok -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                        never -> Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            running -> "Syncing…"
                            ok -> "Up to date"
                            never -> "Not synced yet"
                            else -> "Last sync had a problem"
                        }, style = MaterialTheme.typography.titleMedium)
                }
                val visits = status?.visitsSynced ?: 0
                Text(if (visits == 1) "1 gym visit in Google Health" else "$visits gym visits in Google Health", style = MaterialTheme.typography.bodyMedium)
                Text("Last sync: " + (status?.lastSuccessAt?.let { df.format(Date(it)) } ?: "never"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status != null && status.lastSuccessAt != null && status.sessionsWritten > 0)
                    Text("+${status.sessionsWritten} workouts in the last run", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.lastError?.let { err ->
                    val at = status.lastErrorAt?.let { df.format(Date(it)) } ?: ""
                    Text("$at: $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (status.consecutiveFailures > 1) Text("${status.consecutiveFailures} failures in a row", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = !running, onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Sync now")
                }
            }
        }

        // Schedule card
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Automatic sync times", style = MaterialTheme.typography.titleMedium)
                TimeRow("Afternoon", times.h1, times.m1) { h, m -> onTimes(times.copy(h1 = h, m1 = m)) }
                TimeRow("Evening", times.h2, times.m2) { h, m -> onTimes(times.copy(h2 = h, m2 = m)) }
            }
        }

        // Notifications card
        Card {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Problem alerts", style = MaterialTheme.typography.titleSmall)
                    Text(if (notifOk) "On: you'll be told after 3 failed syncs in a row." else "Tell me if syncing keeps failing.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!notifOk) TextButton(onClick = onNotif) { Text("Allow") }
            }
        }

        var confirmLogout by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(context, RationaleActivity::class.java)) }, modifier = Modifier.weight(1f)) { Text("Privacy") }
            OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.weight(1f)) { Text("Log out") }
        }
        if (confirmLogout) AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("This removes your account and history from this phone. Workouts already in Google Health stay.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
        Spacer(Modifier.height(16.dp))
    }

    @Composable
    private fun WelcomeCard() {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your gym's Technogym machines record every workout, but Google Health never sees them.",
                    style = MaterialTheme.typography.bodyLarge)
                Text("GymSync syncs your workouts from Technogym to Google Health. Automatically, twice a day.",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    private fun TimeRow(label: String, h: Int, m: Int, onPick: (Int, Int) -> Unit) {
        val context = LocalContext.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { TimePickerDialog(context, { _, hh, mm -> onPick(hh, mm) }, h, m, true).show() }) {
                Text("%02d:%02d".format(h, m), style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun isSyncRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow("lu.boud.gymsync.sync.SyncWorker")
            .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }

    private fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun requestUnrestricted(context: Context) {
        @Suppress("BatteryLife")
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        try { context.startActivity(i) } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
