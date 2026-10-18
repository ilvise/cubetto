package it.cubetto.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.cubetto.app.BuildConfig
import it.cubetto.app.R
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.components.BackButton
import it.cubetto.app.ui.components.SectionHeader
import it.cubetto.app.ui.theme.Cubo
import it.cubetto.app.ytdlp.YtDlpStatus

/** Lingue dell'app: quelle in cui FABA™ è distribuito secondo il registro di myfaba-hacks, più l'inglese. */
private val LANGUAGES = listOf(
    "it" to "Italiano", "es" to "Español", "ca" to "Català", "fr" to "Français", "nl" to "Nederlands", "en" to "English",
)

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val yt by vm.ytDlp.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var registryCount by remember { mutableStateOf(vm.registryCount) }
    var registryBusy by remember { mutableStateOf(false) }

    val exportOk = stringResource(R.string.export_ok)
    val importOk = stringResource(R.string.import_ok)
    val failed = stringResource(R.string.operation_failed)
    val registryFail = stringResource(R.string.registry_update_failed)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        u?.let { vm.export(it) { ok -> vm.showMessage(if (ok) exportOk else failed) } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { vm.import(it) { n -> vm.showMessage(if (n != null) "$importOk ($n)" else failed) } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackButton(onBack)
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        }

        SectionHeader(stringResource(R.string.settings_language))
        Card {
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifBlank { null }
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_language_app), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = true }) {
                    Text(LANGUAGES.firstOrNull { it.first == current }?.second ?: stringResource(R.string.settings_language_system))
                }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.settings_language_system)) }, { open = false; vm.setLanguage(null) })
                    LANGUAGES.forEach { (tag, name) -> DropdownMenuItem({ Text(name) }, { open = false; vm.setLanguage(tag) }) }
                }
            }
            Text(stringResource(R.string.settings_language_hint), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
        }

        SectionHeader(stringResource(R.string.settings_audio))
        Card {
            val a = settings.audio
            Toggle(stringResource(R.string.opt_normalize), stringResource(R.string.opt_normalize_hint), a.normalize) { v -> vm.setAudio { it.copy(normalize = v) } }
            Toggle(stringResource(R.string.opt_trim), stringResource(R.string.opt_trim_hint), a.trimSilence) { v -> vm.setAudio { it.copy(trimSilence = v) } }
            Toggle(stringResource(R.string.opt_chapters), stringResource(R.string.opt_chapters_hint), a.splitChapters) { v -> vm.setAudio { it.copy(splitChapters = v) } }
            Toggle(stringResource(R.string.opt_sponsors), stringResource(R.string.opt_sponsors_hint), a.skipSponsors) { v -> vm.setAudio { it.copy(skipSponsors = v) } }
            Toggle(stringResource(R.string.opt_mono), stringResource(R.string.opt_mono_hint), a.mono) { v -> vm.setAudio { it.copy(mono = v) } }
        }

        SectionHeader(stringResource(R.string.tab_box))
        Card {
            Toggle(stringResource(R.string.opt_autosync), stringResource(R.string.opt_autosync_hint), settings.autoSync, vm::setAutoSync)
        }

        SectionHeader(stringResource(R.string.settings_engine))
        Card {
            val version = when (val s = yt) {
                is YtDlpStatus.UpToDate -> s.version
                is YtDlpStatus.Updated -> s.version
                is YtDlpStatus.Error -> s.version
                else -> null
            } ?: "—"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp $version", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (val s = yt) {
                            YtDlpStatus.Starting, YtDlpStatus.Checking -> stringResource(R.string.ytdlp_checking)
                            is YtDlpStatus.UpToDate -> stringResource(R.string.ytdlp_uptodate)
                            is YtDlpStatus.Updated -> stringResource(R.string.ytdlp_updated)
                            is YtDlpStatus.Error -> stringResource(R.string.ytdlp_error) + ": " + s.message
                        },
                        style = MaterialTheme.typography.bodySmall, color = Cubo.Muted,
                    )
                }
                TextButton(onClick = vm::updateYtDlp, enabled = yt !is YtDlpStatus.Checking) { Text(stringResource(R.string.check_now)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.registry_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.registry_count, registryCount), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
                }
                TextButton(enabled = !registryBusy, onClick = {
                    registryBusy = true
                    vm.updateRegistry { n ->
                        registryBusy = false
                        if (n != null) registryCount = n else vm.showMessage(registryFail)
                    }
                }) { Text(stringResource(R.string.update)) }
            }
        }

        SectionHeader(stringResource(R.string.settings_backup))
        Card {
            Text(stringResource(R.string.export_hint), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
            Row {
                TextButton(onClick = { exporter.launch("cubetto-playlist.json") }) { Text(stringResource(R.string.export)) }
                TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.import_)) }
            }
        }

        SectionHeader(stringResource(R.string.settings_about))
        Card {
            Text("Cubetto ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.trademark_notice), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
            Text(stringResource(R.string.own_content_notice), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
            Text(stringResource(R.string.risk_notice), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
            Text(stringResource(R.string.credits), style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
            TextButton(onClick = { uri.openUri("https://github.com/wansors/myfaba-hacks") }) { Text("myfaba-hacks") }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cubo.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Cubo.Muted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Cubo.Green))
    }
}
