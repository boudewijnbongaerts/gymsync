package lu.boud.gymsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Privacy page. Also opened by Health Connect's "privacy policy" link (ACTION_SHOW_PERMISSIONS_RATIONALE / VIEW_PERMISSION_USAGE). */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GymSyncTheme { Scaffold { pad -> PrivacyScreen(Modifier.padding(pad)) } } }
    }

    @Composable
    private fun PrivacyScreen(modifier: Modifier) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Privacy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("GymSync reads your finished workouts from your Technogym account and adds them to Health Connect on this phone, " +
                        "where Google Health picks them up. Nothing else.", style = MaterialTheme.typography.bodyMedium)
                    Text("Stored on this phone, encrypted: your Technogym e-mail and password, your sync times, and which workouts were already synced. " +
                        "Nothing is sent anywhere except to Technogym and to Health Connect.", style = MaterialTheme.typography.bodyMedium)
                    Text("Health Connect: write-only. GymSync never reads, changes or deletes health data, and never creates duplicates.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Log out removes everything GymSync stored. Workouts already in Google Health stay.", style = MaterialTheme.typography.bodyMedium)
                    Text("No analytics, no ads, no third parties. Not affiliated with Technogym or Google.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}
