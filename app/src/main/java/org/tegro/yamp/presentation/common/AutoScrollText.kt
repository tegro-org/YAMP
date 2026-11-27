package org.tegro.yamp.presentation.common

import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The "big bro" title: a one-line text that fits stays still; one that overflows is paused for 2 s showing its
 * start clipped (the caller sets `overflow = TextOverflow.Clip`, so no ellipsis), then scrolls slowly and pauses
 * 2 s before it repeats.
 *
 * `basicMarquee` only scrolls when the content is wider than a bounded one, so the text must be given a width —
 * `Modifier.fillMaxWidth()` — otherwise it measures its content and never moves.
 */
fun Modifier.autoScrollTitle(enabled: Boolean = true): Modifier =
    if (!enabled) this
    else this.basicMarquee(
        iterations = Int.MAX_VALUE,
        initialDelayMillis = 2_000,
        velocity = 30.dp,
        repeatDelayMillis = 2_000,
    )
