package com.controlx.router

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactCaller {
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

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$raw%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        ) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val numberIndex = it.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val matched = it.getString(numberIndex)
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(matched)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return true
        }
    }
}
