package it.cubetto.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.cubetto.app.R
import it.cubetto.app.nfc.NfcResult
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.components.IdBadge
import it.cubetto.app.ui.theme.Cubo

/**
 * Foglio NFC: scrive l'ID [writeId] su un tag, oppure (se null) legge una statuina.
 * [onOpenPlaylist] riceve l'ID letto, per aprire la statuina corrispondente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcSheet(vm: AppViewModel, writeId: String?, title: String?, onDismiss: () -> Unit, onOpenPlaylist: (String) -> Unit = {}) {
    val result by vm.nfcResult.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
    DisposableEffect(writeId) {
        if (writeId != null) vm.startNfcWrite(writeId) else vm.startNfcRead()
        onDispose { vm.stopNfc() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Cubo.Ground) {
        Column(
            // Con l'aiuto aperto il contenuto può superare lo schermo
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (writeId != null) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Cubo.Ink).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.nfc_header, writeId, title ?: ""), color = Cubo.Yellow, style = MaterialTheme.typography.titleSmall)
                }
                TagHelp()
            }
            when {
                adapter == null -> Message(Icons.Rounded.ErrorOutline, stringResource(R.string.nfc_unavailable), null, Cubo.OrangeInk, Cubo.Orange)
                !adapter.isEnabled -> {
                    Message(Icons.Rounded.Warning, stringResource(R.string.nfc_disabled), null, Cubo.OrangeInk, Cubo.Orange)
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text(stringResource(R.string.nfc_open_settings)) }
                }
                else -> when (val r = result) {
                    NfcResult.Waiting -> Waiting(writeId != null)
                    is NfcResult.Written -> {
                        Message(Icons.Rounded.Check, stringResource(R.string.nfc_written_title), null, Cubo.Green, Cubo.GreenSoft)
                        Facts(listOf(
                            stringResource(R.string.nfc_fact_chip) to r.chip.label,
                            stringResource(R.string.nfc_fact_written) to "02190530${r.figureId}00",
                            stringResource(R.string.nfc_fact_verify) to stringResource(R.string.ok),
                        ))
                        Text(stringResource(R.string.nfc_written_hint), style = MaterialTheme.typography.bodyMedium, color = Cubo.Muted, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { vm.startNfcWrite(r.figureId) }, modifier = Modifier.weight(1f).height(54.dp)) { Text(stringResource(R.string.nfc_write_another)) }
                            Button(onClick = onDismiss, modifier = Modifier.weight(1f).height(54.dp)) { Text(stringResource(R.string.done)) }
                        }
                    }
                    is NfcResult.ReadOk -> {
                        if (r.figureId == null) {
                            Message(Icons.Rounded.ErrorOutline, stringResource(R.string.nfc_read_not_faba), null, Cubo.OrangeInk, Cubo.Orange)
                        } else {
                            IdBadge(r.figureId, big = true)
                            val mine = playlists.firstOrNull { it.playlist.figureId == r.figureId }
                            val name = mine?.playlist?.name ?: vm.knownName(r.figureId) ?: stringResource(R.string.unknown_figure)
                            Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                            if (mine != null) Button(onClick = { onOpenPlaylist(r.figureId) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(stringResource(R.string.nfc_open_figure)) }
                            OutlinedButton(onClick = { vm.startNfcWrite(r.figureId) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(stringResource(R.string.nfc_copy_to_tag)) }
                        }
                        Facts(listOf(stringResource(R.string.nfc_fact_chip) to r.chip.label, "UID" to r.uid))
                        if (r.suspicious) Message(Icons.Rounded.Warning, stringResource(R.string.nfc_suspicious_text), null, Cubo.OrangeInk, Cubo.Orange)
                    }
                    is NfcResult.SuspiciousChip -> {
                        Message(Icons.Rounded.Warning, stringResource(R.string.nfc_suspicious_title), stringResource(R.string.nfc_suspicious_text), Cubo.OrangeInk, Cubo.Orange)
                        Facts(listOf(stringResource(R.string.nfc_fact_chip) to r.chip.label, "UID" to r.uid))
                        Button(onClick = { writeId?.let { vm.startNfcWrite(it, force = true) } }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(stringResource(R.string.nfc_write_anyway)) }
                    }
                    is NfcResult.Failed -> {
                        val text = when (r.reason) {
                            NfcResult.Reason.NOT_NTAG -> R.string.nfc_err_not_ntag
                            NfcResult.Reason.LOCKED -> R.string.nfc_err_locked
                            NfcResult.Reason.LOST -> R.string.nfc_err_lost
                            NfcResult.Reason.VERIFY -> R.string.nfc_err_verify
                        }
                        Message(Icons.Rounded.ErrorOutline, stringResource(R.string.nfc_err_title), stringResource(text), Cubo.OrangeInk, Cubo.Orange)
                        Waiting(writeId != null, compact = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Waiting(write: Boolean, compact: Boolean = false) {
    if (!compact) Text(stringResource(if (write) R.string.nfc_bring_tag else R.string.nfc_bring_figure), style = MaterialTheme.typography.headlineMedium)
    Box(Modifier.size(if (compact) 120.dp else 210.dp).clip(CircleShape).background(Cubo.BlueSoft), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (compact) 90.dp else 160.dp)) {
            val s = size.minDimension / 170f
            drawCircle(Color(0xFF9CC6EE), 78 * s, style = Stroke(3 * s))
            drawRoundRect(Cubo.Ink, Offset(52 * s, 24 * s), Size(66 * s, 118 * s), CornerRadius(14 * s))
            drawRoundRect(Color.White, Offset(58 * s, 34 * s), Size(54 * s, 96 * s), CornerRadius(8 * s))
            listOf(14f, 24f, 34f).forEachIndexed { i, r ->
                drawArc(Cubo.Blue, -60f, 120f, false, Offset((62 + i * 4 - r) * s, (80 - r) * s), Size(2 * r * s, 2 * r * s), style = Stroke(4 * s, cap = StrokeCap.Round))
            }
            drawCircle(Cubo.Red, 26 * s, Offset(128 * s, 122 * s))
            drawCircle(Color(0xFFFF4A4F), 17 * s, Offset(128 * s, 122 * s))
            drawCircle(Color.White, 6 * s, Offset(128 * s, 122 * s))
        }
    }
    if (!compact) {
        Text(stringResource(R.string.nfc_bring_hint), style = MaterialTheme.typography.bodyLarge, color = Cubo.Muted, textAlign = TextAlign.Center)
        if (write) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cubo.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.nfc_chip_auto_title), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.nfc_chip_auto_text), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
            }
        }
    }
}

/** Aiuto ben visibile quando si scrive un tag: quali tag comprare e come fare una statuina. */
@Composable
private fun TagHelp() {
    var open by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cubo.YellowSoft)
            .border(2.dp, Cubo.Yellow, RoundedCornerShape(20.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = Cubo.YellowInk)
            Text(stringResource(R.string.nfc_help_button), style = MaterialTheme.typography.titleMedium, color = Cubo.YellowInk, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Cubo.YellowInk)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nfc_help_tags_title), style = MaterialTheme.typography.titleSmall, color = Cubo.Ink)
                Text(stringResource(R.string.nfc_help_tags_text), style = MaterialTheme.typography.bodyMedium, color = Cubo.Ink)
                HelpLink("Amazon IT") { uri.openUri("https://www.amazon.it/dp/B07Q7FZDSC") }
                HelpLink("Amazon ES") { uri.openUri("https://www.amazon.es/dp/B07Q7FZDSC") }
                HelpLink("Amazon DE") { uri.openUri("https://www.amazon.de/gp/product/B07Q43PXMR/") }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.nfc_help_diy_title), style = MaterialTheme.typography.titleSmall, color = Cubo.Ink)
                Text(stringResource(R.string.nfc_help_diy_text), style = MaterialTheme.typography.bodyMedium, color = Cubo.Ink)
                HelpLink(stringResource(R.string.nfc_help_link_base)) {
                    uri.openUri("https://www.printables.com/model/1378409-base-template-for-faba-audioplayer-figurines/files")
                }
                HelpLink(stringResource(R.string.nfc_help_link_photo)) {
                    uri.openUri("https://www.printables.com/model/1876485-faba-disc-with-photoframe/comments/3668118")
                }
            }
        }
    }
}

@Composable
private fun HelpLink(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Cubo.Card).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Cubo.BlueInk, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Cubo.BlueInk, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String?, fg: Color, bg: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = fg, textAlign = TextAlign.Center)
        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Facts(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cubo.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (k, v) ->
            Row { Text(k, color = Cubo.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(v, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    Spacer(Modifier.height(2.dp))
}
