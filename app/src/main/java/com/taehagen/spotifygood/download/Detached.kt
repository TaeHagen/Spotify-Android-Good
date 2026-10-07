package com.taehagen.spotifygood.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Runs [block] as a child of this (long-lived, supervisor) scope and waits for it. A caller that is
 * cancelled meanwhile (a page popped) only stops waiting: the write itself runs to the end, so it
 * cannot stop between its database commit and the steps that must follow it (files, the native
 * offline index, scheduling). Its result or failure still reaches a caller that waits.
 */
internal suspend fun <T> CoroutineScope.detached(block: suspend CoroutineScope.() -> T): T = async(block = block).await()
