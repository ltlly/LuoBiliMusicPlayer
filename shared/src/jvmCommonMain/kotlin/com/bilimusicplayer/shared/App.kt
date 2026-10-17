package com.bilimusicplayer.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import com.bilimusicplayer.core.AppContainer
import com.bilimusicplayer.core.LocalAppContainer
import com.bilimusicplayer.settings.ThemeMode
import com.bilimusicplayer.ui.components.MiniPlayer
import com.bilimusicplayer.ui.screens.*
import com.bilimusicplayer.ui.theme.BiliMusicPlayerTheme
import com.bilimusicplayer.utils.decodeRouteArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun App(container: AppContainer) {
    val themeMode by container.storage.themeMode.collectAsState()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    CompositionLocalProvider(LocalAppContainer provides container) {
        BiliMusicPlayerTheme(darkTheme = darkTheme) {
            MainScreen()
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val container = LocalAppContainer.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showNavigation = currentRoute != Screen.Login.route && currentRoute != Screen.Player.route
    val destinations = remember {
        listOf(
            Triple(Screen.Favorites.route, "收藏夹", Icons.Outlined.FavoriteBorder),
            Triple(Screen.Library.route, "音乐库", Icons.Outlined.LibraryMusic),
            Triple(Screen.Settings.route, "设置", Icons.Outlined.Settings),
        )
    }

    fun selected(route: String) = currentRoute == route ||
        (route == Screen.Favorites.route && currentRoute?.startsWith("favorite_content") == true) ||
        (route == Screen.Settings.route && (currentRoute?.endsWith("_settings") == true || currentRoute == "quality_upgrade"))

    fun navigate(route: String) {
        navController.navigate(route) {
            popUpTo(Screen.Favorites.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = useRail && showNavigation,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Spacer(Modifier.weight(1f))
                    destinations.forEach { (route, label, icon) ->
                        NavigationRailItem(
                            selected = selected(route),
                            onClick = { navigate(route) },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (showNavigation) {
                        Column {
                            MiniPlayer(
                                onExpand = {
                                    navController.navigate(Screen.Player.route) { launchSingleTop = true }
                                },
                            )
                            if (!useRail) {
                                NavigationBar(tonalElevation = 0.dp) {
                                    destinations.forEach { (route, label, icon) ->
                                        NavigationBarItem(
                                            selected = selected(route),
                                            onClick = { navigate(route) },
                                            icon = { Icon(icon, contentDescription = label) },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
                            }
                        }
                    }
                },
            ) { padding ->
                NavigationGraph(navController, Modifier.padding(padding))
            }
        }
    }

    // Android can hand a pending route to the shared graph; desktop leaves it null.
    LaunchedEffect(Unit) {
        container.pendingRoute?.let { route ->
            container.pendingRoute = null
            navController.navigate(route) { launchSingleTop = true }
        }
    }
}

@Composable
fun NavigationGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = Screen.Login.route,
        modifier = modifier,
        enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
        popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) },
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    val lastFolder = container.storage.lastFolder()
                    if (lastFolder != null) {
                        val (folderId, folderTitle) = lastFolder
                        navController.navigate(Screen.Favorites.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                        navController.navigate("favorite_content/$folderId/${com.bilimusicplayer.utils.encodeRouteArg(folderTitle)}")
                    } else {
                        navController.navigate(Screen.Favorites.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(Screen.Favorites.route) { FavoriteListScreen(navController) }
        composable(Screen.Library.route) { LibraryScreen(navController) }
        composable(Screen.Settings.route) { SettingsScreen(navController) }
        composable(Screen.Player.route) { PlayerScreen(navController) }
        composable("favorite_content/{folderId}/{folderTitle}") { backStackEntry ->
            val args = backStackEntry.arguments
            val folderId = args?.read { getString("folderId") }?.toLongOrNull() ?: 0L
            val folderTitle = decodeRouteArg(args?.read { getString("folderTitle") } ?: "")
            FavoriteContentScreen(navController, folderId, folderTitle)
        }
        composable("download_settings") { DownloadSettingsScreen(navController) }
        composable("quality_upgrade") { QualityUpgradeScreen(navController) }
        composable("appearance_settings") { AppearanceSettingsScreen(navController) }
    }
}

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Favorites : Screen("favorites")
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object Player : Screen("player")
}