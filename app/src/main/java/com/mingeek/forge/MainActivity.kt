package com.mingeek.forge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mingeek.forge.core.ui.components.AutoShrinkText
import com.mingeek.forge.feature.settings.SettingsRoute
import com.mingeek.forge.navigation.TopLevelDestination
import com.mingeek.forge.navigation.ForgeNavHost
import com.mingeek.forge.ui.theme.ForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ForgeTheme {
                ForgeApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeApp() {
    val app = LocalContext.current.applicationContext as ForgeApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isSettings = currentRoute == SettingsRoute

    val activeDest = TopLevelDestination.entries.firstOrNull { dest ->
        dest.route == currentRoute || currentRoute?.startsWith("${dest.route}?") == true
    }
    val title = when {
        isSettings -> stringResource(R.string.title_settings)
        activeDest != null -> stringResource(activeDest.labelRes)
        else -> stringResource(R.string.app_name)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (isSettings) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
                actions = {
                    if (!isSettings) {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute) {
                                launchSingleTop = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!isSettings) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        val selected = backStackEntry?.destination
                            ?.hierarchy
                            ?.any { route ->
                                route.route == dest.route ||
                                    route.route?.startsWith("${dest.route}?") == true
                            } == true
                        val label = stringResource(dest.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                val onTab = currentRoute == dest.route ||
                                    currentRoute?.startsWith("${dest.route}?") == true
                                if (!onTab) {
                                    // If the tab destination is already on the back stack,
                                    // pop straight to it (preserving its state). This fixes
                                    // the case where a child screen pushed by another tab
                                    // (e.g. Discover -> Catalog?modelId=…) wouldn't return
                                    // to Discover via the BNB tap because navigate(start)
                                    // + launchSingleTop short-circuits.
                                    val popped = navController.popBackStack(
                                        route = dest.route,
                                        inclusive = false,
                                        saveState = true,
                                    )
                                    if (!popped) {
                                        navController.navigate(dest.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = label) },
                            label = { AutoShrinkText(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        ForgeNavHost(
            navController = navController,
            container = app.container,
            paddingValues = padding,
        )
    }
}
