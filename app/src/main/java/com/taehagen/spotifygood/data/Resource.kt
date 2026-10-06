package com.taehagen.spotifygood.data

/** Stale-while-revalidate result for screens. */
sealed interface Resource<out T> {
    /** Loading; [cached] is shown meanwhile if present. */
    data class Loading<T>(val cached: T? = null) : Resource<T>
    data class Success<T>(val data: T, val fromCache: Boolean = false) : Resource<T>
    /** Failed; [cached] may still be displayed (e.g. offline). */
    data class Error<T>(val error: Throwable, val cached: T? = null) : Resource<T>
}

val <T> Resource<T>.dataOrNull: T?
    get() = when (this) {
        is Resource.Loading -> cached
        is Resource.Success -> data
        is Resource.Error -> cached
    }
