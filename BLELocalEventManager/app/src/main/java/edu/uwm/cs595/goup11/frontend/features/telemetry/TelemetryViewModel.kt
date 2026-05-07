package edu.uwm.cs595.goup11.frontend.features.telemetry

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.uwm.cs595.goup11.backend.network.TelemetryEvent
import edu.uwm.cs595.goup11.backend.network.TelemetryManager
import edu.uwm.cs595.goup11.backend.network.TelemetrySnapshot
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class TelemetryViewModel(
    private val mesh: MeshGateway
) : ViewModel() {

    val snapshot: StateFlow<TelemetrySnapshot> = TelemetryManager.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelemetrySnapshot())

    val eventLog: StateFlow<List<TelemetryEvent>> = TelemetryManager.eventLog
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    // Direct peer list is pushed by SnakeTopology/MeshTopology via TelemetryManager.updateDirectPeers
    // on every connect/disconnect — no polling needed here.

    fun reset() = TelemetryManager.reset()

    fun exportAndShare(context: Context) {
        viewModelScope.launch {
            val csv = TelemetryManager.exportCsv()
            val file = File(context.cacheDir, "telemetry_export.csv")
            file.writeText(csv)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BLE Mesh Telemetry Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export Telemetry"))
        }
    }
}