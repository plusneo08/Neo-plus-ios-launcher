package com.neo.plus.iosluancher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

/**
 * Sending money. The assistant can only PREPARE a transfer: it never sends one by itself.
 *  1. The user asks (recipient = contact name or phone number, plus the amount).
 *  2. The phone shows a screen with the recipient, number, amount and the exact USSD code. Only the user's tap on
 *     "Authorise" goes further (a spoken or typed "yes" to the AI is not enough; the AI cannot press that button).
 *  3. Authorise dials the user's own mobile-money USSD code. The PIN is typed by the user in the network's own dialog:
 *     Neo Plus never asks for, sees or stores it.
 * Nothing can start a transfer while sleep mode is on, from a notification, or from a message sent by someone else.
 */
object MoneySender {
    private const val PREFS = "neo_money"
    private const val EXPIRES_MS = 2 * 60_000L

    class Pending(
        val id: String, val name: String, val number: String, val amount: String, val currency: String,
        val ussd: String, val sim: Int, val createdAt: Long,
        val network: String = "", val withFee: Boolean = false, val netBy: String = ""   // network = "airtel" / "mpamba": the phone picks the menu steps itself
    )

    @Volatile private var pending: Pending? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The USSD code the user saved, with {number} and {amount} where the recipient and the amount go. */
    fun template(ctx: Context): String = prefs(ctx).getString("ussd", "").orEmpty()

    fun setup(ctx: Context, ussd: String): String {
        val t = ussd.trim()
        val ok = Regex("^\\*[0-9*#{}a-z]{3,60}#$").matches(t) && t.contains("{number}") && t.contains("{amount}") &&
            !t.contains("pin", ignoreCase = true)
        if (!ok) return "Couldn’t save that. Give me your mobile-money send code with {number} and {amount} in it, for example *123*1*{number}*{amount}#. Do not put your PIN in it."
        prefs(ctx).edit().putString("ussd", t).apply()
        return "Saved. When you authorise a transfer, the phone will dial that code and you type your PIN yourself."
    }

    private fun cleanAmount(raw: String): String? {
        val s = raw.lowercase().replace(Regex("[\\s,]|mwk|mk|kwacha"), "")
        return if (Regex("^\\d{1,9}(\\.\\d{1,2})?$").matches(s) && s.toDouble() > 0.0) s else null
    }

    /** Called by the assistant. Only opens the authorisation screen. */
    fun request(ctx: Context, a: JSONObject): String {
        val app = ctx.applicationContext
        if (SleepMode.isOn(app)) return "Couldn’t prepare a transfer: " + Modes.labelNow(app).lowercase() + " is on. Switch it off, then ask me again."
        val to = a.optString("to").trim()
        if (to.isEmpty()) return "Couldn’t prepare the transfer: who should get the money? Give a name or a phone number."
        val amount = cleanAmount(a.optString("amount")) ?: return "Couldn’t prepare the transfer: tell me the amount, for example 5000."
        val target = PhoneActions.resolveContact(app, to) ?: return "No contact or number for “$to”"
        val digits = target.second.filter { it.isDigit() }
        if (digits.length < 7) return "Couldn’t prepare the transfer: that number looks too short."
        val said = MoneySteps.normalize(a.optString("network"))
        val byNumber = MoneySteps.networkForNumber(app, digits)
        val net = said ?: byNumber
        val netBy = if (said != null) "you" else "number"
        val tpl = template(app)
        if (net == null && tpl.isBlank()) return "Couldn’t tell from the number which mobile money it is. Is $digits on Airtel Money or Mpamba?"
        val withFee: Boolean = if (net != null) {
            MoneySteps.parseFee(a) ?: return "Should the cashout (withdrawal) fee be included? Say “with fee” or “without fee”."
        } else false
        if (net != null && IosControlService.instance == null) return "Turn on “NEO PLUS iOS Controls” in Settings > Accessibility, then ask me again."
        val wantSim = a.optInt("sim", 0).let { if (it in 1..2 || net == null) it else (CallSim.slotForCarrier(app, MoneySteps.carrierKey(net)) ?: MoneySteps.simFor(app, net)) }
        val chosen = CallSim.pick(app, wantSim)
        if (chosen.ask) return "Which SIM is ${if (net != null) MoneySteps.label(net) else "your mobile money"} on, SIM 1 or SIM 2? Say “SIM 1” or “SIM 2”."
        val ussd = if (net != null) MoneySteps.startCode(app, net) else tpl.replace("{number}", digits).replace("{amount}", amount)
        val p = Pending(UUID.randomUUID().toString(), target.first, digits, amount, a.optString("currency", "MK").ifBlank { "MK" }.take(4),
            ussd, chosen.slot ?: 0, System.currentTimeMillis(), net.orEmpty(), withFee, if (net != null) netBy else "")
        pending = p
        MoneyCmd.clear()
        if (!TaskLog.active || TaskLog.owner == "money") {   // never replaces the feed of a running website task
            TaskLog.start("Send ${p.currency} ${p.amount} to ${p.name}")
            TaskLog.owner = "money"; TaskLog.overlay = false
            TaskLog.onStop = { cancel(p.id) }
            TaskLog.add(TaskLine.Kind.INFO, "Recipient ${p.name} (…${p.number.takeLast(3)})" +
                (if (p.network.isNotEmpty()) ", " + MoneySteps.label(p.network) + ", " + (if (p.withFee) "with" else "without") + " cashout fee" else ""))
            TaskLog.add(TaskLine.Kind.WARN, "Waiting for you: tap Authorise on the screen. Nothing is sent before that.")
            TaskLog.setState("Waiting for you to authorise")
        }
        val ok = app.safeStart(Intent(app, MoneyAuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("id", p.id))
        if (!ok) {
            pending = null
            TaskLog.endOwned("money", "Couldn’t show the authorisation screen. Nothing was sent.", false)
            return "Couldn’t show the authorisation screen. Open Neo Plus and ask me again."
        }
        return "Waiting for you: authorise ${p.currency} ${p.amount} to ${p.name} on the screen. Nothing is sent until you tap Authorise."
    }

    fun current(id: String?): Pending? {
        val p = pending ?: return null
        if (p.id != id || System.currentTimeMillis() - p.createdAt > EXPIRES_MS) { pending = null; return null }
        return p
    }

    fun cancel(id: String?) {
        if (pending?.id == id) {
            pending = null
            TaskLog.endOwned("money", "Cancelled. Nothing was sent.", false)
        }
    }

    /** Only MoneyAuthActivity calls this, from the Authorise button. */
    /** Audit trail without the full number and without any PIN. */
    private fun audit(app: Context, p: Pending) {
        val log = prefs(app).getString("log", "").orEmpty()
        val line = "${System.currentTimeMillis()}|${p.currency} ${p.amount}|${p.name}|…${p.number.takeLast(3)}"
        prefs(app).edit().putString("log", (log + "\n" + line).lines().takeLast(50).joinToString("\n")).apply()
    }

    internal fun authorise(ctx: Context, id: String): String {
        val p = current(id) ?: run {
            TaskLog.endOwned("money", "The request expired. Nothing was sent.", false)
            return "That request expired. Ask me again."
        }
        pending = null
        val app = ctx.applicationContext
        val chosen = CallSim.pick(app, p.sim)
        val action = if (ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:" + Uri.encode(p.ussd))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        chosen.handle?.let { intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        // With the accessibility service on, it does the steps a person would: opens the dialer, types the code, presses Call.
        if (p.network.isNotEmpty() && IosControlService.instance != null) {
            audit(app, p)
            if (TaskLog.owner == "money") {
                TaskLog.add(TaskLine.Kind.OK, "You authorised the transfer")
                TaskLog.overlay = true
                TaskLog.onStop = { UssdFlow.stop() }
                StepOverlay.start(app)
            }
            UssdFlow.dial(app, p, chosen, chosen.slot?.let { "SIM $it" } ?: "the phone’s default SIM", intent)
            return "Opening the phone dialer. I will type the code, press Call, pick the menu steps and stop at the PIN screen: type your PIN there."
        }
        val started = app.safeStart(intent)
        if (started) audit(app, p)
        if (!started) TaskLog.endOwned("money", "Couldn’t start the code. Nothing was sent.", false)
        if (started && p.network.isEmpty()) TaskLog.endOwned("money", "Dialling now. Type your PIN in the network’s box to finish.", true)
        if (started && p.network.isNotEmpty()) {
            if (TaskLog.owner == "money") {
                TaskLog.add(TaskLine.Kind.OK, "You authorised the transfer")
                TaskLog.overlay = true
                TaskLog.onStop = { UssdFlow.stop() }
                StepOverlay.start(app)
            }
            UssdFlow.begin(app, p, chosen.slot?.let { "SIM $it" } ?: "the phone’s default SIM")
            return "Opening ${MoneySteps.label(p.network)}. I will pick the menu steps and stop at the PIN screen: type your PIN there."
        }
        return if (started) "Dialling now. Type your PIN in the network’s box to finish." else "Couldn’t start the code. Nothing was sent."
    }
}

/** The screen the user must tap before any money moves. */
class MoneyAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val p = MoneySender.current(id)
        if (p == null) { finish(); return }
        val how = if (p.network.isNotEmpty())
            "Network: ${MoneySteps.label(p.network)} (" + (if (p.netBy == "you") "as you said" else "from the number’s prefix: check it is right") + ")\n" +
            "Cashout fee: " + (if (p.withFee) "WITH (included)" else "WITHOUT") + "\n" +
            "The phone will open ${p.ussd}, pick the menu steps for you and stop at the PIN screen.\n"
        else "The phone will dial ${p.ussd}\n"
        val msg = "Send ${p.currency} ${p.amount} to ${p.name}\nNumber: ${p.number}\n\n" + how +
            "You type your PIN yourself. Neo Plus never sees it.\nThis request expires in 2 minutes."
        val d = AlertDialog.Builder(this)
            .setTitle("Authorise this transfer?")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Authorise") { _, _ ->
                Toast.makeText(this, MoneySender.authorise(this, p.id), Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> MoneySender.cancel(p.id); finish() }
            .create()
        d.show()
        // Ignore taps while another window is drawn over this one
        d.window?.decorView?.filterTouchesWhenObscured = true
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) { MoneySender.cancel(intent.getStringExtra("id")); finish() }
    }
}

/**
 * “send 5000 to 0992101284 with fee”, “send MK5000 to Anita without fee on Mpamba”: understood on the phone itself, with no AI
 * request (so it is instant and works without a key). The short answers to its questions (“with fee”, “SIM 2”, “Airtel”) are
 * understood for two minutes after the question. It only prepares the transfer: the Authorise screen and the PIN stay yours.
 */
object MoneyCmd {
    private val I = RegexOption.IGNORE_CASE
    private val sendRe = Regex("^(?:please\\s+)?(?:send|transfer)\\s+(?:mwk|mk|k)?\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*(?:mwk|mk|kwacha|kw)?\\s+(?:(?:money|cash)\\s+)?to\\s+(.+)$", I)
    private val feeRe = Regex("\\b(without|with)\\s+(?:the\\s+)?(?:cash\\s*-?\\s*out\\s+|withdrawal\\s+)?fees?\\b", I)
    private val netRe = Regex("\\b(?:on|via|using|from)\\s+(airtel(?:\\s*money)?|mpamba|tnm(?:\\s*mpamba)?)\\b", I)
    private val simRe = Regex("\\b(?:on\\s+)?sim\\s*([12])\\b", I)
    private val feeOnlyRe = Regex("^(?:yes\\s*,?\\s*)?(without|with)(?:\\s+(?:the\\s+)?(?:cash\\s*-?\\s*out\\s+|withdrawal\\s+)?fees?)?$", I)
    private val simOnlyRe = Regex("^(?:on\\s+)?sim\\s*([12])$", I)
    private val netOnlyRe = Regex("^(?:on\\s+)?(airtel(?:\\s*money)?|mpamba|tnm(?:\\s*mpamba)?)$", I)
    private const val KEEP_MS = 2 * 60_000L

    @Volatile private var last: JSONObject? = null
    @Volatile private var lastAt = 0L

    /** The transfer was prepared (or given up): the short answers are no longer about it. */
    fun clear() { last = null }

    fun plan(text: String): LocalPlan? {
        val t = text.trim().trimEnd('.', '!', '?').trim()
        if (t.isEmpty()) return null
        val now = System.currentTimeMillis()
        sendRe.find(t)?.let { m ->
            var rest = m.groupValues[2]
            val a = JSONObject().put("amount", m.groupValues[1].replace(",", ""))
            feeRe.find(rest)?.let { f -> a.put("fee", f.groupValues[1].lowercase()); rest = rest.replace(f.value, " ") }
            netRe.find(rest)?.let { n -> a.put("network", n.groupValues[1].lowercase()); rest = rest.replace(n.value, " ") }
            simRe.find(rest)?.let { s -> a.put("sim", s.groupValues[1].toInt()); rest = rest.replace(s.value, " ") }
            val to = rest.replace(Regex("\\s+"), " ").trim(' ', ',', '.')
            if (to.isEmpty()) return null
            a.put("to", to)
            last = a; lastAt = now
            return LocalPlan("", listOf("send_money" to JSONObject(a.toString())))
        }
        val prev = last ?: return null
        if (now - lastAt > KEEP_MS) { last = null; return null }
        val a = JSONObject(prev.toString())
        val f = feeOnlyRe.find(t)
        val s = simOnlyRe.find(t)
        val n = netOnlyRe.find(t)
        when {
            f != null -> a.put("fee", f.groupValues[1].lowercase())
            s != null -> a.put("sim", s.groupValues[1].toInt())
            n != null -> a.put("network", n.groupValues[1].lowercase())
            else -> return null
        }
        last = a; lastAt = now
        return LocalPlan("", listOf("send_money" to JSONObject(a.toString())))
    }
}
