package com.controlx.router

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import android.provider.MediaStore

object MediaPlaybackController {
    fun play(context: Context, query: String): Boolean {
        val spotifyRequested = Regex("""(?i)\bon\s+spotify\b""").containsMatchIn(query)
        val youtubeRequested = Regex("""(?i)\bon\s+youtube\b""").containsMatchIn(query)
        val cleaned = query
            .replace(Regex("""(?i)\bon\s+(youtube|spotify)\b"""), "")
            .trim()
        if (cleaned.isEmpty()) return false

        if (spotifyRequested) {
            val spotify = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("spotify:search:${Uri.encode(cleaned)}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(spotify)
                return true
            } catch (_: Exception) {
                // Continue through the generic media and YouTube search fallbacks.
            }
        }

        val mediaSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, cleaned)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val youtube = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra(SearchManager.QUERY, cleaned)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val browser = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleaned)}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (youtubeRequested) {
            return try {
                context.startActivity(youtube)
                true
            } catch (_: Exception) {
                try {
                    context.startActivity(browser)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        return try {
            context.startActivity(mediaSearch)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(youtube)
                true
            } catch (_: Exception) {
                try {
                    context.startActivity(browser)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
}
