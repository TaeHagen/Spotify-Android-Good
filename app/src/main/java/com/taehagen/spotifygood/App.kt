package com.taehagen.spotifygood

import android.app.Application

/** Application entry point. Owns the [AppGraph]; initialises the native engine runtime. */
class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        AppInitializer.initialize(this, graph)
    }
}

/**
 * One-time process initialisation (owned by the foundation): calls `NativeBridge.nativeInit`,
 * registers the UI engine holder with ProcessLifecycleOwner, sets up Coil, notification channels
 * and periodic download sync.
 */
object AppInitializer {
    fun initialize(app: App, graph: AppGraph): Unit = TODO()
}
