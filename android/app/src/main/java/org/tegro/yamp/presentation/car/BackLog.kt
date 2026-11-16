package org.tegro.yamp.presentation.car

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Writes a `BACK <where> → <action>` line to the diagnostic log. Provided by MainActivity; a no-op in previews
 * and tests. Lets a head unit's log show whether its system Back reaches the app at all.
 */
val LocalBackLog = staticCompositionLocalOf<(String) -> Unit> { {} }
