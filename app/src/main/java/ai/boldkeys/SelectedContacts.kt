package ai.boldkeys

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils

/** Picker grants are temporary. Only the selected row is read; no grant is persisted. */
internal object SelectedContacts {
    fun picker(call: Boolean) = Intent(Intent.ACTION_PICK).apply {
        type = if (call) ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE else ContactsContract.Contacts.CONTENT_TYPE
    }

    fun isLookupUri(uri: Uri): Boolean = uri.scheme == "content" &&
        uri.authority == ContactsContract.AUTHORITY && uri.pathSegments.take(2) == listOf("contacts", "lookup") &&
        uri.pathSegments.size >= 3 && uri.pathSegments[2].isNotBlank()

    fun read(c: Context, uri: Uri, call: Boolean): Shortcut? {
        if (uri.scheme != "content" || uri.authority != ContactsContract.AUTHORITY) return null
        val columns = if (call) arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            else arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME)
        return try {
            c.contentResolver.query(uri, columns, null, null, null)?.use { row ->
                if (!row.moveToFirst()) return null
                if (call) {
                    val number = DirectCalls.safeNumber(c, row.getString(0) ?: "") ?: return null
                    Shortcut(Shortcut.DIRECT_CALL, number, row.getString(1) ?: "Selected number")
                } else {
                    val key = row.getString(1)?.takeIf { it.isNotBlank() } ?: return null
                    val lookup = ContactsContract.Contacts.getLookupUri(row.getLong(0), key) ?: return null
                    Shortcut(Shortcut.CONTACT, lookup.toString(), row.getString(2) ?: "Selected contact")
                }
            }
        } catch (_: RuntimeException) { null }
    }
}

internal object DirectCalls {
    fun hasPermission(c: Context) = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    // Reject service codes, extensions, pause/wait, URI syntax and malformed international prefixes.
    fun normalize(raw: String): String? {
        if (raw.isBlank() || raw.any { it !in "0123456789+ -()." }) return null
        val n = raw.filter { it in "0123456789+" }
        if (!Regex("\\+?[0-9]{3,15}").matches(n)) return null
        // Always reject common emergency targets, even on a tablet without a telephony database.
        if (n.removePrefix("+") in setOf("911", "112", "999", "000", "110", "118", "119", "120", "122")) return null
        return n
    }

    @Suppress("DEPRECATION")
    fun safeNumber(c: Context, raw: String): String? {
        val n = normalize(raw) ?: return null
        return try { if (PhoneNumberUtils.isLocalEmergencyNumber(c, n)) null else n }
        catch (_: RuntimeException) { null } // Fail closed if the platform cannot check emergency routing.
    }
}

/** One call per held Speed gesture, plus a cross-gesture debounce for accessibility/double taps. */
internal class DirectCallGuard(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private var used = false
    private var last: Long? = null
    fun beginGesture() { used = false }
    fun acquire(): Boolean {
        val now = clock()
        if (used || last?.let { now - it < 3000 } == true) return false
        used = true
        last = now
        return true
    }
}
