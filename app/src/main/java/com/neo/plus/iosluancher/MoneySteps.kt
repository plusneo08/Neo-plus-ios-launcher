package com.neo.plus.iosluancher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Per-network settings for step-by-step mobile-money transfers (Airtel Money, TNM Mpamba).
 * Saved in the same "neo_money" preferences as the plain USSD code.
 */
object MoneySteps {
    private const val PREFS = "neo_money"

    class Profile(val id: String, val label: String, val defaultCode: String)

    /** The start codes are only defaults: change them with money_setup {"network":"...","code":"*123#"} if yours differ. */
    val profiles = listOf(
        Profile("airtel", "Airtel Money", "*211#"),
        Profile("mpamba", "TNM Mpamba", "*444#")
    )

    fun normalize(raw: String): String? {
        val s = raw.lowercase()
        return when {
            s.contains("airtel") -> "airtel"
            s.contains("mpamba") || s.contains("tnm") -> "mpamba"
            else -> null
        }
    }

    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }
    fun label(id: String): String = profile(id)?.label ?: id

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun simFor(ctx: Context, id: String): Int = prefs(ctx).getInt("sim_$id", 0)

    /** The word a SIM's carrier name contains when it belongs to this mobile money (Mpamba = TNM). */
    fun carrierKey(id: String): String = if (id == "mpamba") "tnm" else id
    fun startCode(ctx: Context, id: String): String =
        prefs(ctx).getString("code_$id", "").orEmpty().ifBlank { profile(id)?.defaultCode.orEmpty() }

    /** National 9-digit numbers start with these digits (088... = 88, 099... = 99). Change with money_setup "prefixes". */
    private val defaultPrefixes = mapOf("airtel" to "99,98", "mpamba" to "88,84")

    fun prefixes(ctx: Context, id: String): List<String> =
        prefs(ctx).getString("prefix_$id", "").orEmpty().ifBlank { defaultPrefixes[id].orEmpty() }
            .split(',').map { it.trim().trimStart('0') }.filter { it.isNotEmpty() }

    /** Which mobile money the recipient's number belongs to, or null when the prefix is unknown or listed for both. */
    fun networkForNumber(ctx: Context, digits: String): String? {
        val d = digits.filter { it.isDigit() }
        if (d.length < 9) return null
        val national = d.takeLast(9)
        val hits = profiles.filter { pr -> prefixes(ctx, pr.id).any { national.startsWith(it) } }
        return if (hits.size == 1) hits[0].id else null
    }

    /** true = with the cashout (withdrawal) fee, false = without, null = the user did not say. */
    fun parseFee(a: JSONObject): Boolean? {
        val raw = a.optString("fee").trim().lowercase()
        if (raw.isNotEmpty()) {
            return when {
                raw.contains("without") || raw.startsWith("no") || raw == "false" -> false
                raw.contains("with") || raw == "yes" || raw == "true" -> true
                else -> null
            }
        }
        return if (a.has("with_fee")) a.optBoolean("with_fee") else null
    }

    /** money_setup with a network: which SIM it is on, its start code and the number prefixes that belong to it. */
    fun setup(ctx: Context, a: JSONObject): String {
        val id = normalize(a.optString("network")) ?: return "Which network is it for: Airtel Money or Mpamba?"
        val e = prefs(ctx).edit()
        if (a.has("sim")) {
            val s = a.optInt("sim", 0)
            if (s !in 0..2) return "The SIM must be 1 or 2."
            e.putInt("sim_$id", s)
        }
        val code = a.optString("code").trim()
        if (code.isNotEmpty()) {
            if (!Regex("^\\*[0-9*]{2,12}#$").matches(code)) return "Couldn’t save that code. It should look like *211# (digits and stars only, no PIN)."
            e.putString("code_$id", code)
        }
        val pre = a.optString("prefixes").replace(" ", "")
        if (pre.isNotEmpty()) {
            if (!Regex("^[0-9]{2,3}(,[0-9]{2,3})*$").matches(pre)) return "Couldn’t save the prefixes. Give them like 88,84 (the digits after the 0)."
            e.putString("prefix_$id", pre)
        }
        e.apply()
        val sim = prefs(ctx).getInt("sim_$id", 0)
        return "Saved for ${label(id)}: start code ${startCode(ctx, id)}, " + (if (sim in 1..2) "SIM $sim" else "default SIM") +
            ", numbers starting 0" + prefixes(ctx, id).joinToString(", 0") + "."
    }
}

/**
 * Drives the network's USSD pop-ups after the user tapped Authorise: reads each screen, recognises it, types the
 * answer (a menu number found by its label, the recipient number, the amount) and presses Send. It STOPS at the PIN
 * screen: it never types a PIN, never presses anything on that screen (except Cancel when the amount or number shown
 * there does not match what the user authorised), and it never types into a password-style box.
 * Menus are answered by the WORDS of the option ("Send Money", "Without Cashout Fees"), not by fixed digits, and each
 * kind of screen is used once, so a changed menu order or a repeated screen cannot send it in circles. Anything it does
 * not recognise ends the automation and leaves the screen to the user.
 */
object UssdFlow {
    private val main = Handler(Looper.getMainLooper())

    /** A kind of screen. menu = which option to pick (found by its label); input = what to type in the box. */
    private class Rule(
        val key: String, val screen: Regex,
        val menu: ((MoneySender.Pending) -> Regex)? = null,
        val input: ((MoneySender.Pending) -> String)? = null
    )

    private class Screen(val msg: String, val edit: AccessibilityNodeInfo?, val send: AccessibilityNodeInfo?, val cancel: AccessibilityNodeInfo?)

    private val pinRe = Regex("(?i)enter\\s+(?:your\\s+)?pin")
    private val errorRe = Regex("(?i)\\b(invalid|insufficient|failed|failure|error|not registered|unsuccessful|declined|try again|incorrect|not allowed|limit)\\b")
    private val safeAnswer = Regex("^[0-9]{1,15}(\\.[0-9]{1,2})?$")
    private val optionRe = Regex("^\\s*(\\d{1,2})\\s*[.)\\-:]?\\s*(.+?)\\s*$")
    private val shownRe = Regex("(?is)(?:mwk|mk)\\s*([\\d,]+(?:\\.\\d+)?)\\s*to\\s*\\+?(\\d{7,15})")
    private val nameRe = Regex("(?is)to\\s*\\+?\\d{7,15}\\s*,\\s*([^.]+)\\.")
    private val chargeRe = Regex("(?is)service\\s*charge\\s*(?:mwk|mk)\\s*([\\d,]+(?:\\.\\d+)?)")
    private const val MAX_ACTIONS = 10
    // A USSD session lives for only a few seconds, so every wait between two steps is kept as short as it safely can be:
    private const val FIRST_MS = 400L        // after dialling, before the first look at the screen
    private const val POLL_MS = 150L         // how often it looks for the next pop-up
    private const val TYPE_MS = 100L         // after typing, before the screen is checked once more and Send is pressed
    private const val AFTER_SEND_MS = 150L   // after Send, before it starts looking for the next pop-up

    private fun local(number: String) = "0" + number.filter { it.isDigit() }.takeLast(9)

    private fun rules(p: MoneySender.Pending, destAirtel: Boolean): List<Rule> = when (p.network) {
        "mpamba" -> listOf(
            Rule("main", Regex("(?is)^\\s*mpamba\\W+main\\s*menu"), menu = { Regex("(?i)send\\s*money") }),
            Rule("how", Regex("(?is)enter\\s*phone\\s*number.*favourites"), menu = { Regex("(?i)enter\\s*phone\\s*number") }),
            Rule("dest", Regex("(?is)please\\s*select.*\\bmpamba\\b.*airtel\\s*money"), menu = { if (destAirtel) Regex("(?i)^airtel\\s*money\\s*$") else Regex("(?i)^mpamba\\s*$") }),
            Rule("wallet", Regex("(?is)please\\s*select.*\\bwallet\\b.*favourites"), menu = { Regex("(?i)^wallet\\s*$") }),
            Rule("fee", Regex("(?is)cash\\s*out\\s*fees"),
                menu = { if (it.withFee) Regex("(?i)^with\\s+cash\\s*out") else Regex("(?i)^without\\s+cash\\s*out") }),
            Rule("number", Regex("(?is)^\\s*enter\\s*(?:(?:phone|airtel|mpamba)\\s*)?number\\s*:?"), input = { local(it.number) }),
            Rule("amount", Regex("(?is)^\\s*enter\\s*amount"), input = { it.amount })
        )
        "airtel" -> listOf(
            Rule("main", Regex("(?is)^\\s*airtel\\s*money.*send\\s*money"), menu = { Regex("(?i)send\\s*money") }),
            Rule("to", Regex("(?is)select\\s*:.*airtel\\s*number"),
                menu = { if (it.withFee) Regex("(?i)with\\s+withdrawal") else Regex("(?i)^airtel\\s+number\\s*$") }),
            Rule("how", Regex("(?is)^\\s*1\\s*[.)]?\\s*enter\\s*phone\\s*number.*favourites"), menu = { Regex("(?i)^enter\\s*phone\\s*number") }),
            Rule("number", Regex("(?is)^\\s*enter\\s*(?:phone|airtel)\\s*number\\s*:?"), input = { local(it.number) }),
            Rule("amount", Regex("(?is)^\\s*enter\\s*amount"), input = { it.amount })
        )
        else -> emptyList()
    }

    /** The number in front of the option whose label matches, e.g. "2.Airtel with withdrawal fee" -> "2". */
    private fun optionNumber(msg: String, label: Regex): String? {
        for (line in msg.lines()) {
            val m = optionRe.find(line) ?: continue
            if (label.containsMatchIn(m.groupValues[2].trim())) return m.groupValues[1]
        }
        return null
    }

    private var cur: MoneySender.Pending? = null
    private var list: List<Rule> = emptyList()
    private val used = HashSet<String>()
    private var startedAt = 0L
    private var lastDoneMsg = ""
    private var lastProgressAt = 0L
    private var unknownSince = 0L
    private var gen = 0
    private var appCtx: Context? = null
    private var simText = ""

    private fun log(kind: TaskLine.Kind, text: String) { TaskLog.addOwned("money", kind, text) }

    /** The Stop button of the task feed. */
    fun stop() {
        val app = appCtx ?: return
        main.post { if (cur != null) finish(app, "Stopped by you. Nothing is sent until you enter your PIN.") }
    }

    /** Called right after the network's code was dialled. */
    fun begin(ctx: Context, p: MoneySender.Pending, sim: String = "the default SIM", fromDialer: Boolean = false) {
        val app = ctx.applicationContext
        appCtx = app
        simText = sim
        if (!fromDialer) { stepNo = 0; logStep("dialled ${p.ussd} for ${MoneySteps.label(p.network)} on $sim") }
        TaskLog.owned("money")?.setState("Waiting for the network menu")
        main.post {
            val now = System.currentTimeMillis()
            cur = p; list = rules(p, MoneySteps.networkForNumber(app, p.number) == "airtel"); used.clear()
            startedAt = now; lastProgressAt = now; lastDoneMsg = ""; unknownSince = 0L
            val g = ++gen
            main.postDelayed({ tick(app, g) }, FIRST_MS)
        }
    }

    private var stepNo = 0
    private var dialT0 = 0L
    private var pressedAt = 0L
    private var codeLogged = false

    /** One numbered line in the task feed, shared by the dialler steps and the menu steps. */
    private fun logStep(text: String) {
        stepNo++
        val said = "Step $stepNo: $text"
        log(TaskLine.Kind.STEP, said)
        TaskLog.owned("money")?.setState(said)
    }

    /**
     * Does what a person does, one visible step at a time: opens the phone dialer with the code typed in,
     * presses Call (choosing the SIM if the phone asks), and only then the menu steps follow.
     * If the dialer's Call button cannot be found, the code is dialled directly instead ([direct]).
     */
    fun dial(ctx: Context, p: MoneySender.Pending, chosen: CallSim.Choice, sim: String, direct: Intent) {
        val app = ctx.applicationContext
        main.post {
            appCtx = app; simText = sim
            cur = p; stepNo = 0; pressedAt = 0L; codeLogged = false
            val now = System.currentTimeMillis()
            dialT0 = now; startedAt = now; lastProgressAt = now
            val g = ++gen
            val open = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.ussd))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            chosen.handle?.let { open.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            if (!app.safeStart(open)) { dialDirect(app, g, p, direct, "I could not open the phone dialer"); return@post }
            logStep("opened the phone dialer")
            main.postDelayed({ dialTick(app, g, p, chosen, direct) }, POLL_MS)
        }
    }

    private fun dialTick(app: Context, g: Int, p: MoneySender.Pending, chosen: CallSim.Choice, direct: Intent) {
        if (g != gen || cur == null) return
        val now = System.currentTimeMillis()
        val svc = IosControlService.instance
            ?: run { finish(app, "Stopped: turn on NEO PLUS iOS Controls in Settings > Accessibility."); return }
        val root = svc.rootInActiveWindow
        val inOtherApp = root != null && root.packageName?.toString() != app.packageName
        if (pressedAt == 0L) {
            if (inOtherApp && root != null && shows(root, p.ussd)) {
                if (!codeLogged) { codeLogged = true; logStep("typed ${p.ussd} in the dialer") }
                val btn = callButton(root, chosen.slot)
                if (btn != null && pressNode(btn)) {
                    pressedAt = now
                    logStep("pressed Call" + (chosen.slot?.let { " on SIM $it" } ?: ""))
                    main.postDelayed({ dialTick(app, g, p, chosen, direct) }, POLL_MS)
                    return
                }
            }
            if (now - dialT0 > 5_000L) { dialDirect(app, g, p, direct, "I could not press Call in your phone dialer"); return }
        } else if (inOtherApp && root != null) {
            val menuOpen = nodesOf(root).any { it.className?.toString()?.contains("EditText") == true }
            if (menuOpen) { begin(app, p, simText, fromDialer = true); return }
            val opt = simOption(root, chosen.slot, MoneySteps.carrierKey(p.network))
            if (opt != null && pressNode(opt)) { logStep("chose the SIM in the phone's SIM picker"); begin(app, p, simText, fromDialer = true); return }
            if (now - pressedAt > 800L) { begin(app, p, simText, fromDialer = true); return }
        } else if (now - pressedAt > 800L) { begin(app, p, simText, fromDialer = true); return }
        main.postDelayed({ dialTick(app, g, p, chosen, direct) }, POLL_MS)
    }

    private fun dialDirect(app: Context, g: Int, p: MoneySender.Pending, direct: Intent, why: String) {
        if (g != gen) return
        log(TaskLine.Kind.WARN, "$why, so I am dialling the code directly instead.")
        if (!app.safeStart(direct)) { finish(app, "Stopped: I could not dial the code. Nothing was sent."); return }
        logStep("dialled ${p.ussd} directly")
        begin(app, p, simText, fromDialer = true)
    }

    private fun nodesOf(root: AccessibilityNodeInfo, limit: Int = 400): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty() && out.size < limit) {
            val n = q.removeFirst(); out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return out
    }

    /** True when the dialer's number box really shows the code, so Call is never pressed on something else. */
    private fun shows(root: AccessibilityNodeInfo, code: String): Boolean =
        nodesOf(root).any { (it.text?.toString() ?: "").replace(" ", "").replace("-", "").contains(code) }

    /** The dialer's green Call button. Only exact labels count, so a contact's own "Call …" button is never pressed. */
    private fun callButton(root: AccessibilityNodeInfo, slot: Int?): AccessibilityNodeInfo? {
        val plain = Regex("(?i)^\\s*(call|dial|make a call|voice call|phone call)\\s*$")
        val simBtn = Regex("(?i)^\\s*(?:call|dial)(?:\\s+with)?\\s+sim\\s*0?([12])\\b.*")
        val ids = Regex("(?i)(dialpad_floating_action_button|call_button|dial_button|btn_dial|dial_btn)")
        var single: AccessibilityNodeInfo? = null
        var bySlot: AccessibilityNodeInfo? = null
        for (n in nodesOf(root)) {
            if (n.className?.toString()?.contains("EditText") == true) continue
            val label = (n.contentDescription?.toString() ?: n.text?.toString() ?: "").trim()
            val sm = simBtn.matchEntire(label)
            if (sm != null) { if (slot != null && sm.groupValues[1] == slot.toString()) bySlot = n; continue }
            if (plain.matches(label) || ids.containsMatchIn(n.viewIdResourceName ?: "")) { if (single == null) single = n }
        }
        return bySlot ?: single // two SIM buttons and no known SIM: nothing is guessed
    }

    /** An entry of the phone's "Call with…" SIM picker (a short label like "SIM 2" or the carrier's name). */
    private fun simOption(root: AccessibilityNodeInfo, slot: Int?, carrier: String): AccessibilityNodeInfo? {
        val all = nodesOf(root)
        if (all.any { it.className?.toString()?.contains("EditText") == true }) return null // that is the network's box, not a picker
        val slotRe = slot?.let { Regex("(?i)(^|\\W)sim\\s*0?$it\\b") }
        for (n in all) {
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (t.isEmpty() || t.length > 40) continue
            if ((slotRe != null && slotRe.containsMatchIn(t)) || t.lowercase().contains(carrier)) return n
        }
        return null
    }

    private fun pressNode(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        var i = 0
        while (x != null && i < 4) {
            if (x.isClickable && x.isEnabled) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x.parent; i++
        }
        return false
    }

    private fun finish(app: Context, text: String) {
        cur = null; gen++
        TaskLog.endOwned("money", text, false)
        Announcer.say(app, text)
    }

    private fun later(app: Context, g: Int, ms: Long) { main.postDelayed({ tick(app, g) }, ms) }

    private fun tick(app: Context, g: Int) {
        if (g != gen) return
        val p = cur ?: return
        val now = System.currentTimeMillis()
        if (now - startedAt > 120_000L) { finish(app, "Stopped: the network took too long. Nothing is sent until you enter your PIN."); return }
        val svc = IosControlService.instance
            ?: run { finish(app, "Stopped: turn on NEO PLUS iOS Controls in Settings > Accessibility."); return }
        val s = read(svc.rootInActiveWindow, app.packageName)
        if (s == null || s.msg.isEmpty()) { later(app, g, POLL_MS); return }

        if (s.msg == lastDoneMsg) { // answered already, waiting for the next screen
            if (now - lastProgressAt > 25_000L) finish(app, "Stopped: the network did not move on. The screen is yours.") else later(app, g, POLL_MS)
            return
        }
        if (pinRe.containsMatchIn(s.msg)) { handOver(app, p, s); return }

        val rule = list.firstOrNull { it.key !in used && it.screen.containsMatchIn(s.msg) }
        if (rule != null && used.size < MAX_ACTIONS) { unknownSince = 0L; act(app, g, p, rule, s); return }

        if (errorRe.containsMatchIn(s.msg)) {
            val refused = Regex("(?i)mmi|connection problem").containsMatchIn(s.msg)
            val hint = if (refused) " No menu appeared, so the network refused the code on $simText. Make sure that is the ${MoneySteps.label(p.network)} SIM; if not, say “my ${MoneySteps.label(p.network)} is on SIM 1” (or SIM 2)." else ""
            finish(app, "Stopped. The network says: ${s.msg.replace('\n', ' ').take(120)}$hint")
            return
        }
        if (unknownSince == 0L) unknownSince = now
        if (now - unknownSince > 4_000L) {
            finish(app, "Stopped: I did not recognise this screen, so I left it for you.")
            return
        }
        later(app, g, POLL_MS)
    }

    private fun act(app: Context, g: Int, p: MoneySender.Pending, rule: Rule, s: Screen) {
        val edit = s.edit
        if (edit == null || s.send == null || edit.isPassword) { finish(app, "Stopped: this screen has no normal input box, so I left it for you."); return }
        val answer = when {
            rule.menu != null -> optionNumber(s.msg, rule.menu.invoke(p))
            rule.input != null -> rule.input.invoke(p)
            else -> null
        }
        if (answer == null) { finish(app, "Stopped: I could not find the right option on this menu, so I left it for you."); return }
        if (!safeAnswer.matches(answer)) { finish(app, "Stopped: the answer for this step was not valid."); return }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, answer) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { finish(app, "Stopped: I could not type into this screen."); return }
        main.postDelayed({
            if (g != gen) return@postDelayed
            val svc = IosControlService.instance
            val s2 = if (svc != null) read(svc.rootInActiveWindow, app.packageName) else null
            // still the same screen, with exactly the answer typed in, before anything is pressed
            if (s2 == null || s2.msg != s.msg || s2.send == null || s2.edit?.text?.toString() != answer) {
                finish(app, "Stopped: the screen changed while I was typing, so I left it for you.")
                return@postDelayed
            }
            if (!s2.send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { finish(app, "Stopped: I could not press Send."); return@postDelayed }
            used += rule.key
            logStep(describe(app, rule.key, p, answer))
            lastDoneMsg = s.msg
            lastProgressAt = System.currentTimeMillis()
            later(app, g, AFTER_SEND_MS)
        }, TYPE_MS)
    }

    /** What the step just done looks like in words, for the progress line. */
    private fun describe(app: Context, key: String, p: MoneySender.Pending, answer: String): String = when (key) {
        "main" -> "chose Send Money"
        "how" -> "chose Enter Phone Number"
        "dest" -> "chose " + (if (MoneySteps.networkForNumber(app, p.number) == "airtel") "Airtel Money" else "Mpamba") + " as the recipient's wallet"
        "wallet" -> "chose Wallet"
        "to" -> "chose " + (if (p.withFee) "Airtel with withdrawal fee" else "Airtel number")
        "fee" -> "chose " + (if (p.withFee) "WITH" else "WITHOUT") + " cashout fee"
        "number" -> "typed the recipient's number (…${answer.takeLast(3)})"
        "amount" -> "typed the amount ${p.currency} $answer"
        else -> "done"
    }

    /** The PIN screen: check what it shows against what the user authorised, then leave it to the user. */
    private fun handOver(app: Context, p: MoneySender.Pending, s: Screen) {
        val feeAsked = "fee" in used || "to" in used
        cur = null; gen++
        val m = shownRe.find(s.msg)
        if (m == null) {
            val msg = "I could not read the amount and number on the PIN screen. Check them yourself before you enter your PIN."
            TaskLog.endOwned("money", msg, false)
            Announcer.say(app, msg)
            return
        }
        val amount = m.groupValues[1].replace(",", "").toBigDecimalOrNull()
        val wanted = p.amount.toBigDecimalOrNull()
        val amountOk = amount != null && wanted != null && amount.compareTo(wanted) == 0
        val shownNum = m.groupValues[2]
        val numberOk = shownNum.takeLast(9) == p.number.takeLast(9)
        if (!amountOk || !numberOk) {
            s.cancel?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val msg = "Stopped and cancelled. The PIN screen showed MK ${m.groupValues[1]} to ending ${shownNum.takeLast(3)}, which is not what you authorised."
            TaskLog.endOwned("money", msg, false)
            Announcer.say(app, msg)
            return
        }
        val name = nameRe.find(s.msg)?.groupValues?.get(1)?.trim().orEmpty()
        val charge = chargeRe.find(s.msg)?.groupValues?.get(1).orEmpty()
        val who = if (name.isNotEmpty()) name else p.name
        val fee = if (feeAsked) (if (p.withFee) ", with the cashout fee" else ", without the cashout fee")
                  else ". Note: the network did not ask about the cashout fee, so your choice was not applied"
        val ready = "Ready. Enter your PIN to send ${p.currency} ${p.amount} to $who$fee" +
            (if (charge.isNotEmpty()) ", service charge $charge" else "") + "."
        log(TaskLine.Kind.OK, "PIN screen reached: the amount and number match what you authorised")
        if (!feeAsked) log(TaskLine.Kind.WARN, "The network did not ask about the cashout fee, so your choice was not applied")
        // The user saved this network's PIN on purpose, tapped Authorise, and the screen shows exactly the authorised amount
        // and number (checked above): the phone types the PIN and presses Send once. Nothing is retried, so a wrong PIN
        // cannot lock the account.
        val pin = PinVault.moneyPin(app, p.network)
        val edit = s.edit
        val send = s.send
        if (pin != null && edit != null && send != null) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin) }
            if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                main.postDelayed({
                    val svc = IosControlService.instance
                    val s2 = if (svc != null) read(svc.rootInActiveWindow, app.packageName) else null
                    val send2 = s2?.send
                    val same = s2 != null && s2.msg == s.msg && send2 != null
                    val pressed = same && send2!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    val msg = if (pressed) "I entered your PIN and pressed Send: ${p.currency} ${p.amount} to $who$fee. The network will confirm by message."
                        else "I typed your PIN but the screen changed, so I did not press Send. The screen is yours."
                    TaskLog.endOwned("money", msg, pressed)
                    Announcer.say(app, msg)
                }, TYPE_MS)
                return
            }
        }
        TaskLog.endOwned("money", ready, true)
        Announcer.say(app, ready)
    }

    /** The visible text of a USSD pop-up (without its input box and buttons) plus the input box and the Send / Cancel buttons. */
    private fun read(root: AccessibilityNodeInfo?, ownPkg: String): Screen? {
        if (root == null) return null
        if (root.packageName?.toString() == ownPkg) return null
        val texts = ArrayList<String>()
        var edit: AccessibilityNodeInfo? = null
        var send: AccessibilityNodeInfo? = null
        var cancel: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 12) return
            val cls = n.className?.toString().orEmpty()
            val t = n.text?.toString()?.trim().orEmpty()
            when {
                cls.endsWith("EditText") -> if (edit == null) edit = n
                cls.endsWith("Button") -> {
                    if (t.equals("send", true) || t.equals("ok", true)) { if (send == null) send = n }
                    else if (t.equals("cancel", true)) { if (cancel == null) cancel = n }
                }
                t.isNotEmpty() -> texts += t
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        if (edit == null && send == null) return null // not a USSD pop-up
        return Screen(texts.joinToString("\n").trim(), edit, send, cancel)
    }
}
