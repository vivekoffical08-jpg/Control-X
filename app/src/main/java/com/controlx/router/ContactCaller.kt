package com.controlx.router

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.controlx.platform.ContactCatalogManager

object ContactCaller {
    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    fun resolveNumber(context: Context, query: String): String? {
        val raw = query.trim()
        if (raw.isEmpty()) return null

        val directNumber = raw.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 6 }
        if (directNumber != null) return directNumber

        if (!hasContactsPermission(context)) return null
        if (ContactCatalogManager.snapshot().isEmpty()) {
            ContactCatalogManager.refresh(context)
        }
        return ContactCatalogManager.findUnique(raw)?.phoneNumber
    }

    fun dial(context: Context, query: String): Boolean {
        val number = resolveNumber(context, query) ?: return false
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    fun draftSms(context: Context, recipientQuery: String, messageBody: String?): Boolean {
        val number = resolveNumber(context, recipientQuery) ?: return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            if (!messageBody.isNullOrBlank()) {
                putExtra("sms_body", messageBody.trim())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
