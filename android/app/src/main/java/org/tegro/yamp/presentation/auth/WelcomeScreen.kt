package org.tegro.yamp.presentation.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tegro.yamp.R
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.presentation.common.ProviderBadge
import org.tegro.yamp.presentation.hifi.HiFi

/**
 * The first start (canvas `wl-welcome`, `wl-welcome-head`): no account yet, so the user says where the music is —
 * Yandex Music or a Jellyfin server — instead of being sent straight to Yandex's sign-in. Each choice opens that
 * provider's sign-in; more accounts are added later in Settings → Accounts.
 */
@Composable
fun WelcomeScreen(onYandex: () -> Unit, onJellyfin: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(HiFi.Ground)) {
        // A wide, short screen (the head unit, a phone on its side): the logo on the left, the choices side by side
        val wide = maxWidth > maxHeight * 1.6f && maxWidth >= 900.dp
        if (wide) {
            Row(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 64.dp, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WelcomeLogo(big = true, modifier = Modifier.weight(0.38f))
                Column(Modifier.weight(0.62f).padding(start = 40.dp)) {
                    Text(stringResource(R.string.welcome_line), color = HiFi.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Choices(big = true, onYandex = onYandex, onJellyfin = onJellyfin)
                    }
                    Text(stringResource(R.string.welcome_later), color = HiFi.TextTertiary, fontSize = 17.sp, modifier = Modifier.padding(top = 22.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                WelcomeLogo(big = false)
                Spacer(Modifier.height(40.dp))
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    Text(
                        stringResource(R.string.welcome_line), color = HiFi.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChoiceRow(Provider.YANDEX, stringResource(R.string.add_account_yandex), stringResource(R.string.welcome_yandex_sub), big = false, onClick = onYandex)
                        ChoiceRow(Provider.JELLYFIN, "Jellyfin", stringResource(R.string.welcome_jellyfin_sub), big = false, onClick = onJellyfin)
                    }
                    Text(
                        stringResource(R.string.welcome_later), color = HiFi.TextTertiary, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.Choices(big: Boolean, onYandex: () -> Unit, onJellyfin: () -> Unit) {
    ChoiceRow(Provider.YANDEX, stringResource(R.string.add_account_yandex), stringResource(R.string.welcome_yandex_sub), big, onYandex, Modifier.weight(1f))
    ChoiceRow(Provider.JELLYFIN, "Jellyfin", stringResource(R.string.welcome_jellyfin_sub), big, onJellyfin, Modifier.weight(1f))
}

@Composable
private fun WelcomeLogo(big: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.size(if (big) 150.dp else 96.dp))
        Text("YAMP", color = HiFi.TextPrimary, fontSize = if (big) 52.sp else 38.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = if (big) 20.dp else 14.dp))
        Text("Yet Another Music Player", color = HiFi.TextSecondary, fontSize = if (big) 19.sp else 15.sp)
    }
}

/** One big choice — the "Add account" row look, larger; the whole card is the tap target. */
@Composable
private fun ChoiceRow(provider: Provider, title: String, sub: String, big: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (big) 30.dp else 24.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = if (big) 132.dp else 96.dp).clip(shape).background(HiFi.GlassFill)
            .border(1.dp, HiFi.GlassBorder, shape).clickable(onClick = onClick).padding(horizontal = if (big) 28.dp else 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProviderBadge(provider, size = badgeSize(big))
        Column(Modifier.weight(1f).padding(horizontal = if (big) 22.dp else 16.dp)) {
            Text(title, color = HiFi.TextPrimary, fontSize = if (big) 28.sp else 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(sub, color = HiFi.TextSecondary, fontSize = if (big) 18.sp else 14.sp, lineHeight = if (big) 24.sp else 19.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ChevronRight, null, tint = HiFi.TextTertiary, modifier = Modifier.size(if (big) 36.dp else 26.dp))
        }
    }
}

private fun badgeSize(big: Boolean): Dp = if (big) 64.dp else 48.dp
