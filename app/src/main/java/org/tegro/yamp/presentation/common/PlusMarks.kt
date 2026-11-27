package org.tegro.yamp.presentation.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.presentation.hifi.HiFi

/**
 * The tag on an account card while the developer option lets a Yandex account without Plus play (D101, canvas
 * `px-dev-accounts`): subtle, outlined, grey.
 */
@Composable
fun DeveloperTag(modifier: Modifier = Modifier) {
    Row(
        modifier.height(20.dp).border(1.dp, HiFi.GlassBorder, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Code, null, tint = HiFi.TextSecondary, modifier = Modifier.size(13.dp))
        Text(stringResource(R.string.account_plus_dev_tag), color = HiFi.TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** An account's Plus state line: the warning glyph and "No Yandex Plus subscription" in the warning colour (D101, canvas `px-accounts`). */
@Composable
fun PlusMissingLine(modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Block, null, tint = HiFi.Warning, modifier = Modifier.size(with(androidx.compose.ui.platform.LocalDensity.current) { (fontSize.toDp() + 1.dp) }))
        Text(stringResource(R.string.plus_missing), color = HiFi.Warning, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
