package it.cubetto.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import it.cubetto.app.R
import it.cubetto.app.ui.components.FigureArt
import it.cubetto.app.ui.theme.Cubo
import kotlinx.coroutines.launch

/** Pagina della guida dedicata al box via USB, aperta direttamente dal tasto ? della scheda Box. */
const val GUIDE_PAGE_USB = 3

private class GuidePage(val icon: ImageVector?, val bg: Color, val fg: Color, val title: Int, val points: List<Int>)

private val pages = listOf(
    GuidePage(null, Cubo.YellowSoft, Cubo.YellowInk, R.string.guide_p0_title, listOf(R.string.guide_p0_1, R.string.guide_p0_2, R.string.guide_p0_3)),
    GuidePage(Icons.Rounded.MusicNote, Cubo.Indigo, Cubo.IndigoInk, R.string.guide_p1_title, listOf(R.string.guide_p1_1, R.string.guide_p1_2, R.string.guide_p1_3)),
    GuidePage(Icons.Rounded.Download, Cubo.BlueSoft, Cubo.BlueInk, R.string.guide_p2_title, listOf(R.string.guide_p2_1, R.string.guide_p2_2, R.string.guide_p2_3, R.string.guide_p2_4)),
    GuidePage(Icons.Rounded.Usb, Cubo.GreenSoft, Cubo.GreenInk, R.string.guide_p3_title, listOf(R.string.guide_p3_1, R.string.guide_p3_2, R.string.guide_p3_3, R.string.guide_p3_4, R.string.guide_p3_5)),
    GuidePage(Icons.Rounded.Contactless, Cubo.YellowSoft, Cubo.YellowInk, R.string.guide_p4_title, listOf(R.string.guide_p4_1, R.string.guide_p4_2, R.string.guide_p4_3, R.string.guide_p4_4)),
    GuidePage(Icons.Rounded.Warning, Cubo.Orange, Cubo.OrangeInk, R.string.guide_p5_title, listOf(R.string.guide_p5_1, R.string.guide_p5_2, R.string.guide_p5_3)),
)

/** Guida a pagine: si apre da sola al primo avvio e poi con il tasto ?. */
@Composable
fun GuideDialog(startPage: Int = 0, onDone: () -> Unit) {
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, pages.lastIndex)) { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Cubo.Ground).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.guide), style = MaterialTheme.typography.titleMedium, color = Cubo.Muted, modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.guide_skip), color = Cubo.Muted) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i -> Page(pages[i]) }
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.indices.forEach { i ->
                        val current = i == pager.currentPage
                        val width by animateDpAsState(if (current) 22.dp else 8.dp, label = "dot")
                        val color by animateColorAsState(if (current) Cubo.Ink else Cubo.Line, label = "dot")
                        Box(Modifier.size(width, 8.dp).clip(CircleShape).background(color))
                    }
                }
                if (pager.currentPage > 0) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                        Text(stringResource(R.string.back), color = Cubo.Ink)
                    }
                }
                Button(
                    onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cubo.Yellow, contentColor = Cubo.Ink),
                ) { Text(stringResource(if (last) R.string.guide_start else R.string.guide_next), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun Page(page: GuidePage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(110.dp).clip(RoundedCornerShape(32.dp)).background(page.bg), contentAlignment = Alignment.Center) {
            if (page.icon != null) Icon(page.icon, null, tint = page.fg, modifier = Modifier.size(56.dp))
            else FigureArt(0, Modifier.size(84.dp))
        }
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Cubo.Card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            page.points.forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(page.fg))
                    Text(stringResource(p), style = MaterialTheme.typography.bodyLarge, color = Cubo.Ink)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
