package org.tegro.yamp.presentation.crash

import android.content.Context
import org.tegro.yamp.data.diagnostics.FileShare
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.R
import org.tegro.yamp.data.crash.CrashLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The crash journal in the settings: what is recorded, sending it (the user picks how), clearing it. */
@HiltViewModel
class CrashLogViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _reports = MutableStateFlow<List<CrashLog.Report>>(emptyList())
    val reports: StateFlow<List<CrashLog.Report>> = _reports.asStateFlow()

    private val prefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)

    /**
     * A crash the person has not been told about yet. Shown once on the sign-in screen: a crash before
     * signing in (some head units) would otherwise never reach the journal in the settings.
     */
    val unseen: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        viewModelScope.launch {
            _reports.collect { list ->
                val newest = list.firstOrNull()?.time?.time ?: 0L
                flow.value = newest > prefs.getLong(KEY_OFFERED_UNTIL, 0L)
            }
        }
    }

    /** The person saw the offer: the same crashes are not offered again. */
    fun markSeen() {
        val newest = _reports.value.firstOrNull()?.time?.time ?: return
        prefs.edit { putLong(KEY_OFFERED_UNTIL, newest) }
        (unseen as MutableStateFlow).value = false
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _reports.value = withContext(Dispatchers.IO) { CrashLog.reports(context) } }
    }

    /** The system share sheet with the reports as a file (and as text for messengers): whatever the user chooses. */
    fun send() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { CrashLog.fullText(context) }
            if (text.isBlank()) return@launch
            val file = withContext(Dispatchers.IO) { FileShare.writeTextFile(context, "yamp-crashes", text) }
            FileShare.share(
                context,
                listOf(FileShare.uriFor(context, file)),
                subject = context.getString(R.string.crash_log_subject, BuildConfig.VERSION_NAME),
                text = text,
                title = context.getString(R.string.crash_log_send),
            )
        }
    }

    private companion object {
        const val KEY_OFFERED_UNTIL = "offered_until"
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CrashLog.clear(context) }
            _reports.value = emptyList()
        }
    }
}
