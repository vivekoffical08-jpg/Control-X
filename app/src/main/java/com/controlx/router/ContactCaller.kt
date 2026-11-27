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

    fun dial(context: Context, query: String): Boolean {
        val raw = query.trim()
        if (raw.isEmpty()) return false

        val number = raw.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 6 }
        if (number != null) {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return true
        }

        if (!hasContactsPermission(context)) return false
        if (ContactCatalogManager.snapshot().isEmpty()) {
            ContactCatalogManager.refresh(context)
        }
        val contact = ContactCatalogManager.findUnique(raw) ?: return false
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.phoneNumber)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
