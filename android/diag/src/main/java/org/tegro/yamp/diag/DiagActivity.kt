package org.tegro.yamp.diag

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

/** YAMP Diag's only screen: adb status, Collect, the last run's per-item results, and the saved zips to share. */
class DiagActivity : ComponentActivity() {
    private val vm: DiagViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    DiagScreen(state, vm::connect, vm::collect, ::share, vm::delete)
                }
            }
        }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, file.name))
    }
}

@Composable
private fun DiagScreen(
    state: DiagState,
    onConnect: () -> Unit,
    onCollect: () -> Unit,
    onShare: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("YAMP Diag", style = MaterialTheme.typography.headlineSmall)
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.connected) OutlinedButton(onClick = onConnect, enabled = !state.busy) { Text("Connect") }
            Button(onClick = onCollect, enabled = !state.busy) { Text(if (state.busy) "Working…" else "Collect") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.rows) { row -> ResultRow(row) }
            if (state.zips.isNotEmpty()) {
                item { Text("Saved", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
                items(state.zips) { zip ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(zip.name, Modifier.weight(1f))
                        TextButton(onClick = { onShare(zip) }) { Text("Share") }
                        TextButton(onClick = { onDelete(zip) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(row: ItemResult) {
    val (mark, detail) = when (val s = row.status) {
        is ItemStatus.Ok -> "✓" to "${s.bytes / 1024} KB"
        is ItemStatus.Truncated -> "✓" to "${s.bytes / 1024} KB, truncated"
        is ItemStatus.Failed -> "✗" to s.reason
    }
    Text("$mark ${row.file} — $detail", style = MaterialTheme.typography.bodySmall)
}
