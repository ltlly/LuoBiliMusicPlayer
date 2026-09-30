package com.bilimusicplayer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.bilimusicplayer.ui.screens.*
import com.bilimusicplayer.ui.theme.BiliMusicPlayerTheme
import com.bilimusicplayer.ui.theme.ThemeManager
import com.bilimusicplayer.ui.theme.ThemeMode
import com.bilimusicplayer.ui.components.MiniPlayer
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onStop() {
        BiliMusicApplication.musicPlayerController.saveStateSync()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeManager = remember { ThemeManager(this) }
            val themeMode by themeManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDarkTheme = isSystemInDarkTheme()

            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDarkTheme
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            BiliMusicPlayerTheme(darkTheme = darkTheme) {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showNavigation = currentRoute != Screen.Login.route && currentRoute != Screen.Player.route
    val destinations = remember {
        listOf(
            Triple(Screen.Favorites.route, "收藏夹", Icons.Outlined.FavoriteBorder),
            Triple(Screen.Library.route, "音乐库", Icons.Outlined.LibraryMusic),
            Triple(Screen.Settings.route, "设置", Icons.Outlined.Settings)
        )
    }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && currentRoute != Screen.Login.route && activity?.intent?.getBooleanExtra("open_quality_upgrade", false) == true) {
            activity.intent.removeExtra("open_quality_upgrade")
            navController.navigate("quality_upgrade") { launchSingleTop = true }
        }
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
            androidx.compose.animation.AnimatedVisibility(
                visible = useRail && showNavigation,
                enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut()
            ) {
                NavigationRail(modifier = Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.weight(1f))
                    destinations.forEach { (route, label, icon) ->
                        NavigationRailItem(
                            selected = selected(route), onClick = { navigate(route) },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }, modifier = Modifier.padding(vertical = 8.dp)
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
                            MiniPlayer(onExpand = { navController.navigate(Screen.Player.route) { launchSingleTop = true } })
                            if (!useRail) {
                                NavigationBar(tonalElevation = 0.dp) {
                                    destinations.forEach { (route, label, icon) ->
                                        NavigationBarItem(
                                            selected = selected(route), onClick = { navigate(route) },
                                            icon = { Icon(icon, contentDescription = label) },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            }
                        }
                    }
                }
            ) { padding ->
                // Keep this NavHost in one composition position across folding and resizing.
                NavigationGraph(navController, Modifier.padding(padding))
            }
        }
    }
}

@Composable
fun NavigationGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = Screen.Login.route,
        modifier = modifier,
        enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
        popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) }
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    // Navigate to last opened folder or favorites list
                    scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        val lastFolder = BiliMusicApplication.instance.getLastFolder()
                        if (lastFolder != null) {
                            val (folderId, folderTitle) = lastFolder
                            navController.navigate(Screen.Favorites.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                            navController.navigate("favorite_content/$folderId/${Uri.encode(folderTitle)}")
                        } else {
                            navController.navigate(Screen.Favorites.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    }
                }
            )
        }
        composable(Screen.Favorites.route) {
            FavoriteListScreen(navController)
        }
        composable(Screen.Library.route) {
            LibraryScreen(navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController)
        }
        composable(Screen.Player.route) {
            PlayerScreen(navController)
        }
        composable("favorite_content/{folderId}/{folderTitle}") { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId")?.toLongOrNull() ?: 0L
            val folderTitle = backStackEntry.arguments?.getString("folderTitle") ?: ""
            FavoriteContentScreen(navController, folderId, folderTitle)
        }
        composable("download_settings") {
            DownloadSettingsScreen(navController)
        }
        composable("quality_upgrade") { QualityUpgradeScreen(navController) }
        composable("appearance_settings") {
            AppearanceSettingsScreen(navController)
        }
    }
}

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Favorites : Screen("favorites")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object Player : Screen("player")
}
