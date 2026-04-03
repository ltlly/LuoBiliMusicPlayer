package com.bilimusicplayer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    Scaffold(
        bottomBar = {
            Column {
                // Mini player (shown on all screens except login and player)
                if (currentRoute != Screen.Login.route && currentRoute != Screen.Player.route) {
                    MiniPlayer(
                        onExpand = {
                            navController.navigate(Screen.Player.route)
                        }
                    )
                }

                // Navigation bar (no Home tab)
                if (currentRoute != Screen.Login.route) {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Favorite, contentDescription = "收藏夹") },
                            label = { Text("收藏夹") },
                            selected = currentRoute == Screen.Favorites.route
                                    || currentRoute?.startsWith("favorite_content") == true,
                            onClick = {
                                navController.navigate(Screen.Favorites.route) {
                                    popUpTo(Screen.Favorites.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "音乐库") },
                            label = { Text("音乐库") },
                            selected = currentRoute == Screen.Library.route,
                            onClick = {
                                navController.navigate(Screen.Library.route) {
                                    launchSingleTop = true
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                            label = { Text("设置") },
                            selected = currentRoute == Screen.Settings.route,
                            onClick = {
                                navController.navigate(Screen.Settings.route) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavigationGraph(
            navController = navController,
            modifier = Modifier.padding(paddingValues)
        )
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
        modifier = modifier
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    // Navigate to last opened folder or favorites list
                    scope.launch {
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
