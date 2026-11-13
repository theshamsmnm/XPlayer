package com.xplayer.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xplayer.app.R
import com.xplayer.app.appContainer
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.feature.albums.AlbumDetailScreen
import com.xplayer.app.feature.artists.ArtistDetailScreen
import com.xplayer.app.feature.artists.ArtistsScreen
import com.xplayer.app.feature.collections.CollectionDetailScreen
import com.xplayer.app.feature.collections.CollectionsScreen
import com.xplayer.app.feature.favorites.FavoritesScreen
import com.xplayer.app.feature.library.AlbumGridScreen
import com.xplayer.app.feature.nowplaying.NowPlayingScreen
import com.xplayer.app.feature.search.SearchScreen
import com.xplayer.app.feature.settings.LibraryManageScreen
import com.xplayer.app.feature.settings.SettingsScreen
import com.xplayer.app.ui.components.MiniPlayer
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val COLLECTIONS = "collections"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    const val MANAGE = "manage"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val COLLECTION = "collection/{id}"
    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun collection(id: Long) = "collection/$id"
}

private data class TopLevel(val route: String, val icon: ImageVector, val label: Int)

private val TopLevelDestinations = listOf(
    TopLevel(Routes.HOME, Icons.Filled.GridView, R.string.nav_home),
    TopLevel(Routes.ALBUMS, Icons.Filled.Album, R.string.nav_albums),
    TopLevel(Routes.ARTISTS, Icons.Filled.Person, R.string.nav_artists),
    TopLevel(Routes.COLLECTIONS, Icons.Filled.QueueMusic, R.string.nav_collections),
    TopLevel(Routes.SEARCH, Icons.Filled.Search, R.string.nav_search),
    TopLevel(Routes.FAVORITES, Icons.Filled.Favorite, R.string.nav_favorites),
    TopLevel(Routes.SETTINGS, Icons.Filled.Settings, R.string.nav_settings),
)

/**
 * Root of the signed-in UI: navigation graph + bottom bar (rail on wide screens) + persistent mini player.
 * Playback lives in the service, so navigating (or recreating the activity) never interrupts audio.
 */
@Composable
fun AppRoot() {
    val container = LocalContext.current.appContainer
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playerState by container.playback.state.collectAsStateWithLifecycle()
    val fullPlayer = route == Routes.PLAYER
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun navigateTop(dest: String) {
        nav.navigate(dest) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (wide && !fullPlayer) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                TopLevelDestinations.forEach { d ->
                    NavigationRailItem(
                        selected = backStack.isIn(d.route),
                        onClick = { navigateTop(d.route) },
                        icon = { Icon(d.icon, null) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AppNavHost(nav, onRescan = { scope.launch { runCatching { container.syncCoordinator.syncNow(true) } } })
            }
            if (!fullPlayer) {
                MiniPlayer(playerState, container.playback, onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } })
                if (!wide) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        TopLevelDestinations.forEach { d ->
                            NavigationBarItem(
                                selected = backStack.isIn(d.route),
                                onClick = { navigateTop(d.route) },
                                icon = { Icon(d.icon, stringResource(d.label)) },
                                label = { Text(stringResource(d.label), maxLines = 1) },
                                alwaysShowLabel = false,
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        }
                    }
                } else {
                    Box(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

private fun NavBackStackEntry?.isIn(route: String): Boolean =
    this?.destination?.hierarchy?.any { it.route == route } == true

@Composable
private fun AppNavHost(nav: NavHostController, onRescan: () -> Unit) {
    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(160)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(160)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable(Routes.HOME) {
            AlbumGridScreen(LayoutPrefs.SCOPE_LIBRARY, stringResource(R.string.app_name), { nav.navigate(Routes.album(it)) }, onRescan)
        }
        composable(Routes.ALBUMS) {
            AlbumGridScreen(LayoutPrefs.SCOPE_ALBUMS, stringResource(R.string.nav_albums), { nav.navigate(Routes.album(it)) }, onRescan)
        }
        composable(Routes.ARTISTS) { ArtistsScreen { nav.navigate(Routes.artist(it)) } }
        composable(Routes.COLLECTIONS) { CollectionsScreen { nav.navigate(Routes.collection(it)) } }
        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenAlbum = { nav.navigate(Routes.album(it)) },
                onOpenArtist = { nav.navigate(Routes.artist(it)) },
                onOpenCollection = { nav.navigate(Routes.collection(it)) },
            )
        }
        composable(Routes.FAVORITES) { FavoritesScreen { nav.navigate(Routes.album(it)) } }
        composable(Routes.SETTINGS) { SettingsScreen(onOpenManage = { nav.navigate(Routes.MANAGE) }) }
        composable(Routes.MANAGE) { LibraryManageScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ALBUM, idArg) { AlbumDetailScreen(it.arguments!!.getLong("id")) { nav.popBackStack() } }
        composable(Routes.ARTIST, idArg) {
            ArtistDetailScreen(it.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onOpenAlbum = { id -> nav.navigate(Routes.album(id)) })
        }
        composable(Routes.COLLECTION, idArg) { CollectionDetailScreen(it.arguments!!.getLong("id")) { nav.popBackStack() } }
        composable(
            Routes.PLAYER,
            enterTransition = { slideInVertically(tween(220)) { it } },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(tween(100)) },
            popExitTransition = { slideOutVertically(tween(200)) { it } },
        ) { NowPlayingScreen(onClose = { nav.popBackStack() }) }
    }
}
