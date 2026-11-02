package com.jarvis.assistant.platform

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.jarvis.core.contacts.ContactMatcher
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ContactMatch
import com.jarvis.core.ports.ContactNumber
import com.jarvis.core.ports.ContactSearch
import com.jarvis.core.ports.ContactsGateway
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Looks people up in the device address book (READ_CONTACTS). Matching rules (case, accents, nicknames, near-misses, "exact wins") live in core.contacts.ContactMatcher,
 * which is unit-tested; this class only reads the rows. Contacts are searched on the phone and are never uploaded; the log records counts, not names.
 */
class AndroidContactsGateway(context: Context, private val permissions: PermissionChecker, private val log: JarvisLog = JarvisLog.None) : ContactsGateway {
    private val resolver = context.applicationContext.contentResolver
    private val res = context.applicationContext.resources

    private class Person(val id: String, var name: String, val numbers: MutableList<ContactNumber> = ArrayList())

    override suspend fun search(name: String, limit: Int): ContactSearch = withContext(Dispatchers.IO) {
        if (!permissions.isGranted(Requirement.CONTACTS)) return@withContext ContactSearch(emptyList(), listOf(Requirement.CONTACTS))
        if (name.isBlank()) return@withContext ContactSearch(emptyList())
        try {
            val people = LinkedHashMap<String, Person>()
            val cols = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, ContactsContract.Data.IS_SUPER_PRIMARY, ContactsContract.Data.IS_PRIMARY)
            resolver.query(Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows < 50_000) {
                    rows++
                    val id = c.getString(0) ?: continue
                    val display = c.getString(1) ?: continue
                    val number = c.getString(2)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    val label = try { Phone.getTypeLabel(res, c.getInt(3), c.getString(4)).toString() } catch (_: Exception) { "" }
                    val primary = c.getInt(5) != 0 || c.getInt(6) != 0
                    val p = people.getOrPut(id) { Person(id, display) }
                    val digits = number.filter { it.isDigit() }
                    if (p.numbers.none { it.number.filter { ch -> ch.isDigit() } == digits }) p.numbers.add(ContactNumber(number, label, primary))
                }
            }
            val ranked = ContactMatcher.rank(name, people.values.map { it.id to it.name }, limit)
            val matches = ranked.mapNotNull { r -> people[r.id]?.let { ContactMatch(it.id, it.name, it.numbers.toList()) } }
            log.i("Contacts", "searched ${people.size} contacts -> ${matches.size} match(es)")
            ContactSearch(matches)
        } catch (e: SecurityException) {
            ContactSearch(emptyList(), listOf(Requirement.CONTACTS))
        } catch (e: Exception) {
            log.w("Contacts", "lookup failed (${e.javaClass.simpleName})")
            ContactSearch(emptyList(), error = "I couldn't read your contacts just now.")
        }
    }
}
