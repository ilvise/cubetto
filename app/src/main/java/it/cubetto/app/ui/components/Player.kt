package it.cubetto.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.cubetto.app.R
import it.cubetto.app.playback.NowPlaying
import it.cubetto.app.playback.RepeatMode
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.theme.Cubo

@Composable
fun MiniPlayer(now: NowPlaying, vm: AppViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
            .background(Cubo.Card).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FigureCover(now.color, now.coverPath, Modifier.size(46.dp), corner = 12.dp, artPadding = 4.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.playlistName, style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayPauseButton(now.playing, vm::togglePlayer)
            IconButton(onClick = vm::nextTrack, enabled = now.queue.size > 1) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.next_track), modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = vm::stopPlayer) {
                Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = Cubo.Muted)
            }
        }
        ProgressBar(
            if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f, Cubo.Red,
            Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp), height = 3.dp,
        )
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, big: Boolean = false) {
    val label = stringResource(if (playing) R.string.pause else R.string.play)
    val icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
    if (big) {
        Surface(onClick = onClick, shape = CircleShape, color = Cubo.Ink, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
    } else {
        IconButton(onClick = onClick) { Icon(icon, label, tint = Cubo.Ink, modifier = Modifier.size(30.dp)) }
    }
}

/** Player a tutto schermo: copertina, avanzamento trascinabile, controlli e coda. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(now: NowPlaying, vm: AppViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Cubo.Ground) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.now_playing), style = MaterialTheme.typography.labelMedium, color = Cubo.Muted)
                    Spacer(Modifier.height(16.dp))
                    FigureCover(now.color, now.coverPath, Modifier.size(240.dp), corner = 32.dp, artPadding = 24.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(now.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(now.playlistName, style = MaterialTheme.typography.bodyLarge, color = Cubo.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    SeekBar(now, vm)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::toggleShuffle) {
                            Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), tint = if (now.shuffle) Cubo.Red else Cubo.Muted)
                        }
                        IconButton(onClick = vm::previousTrack, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous_track), modifier = Modifier.size(36.dp))
                        }
                        PlayPauseButton(now.playing, vm::togglePlayer, big = true)
                        IconButton(onClick = vm::nextTrack, enabled = now.queue.size > 1 || now.repeat != RepeatMode.OFF, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Rounded.SkipNext, stringResource(R.string.next_track), modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = vm::cycleRepeat) {
                            Icon(
                                if (now.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                stringResource(R.string.repeat), tint = if (now.repeat == RepeatMode.OFF) Cubo.Muted else Cubo.Red,
                            )
                        }
                    }
                }
            }
            if (now.queue.size > 1) {
                item { SectionHeader(stringResource(R.string.up_next)) }
                itemsIndexed(now.queue, key = { i, q -> "$i-${q.trackId}" }) { i, q ->
                    val current = i == now.index
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (current) Cubo.Card else Color.Transparent)
                            .clickable { vm.skipTo(i) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(34.dp)) {
                            if (current) Icon(Icons.Rounded.GraphicEq, null, tint = Cubo.Red, modifier = Modifier.size(20.dp))
                            else Text("%02d".format(i + 1), style = MaterialTheme.typography.titleSmall, color = Cubo.Muted)
                        }
                        Text(
                            q.title, style = MaterialTheme.typography.titleSmall, color = if (current) Cubo.Red else Cubo.Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                TextButton(onClick = { vm.stopPlayer(); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.stop_player), color = Cubo.RedDark)
                }
            }
        }
    }
}

@Composable
private fun SeekBar(now: NowPlaying, vm: AppViewModel) {
    // Durante il trascinamento si mostra la posizione scelta, non quella del player
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = now.durationMs.coerceAtLeast(1)
    val value = dragging ?: (now.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { vm.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = now.durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = Cubo.Red, activeTrackColor = Cubo.Red, inactiveTrackColor = Cubo.Track),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(((value * duration) / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted, modifier = Modifier.weight(1f))
            Text(formatDuration((now.durationMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
        }
    }
}
