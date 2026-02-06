package com.bilimusicplayer

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
import com.bilimusicplayer.ui.components.MiniPlayer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BiliMusicPlayerTheme {
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

                // Navigation bar
                if (currentRoute != Screen.Login.route) {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                            label = { Text("首页") },
                            selected = currentRoute == Screen.Home.route,
                            onClick = {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Home.route) { inclusive = true }
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Favorite, contentDescription = "收藏夹") },
                            label = { Text("收藏夹") },
                            selected = currentRoute == Screen.Favorites.route,
                            onClick = {
                                navController.navigate(Screen.Favorites.route) {
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
    NavHost(
        navController = navController,
        startDestination = Screen.Login.route,
        modifier = modifier
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(navController)
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
    }
}

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Favorites : Screen("favorites")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object Player : Screen("player")
}
