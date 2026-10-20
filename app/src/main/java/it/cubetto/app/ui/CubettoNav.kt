package it.cubetto.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.cubetto.app.R
import it.cubetto.app.ui.components.MiniPlayer
import it.cubetto.app.ui.screens.AddScreen
import it.cubetto.app.ui.screens.BoxScreen
import it.cubetto.app.ui.screens.ChangeIdScreen
import it.cubetto.app.ui.screens.CreateScreen
import it.cubetto.app.ui.screens.LibraryScreen
import it.cubetto.app.ui.screens.PlaylistScreen
import it.cubetto.app.ui.screens.SettingsScreen
import it.cubetto.app.ui.theme.Cubo

object Routes {
    const val BOX = "box"
    const val ADD = "add?playlist={playlist}"
    const val LIBRARY = "library"
    const val CREATE = "create"
    const val PLAYLIST = "playlist/{id}"
    const val CHANGE_ID = "changeid/{id}"
    const val SETTINGS = "settings"

    fun add(playlist: Long? = null) = "add?playlist=${playlist ?: -1}"
    fun playlist(id: Long) = "playlist/$id"
    fun changeId(id: Long) = "changeid/$id"
}

private data class Tab(val route: String, val nav: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.BOX, Routes.BOX, R.string.tab_box, Icons.Rounded.Usb),
    Tab(Routes.ADD, Routes.add(), R.string.tab_add, Icons.Rounded.Download),
    Tab(Routes.LIBRARY, Routes.LIBRARY, R.string.tab_library, Icons.Rounded.LibraryMusic),
    Tab(Routes.CREATE, Routes.CREATE, R.string.tab_create, Icons.Rounded.MusicNote),
)

/** Eventi esterni che portano a una schermata (condivisione, collegamento USB). */
sealed interface LaunchEvent {
    data class Shared(val url: String) : LaunchEvent
    data object UsbAttached : LaunchEvent
}

@Composable
fun CubettoRoot(vm: AppViewModel, event: LaunchEvent?, onEventHandled: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    // Si naviga solo quando il grafo c'è già (all'avvio a freddo da condivisione/USB)
    val graphReady = backStack != null
    LaunchedEffect(event, graphReady) {
        if (event == null || !graphReady) return@LaunchedEffect
        when (event) {
            is LaunchEvent.Shared -> {
                vm.share(event.url)
                nav.navigateTab(Routes.add())
            }
            LaunchEvent.UsbAttached -> nav.navigateTab(Routes.BOX)
        }
        onEventHandled()
    }

    Scaffold(
        containerColor = Cubo.Ground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = Cubo.Card, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.nav) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cubo.Ink,
                                selectedTextColor = Cubo.Ink,
                                indicatorColor = Cubo.Yellow,
                                unselectedIconColor = Cubo.Muted,
                                unselectedTextColor = Cubo.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Routes.LIBRARY) {
                composable(Routes.LIBRARY) {
                    LibraryScreen(vm, onOpen = { nav.navigate(Routes.playlist(it)) }, onCreate = { nav.navigateTab(Routes.CREATE) }, onSettings = { nav.navigate(Routes.SETTINGS) }, onBox = { nav.navigateTab(Routes.BOX) })
                }
                composable(Routes.BOX) { BoxScreen(vm, onOpen = { nav.navigate(Routes.playlist(it)) }) }
                composable(
                    Routes.ADD,
                    arguments = listOf(navArgument("playlist") { type = NavType.LongType; defaultValue = -1L }),
                ) { entry ->
                    AddScreen(
                        vm,
                        initialPlaylist = entry.arguments?.getLong("playlist")?.takeIf { it > 0 },
                        onCreate = { nav.navigateTab(Routes.CREATE) },
                    )
                }
                composable(Routes.CREATE) {
                    CreateScreen(vm, onBack = { nav.popBackStack() }, onCreated = { id ->
                        nav.navigate(Routes.playlist(id)) { popUpTo(Routes.LIBRARY) }
                    })
                }
                composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments!!.getLong("id")
                    PlaylistScreen(
                        vm, id,
                        onBack = { nav.popBackStack() },
                        onAdd = { nav.navigate(Routes.add(playlist = id)) },
                        onChangeId = { nav.navigate(Routes.changeId(id)) },
                        onBox = { nav.navigateTab(Routes.BOX) },
                    )
                }
                composable(Routes.CHANGE_ID, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    ChangeIdScreen(vm, entry.arguments!!.getLong("id"), onDone = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
            }
            now?.let {
                MiniPlayer(
                    it, onToggle = vm::togglePlayer, onClose = vm::stopPlayer,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(PaddingValues(12.dp)),
                )
            }
        }
    }
}

/**
 * Cambio di tab con stato salvato per ogni tab. La Libreria è la destinazione di
 * partenza: per lei si svuota lo stack senza salvarlo, altrimenti Navigation
 * associa alla Libreria lo stack tolto (es. "Aggiungi" aperto da una condivisione)
 * e da quel momento ogni tap su Libreria lo ripristina invece di mostrarla.
 */
internal fun NavHostController.navigateTab(route: String) {
    if (route == Routes.LIBRARY) {
        if (!popBackStack(Routes.LIBRARY, inclusive = false)) navigate(Routes.LIBRARY)
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
