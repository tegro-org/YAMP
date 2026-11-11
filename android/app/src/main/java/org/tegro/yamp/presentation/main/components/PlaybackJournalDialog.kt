package org.tegro.yamp.presentation.main.components

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.data.player.PlaybackJournal
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiAlertDialog
import org.tegro.yamp.presentation.hifi.HiFiDialogAction
import org.tegro.yamp.presentation.hifi.HiFiDialogActionKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last hour of music: how long it played, its stops, crossfades, skips, errors, focus and pauses. Shared
 * as text with a report, so "it paused by itself" can be told apart: the link, the track, the device or the app.
 * The event lines are technical and English on purpose — they are for the developer.
 */
@Composable
fun PlaybackJournalDialog(
    snapshot: PlaybackJournal.Snapshot,
    onShare: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }
    HiFiAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.journal_title),
        content = {
            Column {
                Text(stringResource(R.string.journal_hint), color = HiFi.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                summaryLines(resources, snapshot.summary).forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (snapshot.events.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.journal_events), color = HiFi.TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    snapshot.events.takeLast(SHOWN).reversed().forEach { event ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(time.format(Date(event.atMs)), color = HiFi.TextTertiary, fontSize = 12.sp, modifier = Modifier.width(62.dp))
                            Text(eventText(event), color = eventColor(event.kind), fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        },
        confirm = { HiFiDialogAction(stringResource(R.string.journal_share), { onShare(journalText(resources, snapshot, time)) }) },
        dismiss = { HiFiDialogAction(stringResource(R.string.journal_clear), { onClear(); onDismiss() }, HiFiDialogActionKind.Quiet) }
    )
}

private const val SHOWN = 40

private fun eventColor(kind: PlaybackJournal.Kind): Color = when (kind) {
    PlaybackJournal.Kind.STALL, PlaybackJournal.Kind.CROSSFADE_LATE, PlaybackJournal.Kind.HOLD_TIMEOUT -> HiFi.Warning
    PlaybackJournal.Kind.ERROR, PlaybackJournal.Kind.PAUSE -> HiFi.Danger
    else -> Color.White.copy(alpha = 0.82f)
}

private fun seconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000f).removeSuffix(".0")

internal fun summaryLines(r: Resources, s: PlaybackJournal.Summary): List<Pair<String, String>> = listOf(
    r.getString(R.string.journal_played) to r.getString(R.string.journal_minutes, s.playedMs / 60_000),
    r.getString(R.string.journal_stalls) to if (s.stalls == 0) "0" else r.getString(R.string.journal_stalls_value, s.stalls, seconds(s.stalledMs)),
    r.getString(R.string.journal_crossfades) to r.getString(R.string.journal_crossfades_value, s.crossfades - s.crossfadesLate, s.crossfades),
    r.getString(R.string.journal_errors) to s.errors.toString(),
    r.getString(R.string.journal_pauses) to s.pauses.toString()
)

/** One line per event, technical and English: what the developer needs. */
internal fun eventText(e: PlaybackJournal.Event): String {
    val title = e.title?.let { "«$it»" }
    val detail = e.detail
    val body = when (e.kind) {
        PlaybackJournal.Kind.START -> "start ${detail.orEmpty()}" + (if (e.valueMs > 0) " at ${e.valueMs / 1000} s" else "")
        PlaybackJournal.Kind.STALL -> "stall ${seconds(e.valueMs)} s"
        PlaybackJournal.Kind.CROSSFADE -> "crossfade, ready after ${seconds(e.valueMs)} s"
        PlaybackJournal.Kind.CROSSFADE_LATE -> "crossfade LATE: previous ended, fade-in after ${seconds(e.valueMs)} s"
        PlaybackJournal.Kind.HOLD_TIMEOUT -> "next track not ready in 8 s"
        PlaybackJournal.Kind.SKIP -> "skip (${detail.orEmpty()})"
        PlaybackJournal.Kind.ERROR -> "error: ${detail.orEmpty()}"
        PlaybackJournal.Kind.FOCUS -> "audio focus ${detail.orEmpty()}"
        PlaybackJournal.Kind.PAUSE -> "PAUSE: ${detail.orEmpty()}"
    }
    return listOfNotNull(body, title).joinToString(" ")
}

/** The journal as plain text, for a message to the developer. */
internal fun journalText(r: Resources, snapshot: PlaybackJournal.Snapshot, time: SimpleDateFormat): String = buildString {
    appendLine(r.getString(R.string.journal_title))
    summaryLines(r, snapshot.summary).forEach { (label, value) -> appendLine("$label: $value") }
    if (snapshot.events.isNotEmpty()) {
        appendLine()
        snapshot.events.forEach { appendLine("${time.format(Date(it.atMs))}  ${eventText(it)}") }
    }
}.trimEnd()
