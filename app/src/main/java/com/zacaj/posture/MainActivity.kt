package com.zacaj.posture

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zacaj.posture.core.Posture

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        val settings = Settings(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(settings) }
            }
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
    }

    @Composable
    private fun Screen(s: Settings) {
        val status by PostureService.status.collectAsState()
        Column(
            Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Posture: ${status.posture.name.lowercase()}", style = MaterialTheme.typography.headlineMedium)
            if (status.running) {
                val mins = if (status.since > 0) (System.currentTimeMillis() - status.since) / 60_000 else 0
                Text("for $mins min · raw ${status.raw?.name?.lowercase() ?: "-"}")
                Text("tilt %.0f° · motion %.2f".format(status.tiltDeg, status.motionStd))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.running) {
                    Button({ PostureService.send(this@MainActivity, PostureService.ACTION_STOP) }) { Text("Stop") }
                } else {
                    Button({
                        requestBatteryExemption()
                        PostureService.send(this@MainActivity, PostureService.ACTION_START)
                    }) { Text("Start") }
                }
                OutlinedButton({ PostureService.send(this@MainActivity, PostureService.ACTION_CALIBRATE) }) {
                    Text("Calibrate (stand)")
                }
            }
            Text("Label what you're doing now:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Posture.SITTING, Posture.STANDING, Posture.WALKING).forEach { p ->
                    OutlinedButton({
                        PostureService.send(this@MainActivity, PostureService.ACTION_LABEL) {
                            putExtra(PostureService.EXTRA_LABEL, p.name)
                        }
                    }) { Text(p.name.lowercase()) }
                }
            }
            OutlinedButton({ PostureService.send(this@MainActivity, PostureService.ACTION_FLUSH) }) {
                Text("Close trace & upload now")
            }

            HorizontalDivider()
            SettingsForm(s)
        }
    }

    @Composable
    private fun SettingsForm(s: Settings) {
        var lanUrl by remember { mutableStateOf(s.lanUrl) }
        var notifyChange by remember { mutableStateOf(s.notifyOnChange) }
        var sit by remember { mutableStateOf(s.limitMin(Posture.SITTING).toString()) }
        var stand by remember { mutableStateOf(s.limitMin(Posture.STANDING).toString()) }
        var repeat by remember { mutableStateOf(s.repeatMin.toString()) }
        var record by remember { mutableStateOf(s.recordTraces) }
        var lanTraces by remember { mutableStateOf(s.lanTraceUpload) }
        var repo by remember { mutableStateOf(s.githubRepo) }
        var branch by remember { mutableStateOf(s.githubBranch) }
        var token by remember { mutableStateOf(s.githubToken) }

        Text("Settings", style = MaterialTheme.typography.titleMedium)
        Field("LAN listener URL (http://host:8765)", lanUrl) { lanUrl = it }
        Toggle("Notify on every state change", notifyChange) { notifyChange = it }
        Field("Sitting alert after (min, 0=off)", sit, number = true) { sit = it }
        Field("Standing alert after (min, 0=off)", stand, number = true) { stand = it }
        Field("Repeat alert every (min)", repeat, number = true) { repeat = it }
        Toggle("Record sensor traces", record) { record = it }
        Toggle("Upload traces to LAN listener", lanTraces) { lanTraces = it }
        Field("GitHub repo", repo) { repo = it }
        Field("GitHub branch", branch) { branch = it }
        Field("GitHub token (contents:write)", token, secret = true) { token = it }
        Button({
            s.lanUrl = lanUrl
            s.notifyOnChange = notifyChange
            s.setLimitMin(Posture.SITTING, sit.toIntOrNull() ?: 0)
            s.setLimitMin(Posture.STANDING, stand.toIntOrNull() ?: 0)
            s.repeatMin = repeat.toIntOrNull() ?: 15
            s.recordTraces = record
            s.lanTraceUpload = lanTraces
            s.githubRepo = repo
            s.githubBranch = branch
            s.githubToken = token
            if (PostureService.status.value.running) {
                PostureService.send(this@MainActivity, PostureService.ACTION_RELOAD)
            }
        }) { Text("Save") }
        OutlinedButton({ s.referenceAxis = null; PostureService.send(this, PostureService.ACTION_RELOAD) }) {
            Text("Reset calibration")
        }
    }

    @Composable
    private fun Field(
        label: String, value: String, number: Boolean = false, secret: Boolean = false, onChange: (String) -> Unit,
    ) = OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(value, onChange)
        }
}
