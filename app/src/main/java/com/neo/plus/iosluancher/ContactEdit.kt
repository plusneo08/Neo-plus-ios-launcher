package com.neo.plus.iosluancher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Opens the phone's own contact editor for a person: the saved contact when the number belongs to one (change the name,
 * number, e-mail, photo...), otherwise a new contact with the number already filled in.
 */
object ContactEdit {
    fun open(ctx: Context, number: String, name: String = "") {
        val saved = lookup(ctx, number)
        val i = if (saved != null) {
            Intent(Intent.ACTION_EDIT).setDataAndType(saved, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        } else {
            Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, number)
                .apply { if (name.isNotBlank() && name != number) putExtra(ContactsContract.Intents.Insert.NAME, name) }
        }
        i.putExtra("finishActivityOnSaveCompleted", true)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!ctx.safeStart(i)) {
            // some phones have no editor for the saved contact: fall back to the add-or-edit chooser
            ctx.safeStart(
                Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number)
                    .apply { if (name.isNotBlank() && name != number) putExtra(ContactsContract.Intents.Insert.NAME, name) }
                    .apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    private fun lookup(ctx: Context, number: String): Uri? = try {
        if (number.isBlank() || ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null
        )?.use { if (it.moveToFirst()) ContactsContract.Contacts.getLookupUri(it.getLong(0), it.getString(1)) else null }
    } catch (e: Exception) { null }
}

/** Goes up whenever a contact picture changes, so every avatar on screen reloads. */
val ContactPhotoVersion = androidx.compose.runtime.mutableIntStateOf(0)

/** The picture of a contact (your own one, else the phone's), loaded off the main thread, for avatars in lists. Null = show the initial. */
@androidx.compose.runtime.Composable
fun rememberContactPhoto(number: String): androidx.compose.ui.graphics.ImageBitmap? {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val ver = ContactPhotoVersion.intValue
    return androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, number, ver) {
        value = if (number.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ContactPhotos.thumb(app, number)?.asImageBitmap() }
    }.value
}

/** A picture for a person, shown on the call screen. Your own picture (added here) wins; otherwise the contact's own photo. */
object ContactPhotos {
    private fun key(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.length >= 7) d.takeLast(9) else number.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun file(ctx: Context, number: String) =
        java.io.File(java.io.File(ctx.filesDir, "contact_photos").apply { mkdirs() }, key(number) + ".jpg")

    private class Cached(val at: Long, val bmp: android.graphics.Bitmap?)
    private val cache = android.util.LruCache<String, Cached>(80)

    /** A small copy for lists; remembered for a few seconds so scrolling stays smooth. */
    fun thumb(ctx: Context, number: String): android.graphics.Bitmap? {
        val k = key(number)
        val hit = cache.get(k)
        if (hit != null && System.currentTimeMillis() - hit.at < 20_000) return hit.bmp
        val full = get(ctx, number)
        val small = if (full == null) null else try {
            val side = 160
            val r = side.toFloat() / maxOf(full.width, full.height)
            if (r >= 1f) full else android.graphics.Bitmap.createScaledBitmap(full, (full.width * r).toInt().coerceAtLeast(1), (full.height * r).toInt().coerceAtLeast(1), true)
        } catch (e: Throwable) { full }
        cache.put(k, Cached(System.currentTimeMillis(), small))
        return small
    }

    fun hasOwn(ctx: Context, number: String): Boolean = number.isNotBlank() && file(ctx, number).exists()

    fun get(ctx: Context, number: String): android.graphics.Bitmap? {
        if (number.isBlank()) return null
        try {
            val f = file(ctx, number)
            if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path)?.let { return it }
        } catch (e: Throwable) { }
        return try {
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
            else ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.PHOTO_URI), null, null, null
            )?.use { c ->
                val u = if (c.moveToFirst()) c.getString(0) else null
                if (u == null) null else ctx.contentResolver.openInputStream(Uri.parse(u))?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
        } catch (e: Throwable) { null }
    }

    /** Copies the picked picture into the app, scaled down. */
    fun save(ctx: Context, number: String, uri: Uri): Boolean = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) false else {
            file(ctx, number).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            cache.remove(key(number)); ContactPhotoVersion.intValue++
            true
        }
    } catch (e: Throwable) { false }

    fun remove(ctx: Context, number: String) { try { file(ctx, number).delete() } catch (e: Throwable) { }; cache.remove(key(number)); ContactPhotoVersion.intValue++ }
}

/** "Edit contact": the picture shown when this person calls, and a button for the name and other details. */
@androidx.compose.runtime.Composable
fun ContactEditDialog(number: String, name: String, progress: String? = null, onStopAll: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val photo = androidx.compose.runtime.remember(tick) { ContactPhotos.get(ctx, number)?.asImageBitmap() }
    val own = androidx.compose.runtime.remember(tick) { ContactPhotos.hasOwn(ctx, number) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (ContactPhotos.save(ctx, number, uri)) tick++
            else android.widget.Toast.makeText(ctx, "Couldn’t use that photo", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF111A2B)).padding(22.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            if (progress != null) androidx.compose.material3.Text(progress, color = androidx.compose.ui.graphics.Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = androidx.compose.ui.Modifier.padding(bottom = 10.dp))
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(120.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color(0xFF1D4ED8)).clickable { pick.launch("image/*") },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                if (photo != null) androidx.compose.foundation.Image(photo, null, androidx.compose.ui.Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else androidx.compose.material3.Text(
                    name.trim().firstOrNull { it.isLetter() }?.uppercase() ?: "+", color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 44.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
            androidx.compose.material3.Text(name.ifBlank { number }, color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
            if (name.isNotBlank() && name != number) androidx.compose.material3.Text(number, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            androidx.compose.material3.Text("This picture shows when they call you", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = androidx.compose.ui.Modifier.padding(top = 6.dp, bottom = 14.dp))
            DialogButton(if (photo == null) "Add picture" else "Change picture", true) { pick.launch("image/*") }
            if (own) DialogButton("Remove my picture", false) { ContactPhotos.remove(ctx, number); tick++ }
            DialogButton("Edit name and details", false) { ContactEdit.open(ctx, number, name); onDismiss() }
            DialogButton(if (progress != null) "Next contact" else "Done", false) { ContactPhotoVersion.intValue++; onDismiss() }
            if (onStopAll != null) DialogButton("Stop editing", false) { ContactPhotoVersion.intValue++; onStopAll() }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(if (primary) androidx.compose.ui.graphics.Color(0xFF0A84FF) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) { androidx.compose.material3.Text(text, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
}
