package com.metarayban.glasses.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.metarayban.glasses.presentation.screens.connect.ConnectScreen
import com.metarayban.glasses.presentation.screens.gallery.GalleryScreen
import com.metarayban.glasses.presentation.screens.home.HomeScreen
import com.metarayban.glasses.presentation.screens.transfer.TransferScreen

object Routes {
    const val HOME = "home"
    const val CONNECT = "connect"
    const val TRANSFER = "transfer"
    const val GALLERY = "gallery"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToConnect = { navController.navigate(Routes.CONNECT) },
                onNavigateToTransfer = { navController.navigate(Routes.TRANSFER) },
                onNavigateToGallery = { navController.navigate(Routes.GALLERY) },
            )
        }
        composable(Routes.CONNECT) {
            ConnectScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TRANSFER) {
            TransferScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.GALLERY) {
            GalleryScreen(onBack = { navController.popBackStack() })
        }
    }
}
