package it.cubetto.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import it.cubetto.app.R
import it.cubetto.app.data.TrackStatus
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.components.InfoBanner
import it.cubetto.app.ui.components.Pill
import it.cubetto.app.ui.components.ProgressBar
import it.cubetto.app.ui.components.SectionHeader
import it.cubetto.app.ui.components.formatDuration
import it.cubetto.app.ui.theme.Cubo
import it.cubetto.app.ui.theme.FigurePalette
import it.cubetto.app.ytdlp.YtDlpStatus

@Composable
fun AddScreen(vm: AppViewModel, initialPlaylist: Long?, onCreate: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val add by vm.add.collectAsStateWithLifecycle()
    val yt by vm.ytDlp.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val shared by vm.shared.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(shared) {
        shared?.let { url = it; vm.resetAdd(); vm.consumeShared() }
    }
    var target by rememberSaveable { mutableStateOf(initialPlaylist) }
    LaunchedEffect(playlists) {
        if (target == null || playlists.none { it.playlist.id == target }) target = playlists.firstOrNull()?.playlist?.id
    }
    LaunchedEffect(add.added) { if (add.added != null) url = "" }

    val queue = playlists.flatMap { p -> p.tracks.map { it to p } }
        .filter { (t, _) -> t.status != TrackStatus.READY }
        .sortedBy { it.first.id }

    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.add_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                YtDlpPill(yt)
            }
        }
        item {
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Cubo.Card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.youtube_link), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        url, { url = it }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("https://youtu.be/…") }, shape = RoundedCornerShape(16.dp),
                    )
                    Button(
                        onClick = { clipboard.getText()?.text?.let { url = it.trim() } },
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.height(54.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Cubo.Ink),
                    ) { Text(stringResource(R.string.paste)) }
                }
                InfoBanner(stringResource(R.string.share_hint), Icons.Rounded.Share, Cubo.BlueSoft, Cubo.BlueInk)
                if (playlists.isEmpty()) {
                    TextButton(onClick = onCreate) { Text(stringResource(R.string.add_need_figure)) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.add_to), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted, modifier = Modifier.weight(1f))
                        PlaylistPicker(playlists.map { it.playlist }, target) { target = it }
                    }
                    Button(
                        onClick = { target?.let { vm.addUrl(it, url) } },
                        enabled = !add.busy && url.isNotBlank() && target != null,
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp),
                    ) {
                        if (add.busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Cubo.Card, strokeWidth = 2.dp)
                            Text("  " + stringResource(R.string.add_resolving))
                        } else Text(stringResource(R.string.add_button), style = MaterialTheme.typography.labelLarge)
                    }
                    add.added?.let { InfoBanner(pluralStringResource(R.plurals.added_tracks, it, it), Icons.Rounded.Check, Cubo.GreenSoft, Cubo.GreenInk) }
                    add.error?.let { InfoBanner(stringResource(R.string.add_error, it), Icons.Rounded.ErrorOutline, Cubo.Orange, Cubo.OrangeInk) }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val a = settings.audio
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionToggle(stringResource(R.string.opt_normalize), a.normalize, Modifier.weight(1f)) { v -> vm.setAudio { it.copy(normalize = v) } }
                    OptionToggle(stringResource(R.string.opt_trim), a.trimSilence, Modifier.weight(1f)) { v -> vm.setAudio { it.copy(trimSilence = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionToggle(stringResource(R.string.opt_chapters), a.splitChapters, Modifier.weight(1f)) { v -> vm.setAudio { it.copy(splitChapters = v) } }
                    OptionToggle(stringResource(R.string.opt_sponsors), a.skipSponsors, Modifier.weight(1f)) { v -> vm.setAudio { it.copy(skipSponsors = v) } }
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.queue)) {
                Text(stringResource(R.string.queue_hint), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
            }
        }
        if (queue.isEmpty()) item {
            Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted)
        }
        items(queue, key = { it.first.id }) { (t, p) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cubo.Card).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(10.dp)).background(FigurePalette[p.playlist.color.mod(FigurePalette.size)].first)) {
                    if (t.thumbnailUrl != null) AsyncImage(t.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (t.status) {
                        TrackStatus.ERROR -> {
                            Text(t.error ?: stringResource(R.string.track_error), style = MaterialTheme.typography.bodySmall, color = Cubo.RedDark, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { vm.retry(t) }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.retry)) }
                        }
                        else -> {
                            ProgressBar(t.progress / 100f, if (t.status == TrackStatus.CONVERTING) Cubo.Yellow else Cubo.Blue)
                            Text(
                                when (t.status) {
                                    TrackStatus.DOWNLOADING -> stringResource(R.string.status_downloading, t.progress)
                                    TrackStatus.CONVERTING -> stringResource(R.string.status_converting)
                                    else -> stringResource(R.string.status_waiting)
                                } + " · " + p.playlist.name + (if (t.durationSec > 0) " · " + formatDuration(t.durationSec) else ""),
                                style = MaterialTheme.typography.bodySmall, color = Cubo.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YtDlpPill(status: YtDlpStatus) {
    when (status) {
        YtDlpStatus.Starting, YtDlpStatus.Checking -> Pill(stringResource(R.string.ytdlp_checking), Cubo.BlueSoft, Cubo.BlueInk)
        is YtDlpStatus.UpToDate, is YtDlpStatus.Updated -> Pill(stringResource(R.string.ytdlp_ok), Cubo.GreenSoft, Cubo.GreenInk, icon = Icons.Rounded.Check)
        is YtDlpStatus.Error -> Pill(stringResource(R.string.ytdlp_error), Cubo.Orange, Cubo.OrangeInk)
    }
}

@Composable
private fun OptionToggle(label: String, checked: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Cubo.Card).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Cubo.Green))
    }
}

@Composable
private fun PlaylistPicker(items: List<it.cubetto.app.data.Playlist>, selected: Long?, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = items.firstOrNull { it.id == selected }
    Box {
        Surface(onClick = { open = true }, shape = RoundedCornerShape(50), color = FigurePalette[0].first) {
            Row(Modifier.height(40.dp).padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(FigurePalette[(current?.color ?: 0).mod(FigurePalette.size)].second))
                Text(current?.name ?: "—", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp))
                Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(open, { open = false }) {
            items.forEach { p -> DropdownMenuItem({ Text("${p.figureId} · ${p.name}") }, { open = false; onSelect(p.id) }) }
        }
    }
}
