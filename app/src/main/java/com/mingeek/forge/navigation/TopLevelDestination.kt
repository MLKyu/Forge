package com.mingeek.forge.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.mingeek.forge.feature.agents.AgentsRoute
import com.mingeek.forge.feature.catalog.CatalogRoute
import com.mingeek.forge.feature.chat.ChatRoute
import com.mingeek.forge.feature.compare.CompareRoute
import com.mingeek.forge.feature.discover.DiscoverRoute
import com.mingeek.forge.feature.library.LibraryRoute

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DISCOVER(DiscoverRoute, "Discover", Icons.Filled.Explore),
    CATALOG(CatalogRoute, "Catalog", Icons.Filled.Search),
    LIBRARY(LibraryRoute, "Library", Icons.Filled.Folder),
    CHAT(ChatRoute, "Chat", Icons.AutoMirrored.Filled.Chat),
    COMPARE(CompareRoute, "Compare", Icons.AutoMirrored.Filled.CompareArrows),
    AGENTS(AgentsRoute, "Agents", Icons.Filled.Apps),
}
