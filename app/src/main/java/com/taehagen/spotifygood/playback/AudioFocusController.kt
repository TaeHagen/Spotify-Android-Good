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
 */
internal class AudioFocusController(context: Context, private val callbacks: Callbacks) {

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

    private val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(::onFocusChange, main)
        .build()

    /** True while we are registered in the focus stack (even if temporarily lost). */
    var isRequested: Boolean = false
        private set

    /** True while we actually own focus. */
    var hasFocus: Boolean = false
        private set

    private var ducked = false
    private var resumeOnGainUntil = 0L

    /**
     * Requests focus for local playback that just started. Returns false if playback must not
     * continue right now (the caller has been asked to pause through [Callbacks.pauseForFocus]).
     */
    fun request(): Boolean {
        if (hasFocus) return true
        val result = try {
            AudioManagerCompat.requestAudioFocus(audioManager, request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "requestAudioFocus failed", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                isRequested = true
                hasFocus = true
                resumeOnGainUntil = 0L
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // E.g. during a phone call: wait for GAIN before making noise.
                isRequested = true
                hasFocus = false
                resumeOnGainUntil = SystemClock.elapsedRealtime() + RESUME_WINDOW_MS
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

    /** Leaves the focus stack (stop, long pause, remote playback, engine stop). */
    fun abandon() {
        if (!isRequested && !hasFocus) return
        runCatching { AudioManagerCompat.abandonAudioFocusRequest(audioManager, request) }
        isRequested = false
        hasFocus = false
        resumeOnGainUntil = 0L
        if (ducked) {
            ducked = false
            callbacks.setDuck(false)
        }
    }

    /** True while a transient loss is pending a resume (keep focus requested meanwhile). */
    val isWaitingForGain: Boolean get() = resumeOnGainUntil > SystemClock.elapsedRealtime()

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                isRequested = true
                if (ducked) {
                    ducked = false
                    callbacks.setDuck(false)
                }
                if (resumeOnGainUntil > SystemClock.elapsedRealtime()) {
                    resumeOnGainUntil = 0L
                    callbacks.resumeAfterFocus()
                }
                resumeOnGainUntil = 0L
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
                    hasFocus = false
                    ducked = true
                    callbacks.setDuck(true)
                }
            }
        }
    }

    private fun transientLoss() {
        hasFocus = false
        // Only auto-resume what we interrupted, never something the user had paused.
        if (!callbacks.isPlayingLocally()) return
        resumeOnGainUntil = SystemClock.elapsedRealtime() + RESUME_WINDOW_MS
        callbacks.pauseForFocus()
    }

    companion object {
        private const val TAG = "AudioFocus"
        const val DUCK_VOLUME = 0.2f
        /** Transient losses longer than this do not auto-resume; focus is abandoned after it as well. */
        const val RESUME_WINDOW_MS = 10 * 60_000L
    }
}
