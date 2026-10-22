package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** Chooses which SIM (1 or 2) places a call, so the user never has to tap the SIM picker. */
object CallSim {
    class Choice(val handle: PhoneAccountHandle?, val slot: Int?, val ask: Boolean = false)

    private val none = Choice(null, null)

    /** [sim] 1 or 2 when the user said it; 0 = not said (uses the phone's default calling SIM, or asks if there is none). */
    fun pick(ctx: Context, sim: Int): Choice {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return none
        return try {
            val tel = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handles = tel.callCapablePhoneAccounts
            if (handles.size < 2) return none // single SIM: nothing to choose

            if (sim in 1..2) {
                val h = handleForSlot(ctx, handles, sim - 1) ?: return none
                return Choice(h, sim)
            }
            val def = tel.getDefaultOutgoingPhoneAccount("tel")
            if (def != null) Choice(def, slotOf(ctx, handles, def)) else Choice(null, null, ask = true)
        } catch (e: Exception) { none }
    }

    /** The SIM (1 or 2) whose carrier name contains [keyword] ("airtel", "tnm"), when exactly one does; null when it cannot be told. */
    fun slotForCarrier(ctx: Context, keyword: String): Int? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val hits = (sm.activeSubscriptionInfoList ?: emptyList()).filter {
                (it.carrierName?.toString().orEmpty() + " " + it.displayName?.toString().orEmpty()).contains(keyword, ignoreCase = true)
            }
            if (hits.size == 1) hits[0].simSlotIndex + 1 else null
        } catch (e: Exception) { null }
    }

    private fun handleForSlot(ctx: Context, handles: List<PhoneAccountHandle>, slot: Int): PhoneAccountHandle? {
        try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val info = sm.getActiveSubscriptionInfoForSimSlotIndex(slot)
            if (info != null) {
                handles.firstOrNull { it.id == info.subscriptionId.toString() || (info.iccId != null && it.id.contains(info.iccId!!)) }
                    ?.let { return it }
            }
        } catch (e: Exception) { }
        return handles.getOrNull(slot) // phones list accounts in slot order
    }

    private fun slotOf(ctx: Context, handles: List<PhoneAccountHandle>, h: PhoneAccountHandle): Int? {
        for (slot in 0..1) if (handleForSlot(ctx, handles, slot) == h) return slot + 1
        return null
    }
}
