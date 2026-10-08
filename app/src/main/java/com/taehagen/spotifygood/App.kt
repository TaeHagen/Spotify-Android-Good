package com.taehagen.spotifygood

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.taehagen.spotifygood.engine.EngineHolder
import com.taehagen.spotifygood.engine.HolderType
import com.taehagen.spotifygood.engine.NativeStatus
import com.taehagen.spotifygood.engine.defaultDeviceName
import com.taehagen.spotifygood.nativebridge.NativeBridge
import com.taehagen.spotifygood.nativebridge.NativeCallbacksImpl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toOkioPath

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
 * registers the UI engine holder with ProcessLifecycleOwner, sets up Coil and the notification
 * channels. Runs on the main thread in `Application.onCreate`, so it only does cheap work; every
 * repository stays lazy (periodic download sync is scheduled by the download component itself).
 */
object AppInitializer {
    private const val TAG = "AppInitializer"

    /** Coil's disk cache directory under `cacheDir` (read by `ArtworkProvider` via the singleton loader). */
    const val IMAGE_CACHE_DIR = "coil_images"
    private const val IMAGE_DISK_CACHE_BYTES = 256L * 1024 * 1024
    private const val IMAGE_MEMORY_CACHE_PERCENT = 0.15

    fun initialize(app: App, graph: AppGraph) {
        createNotificationChannels(app)
        initNative(app, graph)
        setUpImageLoader(graph)
        registerUiHolder(graph)
    }

    private fun initNative(app: App, graph: AppGraph) {
        try {
            val config = buildJsonObject {
                put("filesDir", app.filesDir.absolutePath)
                put("cacheDir", app.cacheDir.absolutePath)
                put("noBackupDir", app.noBackupFilesDir.absolutePath)
                put("deviceId", graph.credentialStore.deviceId)
                put("deviceName", defaultDeviceName())
                put("logLevel", if (BuildConfig.DEBUG) "debug" else "info")
            }
            NativeBridge.nativeInit(NativeCallbacksImpl(graph.rpc, graph.events), graph.audioSink, config.toString())
            NativeStatus.initialized = true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError / a native panic surfaced as an
            // exception: keep the process alive, the engine reports the error state to the UI.
            NativeStatus.initError = t
            Log.e(TAG, "Native engine initialisation failed", t)
        }
    }

    private fun setUpImageLoader(graph: AppGraph) {
        // The factory runs lazily on first image request, so nothing here touches disk or network.
        SingletonImageLoader.setSafe(
            object : SingletonImageLoader.Factory {
                override fun newImageLoader(context: Context): ImageLoader =
                    ImageLoader.Builder(context)
                        .components {
                            add(OkHttpNetworkFetcherFactory(callFactory = { graph.httpClient }))
                            // Downloaded covers for the CDN URLs of downloads (offline lists). Resolved at
                            // the first request, off the main thread (the downloads stay lazy here).
                            add(Interceptor { chain -> graph.downloads.coverInterceptor.intercept(chain) })
                        }
                        .memoryCache { MemoryCache.Builder().maxSizePercent(context, IMAGE_MEMORY_CACHE_PERCENT).build() }
                        .diskCache {
                            DiskCache.Builder()
                                .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR).toOkioPath())
                                .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                                .build()
                        }
                        .crossfade(true)
                        .build()
            },
        )
    }

    private fun registerUiHolder(graph: AppGraph) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                // Main thread only.
                private var holder: EngineHolder? = null

                override fun onStart(owner: LifecycleOwner) {
                    if (holder == null) holder = graph.engine.acquire(HolderType.UI)
                }

                override fun onStop(owner: LifecycleOwner) {
                    holder?.release()
                    holder = null
                }
            },
        )
    }

    private fun createNotificationChannels(app: App) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                Notifications.CHANNEL_DOWNLOADS,
                app.getString(R.string.foundation_channel_downloads_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = app.getString(R.string.foundation_channel_downloads_description)
                setShowBadge(false)
            },
            NotificationChannel(
                Notifications.CHANNEL_CONNECT_PRESENCE,
                app.getString(R.string.foundation_channel_presence_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = app.getString(R.string.foundation_channel_presence_description)
                setShowBadge(false)
            },
            NotificationChannel(
                Notifications.CHANNEL_ALERTS,
                app.getString(R.string.foundation_channel_alerts_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = app.getString(R.string.foundation_channel_alerts_description)
            },
        )
        try {
            manager.createNotificationChannels(channels)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Creating notification channels failed", e)
        }
    }
}
