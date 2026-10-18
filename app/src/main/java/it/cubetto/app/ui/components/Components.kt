package it.cubetto.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.cubetto.app.NowPlaying
import it.cubetto.app.R
import it.cubetto.app.ui.theme.Cubo
import it.cubetto.app.ui.theme.FigurePalette
import java.io.File

/** Il personaggio disegnato sulla base rossa, come una statuina FABA™ stilizzata. */
@Composable
fun FigureArt(color: Int, modifier: Modifier = Modifier) {
    val body = FigurePalette[color.mod(FigurePalette.size)].second
    Canvas(modifier) {
        val s = size.minDimension / 78f
        val ox = (size.width - 64 * s) / 2
        fun o(x: Float, y: Float) = Offset(ox + x * s, y * s)
        drawOval(Cubo.RedDark, o(6f, 67f), Size(52 * s, 10 * s))
        drawRoundRect(Cubo.Red, o(6f, 66f), Size(52 * s, 6 * s), CornerRadius(3 * s))
        drawRoundRect(body, o(14f, 26f), Size(36 * s, 42 * s), CornerRadius(16 * s))
        drawCircle(body, 17 * s, o(32f, 22f))
        drawCircle(body, 6 * s, o(18f, 9f))
        drawCircle(body, 6 * s, o(46f, 9f))
        drawCircle(Color.White, 3.4f * s, o(26f, 20f))
        drawCircle(Color.White, 3.4f * s, o(38f, 20f))
        drawCircle(Cubo.Ink, 1.7f * s, o(26.5f, 20.5f))
        drawCircle(Cubo.Ink, 1.7f * s, o(38.5f, 20.5f))
        drawOval(Color(0xFFF7A8B8), o(27f, 24.5f), Size(10 * s, 7 * s))
        drawOval(Color.White.copy(alpha = 0.85f), o(21f, 35f), Size(22 * s, 26 * s))
    }
}

/** Copertina: foto scelta dal genitore oppure il personaggio disegnato. */
@Composable
fun FigureCover(color: Int, coverPath: String?, modifier: Modifier = Modifier, corner: Dp = 18.dp, artPadding: Dp = 10.dp) {
    val bg = FigurePalette[color.mod(FigurePalette.size)].first
    Box(modifier.clip(RoundedCornerShape(corner)).background(bg), contentAlignment = Alignment.BottomCenter) {
        if (coverPath != null && File(coverPath).exists()) {
            AsyncImage(model = File(coverPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            FigureArt(color, Modifier.fillMaxSize().padding(artPadding))
        }
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, bg: Color = Cubo.Card, tint: Color = Cubo.Ink) {
    Surface(onClick = onClick, shape = CircleShape, color = bg, modifier = modifier.size(48.dp), shadowElevation = 1.dp) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = tint) }
    }
}

@Composable
fun BackButton(onClick: () -> Unit) =
    RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onClick)

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

@Composable
fun IdBadge(id: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(if (big) 12.dp else 50.dp)).background(Cubo.Ink)
            .padding(horizontal = if (big) 12.dp else 10.dp, vertical = if (big) 8.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (big) id else stringResource(R.string.id_badge, id),
            color = Cubo.Yellow,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit, selectedColor: Color = Cubo.Ink, leading: (@Composable () -> Unit)? = null) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) selectedColor else Cubo.Card,
        contentColor = if (selected && selectedColor == Cubo.Ink) Color.White else Cubo.Ink,
    ) {
        Row(Modifier.height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leading?.invoke()
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun InfoBanner(text: String, icon: ImageVector, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, track: Color = Cubo.Track) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)),
        color = color,
        trackColor = track,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun MiniPlayer(now: NowPlaying, onToggle: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
            .background(Cubo.Card).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(FigurePalette[0].first))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.preview_of, now.subtitle), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (now.playing) R.string.pause else R.string.play),
                tint = Cubo.Ink,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/** Mostra durate come 1:02:03 o 3:05. */
fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
    else -> "%.0f KB".format(b / 1e3)
}
