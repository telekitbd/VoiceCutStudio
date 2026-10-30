package com.voicecut.studio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Plays a short part of the imported audio/video so a voice can be checked by ear. */
class ClipPlayer {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }

    /** Which clip is playing right now (so its button can show "stop"). */
    var playingKey by mutableStateOf<String?>(null)
        private set

    fun toggle(ctx: Context, uri: Uri, startMs: Long, endMs: Long, key: String) {
        if (playingKey == key) { stop(); return }
        stop()
        val length = (endMs - startMs).coerceIn(1500L, 10_000L)
        val mp = MediaPlayer()
        player = mp
        playingKey = key
        try {
            mp.setDataSource(ctx.applicationContext, uri)
            mp.setOnPreparedListener { p ->
                p.setOnSeekCompleteListener { it.start(); handler.postDelayed(stopper, length) }
                p.seekTo(startMs.toInt())
            }
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(stopper)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playingKey = null
    }
}
