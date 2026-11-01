package com.neo.plus.iosluancher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat

/**
 * Sends a text message for real and reports back through [Announcer]:
 *  - "sent" when the phone handed it to the network,
 *  - "delivered" when the other phone confirmed it (needs the carrier to send delivery reports),
 *  - or what went wrong.
 */
object SmsSender {
    private const val PREFIX = "com.neo.plus.iosluancher.SMS_"

    private class Job(val parts: Int, val label: String) {
        var sent = 0
        var delivered = 0
        var finished = false
        var announcedSent = false
    }

    fun send(ctx: Context, number: String, text: String, label: String, slot: Int = 0): String {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val sms: SmsManager = AssistantLine.smsManagerFor(app, slot)
            ?: if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()

        val parts = sms.divideMessage(text)
        val job = Job(parts.size, label)
        val id = java.lang.Long.toString(System.nanoTime(), 36)
        val sentAction = PREFIX + "SENT_" + id
        val deliveredAction = PREFIX + "DELIVERED_" + id

        lateinit var receiver: BroadcastReceiver
        fun cleanup() {
            if (job.finished) return
            job.finished = true
            try { app.unregisterReceiver(receiver) } catch (e: Exception) { }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    sentAction -> {
                        if (resultCode == Activity.RESULT_OK) {
                            job.sent++
                            if (job.sent == job.parts && !job.announcedSent) {
                                job.announcedSent = true
                                Announcer.say(app, "Message to ${job.label} sent.")
                            }
                        } else if (!job.finished) {
                            Announcer.say(app, "I couldn’t send the message to ${job.label}: ${reason(resultCode)}.")
                            cleanup()
                        }
                    }
                    deliveredAction -> {
                        when (deliveryState(intent)) {
                            DELIVERED -> {
                                job.delivered++
                                if (job.delivered == job.parts) {
                                    Announcer.say(app, "Message to ${job.label} was delivered.")
                                    cleanup()
                                }
                            }
                            FAILED -> {
                                Announcer.say(app, "The message to ${job.label} was not delivered.")
                                cleanup()
                            }
                            else -> Unit // network is still trying; a later report will follow
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply { addAction(sentAction); addAction(deliveredAction) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Mutable on purpose: the system adds the delivery report ("pdu") to the broadcast.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val sentPi = PendingIntent.getBroadcast(app, 0, Intent(sentAction).setPackage(app.packageName), flags)
        val deliveredPi = PendingIntent.getBroadcast(app, 1, Intent(deliveredAction).setPackage(app.packageName), flags)

        try {
            if (parts.size == 1) {
                sms.sendTextMessage(number, null, text, sentPi, deliveredPi)
            } else {
                sms.sendMultipartTextMessage(
                    number, null, parts,
                    ArrayList<PendingIntent>(parts.map { sentPi }),
                    ArrayList<PendingIntent>(parts.map { deliveredPi })
                )
            }
        } catch (e: Exception) {
            cleanup()
            return "Couldn’t send the message (${e.javaClass.simpleName})"
        }

        // Some networks never return delivery reports: say so instead of waiting in silence.
        main.postDelayed({
            if (!job.finished && job.sent == job.parts && job.delivered < job.parts) {
                Announcer.say(app, "The message to ${job.label} was sent, but the network has not confirmed delivery yet.")
            }
        }, 90_000L)
        main.postDelayed({ cleanup() }, 15 * 60_000L)

        return "Sending your message to $label…"
    }

    private fun reason(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "network or SIM problem"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no network signal"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode is on"
        SmsManager.RESULT_ERROR_NULL_PDU -> "the message was empty"
        5 -> "too many texts were sent in a short time, try again later"
        6 -> "fixed dialling is switched on for this SIM"
        7, 8 -> "the phone blocked this number as a short code"
        10, 17 -> "the network rejected the text"
        11, 14 -> "the number or the message is not valid"
        16 -> "the SIM or modem refused it (check airtime or an SMS bundle, the SIM card and the number)"
        else -> "error $code"
    }

    private const val DELIVERED = 0
    private const val FAILED = 1
    private const val PENDING = 2

    /** SMS status 0 = delivered, 64 and above = permanently failed, 32-63 = network still trying. */
    private fun deliveryState(intent: Intent): Int = try {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        if (pdu == null) DELIVERED
        else {
            val status = SmsMessage.createFromPdu(pdu, format)?.status ?: 0
            when {
                status >= 64 -> FAILED
                status >= 32 -> PENDING
                else -> DELIVERED
            }
        }
    } catch (e: Exception) { DELIVERED }
}
