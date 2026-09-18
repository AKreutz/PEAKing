package com.example.peaking.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Explore : Destination("explore", "Explore", Icons.Filled.Explore)
    data object MyPeaks : Destination("my_peaks", "My Peaks", Icons.Filled.Terrain)
}

val bottomNavDestinations = listOf(Destination.Explore, Destination.MyPeaks)
