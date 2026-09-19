package com.akreutz.peaking.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.akreutz.peaking.R
import com.akreutz.peaking.ui.explore.ExploreScreen
import com.akreutz.peaking.ui.hike.HikeScreen
import com.akreutz.peaking.ui.mypeaks.MyPeaksScreen
import com.akreutz.peaking.ui.navigation.Destination
import com.akreutz.peaking.ui.navigation.bottomNavDestinations

@Composable
fun PeakingNavHost() {
    val navController = rememberNavController()
    var hikeInProgress by remember { mutableStateOf(false) }
    var discardRequested by remember { mutableStateOf(false) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route

            NavigationBar {
                bottomNavDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            if (destination.route == currentRoute) return@NavigationBarItem
                            if (currentRoute == Destination.Hike.route && hikeInProgress) {
                                pendingRoute = destination.route
                            } else {
                                navigateTo(destination.route)
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Hike.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Hike.route) {
                HikeScreen(
                    onHikeInProgressChanged = { hikeInProgress = it },
                    discardRequested = discardRequested,
                    onDiscardHandled = { discardRequested = false }
                )
            }
            composable(Destination.Explore.route) { ExploreScreen() }
            composable(Destination.MyPeaks.route) { MyPeaksScreen() }
        }
    }

    val routeToDiscard = pendingRoute
    if (routeToDiscard != null) {
        AlertDialog(
            onDismissRequest = { pendingRoute = null },
            title = { Text(text = stringResource(R.string.hike_discard_dialog_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardRequested = true
                        pendingRoute = null
                        navigateTo(routeToDiscard)
                    }
                ) {
                    Text(text = stringResource(R.string.hike_discard_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRoute = null }) {
                    Text(text = stringResource(R.string.hike_discard_dialog_cancel))
                }
            }
        )
    }
}
