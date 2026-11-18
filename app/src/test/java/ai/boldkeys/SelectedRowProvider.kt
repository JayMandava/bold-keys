package ai.boldkeys

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.robolectric.shadows.ShadowContentResolver

/** A single record provider: any attempt to enumerate contacts fails the test. */
internal fun selectedRow(expected: Uri, row: Cursor) {
    ShadowContentResolver.registerProviderInternal("com.android.contacts", object : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            assertEquals(expected, uri)
            return row
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
    })
}
