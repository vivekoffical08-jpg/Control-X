package com.controlx.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

data class CatalogContact(
    val displayName: String,
    val phoneNumber: String,
)

object ContactCatalogManager {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var catalog: List<CatalogContact> = emptyList()

    fun snapshot(): List<CatalogContact> = catalog

    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        executor.execute { refresh(appContext) }
    }

    fun findUnique(query: String): CatalogContact? =
        LocalCatalogMatcher.uniqueBestMatch(
            query = query,
            candidates = catalog,
            label = CatalogContact::displayName,
        )

    @Synchronized
    fun refresh(context: Context): Boolean {
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_CONTACTS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            catalog = emptyList()
            return false
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val contacts: List<CatalogContact> = try {
            val cursor = appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            ) ?: run {
                catalog = emptyList()
                return false
            }
            cursor.use {
                val nameIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                )
                val numberIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                val result = mutableListOf<CatalogContact>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    val number = cursor.getString(numberIndex)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty()) {
                        result += CatalogContact(name, number)
                    }
                }
                result
            }
        } catch (_: SecurityException) {
            catalog = emptyList()
            return false
        }

        catalog = contacts.distinctBy {
            "${LocalCatalogMatcher.normalize(it.displayName)}|${it.phoneNumber}"
        }
        return true
    }
}
