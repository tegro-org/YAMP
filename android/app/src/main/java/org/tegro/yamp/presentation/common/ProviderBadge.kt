package org.tegro.yamp.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.domain.account.Provider

/**
 * The provider badge of merged lists (D21, canvas `sr-results`): a small rounded square with the service's letter,
 * shown only while a list mixes providers (D75). The colours are the canvas's.
 */
@Composable
fun ProviderBadge(provider: Provider, modifier: Modifier = Modifier, size: Dp = 15.dp) {
    val (letter, fill, ink) = when (provider) {
        Provider.YANDEX -> Triple("Y", Color(0xFFFFCC00), Color(0xFF1A1400))
        Provider.JELLYFIN -> Triple("J", Color(0xFF9B6BE0), Color.White)
    }
    Box(
        modifier
            .size(size)
            .background(fill, RoundedCornerShape(size * 0.3f))
            .semantics { contentDescription = provider.name.lowercase().replaceFirstChar { it.uppercase() } },
        contentAlignment = Alignment.Center
    ) {
        Text(letter, color = ink, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.ExtraBold, lineHeight = size.value.sp)
    }
}
