package com.mingeek.forge.feature.catalog

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.data.catalog.ModelCatalogSource
import com.mingeek.forge.data.download.DownloadQueue
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.runtime.registry.RuntimeRegistry

const val CatalogRoute = "catalog"
private const val ARG_MODEL_ID = "modelId"
private const val CATALOG_PATTERN = "$CatalogRoute?$ARG_MODEL_ID={$ARG_MODEL_ID}"

fun catalogRouteWithModelId(id: String): String =
    "$CatalogRoute?$ARG_MODEL_ID=${java.net.URLEncoder.encode(id, Charsets.UTF_8)}"

fun NavGraphBuilder.catalogScreen(
    catalogSource: ModelCatalogSource,
    downloadQueue: DownloadQueue,
    storage: ModelStorage,
    fitScorer: DeviceFitScorer,
    runtimeRegistry: RuntimeRegistry,
    modifier: Modifier = Modifier,
) {
    composable(
        route = CATALOG_PATTERN,
        arguments = listOf(
            navArgument(ARG_MODEL_ID) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        ),
    ) { entry ->
        val pendingModelId = entry.arguments?.getString(ARG_MODEL_ID)?.takeIf { it.isNotBlank() }

        val viewModel: CatalogViewModel = viewModel(
            factory = viewModelFactory {
                initializer {
                    CatalogViewModel(catalogSource, downloadQueue, storage, fitScorer, runtimeRegistry)
                }
            }
        )

        LaunchedEffect(pendingModelId) {
            if (pendingModelId != null) viewModel.openDetailsById(pendingModelId)
        }

        CatalogScreen(viewModel = viewModel, modifier = modifier)
    }
}
