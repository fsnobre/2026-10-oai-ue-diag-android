package br.dev.nobre.oaiuediag

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import br.dev.nobre.oaiuediag.diagnosis.OaiDiagnosisEngine
import br.dev.nobre.oaiuediag.data.OaiSettings
import br.dev.nobre.oaiuediag.recorder.TelemetryRecorder
import br.dev.nobre.oaiuediag.settings.OaiSettingsStore
import br.dev.nobre.oaiuediag.telephony.TelephonyReader
import br.dev.nobre.oaiuediag.ui.DashboardScreen

class MainActivity : ComponentActivity() {
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val reader = remember { TelephonyReader(applicationContext) }
                val settingsStore = remember { OaiSettingsStore(applicationContext) }
                var snapshot by remember { mutableStateOf(reader.readSnapshot()) }
                var oaiSettings by remember { mutableStateOf(settingsStore.load()) }
                val recorder = remember { TelemetryRecorder(applicationContext, reader) { oaiSettings } }
                val recorderState by recorder.state.collectAsState()
                val diagnosis = remember(recorderState.events, oaiSettings) {
                    OaiDiagnosisEngine.diagnose(recorderState.events, oaiSettings)
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    snapshot = reader.readSnapshot()
                }

                LaunchedEffect(Unit) {
                    if (!hasAllPermissions()) {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }

                DisposableEffect(recorder) {
                    onDispose { recorder.close() }
                }

                DashboardScreen(
                    snapshot = snapshot,
                    recorderState = recorderState,
                    oaiSettings = oaiSettings,
                    diagnosis = diagnosis,
                    hasAllPermissions = hasAllPermissions(),
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    onRefresh = { snapshot = reader.readSnapshot() },
                    onOpenTesting = ::openRadioInfo,
                    onStartRecording = recorder::start,
                    onStopRecording = recorder::stop,
                    onAddMarker = recorder::recordMarker,
                    onClearSession = recorder::clearCurrentSession,
                    onShareLatest = { shareRecording(recorder) },
                    onSaveOaiSettings = { settings ->
                        settingsStore.save(settings)
                        oaiSettings = settingsStore.load()
                    },
                )
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openRadioInfo() {
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.android.settings", "com.android.settings.RadioInfo"),
            ),
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.android.settings", "com.android.settings.TestingSettings"),
            ),
            Intent("android.intent.action.MAIN").setClassName(
                "com.android.settings",
                "com.android.settings.radio.RadioInfo",
            ),
            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode("*#*#4636#*#*")}")),
        )

        val launched = intents.any { intent ->
            runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrElse { error ->
                error !is ActivityNotFoundException && false
            }
        }

        if (!launched) {
            Toast.makeText(
                this,
                "Unable to open testing screen or mobile network settings.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun shareRecording(recorder: TelemetryRecorder) {
        val file = recorder.latestSessionFile()
        if (file == null) {
            Toast.makeText(this, "No recording file available to share.", Toast.LENGTH_LONG).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/jsonl"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, "Share OAI UE Diag recording"))
    }
}
