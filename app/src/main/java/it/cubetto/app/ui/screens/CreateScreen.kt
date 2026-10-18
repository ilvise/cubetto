package it.cubetto.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.cubetto.app.R
import it.cubetto.app.faba.FabaTag
import it.cubetto.app.faba.IdAdvisor
import it.cubetto.app.faba.IdOption
import it.cubetto.app.faba.IdStatus
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.components.BackButton
import it.cubetto.app.ui.components.ChipButton
import it.cubetto.app.ui.components.FigureCover
import it.cubetto.app.ui.components.Pill
import it.cubetto.app.ui.theme.Cubo
import kotlinx.coroutines.launch

@Composable
fun CreateScreen(vm: AppViewModel, onBack: () -> Unit, onCreated: (Long) -> Unit) {
    var step by rememberSaveable { mutableStateOf(1) }
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf((0..5).random()) }
    val scope = rememberCoroutineScope()

    if (step == 1) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepHeader(stringResource(R.string.create_step, 1, 2), stringResource(R.string.create_title), onBack)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FigureCover(color, null, Modifier.size(150.dp), corner = 30.dp, artPadding = 14.dp)
            }
            OutlinedTextField(
                name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.create_name)) },
                placeholder = { Text(stringResource(R.string.create_name_hint)) },
                shape = RoundedCornerShape(16.dp),
            )
            Text(stringResource(R.string.create_color), style = MaterialTheme.typography.titleMedium)
            ColorPicker(color) { color = it }
            Spacer(Modifier.weight(1f))
            Button(onClick = { step = 2 }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
                Text(stringResource(R.string.next), style = MaterialTheme.typography.labelLarge)
            }
        }
    } else {
        IdPicker(
            vm, excludePlaylist = null,
            header = { StepHeader(stringResource(R.string.create_step, 2, 2), stringResource(R.string.pick_id_title)) { step = 1 } },
            confirmLabel = { stringResource(R.string.create_with_id, it) },
        ) { id ->
            scope.launch { onCreated(vm.createPlaylist(name, color, id)) }
        }
    }
}

@Composable
fun ChangeIdScreen(vm: AppViewModel, playlistId: Long, onDone: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val p = playlists.firstOrNull { it.playlist.id == playlistId }?.playlist ?: return
    IdPicker(
        vm, excludePlaylist = playlistId, initial = p.figureId,
        header = { StepHeader(p.name, stringResource(R.string.change_id), onDone) },
        confirmLabel = { stringResource(R.string.use_id, it) },
    ) { id ->
        vm.updatePlaylist(p.copy(figureId = id))
        onDone()
    }
}

@Composable
private fun StepHeader(kicker: String, title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BackButton(onBack)
        Column {
            Text(kicker, style = MaterialTheme.typography.labelMedium, color = Cubo.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private enum class IdTab { SUGGESTED, ALL, FREE }

/**
 * Selettore ID: tutta la lista di myfaba-hacks, con in cima gli ID delle statuine
 * vendute in lingue diverse da quella dell'app (difficilmente le hai in casa).
 */
@Composable
fun IdPicker(
    vm: AppViewModel,
    excludePlaylist: Long?,
    initial: String? = null,
    header: @Composable () -> Unit,
    confirmLabel: @Composable (String) -> String,
    onConfirm: (String) -> Unit,
) {
    val language = LocalConfiguration.current.locales[0].language
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val options = remember(language, playlists.size) { vm.idOptions(language, excludePlaylist) }
    var tab by rememberSaveable { mutableStateOf(IdTab.SUGGESTED) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(initial ?: IdAdvisor.firstSuggestion(options) ?: "1032") }
    var manual by remember { mutableStateOf(false) }
    var confirmRisk by remember { mutableStateOf<IdOption?>(null) }

    val visible = options.filter {
        when (tab) {
            IdTab.SUGGESTED -> it.status == IdStatus.SUGGESTED || it.status == IdStatus.FREE
            IdTab.ALL -> true
            IdTab.FREE -> it.status == IdStatus.FREE
        }
    }.filter { o -> query.isBlank() || o.id.contains(query) || o.figures.any { it.name.contains(query, true) } }
    val selectedOption = options.firstOrNull { it.id == selected } ?: IdOption(selected, emptyList(), IdStatus.FREE)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { header() }
            item { Text(stringResource(R.string.pick_id_explain), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4F4A44), modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text(stringResource(R.string.pick_id_search)) }, shape = RoundedCornerShape(16.dp),
                    )
                    TextButton(onClick = { manual = true }) { Text(stringResource(R.string.pick_id_manual)) }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton(stringResource(R.string.pick_id_suggested), tab == IdTab.SUGGESTED, { tab = IdTab.SUGGESTED })
                    ChipButton(stringResource(R.string.pick_id_all, options.size), tab == IdTab.ALL, { tab = IdTab.ALL })
                    ChipButton(stringResource(R.string.pick_id_free), tab == IdTab.FREE, { tab = IdTab.FREE })
                }
            }
            items(visible, key = { it.id }) { o -> IdRow(o, o.id == selected) { selected = o.id } }
        }
        Column(Modifier.fillMaxWidth().background(Cubo.Card).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.pick_id_will_write, FabaTag.payloadText(selected)), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted)
            Button(
                onClick = {
                    if (selectedOption.status == IdStatus.OWN_LANGUAGE || selectedOption.status == IdStatus.USED_BY_ME || selectedOption.status == IdStatus.NEUTRAL) confirmRisk = selectedOption
                    else onConfirm(selected)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp),
            ) { Text(confirmLabel(selected), style = MaterialTheme.typography.labelLarge) }
        }
    }

    if (manual) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text(stringResource(R.string.pick_id_manual_title)) },
            text = {
                OutlinedTextField(
                    text, { v -> text = v.filter { it.isDigit() }.take(4) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text("0742") }, shape = RoundedCornerShape(14.dp),
                )
            },
            confirmButton = {
                TextButton(enabled = text.length == 4, onClick = { selected = text; manual = false; tab = IdTab.ALL; query = text }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton({ manual = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    confirmRisk?.let { o ->
        AlertDialog(
            onDismissRequest = { confirmRisk = null },
            title = { Text(stringResource(R.string.pick_id_risk_title, o.id)) },
            text = {
                Text(
                    if (o.status == IdStatus.USED_BY_ME) stringResource(R.string.pick_id_risk_mine, o.usedBy ?: "")
                    else stringResource(R.string.pick_id_risk_official, o.figures.firstOrNull()?.name ?: "")
                )
            },
            confirmButton = { TextButton({ confirmRisk = null; onConfirm(o.id) }) { Text(stringResource(R.string.pick_id_risk_confirm)) } },
            dismissButton = { TextButton({ confirmRisk = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun IdRow(o: IdOption, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp), color = Cubo.Card,
        modifier = Modifier.fillMaxWidth().border(3.dp, if (selected) Cubo.Yellow else Color.Transparent, RoundedCornerShape(18.dp)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(12.dp)).background(Cubo.Ink), contentAlignment = Alignment.Center) {
                Text(o.id, color = Cubo.Yellow, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    o.figures.joinToString(" / ") { it.name }.ifBlank { stringResource(R.string.pick_id_not_in_registry) },
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val langs = o.figures.map { it.language }.distinct()
                    if (langs.isEmpty()) Pill(stringResource(R.string.lang_free), Cubo.Track, Cubo.Ink)
                    langs.take(2).forEach { Pill(languageLabel(it), Cubo.Track, Cubo.Ink) }
                    when (o.status) {
                        IdStatus.SUGGESTED -> Pill(stringResource(R.string.status_suggested), Cubo.GreenSoft, Cubo.GreenInk)
                        IdStatus.FREE -> Pill(stringResource(R.string.status_free), Cubo.GreenSoft, Cubo.GreenInk)
                        IdStatus.OWN_LANGUAGE -> Pill(stringResource(R.string.status_own_language), Cubo.Orange, Cubo.OrangeInk)
                        IdStatus.NEUTRAL -> Pill(stringResource(R.string.status_neutral), Cubo.Orange, Cubo.OrangeInk)
                        IdStatus.USED_BY_ME -> Pill(stringResource(R.string.status_used_by_me, o.usedBy ?: ""), Cubo.Indigo, Cubo.IndigoInk)
                    }
                }
            }
        }
    }
}

@Composable
fun languageLabel(registryLanguage: String): String = when (registryLanguage) {
    "Italian" -> stringResource(R.string.lang_italian)
    "Italian/English" -> stringResource(R.string.lang_italian_english)
    "Spanish" -> stringResource(R.string.lang_spanish)
    "Latam" -> stringResource(R.string.lang_latam)
    "French" -> stringResource(R.string.lang_french)
    "Dutch" -> stringResource(R.string.lang_dutch)
    "Catalan" -> stringResource(R.string.lang_catalan)
    "Neutral" -> stringResource(R.string.lang_neutral)
    else -> registryLanguage
}
