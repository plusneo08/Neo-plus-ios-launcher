package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** Which SIM card sends the texts written in Neo Messages (only matters on a phone with two active SIMs). */
object SimChoice {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("neo_messages", Context.MODE_PRIVATE)

    private fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** The slots (1 and/or 2) that hold an active SIM. */
    fun activeSlots(ctx: Context): List<Int> {
        if (!granted(ctx)) return emptyList()
        return try {
            val sm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            sm.activeSubscriptionInfoList.orEmpty().map { it.simSlotIndex + 1 }.filter { it in 1..2 }.distinct().sorted()
        } catch (e: Throwable) { emptyList() }
    }

    /** The SIM the phone itself uses for texts (slot 1 or 2), or 0 when unknown. */
    private fun phoneDefault(ctx: Context, calls: Boolean = false): Int = try {
        val sm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val id = if (calls) SubscriptionManager.getDefaultVoiceSubscriptionId() else SubscriptionManager.getDefaultSmsSubscriptionId()
        (sm.getActiveSubscriptionInfo(id)?.simSlotIndex ?: -1) + 1
    } catch (e: Throwable) { 0 }

    /** The slot that will send the next text. */
    fun current(ctx: Context, calls: Boolean = false): Int {
        val act = activeSlots(ctx)
        val saved = prefs(ctx).getInt(if (calls) "call_sim" else "sim", 0)
        val def = phoneDefault(ctx, calls)
        return when {
            saved in act -> saved
            def in act -> def
            else -> act.firstOrNull() ?: 0
        }
    }

    fun set(ctx: Context, slot: Int, calls: Boolean = false) { prefs(ctx).edit().putInt(if (calls) "call_sim" else "sim", slot).apply() }

    /** Switches to the other SIM and returns the new slot. */
    fun toggle(ctx: Context, calls: Boolean = false): Int {
        val act = activeSlots(ctx)
        if (act.size < 2) return current(ctx, calls)
        val next = if (current(ctx, calls) == act[0]) act[1] else act[0]
        set(ctx, next, calls)
        return next
    }

    /** The "Remember my choice" box: ticked = the SIM stays chosen; unticked = it is used once and the phone's default comes back. */
    fun remember(ctx: Context, calls: Boolean = false): Boolean = prefs(ctx).getBoolean(if (calls) "call_sim_keep" else "sim_keep", true)

    fun setRemember(ctx: Context, on: Boolean, calls: Boolean = false) { prefs(ctx).edit().putBoolean(if (calls) "call_sim_keep" else "sim_keep", on).apply() }

    /** Call after a text was sent / a call was placed: forgets the choice unless "Remember" is ticked. */
    fun afterUse(ctx: Context, calls: Boolean = false) { if (!remember(ctx, calls)) set(ctx, 0, calls) }

    /** The calling account of the SIM chosen for calls, or null (the phone's default SIM is then used). */
    fun callAccount(ctx: Context): android.telecom.PhoneAccountHandle? {
        if (activeSlots(ctx).size < 2) return null
        return try {
            val slot = current(ctx, true)
            val sm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val info = sm.getActiveSubscriptionInfoForSimSlotIndex(slot - 1) ?: return null
            val telecom = ctx.getSystemService(android.telecom.TelecomManager::class.java)
            telecom.callCapablePhoneAccounts.firstOrNull { h ->
                h.id == info.subscriptionId.toString() || (!info.iccId.isNullOrBlank() && h.id.startsWith(info.iccId.take(18)))
            }
        } catch (e: Throwable) { null }
    }

    fun label(ctx: Context, slot: Int): String = AssistantLine.label(ctx, slot)

    /** The SMS manager for the chosen SIM (the phone's default SIM when there is only one or it cannot be told). */
    fun manager(ctx: Context): SmsManager {
        val slot = if (activeSlots(ctx).size >= 2) current(ctx) else 0
        return AssistantLine.smsManagerFor(ctx, slot)
            ?: if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
    }
}
