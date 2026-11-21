package com.raza.vid2doc

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Starts the bundled yt-dlp once per app launch and keeps it up to date. */
object Engine {
    private val mutex = Mutex()
    private var ready = false

    suspend fun ready(ctx: Context) = mutex.withLock {
        if (ready) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            // YouTube changes often; grab the newest yt-dlp. Ignore failures (offline etc.)
            runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(
                    ctx.applicationContext,
                    YoutubeDL.UpdateChannel.STABLE
                )
            }
        }
        ready = true
    }
}
