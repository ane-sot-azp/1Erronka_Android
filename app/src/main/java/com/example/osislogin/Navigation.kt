package com.example.osislogin

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.ui.HomeScreen
import com.example.osislogin.ui.HomeViewModel
import com.example.osislogin.ui.LoginScreen
import com.example.osislogin.ui.LoginViewModel
import com.example.osislogin.util.SessionManager

sealed class Route(val route: String) {
    object Login : Route("login")
    object Home : Route("home")
}

@Composable
fun AppNavigation(database: AppDatabase, sessionManager: SessionManager, startDestination: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Route.Login.route) {
            val viewModel = remember { LoginViewModel(database, sessionManager) }
            LoginScreen(
                    viewModel = viewModel,
                    onLoginSuccess = {
                        navController.navigate(Route.Home.route) {
                            popUpTo(Route.Login.route) { inclusive = true }
                        }
                    }
            )
        }

        composable(Route.Home.route) {
            val viewModel = remember { HomeViewModel(sessionManager) }
            HomeScreen(
                    viewModel = viewModel,
                    onLogout = {
                        navController.navigate(Route.Login.route) {
                            popUpTo(Route.Home.route) { inclusive = true }
                        }
                    },
                    onTableClick = { _ -> }
            )
        }
    }
}
