package com.controlx.router

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import com.controlx.domain.FastPathResult
import com.controlx.platform.AppCatalogManager

object AppActionDispatcher {
    fun whatsappMessage(
        context: Context,
        recipientQuery: String?,
        messageText: String?,
    ): FastPathResult {
        val text = messageText?.trim().orEmpty()
        val recipient = recipientQuery?.trim().orEmpty()
        if (recipient.isNotEmpty()) {
            val number = ContactCaller.resolveNumber(context, recipient)
            if (number == null) {
                val isPhone = recipient.filter { it.isDigit() || it == '+' }.length >= 6
                if (!isPhone && !ContactCaller.hasContactsPermission(context)) {
                    return FastPathResult.Error(
                        "Grant Contacts permission to message a contact on WhatsApp by name.",
                    )
                }
            } else {
                val digits = number.filter { it.isDigit() }
                if (digits.length >= 10) {
                    val uri = Uri.parse(
                        "https://api.whatsapp.com/send?phone=$digits" +
                            if (text.isNotEmpty()) "&text=${Uri.encode(text)}" else "",
                    )
                    val directIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (tryStartActivity(context, directIntent)) {
                        return FastPathResult.Executed("Opened WhatsApp chat for $recipient.", 0)
                    }
                }
            }
        }

        if (text.isNotEmpty()) {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tryStartActivity(context, sendIntent)) {
                    return FastPathResult.Executed("Opened WhatsApp with message draft.", 0)
                }
            }
        }

        if (AppLauncher.launch(context, "com.whatsapp")) {
            return FastPathResult.Executed("Opened WhatsApp.", 0)
        }
        return FastPathResult.Error("WhatsApp is not installed on this device.")
    }

    fun searchInApp(
        context: Context,
        appQuery: String,
        searchQuery: String,
    ): FastPathResult {
        val query = searchQuery.trim()
        val appTarget = appQuery.trim()
        if (query.isEmpty()) return FastPathResult.Error("Specify what to search for.")
        if (appTarget.isEmpty()) return FastPathResult.Error("Specify which app to search in.")

        if (AppCatalogManager.snapshot().isEmpty()) {
            AppCatalogManager.refresh(context)
        }
        val matched = AppCatalogManager.findLaunchable(appTarget)
        val pkg = matched?.packageName ?: appTarget.takeIf { it.contains('.') }
        val displayLabel = matched?.label ?: appTarget

        when {
            pkg == "com.spotify.music" || appTarget.equals("spotify", ignoreCase = true) -> {
                val spotify = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("spotify:search:${Uri.encode(query)}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tryStartActivity(context, spotify)) {
                    return FastPathResult.Executed("Searched '$query' in Spotify.", 0)
                }
            }
            pkg == "com.google.android.youtube" || appTarget.equals("youtube", ignoreCase = true) -> {
                val yt = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tryStartActivity(context, yt)) {
                    return FastPathResult.Executed("Searched '$query' in YouTube.", 0)
                }
            }
            pkg == "com.google.android.apps.maps" ||
                appTarget.equals("maps", ignoreCase = true) ||
                appTarget.equals("google maps", ignoreCase = true) -> {
                return mapsAction(context, query, navigate = false)
            }
            pkg == "com.android.vending" || appTarget.equals("play store", ignoreCase = true) -> {
                val store = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=${Uri.encode(query)}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tryStartActivity(context, store)) {
                    return FastPathResult.Executed("Searched '$query' in Play Store.", 0)
                }
            }
            pkg == "com.android.chrome" || appTarget.equals("chrome", ignoreCase = true) -> {
                val browser = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"),
                ).apply {
                    if (pkg != null) setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tryStartActivity(context, browser)) {
                    return FastPathResult.Executed("Searched '$query' in Chrome.", 0)
                }
            }
        }

        if (pkg != null) {
            val inAppSearch = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(pkg)
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(context, inAppSearch)) {
                return FastPathResult.Executed("Searched '$query' inside $displayLabel.", 0)
            }
            if (AppLauncher.launch(context, pkg)) {
                return FastPathResult.Executed(
                    "Opened $displayLabel. Enable optional Screen Automation to tap search inside this app.",
                    0,
                )
            }
        }
        return FastPathResult.Error("Could not find installed app '$appTarget'.")
    }

    fun mapsAction(context: Context, query: String, navigate: Boolean): FastPathResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return FastPathResult.Error("Specify a destination or place.")
        val uri = if (navigate) {
            Uri.parse("google.navigation:q=${Uri.encode(trimmed)}")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(trimmed)}")
        }
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, mapsIntent)) {
            val actionVerb = if (navigate) "navigation" else "Maps search"
            return FastPathResult.Executed("Opened $actionVerb for '$trimmed'.", 0)
        }
        val fallbackUrl = if (navigate) {
            "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(trimmed)}"
        } else {
            "https://www.google.com/maps/search/?api=1&query=${Uri.encode(trimmed)}"
        }
        val browserFallback = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (tryStartActivity(context, browserFallback)) {
            FastPathResult.Executed("Opened Maps in browser for '$trimmed'.", 0)
        } else {
            FastPathResult.Error("No maps or browser app available.")
        }
    }

    fun draftEmail(
        context: Context,
        recipientEmail: String?,
        subject: String?,
        body: String?,
    ): FastPathResult {
        val recipient = recipientEmail?.trim().orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(recipient)}")).apply {
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject.trim())
            if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body.trim())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (tryStartActivity(context, intent)) {
            FastPathResult.Executed("Opened email draft.", 0)
        } else {
            FastPathResult.Error("No email client found on this device.")
        }
    }

    fun openCamera(context: Context, video: Boolean): FastPathResult {
        val action = if (video) {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA
        } else {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (tryStartActivity(context, intent)) {
            FastPathResult.Executed(if (video) "Video camera opened." else "Camera opened.", 0)
        } else {
            FastPathResult.Error("Unable to open device camera.")
        }
    }

    fun createCalendarEvent(
        context: Context,
        title: String?,
        description: String?,
    ): FastPathResult {
        val eventTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: return FastPathResult.Error("Specify a calendar event title.")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, eventTitle)
            if (!description.isNullOrBlank()) {
                putExtra(CalendarContract.Events.DESCRIPTION, description.trim())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (tryStartActivity(context, intent)) {
            FastPathResult.Executed("Opened calendar event draft '$eventTitle'.", 0)
        } else {
            FastPathResult.Error("No calendar app available on this device.")
        }
    }

    private fun tryStartActivity(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
