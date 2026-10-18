package it.cubetto.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.cubetto.app.R
import it.cubetto.app.data.Playlist
import it.cubetto.app.data.Track
import it.cubetto.app.data.TrackStatus
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.components.BackButton
import it.cubetto.app.ui.components.FigureCover
import it.cubetto.app.ui.components.IdBadge
import it.cubetto.app.ui.components.InfoBanner
import it.cubetto.app.ui.components.Pill
import it.cubetto.app.ui.components.ProgressBar
import it.cubetto.app.ui.components.formatDuration
import it.cubetto.app.ui.theme.Cubo
import it.cubetto.app.ui.theme.FigurePalette
import it.cubetto.app.usb.BoxState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistScreen(vm: AppViewModel, id: Long, onBack: () -> Unit, onAdd: () -> Unit, onChangeId: () -> Unit, onBox: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val box by vm.box.collectAsStateWithLifecycle()
    val ui = playlists.firstOrNull { it.playlist.id == id } ?: return
    val p = ui.playlist

    var writing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var renamingTrack by remember { mutableStateOf<Track?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.setCover(p, it) } }

    // Ordine locale durante il trascinamento, poi salvato nel database
    var order by remember { mutableStateOf(ui.tracks) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(ui.tracks) { if (!dragging) order = ui.tracks }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = order.indexOfFirst { it.id == from.key }
        val toIdx = order.indexOfFirst { it.id == to.key }
        if (fromIdx >= 0 && toIdx >= 0) order = order.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BackButton(onBack)
                    Spacer(Modifier.weight(1f))
                    Surface(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, shape = RoundedCornerShape(50), color = Cubo.Card) {
                        Row(Modifier.height(40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.PhotoCamera, null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.figure_photo), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { menu = false; renaming = true })
                            DropdownMenuItem({ Text(stringResource(R.string.change_id)) }, { menu = false; onChangeId() })
                            DropdownMenuItem({ Text(stringResource(R.string.change_color)) }, { menu = false; coloring = true })
                            if (p.coverPath != null) DropdownMenuItem({ Text(stringResource(R.string.remove_photo)) }, { menu = false; vm.removeCover(p) })
                            DropdownMenuItem({ Text(stringResource(R.string.delete_figure), color = Cubo.RedDark) }, { menu = false; deleting = true })
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FigureCover(p.color, p.coverPath, Modifier.size(124.dp), corner = 26.dp, artPadding = 12.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            pluralStringResource(R.plurals.tracks_count, ui.tracks.size, ui.tracks.size) +
                                (if (ui.durationSec > 0) " · " + formatDuration(ui.durationSec) else ""),
                            style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IdBadge(p.figureId)
                            when {
                                ui.needsSync -> Pill(stringResource(R.string.to_sync), Cubo.Yellow, Cubo.Ink)
                                ui.onBox -> Pill(stringResource(R.string.on_box), Cubo.GreenSoft, Cubo.GreenInk, icon = Icons.Rounded.Check)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = { vm.toggleFavorite(p) }) {
                        Icon(if (p.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(R.string.favorite), tint = Cubo.Red, modifier = Modifier.size(28.dp))
                    }
                    ActionPill(stringResource(R.string.write_tag), Icons.Rounded.Contactless, Cubo.Card) { writing = true }
                    ActionPill(stringResource(R.string.copy_to_box), Icons.Rounded.Sync, Cubo.Yellow) {
                        if (box is BoxState.Connected) vm.sync(p)
                        onBox()
                    }
                    Spacer(Modifier.weight(1f))
                    val first = ui.tracks.firstOrNull { it.status == TrackStatus.READY }
                    Surface(onClick = { first?.let { vm.play(it, p.name) } }, shape = CircleShape, color = Cubo.Ink, modifier = Modifier.size(60.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play), tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                }
            }
            if (ui.tracks.isEmpty()) item { NextSteps() }
            if (ui.errorCount > 0) item {
                InfoBanner(
                    pluralStringResource(R.plurals.tracks_failed, ui.errorCount, ui.errorCount),
                    Icons.Rounded.ErrorOutline, Cubo.Orange, Cubo.OrangeInk, Modifier.padding(top = 14.dp),
                    onClick = { vm.retryAll(p.id) },
                )
            }
            if (ui.tracks.size > 99) item {
                InfoBanner(stringResource(R.string.too_many_tracks), Icons.Rounded.ErrorOutline, Cubo.Orange, Cubo.OrangeInk, Modifier.padding(top = 14.dp))
            }
            if (settings.audio.normalize && ui.tracks.isNotEmpty()) item {
                InfoBanner(stringResource(R.string.normalized_banner), Icons.Rounded.GraphicEq, Cubo.YellowSoft, Cubo.YellowInk, Modifier.padding(top = 14.dp))
            }
            if (ui.tracks.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tracks), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.drag_to_reorder), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
                }
            }
            items(order, key = { it.id }) { t ->
                ReorderableItem(reorderState, key = t.id) { isDragging ->
                    TrackRow(
                        t, order.indexOf(t) + 1, isDragging,
                        onPlay = { vm.play(t, p.name) },
                        onRetry = { vm.retry(t) },
                        onDelete = { vm.deleteTrack(t) },
                        onRename = { renamingTrack = t },
                        handle = {
                            Icon(
                                Icons.Rounded.DragHandle, stringResource(R.string.drag_to_reorder), tint = Color(0xFFA8A29A),
                                modifier = Modifier.size(40.dp).padding(8.dp).draggableHandle(
                                    onDragStarted = { dragging = true },
                                    onDragStopped = { dragging = false; vm.reorder(p.id, order.map { it.id }) },
                                ),
                            )
                        },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd, containerColor = Cubo.Red, contentColor = Color.White, shape = RoundedCornerShape(28.dp),
            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.add_tracks), style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    if (writing) NfcSheet(vm, p.figureId, p.name, onDismiss = { writing = false })
    if (renaming) TextDialog(stringResource(R.string.rename), p.name, { renaming = false }) { vm.updatePlaylist(p.copy(name = it)) }
    renamingTrack?.let { t -> TextDialog(stringResource(R.string.rename), t.title, { renamingTrack = null }) { vm.renameTrack(t, it) } }
    if (coloring) ColorDialog(p, { coloring = false }) { vm.updatePlaylist(p.copy(color = it)) }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_figure)) },
            text = { Text(stringResource(R.string.delete_figure_text, p.name)) },
            confirmButton = { TextButton({ deleting = false; vm.deletePlaylist(p); onBack() }) { Text(stringResource(R.string.delete), color = Cubo.RedDark) } },
            dismissButton = { TextButton({ deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ActionPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = bg, shadowElevation = if (bg == Cubo.Card) 1.dp else 0.dp) {
        Row(Modifier.height(48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NextSteps() {
    Column(
        Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cubo.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.next_steps), style = MaterialTheme.typography.titleMedium)
        listOf(R.string.next_step_1, R.string.next_step_2, R.string.next_step_3).forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Cubo.Yellow), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleSmall)
                }
                Text(stringResource(s), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TrackRow(
    t: Track, number: Int, dragging: Boolean,
    onPlay: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit,
    handle: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (dragging) Cubo.Card else Color.Transparent)
            .clickable(enabled = t.status == TrackStatus.READY, onClick = onPlay),
    ) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("%02d".format(number), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(34.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (t.status) {
                    TrackStatus.READY -> Text(
                        "CP%02d".format(number) + (if (t.durationSec > 0) " · " + formatDuration(t.durationSec) else ""),
                        style = MaterialTheme.typography.bodySmall, color = Cubo.Muted,
                    )
                    TrackStatus.ERROR -> Text(stringResource(R.string.track_error), style = MaterialTheme.typography.bodySmall, color = Cubo.RedDark, maxLines = 1)
                    TrackStatus.QUEUED -> Text(stringResource(R.string.status_waiting), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
                    TrackStatus.DOWNLOADING, TrackStatus.CONVERTING -> {
                        Text(
                            if (t.status == TrackStatus.CONVERTING) stringResource(R.string.status_converting) else stringResource(R.string.status_downloading, t.progress),
                            style = MaterialTheme.typography.bodySmall, color = Cubo.Muted,
                        )
                        ProgressBar(t.progress / 100f, Cubo.Blue, Modifier.padding(top = 2.dp, end = 8.dp), height = 6.dp)
                    }
                }
            }
            if (t.status == TrackStatus.DOWNLOADING || t.status == TrackStatus.CONVERTING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cubo.Blue)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = Cubo.Muted) }
                DropdownMenu(menu, { menu = false }) {
                    if (t.status == TrackStatus.READY) DropdownMenuItem({ Text(stringResource(R.string.play)) }, { menu = false; onPlay() })
                    if (t.status == TrackStatus.ERROR) DropdownMenuItem({ Text(stringResource(R.string.retry)) }, { menu = false; onRetry() })
                    DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { menu = false; onRename() })
                    DropdownMenuItem({ Text(stringResource(R.string.delete), color = Cubo.RedDark) }, { menu = false; onDelete() })
                }
            }
            handle()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cubo.Line))
    }
}

@Composable
fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton({ if (text.isNotBlank()) { onConfirm(text); onDismiss() } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ColorDialog(p: Playlist, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_color)) },
        text = { ColorPicker(p.color) { onPick(it); onDismiss() } },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
fun ColorPicker(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FigurePalette.forEachIndexed { i, (_, body) ->
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(body)
                    .border(3.dp, if (i == selected) Cubo.Yellow else Color.Transparent, CircleShape)
                    .clickable { onPick(i) },
            )
        }
    }
}
