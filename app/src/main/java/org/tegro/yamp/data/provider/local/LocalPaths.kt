package org.tegro.yamp.data.provider.local

/** Whether volume-relative [relPath] is [dir] itself or inside it; the empty [dir] is the volume root. */
internal fun isUnder(relPath: String, dir: String): Boolean = dir.isEmpty() || relPath == dir || relPath.startsWith("$dir/")

/** `parent/name`, or `name` under the volume root. */
internal fun childPath(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

/** Id lists go to SQLite in chunks: old devices allow 999 variables per statement. */
internal const val SQL_CHUNK = 500
