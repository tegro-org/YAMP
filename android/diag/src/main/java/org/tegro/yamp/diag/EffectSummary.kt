package org.tegro.yamp.diag

/**
 * One line for the zip's manifest and `summary.txt`: which audio effects sit on YAMP's session(s), who attached
 * them, and whether any takes over the session's volume ("volume mgmt: implements control" — Poweramp's DVC).
 * With such an effect a player's own volume is not applied, so fades and the crossfade are not heard as such
 * (docs/playback-stability.md). Pure: parses `dumpsys media.audio_flinger` text.
 */
object EffectSummary {

    data class Effect(val name: String, val session: Int, val takesVolume: Boolean, val clientPids: List<Int>)

    /** YAMP's sessions: the "Global session refs" rows that belong to [yampPid]. */
    fun yampSessions(dump: String, yampPid: Int): Set<Int> {
        val start = dump.lines().indexOfFirst { it.trim().startsWith("Global session refs") }
        if (start < 0) return emptySet()
        return dump.lines().drop(start + 2)
            .takeWhile { it.isNotBlank() && it.trim().first().isDigit() }
            .mapNotNull { row ->
                val cols = row.trim().split(Regex("\\s+"))
                val session = cols.getOrNull(0)?.toIntOrNull()
                val pid = cols.getOrNull(1)?.toIntOrNull()
                if (session != null && pid == yampPid) session else null
            }.toSet()
    }

    /** Every effect of every "N effects for session S" chain. */
    fun effects(dump: String): List<Effect> {
        val result = mutableListOf<Effect>()
        var session: Int? = null
        var name: String? = null
        var takesVolume = false
        var pids = mutableListOf<Int>()
        var inClients = false
        fun flush() {
            val s = session
            val n = name
            if (s != null && n != null) result += Effect(n, s, takesVolume, pids.toList())
            name = null
            takesVolume = false
            pids = mutableListOf()
            inClients = false
        }
        for (raw in dump.lines()) {
            val line = raw.trim()
            Regex("""^\d+ effects for session (\d+)""").find(line)?.let {
                flush()
                session = it.groupValues[1].toInt()
                return@let
            }
            when {
                line.startsWith("Effect ID") -> flush()
                line.startsWith("- flags:") -> takesVolume = "volume mgmt: implements control" in line
                line.startsWith("- name:") -> name = line.removePrefix("- name:").trim()
                line.endsWith("Clients:") -> inClients = true
                inClients && line.startsWith("Pid") -> Unit
                inClients -> line.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()?.let { pids += it } ?: run { inClients = false }
                line.endsWith("Effect Chains") || line.startsWith("Output thread") -> {
                    flush()
                    session = null
                }
            }
        }
        flush()
        return result
    }

    /** `ps -A -o PID,NAME` → pid to process name. */
    fun processes(ps: String): Map<Int, String> = ps.lines().mapNotNull { row ->
        val cols = row.trim().split(Regex("\\s+"), limit = 2)
        val pid = cols.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        pid to (cols.getOrNull(1) ?: return@mapNotNull null)
    }.toMap()

    /** The summary line; says plainly when YAMP was not running or the dump was not there. */
    fun line(dump: String?, pidText: String?, ps: String?): String {
        if (dump.isNullOrBlank()) return "effects: no audio_flinger dump"
        val yampPid = pidText?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            ?: return "effects: YAMP not running"
        val sessions = yampSessions(dump, yampPid)
        if (sessions.isEmpty()) return "effects: YAMP (pid $yampPid) holds no audio session"
        val names = processes(ps.orEmpty())
        val onYamp = effects(dump).filter { it.session in sessions }
        return sessions.sorted().joinToString("; ") { s ->
            val here = onYamp.filter { it.session == s }
            val dvc = here.filter { it.takesVolume }
            val list = here.joinToString(", ") { e ->
                val who = e.clientPids.filter { it != yampPid }.joinToString("/") { pid -> names[pid] ?: "pid $pid" }
                e.name + (if (who.isNotEmpty()) " ($who)" else "") + (if (e.takesVolume) " [takes volume]" else "")
            }
            "DVC on YAMP session $s: " + (if (dvc.isNotEmpty()) "yes (${dvc.joinToString(", ") { it.name }})" else "no") +
                (if (list.isNotEmpty()) " — effects: $list" else " — no effects")
        }
    }
}
