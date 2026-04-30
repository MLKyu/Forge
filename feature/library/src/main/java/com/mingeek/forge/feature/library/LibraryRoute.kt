package com.mingeek.forge.feature.library

import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.data.storage.BenchmarkStore
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.runtime.registry.BenchmarkRunner

const val LibraryRoute = "library"

fun NavGraphBuilder.libraryScreen(
    storage: ModelStorage,
    benchmarkStore: BenchmarkStore,
    benchmarkRunner: BenchmarkRunner,
    fitScorer: DeviceFitScorer,
    modifier: Modifier = Modifier,
) {
    composable(LibraryRoute) {
        val viewModel: LibraryViewModel = viewModel(
            factory = viewModelFactory {
                initializer { LibraryViewModel(storage, benchmarkStore, benchmarkRunner, fitScorer) }
            }
        )
        LibraryScreen(viewModel = viewModel, modifier = modifier)
    }
}
