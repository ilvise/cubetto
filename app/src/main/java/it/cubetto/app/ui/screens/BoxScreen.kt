package it.cubetto.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.cubetto.app.R
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.components.ProgressBar
import it.cubetto.app.ui.components.SectionHeader
import it.cubetto.app.ui.components.formatBytes
import it.cubetto.app.ui.theme.Cubo
import it.cubetto.app.usb.Backup
import it.cubetto.app.usb.BoxState
import it.cubetto.app.usb.SyncError
import java.text.DateFormat
import java.util.Date

@Composable
fun BoxScreen(vm: AppViewModel, onOpen: (Long) -> Unit) {
    val box by vm.box.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var backups by remember { mutableStateOf(vm.backups()) }
    var restoring by remember { mutableStateOf<Backup?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.refreshBox() }
    LaunchedEffect(sync?.finished, refreshKey) { backups = vm.backups() }

    val toSync = playlists.filter { it.needsSync }
    val backupDoneMsg = stringResource(R.string.backup_done)
    val restoreOk = stringResource(R.string.restore_ok)
    val restoreFail = stringResource(R.string.restore_failed)

    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.box_title), style = MaterialTheme.typography.displaySmall) }
        item { Hero(box, onPermission = vm::requestUsbPermission, onRetry = vm::refreshBox) }

        sync?.let { s ->
            item {
                Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cubo.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (s.finished && s.error == null) R.string.sync_done_title else R.string.sync_copying, s.playlistName),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        Text("${s.current}/${s.total}", style = MaterialTheme.typography.titleSmall, color = Cubo.Muted)
                    }
                    ProgressBar(if (s.total == 0) 0f else s.current / s.total.toFloat(), if (s.error != null) Cubo.RedDark else Cubo.Blue, height = 12.dp)
                    if (s.error != null) {
                        Check(
                            when (s.error) {
                                SyncError.NO_SPACE -> stringResource(R.string.sync_err_space, formatBytes(s.neededBytes))
                                SyncError.TOO_MANY_TRACKS -> stringResource(R.string.too_many_tracks)
                                SyncError.NO_TRACKS -> stringResource(R.string.sync_err_no_tracks)
                                SyncError.IO -> stringResource(R.string.sync_err_io)
                            }, Cubo.RedDark,
                        )
                    } else {
                        if (s.backupDone) Check(stringResource(R.string.sync_check_backup), Cubo.Green)
                        if (s.neededBytes > 0) Check(stringResource(R.string.sync_check_space, formatBytes(s.neededBytes)), Cubo.Green)
                        Check(stringResource(R.string.sync_check_tracks, s.total), Cubo.Green)
                        Check(pluralStringResource(R.plurals.sync_check_diff, s.skipped, s.skipped), Cubo.Blue)
                    }
                    if (s.finished) TextButton(onClick = vm::clearSync) { Text(stringResource(R.string.close)) }
                }
            }
        }

        if (box is BoxState.Connected && toSync.isNotEmpty() && (sync == null || sync!!.finished)) {
            item {
                Button(onClick = vm::syncAll, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp), shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cubo.Yellow, contentColor = Cubo.Ink)) {
                    Text(pluralStringResource(R.plurals.sync_all, toSync.size, toSync.size), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        (box as? BoxState.Connected)?.let { c ->
            item {
                SectionHeader(stringResource(R.string.on_box)) {
                    TextButton(enabled = !backupBusy, onClick = {
                        backupBusy = true
                        vm.backupAll { n -> backupBusy = false; refreshKey++; vm.showMessage("$backupDoneMsg ($n)") }
                    }) { Text(stringResource(R.string.backup_all)) }
                }
            }
            if (c.info.figures.isEmpty()) item { Text(stringResource(R.string.box_empty), color = Cubo.Muted, style = MaterialTheme.typography.bodyMedium) }
            items(c.info.figures, key = { it.id }) { f ->
                val mine = playlists.firstOrNull { it.playlist.figureId == f.id && it.playlist.syncedFigureId == f.id }
                val name = mine?.playlist?.name ?: (vm.knownName(f.id)?.let { stringResource(R.string.original_suffix, it) }) ?: stringResource(R.string.unknown_figure)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cubo.Card)
                        .clickable(enabled = mine != null) { mine?.let { onOpen(it.playlist.id) } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(56.dp, 34.dp).clip(RoundedCornerShape(10.dp)).background(if (mine != null) Cubo.Ink else Cubo.Track),
                        contentAlignment = Alignment.Center,
                    ) { Text(f.id, color = if (mine != null) Cubo.Yellow else Cubo.Ink, style = MaterialTheme.typography.titleSmall) }
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatBytes(f.bytes), style = MaterialTheme.typography.labelMedium, color = Cubo.Muted)
                }
            }
        }

        if (backups.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.backups)) }
            items(backups, key = { it.dir.name }) { b ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cubo.Card).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("K${b.figureId} · " + pluralStringResource(R.plurals.files_count, b.files, b.files), style = MaterialTheme.typography.titleSmall)
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(b.date)), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
                    }
                    TextButton(enabled = box is BoxState.Connected, onClick = { restoring = b }) { Text(stringResource(R.string.restore)) }
                    TextButton(onClick = { vm.deleteBackup(b); refreshKey++ }) { Text(stringResource(R.string.delete), color = Cubo.RedDark) }
                }
            }
        }

        item {
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cubo.Orange).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Warning, null, tint = Cubo.OrangeInk, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.warning_official_app), color = Cubo.OrangeInk, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    restoring?.let { b ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(R.string.restore)) },
            text = { Text(stringResource(R.string.restore_text, b.figureId)) },
            confirmButton = {
                TextButton({
                    restoring = null
                    vm.restore(b) { ok -> vm.showMessage(if (ok) restoreOk else restoreFail) }
                }) { Text(stringResource(R.string.restore)) }
            },
            dismissButton = { TextButton({ restoring = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Hero(box: BoxState, onPermission: () -> Unit, onRetry: () -> Unit) {
    val connected = box is BoxState.Connected
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(if (connected) Cubo.Red else Cubo.Card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BoxArt(connected, Modifier.size(78.dp))
        val fg = if (connected) Color.White else Cubo.Ink
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (box) {
                BoxState.Disconnected -> {
                    Text(stringResource(R.string.box_disconnected), style = MaterialTheme.typography.titleLarge, color = fg)
                    Text(stringResource(R.string.box_disconnected_hint), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
                }
                BoxState.NeedsPermission -> {
                    Text(stringResource(R.string.box_found), style = MaterialTheme.typography.titleLarge, color = fg)
                    Text(stringResource(R.string.box_permission_hint), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
                    Button(onClick = onPermission, shape = RoundedCornerShape(50)) { Text(stringResource(R.string.box_allow)) }
                }
                BoxState.Connecting -> {
                    Text(stringResource(R.string.box_connecting), style = MaterialTheme.typography.titleLarge, color = fg)
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                }
                is BoxState.Error -> {
                    Text(stringResource(R.string.box_error), style = MaterialTheme.typography.titleLarge, color = fg)
                    Text(box.message, style = MaterialTheme.typography.bodySmall, color = Cubo.Muted, maxLines = 3)
                    OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(50)) { Text(stringResource(R.string.retry)) }
                }
                is BoxState.Connected -> {
                    val used = box.info.capacity - box.info.free
                    Text(stringResource(R.string.box_connected), style = MaterialTheme.typography.titleLarge, color = fg)
                    Text(stringResource(R.string.box_connected_sub), style = MaterialTheme.typography.bodySmall, color = fg)
                    ProgressBar(if (box.info.capacity > 0) used / box.info.capacity.toFloat() else 0f, Cubo.Yellow, Modifier.padding(top = 6.dp), height = 10.dp, track = Color.White.copy(alpha = 0.3f))
                    Text(stringResource(R.string.box_space, formatBytes(used), formatBytes(box.info.capacity)), style = MaterialTheme.typography.labelMedium, color = fg)
                }
            }
        }
    }
}

@Composable
private fun Check(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(if (color == Cubo.RedDark) Icons.Rounded.ErrorOutline else Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4F4A44))
    }
}

/** Il cubo rosso disegnato; grigio quando non è collegato. */
@Composable
private fun BoxArt(connected: Boolean, modifier: Modifier) {
    val dark = if (connected) Color(0xFFB5161B) else Color(0xFFB9B3AB)
    val light = if (connected) Color(0xFFFF4A4F) else Color(0xFFD9D4CC)
    Canvas(modifier) {
        val s = size.minDimension / 80f
        drawRoundRect(dark, Offset(6 * s, 10 * s), Size(68 * s, 64 * s), CornerRadius(16 * s))
        drawRoundRect(light, Offset(6 * s, 6 * s), Size(68 * s, 62 * s), CornerRadius(16 * s))
        drawCircle(dark, 15 * s, Offset(40 * s, 42 * s))
        drawCircle(Color.White.copy(alpha = 0.9f), 9 * s, Offset(40 * s, 42 * s))
        drawCircle(Color.White, 4 * s, Offset(20 * s, 20 * s))
        drawCircle(if (connected) Cubo.Yellow else Color.White, 4 * s, Offset(60 * s, 20 * s))
    }
}
