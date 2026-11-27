package org.tegro.yamp.presentation.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.utils.cover

/**
 * A folder's art (canvas `fd-covers`): its image when it has one, else the drawn placeholder — a steel plate with a
 * folder (tab + body) carrying the name's monogram, faintly tinted by the name (the same name, the same tint).
 */
object FolderLook {
    /** The canvas' cover hues (hifi.jsx `COVER_HUES`), the light end of each. */
    private val Tints = listOf(
        Color(0xFF9A7BE0), // violet
        Color(0xFF4FB3C2), // teal
        Color(0xFFD96C8A), // rose
        Color(0xFFE0A050), // amber
        Color(0xFF5B86DD), // blue
        Color(0xFF58B889) // green
    )

    /**
     * The monogram: the part before " - ", without "(…)", initials of up to two words that start with a letter
     * ("Мельница & Хелависа" → МХ, "RC - Sorted" → RC, "Детские" → Д, "2003 — Перевал" → П). A single all-caps word of
     * two or three letters keeps its first two ("RC", "БГ").
     */
    fun monogram(name: String): String {
        val head = name.split(" - ").first().replace(Regex("\\(.*?\\)"), " ")
        val words = head.split(Regex("\\s+")).filter { it.isNotEmpty() && it.first().isLetter() }
        if (words.isEmpty()) return name.trim().take(1).uppercase()
        if (words.size == 1) {
            val w = words.single()
            return if (w.length in 2..3 && w.all { it.isUpperCase() || it.isDigit() }) w.take(2) else w.first().uppercase()
        }
        return words.take(2).joinToString("") { it.first().uppercase() }
    }

    /** The tint for [name]: the canvas' hash (`h * 31 + code point`, unsigned) over the six hues. */
    fun tintOf(name: String): Color {
        var h = 0L
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            h = (h * 31 + cp) and 0xFFFFFFFFL
            i += Character.charCount(cp)
        }
        return Tints[(h % Tints.size).toInt()]
    }
}

/** The placeholder at [size] (square) or [size] × [height]. */
@Composable
fun FolderPlaceholder(name: String, size: Dp, modifier: Modifier = Modifier, radius: Dp = 10.dp, height: Dp = size) {
    val shape = RoundedCornerShape(radius)
    val tint = FolderLook.tintOf(name)
    BoxWithConstraints(
        modifier
            .size(size, height)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(HiFi.SteelLight, HiFi.SteelDark)))
            .drawBehind {
                // The name's tint glows from the top-left corner
                drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.33f), Color.Transparent), center = Offset.Zero, radius = this.size.maxDimension * 0.9f))
            }
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
    ) {
        val u = minOf(maxWidth, maxHeight)
        val bodyTop = (maxHeight - u * 0.62f) / 2 + u * 0.02f
        // The folder's tab
        Box(
            Modifier
                .offset(x = maxWidth * 0.14f, y = bodyTop - u * 0.09f)
                .size(u * 0.3f, u * 0.1f)
                .clip(RoundedCornerShape(topStart = u * 0.04f, topEnd = u * 0.04f))
                .background(Color.White.copy(alpha = 0.16f))
        )
        // Its body with the monogram
        Box(
            Modifier
                .offset(x = maxWidth * 0.14f, y = bodyTop)
                .size(maxWidth * 0.72f, u * 0.6f)
                .clip(RoundedCornerShape(u * 0.07f))
                .background(Color.White.copy(alpha = 0.08f))
                .border(if (u >= 96.dp) 2.dp else 1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(u * 0.07f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                FolderLook.monogram(name),
                color = Color.White.copy(alpha = 0.88f),
                fontWeight = FontWeight.ExtraBold,
                fontSize = maxOf(11f, u.value * 0.25f).sp,
                lineHeight = maxOf(11f, u.value * 0.25f).sp,
                maxLines = 1
            )
        }
    }
}

/** The folder's image over the placeholder: while it loads, or when it fails, the placeholder shows. */
@Composable
fun FolderArt(name: String, cover: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 10.dp, height: Dp = size) {
    Box(modifier.size(size, height).clip(RoundedCornerShape(radius))) {
        FolderPlaceholder(name, size, radius = radius, height = height)
        if (cover != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).cover(cover, if (size >= 160.dp) CoverSize.Large else CoverSize.Medium).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
