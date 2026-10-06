package com.taehagen.spotifygood.nativebridge

object NativeBridge {
    init { System.loadLibrary("spotcore") }
    external fun nativeVersion(): String
}
