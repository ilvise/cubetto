package it.cubetto.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.cubetto.app.R
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.PlaylistUi
import it.cubetto.app.ui.components.ChipButton
import it.cubetto.app.ui.components.FigureArt
import it.cubetto.app.ui.components.FigureCover
import it.cubetto.app.ui.components.InfoBanner
import it.cubetto.app.ui.components.RoundIconButton
import it.cubetto.app.ui.theme.Cubo
import it.cubetto.app.usb.BoxState

private enum class Filter { FAVORITES, ALL, ON_BOX, TO_SYNC }

@Composable
fun LibraryScreen(vm: AppViewModel, onOpen: (Long) -> Unit, onCreate: () -> Unit, onSettings: () -> Unit, onBox: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val box by vm.box.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var search by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by rememberSaveable { mutableStateOf(false) }

    val visible = playlists.filter {
        when (filter) {
            Filter.FAVORITES -> it.playlist.favorite
            Filter.ALL -> true
            Filter.ON_BOX -> it.onBox
            Filter.TO_SYNC -> it.needsSync
        }
    }.filter { p -> search.isNullOrBlank() || p.playlist.name.contains(search!!, true) || p.playlist.figureId.contains(search!!) }
    val toSync = playlists.count { it.needsSync }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.library_greeting), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted)
                        Text(stringResource(R.string.tab_library), style = MaterialTheme.typography.displaySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundIconButton(Icons.Rounded.Contactless, stringResource(R.string.read_figure), { reading = true })
                        RoundIconButton(Icons.Rounded.Search, stringResource(R.string.search), { search = if (search == null) "" else null })
                        RoundIconButton(Icons.Rounded.Settings, stringResource(R.string.settings), onSettings)
                    }
                }
                if (search != null) {
                    OutlinedTextField(
                        value = search!!, onValueChange = { search = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton(stringResource(R.string.filter_favorites), filter == Filter.FAVORITES, { filter = Filter.FAVORITES }, selectedColor = Cubo.Yellow) {
                        Icon(Icons.Rounded.Favorite, null, tint = Cubo.Red, modifier = Modifier.size(16.dp))
                    }
                    ChipButton(stringResource(R.string.filter_all), filter == Filter.ALL, { filter = Filter.ALL })
                    ChipButton(stringResource(R.string.filter_on_box), filter == Filter.ON_BOX, { filter = Filter.ON_BOX })
                    ChipButton(stringResource(R.string.filter_to_sync), filter == Filter.TO_SYNC, { filter = Filter.TO_SYNC })
                }
                if (!settings.warningDismissed) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cubo.Orange).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Warning, null, tint = Cubo.OrangeInk, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.warning_official_app), color = Cubo.OrangeInk, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        RoundIconButton(Icons.Rounded.Close, stringResource(R.string.close), vm::dismissWarning, bg = Color.Transparent, tint = Cubo.OrangeInk)
                    }
                }
                if (box is BoxState.Connected && toSync > 0) {
                    InfoBanner(
                        pluralStringResource(R.plurals.box_connected_to_sync, toSync, toSync),
                        Icons.Rounded.Usb, Cubo.GreenSoft, Cubo.GreenInk, onClick = onBox,
                    )
                }
                Surface(onClick = onCreate, shape = RoundedCornerShape(22.dp), color = Cubo.Card, shadowElevation = 1.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Cubo.Red), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Column {
                            Text(stringResource(R.string.create_new_figure), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.create_new_figure_sub), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted)
                        }
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(playlists.isEmpty()) }
        }
        items(visible, key = { it.playlist.id }) { p -> FigureCard(p) { onOpen(p.playlist.id) } }
    }

    if (reading) {
        NfcSheet(vm, writeId = null, title = null, onDismiss = { reading = false }, onOpenPlaylist = { id ->
            reading = false
            playlists.firstOrNull { it.playlist.figureId == id }?.let { onOpen(it.playlist.id) }
        })
    }
}

@Composable
private fun FigureCard(p: PlaylistUi, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            FigureCover(p.playlist.color, p.playlist.coverPath, Modifier.fillMaxWidth().aspectRatio(1f))
            val badgeMod = Modifier.align(Alignment.TopEnd).padding(8.dp)
            when {
                p.pendingCount > 0 -> CircularProgressIndicator(badgeMod.size(20.dp), color = Cubo.Blue, strokeWidth = 3.dp)
                p.needsSync -> Text(
                    stringResource(R.string.badge_new), style = MaterialTheme.typography.labelSmall,
                    modifier = badgeMod.clip(RoundedCornerShape(50)).background(Cubo.Yellow).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                p.onBox -> Box(badgeMod.size(22.dp).clip(CircleShape).background(Cubo.Green), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, stringResource(R.string.on_box), tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(p.playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${p.playlist.figureId} · " + pluralStringResource(R.plurals.tracks_count, p.tracks.size, p.tracks.size),
            style = MaterialTheme.typography.bodySmall, color = Cubo.Muted,
        )
    }
}

@Composable
private fun EmptyLibrary(nothing: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FigureArt(4, Modifier.size(110.dp))
        Text(stringResource(if (nothing) R.string.empty_library_title else R.string.empty_filter), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (nothing) Text(stringResource(R.string.empty_library_text), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
}
