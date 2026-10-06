package com.taehagen.spotifygood.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.AppGraph

/** `viewModel()` with access to the [AppGraph] (manual DI). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = (LocalContext.current.applicationContext as App).graph
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}
