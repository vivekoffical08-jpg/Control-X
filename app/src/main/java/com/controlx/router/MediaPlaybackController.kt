package com.controlx.router

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager

object MediaPlaybackController {
    fun play(context: Context, query: String): Boolean {
        val cleaned = query
            .replace(Regex("""(?i)\bon\s+(youtube|spotify)\b"""), "")
            .trim()
        if (cleaned.isEmpty()) return false

        val youtube = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra(SearchManager.QUERY, cleaned)
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
