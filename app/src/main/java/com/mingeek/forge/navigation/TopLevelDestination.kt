package com.mingeek.forge.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.mingeek.forge.R
import com.mingeek.forge.feature.agents.AgentsRoute
import com.mingeek.forge.feature.catalog.CatalogRoute
import com.mingeek.forge.feature.chat.ChatRoute
import com.mingeek.forge.feature.compare.CompareRoute
import com.mingeek.forge.feature.discover.DiscoverRoute
import com.mingeek.forge.feature.library.LibraryRoute

enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    DISCOVER(DiscoverRoute, R.string.nav_discover, Icons.Filled.Explore),
    CATALOG(CatalogRoute, R.string.nav_catalog, Icons.Filled.Search),
    LIBRARY(LibraryRoute, R.string.nav_library, Icons.Filled.Folder),
    CHAT(ChatRoute, R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    COMPARE(CompareRoute, R.string.nav_compare, Icons.AutoMirrored.Filled.CompareArrows),
    AGENTS(AgentsRoute, R.string.nav_agents, Icons.Filled.Apps),
}
