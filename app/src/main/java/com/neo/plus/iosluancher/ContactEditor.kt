package com.neo.plus.iosluancher

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Edits a contact INSIDE this app (no other app is opened): name, number, e-mail, and where it is saved:
 *   Phone  |  SIM 1  |  SIM 2  |  every e-mail account on the phone (Google, Exchange...)
 */
enum class TargetKind { PHONE, SIM, EMAIL }

class SaveTarget(
    val kind: TargetKind, val label: String, val detail: String,
    val accountType: String?, val accountName: String?, val slot: Int = 0, val subId: Int = -1
) {
    val id: String get() = "$kind|$accountType|$accountName|$slot"
    /** A SIM that the phone does not list as a contact account: it is written straight to the SIM card instead. */
    val rawSim: Boolean get() = kind == TargetKind.SIM && accountType.isNullOrBlank()
}

class ExistingContact(
    val rawId: Long, val name: String, val number: String, val email: String,
    val accountType: String?, val accountName: String?
)

object ContactStore {
    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context) = has(ctx, Manifest.permission.WRITE_CONTACTS) && has(ctx, Manifest.permission.READ_CONTACTS)

    /** Every place a contact can be saved on this phone. */
    fun targets(ctx: Context): List<SaveTarget> {
        val out = ArrayList<SaveTarget>()
        out += SaveTarget(TargetKind.PHONE, "Phone", "Only on this phone", null, null)

        val seen = HashSet<String>()
        val simAcc = ArrayList<Pair<String?, String?>>()
        val mailAcc = ArrayList<Pair<String?, String?>>()
        fun addAcc(type: String?, name: String?) {
            if (type.isNullOrBlank() && name.isNullOrBlank()) return
            if (!seen.add("$type|$name")) return
            val t = (type ?: "").lowercase(); val n = (name ?: "").lowercase()
            when {
                t.contains("sim") || n.contains("sim") || t.contains("icc") || n.contains("icc") -> simAcc += type to name
                n.contains("@") || t == "com.google" || t.contains("exchange") || t.contains("outlook") || t.contains("yahoo") -> mailAcc += type to name
            }
        }
        try { AccountManager.get(ctx).accounts.forEach { addAcc(it.type, it.name) } } catch (e: Exception) { }
        if (has(ctx, Manifest.permission.READ_CONTACTS)) {
            try {
                ctx.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
                    "${ContactsContract.RawContacts.DELETED}=0", null, null
                )?.use { while (it.moveToNext()) addAcc(it.getString(0), it.getString(1)) }
            } catch (e: Exception) { }
        }

        // SIM cards: which slots really have a SIM, and the contact account each one uses (when the phone has one).
        class Slot(val slot: Int, val subId: Int, val carrier: String)
        val slots = ArrayList<Slot>()
        try {
            if (has(ctx, Manifest.permission.READ_PHONE_STATE)) {
                val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                sm.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex }?.forEach {
                    slots += Slot(it.simSlotIndex + 1, it.subscriptionId, it.carrierName?.toString().orEmpty())
                }
            }
        } catch (e: Exception) { }
        fun accFor(slot: Int): Pair<String?, String?>? {
            val withNumber = simAcc.filter { (it.first.orEmpty() + it.second.orEmpty()).contains(slot.toString()) }
            return withNumber.firstOrNull() ?: if (slot == 1) simAcc.firstOrNull { (it.first.orEmpty() + it.second.orEmpty()).none { c -> c == '2' } } else null
        }
        val slotNumbers = (slots.map { it.slot } + simAcc.indices.map { it + 1 }).ifEmpty { listOf(1) }.distinct().sorted().take(2)
        for (n in slotNumbers) {
            val info = slots.firstOrNull { it.slot == n }
            val acc = accFor(n)
            out += SaveTarget(
                TargetKind.SIM, "SIM $n", info?.carrier?.takeIf { it.isNotBlank() } ?: "SIM card $n (name and number only)",
                acc?.first, acc?.second, n, info?.subId ?: -1
            )
        }

        for ((type, name) in mailAcc) {
            val kind = when {
                (type ?: "") == "com.google" -> "Google"
                (type ?: "").contains("exchange", true) -> "Exchange"
                else -> "Email"
            }
            out += SaveTarget(TargetKind.EMAIL, name ?: kind, kind + " account", type, name)
        }
        return out
    }

    /** The saved contact for [number], or null when it is not saved yet. */
    fun load(ctx: Context, number: String): ExistingContact? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            var contactId = -1L
            var disp = ""
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) { contactId = it.getLong(0); disp = it.getString(1).orEmpty() } }
            if (contactId < 0) return null
            var rawId = -1L; var type: String? = null; var acc: String? = null
            ctx.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0", arrayOf(contactId.toString()), null
            )?.use { if (it.moveToFirst()) { rawId = it.getLong(0); type = it.getString(1); acc = it.getString(2) } }
            if (rawId < 0) return null
            fun first(mime: String, col: String): String {
                var v = ""
                ctx.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI, arrayOf(col),
                    "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(rawId.toString(), mime), null
                )?.use { if (it.moveToFirst()) v = it.getString(0).orEmpty() }
                return v
            }
            ExistingContact(
                rawId, disp,
                first(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER).ifBlank { number },
                first(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS),
                type, acc
            )
        } catch (e: Exception) { null }
    }

    private fun upsert(ctx: Context, raw: Long, mime: String, col: String, value: String, extra: ContentValues? = null) {
        val cr = ctx.contentResolver
        val v = ContentValues().apply { put(col, value); extra?.let { putAll(it) } }
        val n = cr.update(
            ContactsContract.Data.CONTENT_URI, v,
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(raw.toString(), mime)
        )
        if (n == 0 && value.isNotBlank()) {
            val ins = ContentValues(v).apply { put(ContactsContract.Data.RAW_CONTACT_ID, raw); put(ContactsContract.Data.MIMETYPE, mime) }
            cr.insert(ContactsContract.Data.CONTENT_URI, ins)
        }
    }

    /** Saves the contact. Returns Pair(success, message to show). */
    fun save(ctx: Context, ex: ExistingContact?, name: String, number: String, email: String, t: SaveTarget): Pair<Boolean, String> {
        if (name.isBlank() && number.isBlank()) return false to "Type a name or a number first"
        if (!canWrite(ctx)) return false to "Allow Contacts for Neo Plus first"
        return try {
            val sameAccount = ex != null && !t.rawSim && (ex.accountType.orEmpty() == t.accountType.orEmpty()) && (ex.accountName.orEmpty() == t.accountName.orEmpty())
            when {
                t.rawSim -> {
                    val uri = if (t.subId >= 0) Uri.parse("content://icc/adn/subId/${t.subId}") else Uri.parse("content://icc/adn")
                    val v = ContentValues().apply { put("tag", name.ifBlank { number }); put("number", number) }
                    val r = ctx.contentResolver.insert(uri, v)
                    if (r != null) true to "Saved to ${t.label}" else false to "This phone did not let Neo Plus write to ${t.label}"
                }
                sameAccount && ex != null -> {
                    upsert(ctx, ex.rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    upsert(ctx, ex.rawId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER, number,
                        ContentValues().apply { put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) })
                    if (t.kind != TargetKind.SIM) upsert(ctx, ex.rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim(),
                        ContentValues().apply { put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME) })
                    true to "Contact updated in ${t.label}"
                }
                else -> {
                    val ops = ArrayList<ContentProviderOperation>()
                    ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, t.accountType)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, t.accountName).build()
                    ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.ifBlank { number }).build()
                    if (number.isNotBlank()) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
                    if (email.isNotBlank() && t.kind != TargetKind.SIM) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim())
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME).build()
                    ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                    true to (if (ex != null) "Saved a copy in ${t.label}" else "Saved to ${t.label}")
                }
            }
        } catch (e: Exception) {
            false to ("Couldn’t save: " + (e.message?.take(80) ?: e.javaClass.simpleName))
        }
    }
}

/** The editor itself: used inside the contact dialog, so nothing outside the app opens. */
@Composable
fun ContactEditorForm(number: String, name: String, onClose: (saved: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val existing by produceState<ExistingContact?>(null, number) { value = withContext(Dispatchers.IO) { ContactStore.load(app, number) } }
    val targets by produceState<List<SaveTarget>>(emptyList(), number) { value = withContext(Dispatchers.IO) { ContactStore.targets(app) } }

    var nameF by remember { mutableStateOf(if (name == number) "" else name) }
    var phoneF by remember { mutableStateOf(number) }
    var mailF by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var wantSave by remember { mutableStateOf(false) }

    // Fill the boxes with the saved contact once, and pre-select the place it already lives in.
    val ex = existing
    if (ex != null && !loaded) {
        loaded = true
        nameF = ex.name; phoneF = ex.number; mailF = ex.email
    }
    val list = targets
    val sel: SaveTarget? = list.firstOrNull { it.id == chosen }
        ?: (if (ex != null) list.firstOrNull { it.accountType.orEmpty() == ex.accountType.orEmpty() && it.accountName.orEmpty() == ex.accountName.orEmpty() } else null)
        ?: list.firstOrNull()
    val simSelected = sel?.kind == TargetKind.SIM

    fun doSave() {
        val t = sel ?: return
        val (ok, text) = ContactStore.save(app, ex, nameF.trim(), phoneF.trim(), mailF.trim(), t)
        msg = text
        if (ok) { ContactPhotoVersion.intValue++; onClose(true) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (ContactStore.canWrite(app)) { if (wantSave) doSave() } else msg = "Contacts permission is needed to save"
        wantSave = false
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF111A2B)).padding(18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(if (ex != null) "Edit contact" else "New contact", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Field("Name", nameF) { nameF = it }
        Field("Phone number", phoneF, KeyboardType.Phone) { phoneF = it }
        Field(if (simSelected) "Email (SIM cards keep name and number only)" else "Email", mailF, KeyboardType.Email, enabled = !simSelected) { mailF = it }

        Spacer(Modifier.height(12.dp))
        Text("Save to", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val groups = listOf(
            TargetKind.PHONE to "On the phone",
            TargetKind.SIM to "SIM card",
            TargetKind.EMAIL to "Email account"
        )
        for ((kind, title) in groups) {
            val items = list.filter { it.kind == kind }
            if (items.isEmpty() && kind != TargetKind.EMAIL) continue
            Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            if (items.isEmpty()) {
                Text("No email account found. Add one in Settings → Accounts.", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
            }
            items.forEach { t ->
                val on = sel?.id == t.id
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (on) Color(0xFF0A84FF).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.07f))
                        .border(1.dp, if (on) Color(0xFF38BDF8) else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { chosen = t.id }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (on) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (t.detail.isNotBlank()) Text(t.detail, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
        if (ex != null && sel != null && (ex.accountType.orEmpty() != sel.accountType.orEmpty() || ex.accountName.orEmpty() != sel.accountName.orEmpty()) && !sel.rawSim) {
            Text("This contact will also be saved as a copy in ${sel.label}.", color = Color(0xFFFFD60A), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (msg.isNotBlank()) Text(msg, color = Color(0xFFFF9F0A), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.10f)).clickable { onClose(false) }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                Text("Cancel", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A84FF)).clickable {
                if (ContactStore.canWrite(app)) doSave()
                else { wantSave = true; ask.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS)) }
            }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                Text("Save", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, kb: KeyboardType = KeyboardType.Text, enabled: Boolean = true, onChange: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f)).padding(horizontal = 14.dp, vertical = 11.dp)) {
            BasicTextField(
                value, onChange, enabled = enabled, singleLine = true,
                textStyle = TextStyle(color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White), keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
