package com.taehagen.spotifygood.playback

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat

/**
 * Audio focus for local playback (docs/ARCHITECTURE.md §9.4), on the main thread:
 * * `LOSS` → pause, no automatic resume;
 * * `LOSS_TRANSIENT` → pause, resume on `GAIN` if it comes within [RESUME_WINDOW_MS];
 * * `LOSS_TRANSIENT_CAN_DUCK` → duck to [DUCK_VOLUME] (pause + resume for speech content);
 * * a failed request → pause; a delayed request (phone call) → pause and resume on `GAIN`.
 *
 * `willPauseWhenDucked` is set so the system delivers duck events to us instead of ducking
 * automatically; ducking is applied to our AudioTrack gain only, never to the system volume.
 *
 * The state machine itself is [AudioFocusState] (platform-free, unit tested); this class only
 * binds it to [AudioManager].
 */
internal class AudioFocusController(context: Context, callbacks: Callbacks) {

    interface Callbacks {
        fun pauseForFocus()
        fun resumeAfterFocus()
        fun setDuck(ducked: Boolean)
        /** Whether the current content is speech (podcast): pause instead of ducking. */
        fun isSpeech(): Boolean
        /** Whether audio is currently playing (or about to) on this phone. */
        fun isPlayingLocally(): Boolean
    }

    private val audioManager = AudioManagerCompat.getAudioManager(context)
    private val main = Handler(Looper.getMainLooper())

    private val request: AudioFocusRequestCompat = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener({ change -> state.onFocusChange(change) }, main)
        .build()

    private val state: AudioFocusState = AudioFocusState(
        callbacks = callbacks,
        platform = object : AudioFocusState.Platform {
            override fun requestFocus(): Int = try {
                AudioManagerCompat.requestAudioFocus(audioManager, request)
            } catch (e: RuntimeException) {
                Log.w(TAG, "requestAudioFocus failed", e)
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }

            override fun abandonFocus() {
                runCatching { AudioManagerCompat.abandonAudioFocusRequest(audioManager, request) }
            }

            override fun now(): Long = SystemClock.elapsedRealtime()
        },
    )

    /** True while we are registered in the focus stack (even if temporarily lost). */
    val isRequested: Boolean get() = state.isRequested

    /** True while we own focus (ducked included: a duck is not a loss of focus). */
    val hasFocus: Boolean get() = state.hasFocus

    /**
     * Requests focus for local playback that just started. Returns false if playback must not
     * continue right now (the caller has been asked to pause through [Callbacks.pauseForFocus]).
     */
    fun request(): Boolean = state.request()

    /** Leaves the focus stack (stop, long pause, remote playback, engine stop). */
    fun abandon() = state.abandon()

    /** True while a transient loss is pending a resume (keep focus requested meanwhile). */
    val isWaitingForGain: Boolean get() = state.isWaitingForGain

    /** The output went away (becoming noisy): a later GAIN must not resume on another one. */
    fun cancelPendingResume() = state.cancelPendingResume()

    companion object {
        private const val TAG = "AudioFocus"
        const val DUCK_VOLUME = 0.2f
        /** Transient losses longer than this do not auto-resume; focus is abandoned after it as well. */
        const val RESUME_WINDOW_MS = AudioFocusState.RESUME_WINDOW_MS
    }
}

/**
 * Platform-free audio focus state machine behind [AudioFocusController] (main thread only).
 *
 * A duck (`LOSS_TRANSIENT_CAN_DUCK` for music) keeps [hasFocus]: we are still in the focus stack
 * and only lowered our gain, so a sink restart during the duck (skip, pause/resume, watchdog)
 * must not re-request focus — that would steal it from the ducking app (cutting a navigation
 * prompt) and no `GAIN` would ever arrive to restore our gain. If a request is granted anyway
 * (we were not holding focus), we are at the top of the stack and can never be ducked there,
 * so the duck is cleared explicitly.
 */
internal class AudioFocusState(
    private val callbacks: AudioFocusController.Callbacks,
    private val platform: Platform,
) {
    interface Platform {
        /** `AudioManager.AUDIOFOCUS_REQUEST_*`. */
        fun requestFocus(): Int
        fun abandonFocus()
        /** Monotonic clock in ms. */
        fun now(): Long
    }

    var isRequested: Boolean = false
        private set
    var hasFocus: Boolean = false
        private set
    var isDucked: Boolean = false
        private set
    private var resumeOnGainUntil = 0L

    val isWaitingForGain: Boolean get() = resumeOnGainUntil > platform.now()

    fun request(): Boolean {
        if (hasFocus) return true
        return when (platform.requestFocus()) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                isRequested = true
                hasFocus = true
                resumeOnGainUntil = 0L
                // Granted = top of the focus stack: no GAIN callback follows, so un-duck ourselves.
                setDucked(false)
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // E.g. during a phone call: wait for GAIN before making noise.
                isRequested = true
                hasFocus = false
                resumeOnGainUntil = platform.now() + RESUME_WINDOW_MS
                callbacks.pauseForFocus()
                false
            }
            else -> {
                isRequested = false
                hasFocus = false
                callbacks.pauseForFocus()
                false
            }
        }
    }

    /** Drops the auto-resume of a transient loss (the output went away meanwhile). */
    fun cancelPendingResume() {
        resumeOnGainUntil = 0L
    }

    fun abandon() {
        if (!isRequested && !hasFocus && !isDucked) return
        if (isRequested || hasFocus) platform.abandonFocus()
        isRequested = false
        hasFocus = false
        resumeOnGainUntil = 0L
        setDucked(false)
    }

    fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                isRequested = true
                setDucked(false)
                val resume = resumeOnGainUntil > platform.now()
                resumeOnGainUntil = 0L
                if (resume) callbacks.resumeAfterFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another media app took over: pause (Connect sees it) and never auto-resume.
                resumeOnGainUntil = 0L
                callbacks.pauseForFocus()
                abandon()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (callbacks.isSpeech()) {
                    transientLoss()
                } else {
                    // Still our focus (see the class comment); only the gain is lowered.
                    setDucked(true)
                }
            }
        }
    }

    private fun transientLoss() {
        hasFocus = false
        // Only auto-resume what we interrupted, never something the user had paused.
        if (!callbacks.isPlayingLocally()) return
        resumeOnGainUntil = platform.now() + RESUME_WINDOW_MS
        callbacks.pauseForFocus()
    }

    private fun setDucked(ducked: Boolean) {
        if (isDucked == ducked) return
        isDucked = ducked
        callbacks.setDuck(ducked)
    }

    companion object {
        const val RESUME_WINDOW_MS = 10 * 60_000L
    }
}
