package com.controlx.router

import android.content.Context
import android.content.Intent
import android.net.Uri

object MediaPlaybackController {
    fun play(context: Context, query: String): Boolean {
        val cleaned = query
            .replace(Regex("""(?i)\bon\s+(youtube|spotify)\b"""), "")
            .trim()
        if (cleaned.isEmpty()) return false

        val youtube = Intent(
            Intent.ACTION_SEARCH,
            Uri.parse("vnd.youtube://search_query=${Uri.encode(cleaned)}"),
        ).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val spotify = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("spotify:search:${Uri.encode(cleaned)}"),
        ).apply {
            setPackage("com.spotify.music")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val browser = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleaned)}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
}
