package it.cubetto.app.ui

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationTest {

    private fun nav() = TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        graph = createGraph(startDestination = Routes.LIBRARY) {
            listOf(Routes.LIBRARY, Routes.BOX, Routes.ADD, Routes.CREATE, Routes.PLAYLIST).forEach { composable(it) {} }
        }
    }

    private val NavHostController.route get() = currentDestination?.route

    @Test
    fun libraryReachableAfterShare() {
        val n = nav()
        n.navigateTab(Routes.add()) // come fa il "Condividi" da YouTube
        assertEquals(Routes.ADD, n.route)
        n.navigateTab(Routes.LIBRARY)
        assertEquals(Routes.LIBRARY, n.route)
        n.navigateTab(Routes.BOX)
        n.navigateTab(Routes.LIBRARY)
        assertEquals(Routes.LIBRARY, n.route)
    }

    @Test
    fun libraryReachableFromPlaylistAdd() {
        val n = nav()
        n.navigate(Routes.playlist(1))
        n.navigate(Routes.add(playlist = 1))
        n.navigateTab(Routes.LIBRARY)
        assertEquals(Routes.LIBRARY, n.route)
        n.navigateTab(Routes.CREATE)
        n.navigateTab(Routes.LIBRARY)
        assertEquals(Routes.LIBRARY, n.route)
    }
}
