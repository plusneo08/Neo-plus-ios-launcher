## Neo lock: PIN, password or pattern, replacing Android's lock (new)
- Assistant settings > Lock > "Neo lock": choose PIN (4 or 6 digits), Password or Pattern. Neo Plus keeps only a salted hash. Every 5 wrong tries add a wait (30 s, 60 s ... up to 30 min), and the count survives restarts. The same prompt also opens locked apps and confirms changes to the lock settings.
- "Replace Android's lock with the Neo lock" (device owner only): Android's lock screen is switched off (DevicePolicyManager.setKeyguardDisabled) and the clock screen + Neo PIN pad / password / pattern is the phone's lock. It closes when the screen turns off and after every restart. While it is up the home button and notification shade are off. Wake-up and approval calls still appear above it.
- Limits, by design of Android: setKeyguardDisabled only works when Android's own lock is removed first; fingerprint and face unlock exist only together with an Android lock, so they are NOT available while the Neo lock replaces it (the fingerprint button appears only if the phone reports an enrolled fingerprint or face). With the replacement OFF, the clock screen opens Android's real unlock, which takes every type including fingerprint and face.
- Weak points to know: files are not protected by the Neo PIN at the phone's deepest level; safe mode skips the Neo lock; a forgotten Neo PIN has no reset from the phone and may need a factory reset. No Emergency call button is offered on the lock.

## Wake-up call: summary ready before you pick up (new)
- Sleep mode now prepares the night summary 5 minutes before the wake-up time: it goes through the night log, writes the summary and makes the assistant's voice for it. When you answer, it speaks at once instead of searching first.
- If something new arrives after that (a message in the last minutes), the summary and voice are redone in the background, so what you hear is always complete.
- If the preparation could not run (phone off, alarm missed), answering the call builds the summary then, exactly as before. Needs the exact-alarm permission already used for the wake-up call; without it Android may run the preparation a little late.

## iOS-style widget gallery and edit mode (new)
- Today View → "＋" opens a bottom sheet like iOS: search box, the apps that have widgets (with their icons), then a swipeable preview of each widget with its size (Small / Medium / Large) and a blue "Add Widget" button.
- Today View → Edit: widgets wiggle and added widgets get the red "−" badge to remove them.

## iOS-style lock screen (new)
- Assistant settings → Lock → "Show the Neo Plus lock screen". When the screen turns on it shows a big clock, the date and how many notifications wait (never their text). Swipe up: Android opens its OWN unlock (fingerprint, face, PIN, pattern or password, whatever the phone uses). Neo Plus never checks them, so every lock type works and none can be bypassed. Needs the NEO PLUS iOS Controls service on and "Display over other apps".
- Android does not let an app replace the real lock screen: this sits in front of it, and Android's lock is still behind it.

## Money: steps checked against screen recordings (v3.11.14)
- Airtel Money now handles the extra "1.Enter Phone Number / 2.Favourites" screen and the "Enter Phone Number:" prompt (it used to stop there).
- TNM Mpamba now also works when the recipient is on Airtel Money: it picks "Airtel Money" on the "Please Select" menu and accepts "Enter Airtel Number:".
- A transfer is now a **task**, like the website and Excel tasks: it opens the same live task feed from the moment you ask ("Send MK 5000 to …", "Waiting for you: tap Authorise"), then writes every step as it is done ("Step 1: chose Send Money", "Step 4: typed the amount MK 5000"…). While the network's pop-ups are on screen the latest steps are also drawn over them (needs "Display over other apps"), with **Hide** and **Stop** buttons. Stop ends the automation at once and leaves the screen to you.
- The feed (and the overlay) end at the PIN screen, so nothing covers it while you type your PIN. Cancel / expiry / any stop writes why into the feed. A running website task's feed is never replaced by a transfer.
- **It now does the steps a person does, one by one, and you see each in the feed:** Step 1 opened the phone dialer, Step 2 typed *211# in the dialer, Step 3 pressed Call (on SIM x; if the phone shows a SIM picker it taps the right SIM), then Step 4 onward = the menus (Send Money, the number, the amount…) until the PIN screen. Needs NEO PLUS iOS Controls on. It presses Call only when the dialer's box really shows the code, and only a button labelled exactly Call/Dial (never a contact's own Call button). If the Call button can't be found within 5 s it dials the code directly instead and says so. The USSD session only starts when Call is pressed, so these extra steps do not use up its seconds.
- **Faster steps:** USSD sessions last only seconds, so the waits between steps were cut: first look 0.4 s after dialling (was 1.2 s), pop-up check every 0.15 s (was 0.35 s), 0.1 s between typing and Send (was 0.3 s), 0.15 s after Send (was 0.7 s). About a second saved on every step.
- **Right SIM:** the SIM is now chosen by its carrier name (a SIM named Airtel for Airtel Money, TNM for Mpamba) when exactly one matches, then by what you saved. The feed says which SIM it dialled on. If the phone's own "Connection problem or invalid MMI code" appears before any menu, the feed explains that the network refused the code on that SIM. Needs the Phone permission.
- Not recorded yet, so not automated: Airtel to a TNM number, and any screens after "Airtel with withdrawal fee" if they differ.

## Hidden wake standby (v3.11.13)
- In **WAKE** mode the floating assistant now stays **invisible** while it waits: no logo, no ring, no subtitles. It appears only when you say “Hey Neo” or 147 (or when it is thinking / speaking / waiting for your answer) and hides again a few seconds after it finishes. In **TAP** mode the logo stays visible as before.
- The standby notification is as quiet as Android allows (lowest importance, hidden on the lock screen) and says “Neo Plus is waiting”. Android requires it while the microphone service runs, and it also always shows its own microphone indicator (green dot) while any app uses the mic: apps cannot hide either.
- The recogniser's start / stop beeps are muted for a moment on every cycle (notification and system sounds only, media keeps playing).
- Keep the logo visible in WAKE mode: ask the assistant `listen {"on":true,"hidden":false}`; hide it again with `{"hidden":true}`. To get back to the WAKE / TAP badge while hidden, say “Hey Neo” and tap the badge while the logo is showing.
- The orb has to be started once (“start listening”, which needs Display over other apps + microphone). Android does not allow a microphone service to start by itself after a reboot, so start it again after restarting the phone.

## Money: automatic menu steps (v3.11.12)
- Say e.g. “send 5000 to 0992101284 with fee” or “… without cashout fee”. The **number decides the network** (099 / 098 = Airtel Money, 088 = TNM Mpamba; change with `money_setup {"network":"mpamba","prefixes":"88,84"}`), and you must say **with or without** the cashout (withdrawal) fee: if you don't, the assistant asks. The Authorise screen shows the network, the fee choice, the recipient and the amount.
- After you tap **Authorise** the phone opens the network's menu (default codes: Airtel Money `*211#`, TNM Mpamba `*444#`), reads each pop-up, types the menu option (found by its words, e.g. “Send Money”, “Without Cashout Fees”, “Airtel with withdrawal fee”), the recipient number and the amount, presses Send each time, **and stops at the PIN screen**. You type the PIN yourself.
- It only types into a screen it recognises, never into a password-style box; each kind of screen is used once; anything unexpected (an error, a new menu) ends the automation and leaves the screen to you. If the network never asked about the cashout fee, it tells you your choice was not applied.
- On the PIN screen it checks that the amount and number shown are the ones you authorised. If not, it presses Cancel and tells you. If they match, it says who gets the money (and the service charge when shown).
- Save once: `money_setup {"network":"airtel","sim":2}` (say “my Airtel Money is on SIM 2”). Add `"code":"*123#"` if your start code differs.
- Mobile number portability can make a prefix wrong, so check the network on the Authorise screen; you can always say “on Mpamba” / “on Airtel money” to override. Needs the “NEO PLUS iOS Controls” accessibility service. Same-network transfers only. Try a very small amount first.

## Faster voice (v3.11.10)
- The first words of a spoken reply now start sooner: the first piece is shorter (a long first sentence is cut at its first comma), it is made with the lighter voice model first, and if the cloud voice has not produced audio after **5 s** (was 12 s) the phone's own voice takes over.
- When the floating assistant starts, the connection to Google's voice is opened ahead of time and "Yes?" / "Okay." are made and cached, so the answer to the wake phrase plays at once.

## v3.11.2
- **Notifications as a card.** “Read my notifications” now shows a glass card list (coloured app badge, app, time, title, text) and the only text under it is one line, e.g. “8 notifications received”. The AI no longer repeats the notifications.
- **Gmail send is more reliable.** The assistant now waits for Gmail’s Send button to switch on (while the attachment is added), finds it by id, name or any button called “send”, presses “Send anyway” if Gmail asks, touches the button on screen if a normal press is refused, and waits up to 45 s (was 20 s).
- **Messages.** “Did anyone send any message”, “has anybody texted me”, “who messaged me” now read your recent chats directly, and the AI is told to use the message-reading actions instead of saying it cannot see messages.
- **Watch page is full screen.** With “Watch page” on, the website fills the whole screen and the task steps are drawn over it on a transparent background (the last 7 steps, status and row count, plus Hide page and Stop). Toggling Watch page now shows / hides the page within half a second. The steps step aside while the task waits for you (CAPTCHA / code) so nothing covers the page.
- **Passwords in phone apps.** The `ui_type` action accepts `{{username}}` and `{{password}}` (with `"account":"google.com"` or the app name): the phone types the saved login from Assistant settings → Websites into the app’s own login or password box (also the password box of Google’s “verify it’s you” screens). The AI never sees the real value, nothing is pasted through the clipboard, and it works only for logins that are switched on. Do not type passwords in the chat: save them in Websites.
- **CAPTCHA hand-off is faster.** You still tick the box and do any picture challenge yourself; the app never clicks it. The page now scrolls the CAPTCHA to the middle and is taller, and when the page itself reports the CAPTCHA as solved the task continues by itself (Continue still works).

## v3.11.0 — far fewer Gemini requests, and a full task view
- **Batches replay offline.** In `web_batch`, Gemini does the first row and the app records what it did (which button, which field, what was typed, with the row’s cell values turned into placeholders). Rows 2 to N then **replay those steps on the phone with no Gemini request**. Every replayed step first checks it is still on the same page (host + path) and that its element is found unambiguously (tag, type, label / placeholder / link path and a stable id or name must all match). If a step does not match, fails, or the file the row should produce does not arrive, the replay stops and **Gemini continues from the page it is on** (told which steps are already done), and the new steps become the recording for the next rows. Batches that collect data into Excel are not replayed, and neither is anything the app cannot recognise reliably: those rows go through Gemini as before. A batch of hundreds of rows drops from thousands of requests to a handful.
- **Flash first.** Website tasks start on Gemini 3.8 Flash. Gemini 4 Argon takes over only after **two failed steps in a row** (and for the retry of a row that failed). The chat no longer jumps to Argon just because a message mentions a website, a download or Excel, and the troubleshooting notes written after a failure use Flash.
- **Less sent per step.** The last **3** pages of history (was 7), older pages shortened to **500** characters (was 1,500), at most **60** elements per page (was 150; links capped at 25 and pictures at 8 so form fields always fit), page text 1,500 characters (was 2,200). Chat history sent is the last 14 messages (was 24).
- **Login without Gemini.** On a page with exactly one password field the phone types the saved username and password and presses the login button itself (3 to 5 steps saved per task). It is tried at most twice per task, never on forms with two password fields (sign-up / change password), and the AI still never sees the password. In a batch, login steps are optional on replay (skipped when already logged in).
- **Daily limit and counter.** Assistant settings → “Daily limit for website tasks” (default 300, 0 = no limit) with “used today / steps saved by offline replay” and Reset. When the limit is reached website tasks stop and say so (a batch tells you the Excel row to restart from). Only website-task requests count; offline steps do not.
- **Full task view.** A running task opens full screen as a chat: your request, then every step, result, fix and problem as readable bubbles, the progress bar, the request counter, **Continue** (when it is waiting for you), **Stop**, **Watch page** and **Minimise** (back to the small panel; “Full view” reopens it). The chat behind it is blurred so only the task page is clear.
- **Clear pages over a transparent screen.** Settings and the websites page are now solid dark cards, and everything behind them is blurred (Android 12+; older phones get a darker cover instead).

## v3.10.0
- **CAPTCHAs and codes are now a real hand-off, not a stop.** The assistant still never tries to solve or bypass a CAPTCHA. When a website task (`web_task` or `web_batch`) meets a CAPTCHA, a code sent to you or a confirmation, it now **pauses with the page left open**:
  - the page appears on screen and becomes **touchable** (and can take the keyboard for a code), with a banner above it: **“Solve the CAPTCHA, then tap Continue”**, a **Continue** button, a **Cancel** button and the time left;
  - you do the step yourself, then tap **Continue** (banner or the task panel) or say / type **“continue”** (also “done”, “I solved it”, “carry on”); the task goes on **from the same page**, keeps the steps already done, and in an Excel batch **stays on the same row** (no restart);
  - **Cancel** (or “cancel” / “stop”) ends the task; if you do not answer within **5 minutes** it stops by itself (the 5 minutes do not count against the task’s own time limit). A batch that times out tells you which Excel row to restart from (`from_row`).
  - Needs “Display over other apps” for NEO PLUS (same permission as “Watch page”). Without it the task stops as before and says what to allow.
- New action `continue_web_task`; typed or spoken “continue” / “cancel” now get through while a task is running.

## v3.9.6
- **Offline model removed**: the Gemma / MediaPipe offline chat model (settings section, switch, code and library) is gone. Only phone commands that run on the phone (open apps, flashlight, Wi-Fi, etc.) and Gemini remain.
- **No limit on saved keys**: the old cap of 30 is gone; add as many Gemini keys as you like.
- **Top bar**: the launcher now draws an Android-style status bar (clock left; signal, data type, Wi-Fi and battery with % right) over a soft fade, 12/24 h clock follows the phone. Android's own top bar stays hidden but its notification shade / quick settings can be opened: swipe down from the top edge, or swipe down on the launcher bar (left half = notifications, right half = quick settings). The launcher no longer closes Android panels and no longer uses the top edge for its own panels (Control Center / notifications are still opened from the bottom). Device-owner kiosk now also allows the notification shade.
- **Voice**: female and male each have their own Gemini voices (female: Aoede, Kore; male: Charon, Orus). A failed voice retries with another voice of the same gender, waits longer (12 s) before using the phone voice, and the phone-voice fallback never picks a voice of the other gender.
- **Invalid / expired key now skipped**: Google reports a bad key as HTTP 400 "API key not valid"; that is now treated like a rejected key, so the next saved key takes over instead of the chat failing.
- **Multiple Gemini API keys (as many as you like)**: assistant settings (gear icon) now lets you add any number of Gemini keys (“+ Add another key”, “Remove” on each). Neo Plus uses **one key at a time**. When Google answers that a key has reached its quota / rate limit (HTTP 429), that key is rested and the next key takes over, and the same request is repeated on it, so the task carries on without you noticing. A rested key is used again when its limit has reset (the delay Google gives, or midnight Pacific time for a daily limit). A key Google rejects is skipped. It works the same for chat, phone control, Excel and website tasks, and the natural voice. Each key shows its status (In use / Ready / Limit reached · back in …). Limits are tracked per model, so a key that is out of Gemini 4 Argon still serves Gemini 3.8 Flash. Existing single keys keep working. Keys from the same Google Cloud project share one quota: use keys from different projects or accounts.

## v3.9.0
- **Post to social apps by command, fully offline**: Facebook, Facebook Lite, TikTok, WhatsApp Status (and WhatsApp Business), Instagram (post, reel, story) and any other app that accepts shared text, photos or videos. Examples: “post good morning everyone on Facebook”, “post hello on Facebook Lite”, “upload my latest video to TikTok with caption dance challenge”, “post my latest photo on my WhatsApp status”, “set my WhatsApp status to busy at work”, “add my latest photo to my Instagram story”, “post my latest photo on Instagram saying beautiful day”, “share this on Telegram” (with a file attached by the paperclip, say “post this on Facebook”). The phone opens the app with the content and presses Post / Share / Send through the accessibility service. It only says “Posted” after the button was really pressed and the screen moved on; otherwise it says nothing was posted and why. Instagram and TikTok need a photo or video. “Which apps can you post to?” lists what is installed.
- **Control any app by command**: “tap Post”, “click Send”, “in WhatsApp tap New chat, then type hello”, “type hello world and send”, “search for cats in YouTube”, “scroll down 3 times”, “swipe up”, “next video”, “like this post”, “follow”, “comment saying nice one”, “go back”, “what can I tap”, “read the screen”. Buttons are found by the words written on them, so it works in every app. Say “in <app> …” to open the app first, or use the floating assistant while the app is open.
- **No Gemini quota for phone commands**: all of the above (and calls, texts, WhatsApp, notifications) are understood by the phone itself before the AI model is ever asked. Replies to phone commands are now spoken with the phone's own voice (the Gemini voice is only used for replies the AI model wrote), and the speech recognisers prefer the offline language pack when one is installed (Settings > System > Languages > Voice typing / Google > Offline speech recognition).
- **One-time step after updating**: the accessibility service gained two abilities (swipe gestures and seeing every on-screen element). Switch “NEO PLUS iOS Controls” off and on once in Accessibility so Android applies them.
- Apps change their screens often. If one stops working, say “what can I tap” inside it to see the button names the phone can see.

## v3.8.5
- **Voice**: the voice model no longer reads out its own instructions (“speak in a natural human voice…” came from the request text, which is now only the words to speak). The audio is found in any part of Google's reply, the connection waits at most 6 seconds, the hissy tail after the last word (the “radio” ending) is cut and faded, and every reply (also quick commands and announcements) uses the natural voice. If the first words aren't ready after 6 seconds the phone's own voice takes over. The first piece is short and the rest are made while it plays; repeated sentences are cached. With “read replies aloud” on, the voice starts at the same moment the reply text appears.
- **Attach files like a card**: the paperclip now picks one or more files (photos, songs, videos, documents). Each shows as a card above the text box (type badge, name, spinner while it is copied, ✕ to remove). Write what to do with it and press send; sent files also show as cards in the chat.
- **Send files, photos and songs**: “send this to Mum on WhatsApp”, “send my latest photo to Joe on Instagram”, “send the song Despacito to Mary on WhatsApp Business saying enjoy”, “email this to bob@example.com”. New action `share_file` works with WhatsApp, WhatsApp Business, Messenger / Facebook, Instagram (direct message) and email (Gmail is sent for you). It finds attached files, files by name, or “latest photo / last song / latest screenshot / video birthday”. Choosing the contact and pressing Send is done through the accessibility service (switch on “NEO PLUS iOS Controls”); without it the app opens with the file ready and says it is NOT sent.
- **WhatsApp calls**: “call Mum on WhatsApp”, “video call John on WhatsApp Business” (new action `whatsapp_call`). Needs Contacts permission.

## v3.8.1
- **Give it any file**: the paperclip next to the text box attaches a file (it is copied to Downloads/NeoPlus and named in your message). New action `file_read` reads .xlsx, .csv, .tsv, .txt, .md, .json and .docx; the assistant then does what your task needs: edit or convert it with Excel actions, upload any file type (pictures and PDFs too) to an authorised website, or run a batch. `web_batch` now accepts .csv/.tsv as well as .xlsx and can also upload one named file on every row.

## v3.8.0
- **Excel → website batch** (`web_batch`): say e.g. “Open Codes.xlsx and make a barcode on mysite.com for every code, download each one.” The assistant reads the sheet (first row = column names) and repeats the website job for every row until the sheet is finished. Authorised websites only, same safety rules as `web_task`.
- **Finds and fixes problems**: before each row, cell damage (extra spaces, `123.0`, `1.2E+12`) is corrected automatically and logged. If the site rejects a row, it is retried once (with a corrected value if the page makes the fix obvious). Rows that still fail are explained in plain words with how to correct them, in `<your file> - results.xlsx` (Status, What happened, Problem, How to fix, Files) in Downloads/NeoPlus. If 3 rows in a row fail, it stops, because the cause is probably shared.
- **Live task panel**: a panel above the text box shows every step as it happens (page opened, what was clicked or typed, the result, auto-fixes in blue, problems in red), a progress bar with row counts, and **Stop**. **Watch page** draws the built-in browser on the top of the screen so you can see the website itself (needs “Display over other apps”).
- New browser action `save_image`: saves a generated picture / canvas / drawing (such as a barcode with no download button) to a file.
- Limits: a batch is up to 1000 rows or 3 hours; keep the phone awake and the launcher open while it runs. CAPTCHAs and one-time codes still stop the batch and are handed back to you.

## v3.7.0
- Models: the assistant now uses only **Gemini 3.8 Flash** (`gemini-3.8-flash`, chat and phone control) and **Gemini 4 Argon** (`gemini-4-argon`, Excel and website tasks). `gemini-2.5-flash` is removed everywhere, including the old saved model setting and the voice (now `gemini-3.8-flash-tts`). Argon needs a paid Gemini API key; if the key can't use it, Gemini 3.8 Flash does the task instead.
- Excel: new actions `excel_create`, `excel_edit`, `excel_read` make, change and read real .xlsx files (formulas, several sheets, bold header). Files go to Downloads/NeoPlus. Editing keeps values and formulas but not charts, images or colours, and the original is backed up first.
- Websites: new action `web_task`. Settings -> Authorised websites: add a site, save a login, switch it on. The assistant then drives a built-in browser on that site only: logs in, clicks, fills forms, uploads a named file, downloads files, collects data into Excel. Logins are encrypted with the Android Keystore and the AI never sees the password (it types {{password}} and the phone fills it in). Links to other sites are blocked, only files you named (or that the task itself made) can be uploaded, and "stop the website task" cancels it. CAPTCHAs and one-time codes are handed back to you.

## v3.6.0
- Gemini only: Claude and OpenRouter removed.
- Calls, SMS, WhatsApp / WhatsApp Business, Facebook / Messenger, email, notifications and call log are handled by the phone itself, never sent to the model.
- Launcher insets use the phone's real system bar sizes.

# NEO PLUS IOS LUANCHER

iPhone-style Android launcher built with Kotlin + Jetpack Compose.

## Features
- **Real home screen**: scans every installed app, real icons (adaptive icons rendered as iOS squircles), real launching, auto-updates on install/uninstall.
- **Translucent**: wallpaper-theme window + frosted glass panels; optional window blur on Android 12+.
- **Default apps**: Phone, Messages, Safari (your default browser) and Camera sit in the dock on a fresh install. Long-press any app > **Remove from Home Screen** to delete it from the launcher (it stays installed and stays in the App Library; long-press it there > **Add to Home Screen** / **Add to Dock** to bring it back). **Uninstall** is offered for apps you installed yourself.
- **Dock**: 4 pinned apps. Long-press any app: shortcuts, Add/Remove from Dock, Remove from Home Screen, App Info, Uninstall.
- **Today View** (swipe right): clock, battery, calendar, storage widgets + real Android widgets via AppWidgetHost ("+ Widget", "Edit" to remove).
- **App Library** (swipe past the last page): auto categories, Recently Added, search, tap a category to open it.
- **iOS status bar**: Android's status bar is hidden and replaced by an iPhone one: time, SIM signal bars + network type (5G / LTE / 3G), Wi-Fi, airplane icon, "No SIM", and battery with the percentage inside (green charging, yellow Low Power, red under 20%).
- **Control Center (the only control panel)**: swipe DOWN from the top-right, or UP from the bottom centre. Notification Centre: swipe down from the top-left, or UP from the bottom left/right. If Android's own shade is pulled down the launcher closes it again.
  Tiles: Airplane, Mobile data, Wi-Fi, Bluetooth, rotation lock, Focus, brightness/volume, flashlight, timer, calculator, camera, **built-in screen recorder**, notes, QR, dark mode, Low Power.
- **Music player in Control Center**: tap the app icon in the Now Playing card to choose any installed music app. Play then starts everything on that player (running session -> its media service -> "play from search" -> opens the app). Prev / next / pause control it afterwards.
- **Spotlight search** (swipe down anywhere, or tap the Search pill above the dock): apps + web.
- Home button returns to the first page; Back is swallowed like a real launcher.

## Look & speed
- NEO PLUS logo as the app icon (adaptive, dark background, Apple-style squircle on supported launchers) and as a badge on the clock widget.
- Apple-style continuous-corner (squircle) icons, flat clean icons with no drop shadows, app zooms out of the icon you tap.
- Faster startup: icons are rendered in parallel, sized to your screen, and package changes are debounced.

## Build
JDK 17 + Android SDK 35, then `gradle assembleDebug`. The GitHub Actions workflow builds the APK and uploads it as an artifact.

## Setup on the phone
1. Install the APK, press Home, choose **NEO PLUS** and **Always**.
2. Accept the permission prompts on first launch.
3. For brightness/rotation, Focus mode and all-files access, tap the matching tile once - Android opens the page where you flip the switch.

## Real toggles (no Settings screens)
Wi-Fi, Bluetooth, Airplane, Mobile data, Dark mode and Low power switch directly. Each tries the normal Android API, then hidden
system APIs, then root. The app targets Android 9 behaviour so Android still allows Wi-Fi/Bluetooth switching for it.
- Stock Android 13/14 blocks Airplane and Mobile data for normal apps. They work with **root**, or when the APK is installed as a
  **system priv-app** (custom ROM): copy the APK to `/system/priv-app/NeoPlusLauncher/` and `rom/privapp-permissions-com.neo.plus.iosluancher.xml` to `/system/etc/permissions/`.
- Low power / Dark mode also work after one adb grant: `adb shell pm grant com.neo.plus.iosluancher android.permission.WRITE_SECURE_SETTINGS`.
- Notification Centre and the Control Center "Now Playing" card need Notification access once (the panel shows a button for it).
- Screen recording is built in: tap the tile, accept Android's one-time capture prompt, tap again (or the notification) to stop. Videos go to Movies/NeoRecordings. No audio is recorded.
- Notes and QR scanner tiles open the matching app installed on your phone.
- If a switch is blocked, the matching Android settings page opens instead.

## One-time adb grants (no root, makes Dark mode / Low Power work)
```
adb shell pm grant com.neo.plus.iosluancher android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.neo.plus.iosluancher android.permission.READ_PHONE_STATE
```
Airplane mode and Mobile data still need root or the priv-app install on Android 10+ (Android does not allow them for any normal app).

## Icons
Phone, Messages, Camera, Chrome, Play Store and the voice Recorder are drawn as iPhone-style icons; every other app fills the iOS squircle. Icons are ~76 dp (iOS 26 size) with a glass sheen.

## iOS volume bar + no Android shade (Accessibility service)
Switch on **Settings > Accessibility > NEO PLUS iOS Controls** once (the launcher opens that page on first run).
- Volume buttons show an iOS vertical volume bar on the left and Android's volume panel is suppressed. It adjusts call, media (when playing) or ringer volume.
- If Android's notification shade / quick settings opens, it is closed immediately and the iOS Control Center opens instead.
The service does not read screen content.

## Home panel (v2.6)
The top of the first home page is a clean glass panel: big thin clock (follows your 12/24 h setting), weekday + date, and live weather (temperature, condition, high/low, place name) drawn with hand-made glyphs. Weather comes from Open-Meteo (no key needed) for the phone's last known location and refreshes every ~20 minutes; tap the weather to allow location if it was denied. Tap the time for alarms, the date for the calendar.

## Neo Plus Assistant (v2.7)
Tap the glowing orb next to Search. Say or type "Neo Plus Assistant, ..." (or just the command). (It also opens on the system assist / voice-command intent.)
- **Chat**: talks like Gemini or Siri through your own API key. Open the gear icon, pick **Gemini** or **Claude**, paste a key (aistudio.google.com/apikey or console.anthropic.com/settings/keys) and optionally change the model name. The key is stored on the phone only and sent only to that provider. History is kept (clear it with the bin icon). Mic button = voice input, speaker icon = read replies aloud.
- **Phone control**: open any app by name, call a contact or number (dials directly if the Phone permission is granted), prefill an SMS, set alarms and timers, flashlight, Wi-Fi / Bluetooth / Airplane / Mobile data / Dark mode / Low Power / Focus (same limits as the Control Center toggles), volume, brightness, media keys, web search, open links, navigate, open Settings pages, Control Center, and Home / Back / Recents / Lock / Screenshot (needs the NEO PLUS iOS Controls accessibility service).
- **Works without a key**: simple commands ("open Camera", "turn on the flashlight", "turn off Wi-Fi", "take a screenshot", "lock the screen") run instantly on the phone. Everything else goes to the AI model.
- Brightness needs "Modify system settings" once. Do Not Disturb needs its access page once. The assistant opens the right page if one is missing.

## v2.7 changes
- **Stays on NEO PLUS iOS Launcher**: with the accessibility service on, an iOS status bar (time + battery) is drawn over every app you open, so Android's own bar never shows. It hides in landscape so videos/games stay clean. If the stock Android home screen ever appears, you are sent straight back to NEO PLUS (Recents is left alone). No launcher layout was changed.
- **Neo Plus Assistant**: the assistant is renamed. Battery saver and dark mode are now also local voice/typed commands ("turn on battery saver", "dark mode off") that work even without an API key.
- **Device admin**: the first-run walkthrough now asks for device admin (lock-screen policy only; it cannot wipe data or change passwords). "Lock the screen" then locks instantly.
- **Direct Dark mode + Battery saver**: both flip without opening Settings, from Control Center and from the assistant. Android requires the WRITE_SECURE_SETTINGS permission for this and does not let device admin grant it, so grant it once:
  `adb shell pm grant com.neo.plus.iosluancher android.permission.WRITE_SECURE_SETTINGS`
  (on a PC, or on the phone with an adb app such as LADB). Rooted phones and device-owner installs get it automatically. Until then, tapping the switch shows a dialog with the command instead of opening Settings.

## Whole-phone launcher mode (v2.8)
Android does not let a normal app hide its own status bar / navigation bar inside other apps. Three layers get as close as possible:
1. **Always (accessibility on)**: the iOS status bar is drawn over the top of every app. Since v3.9.1 nothing is drawn over the bottom edge any more, so Android's own navigation bar / gesture area stays free and no app is covered.
2. **`sh grant-all.sh`** (computer + USB debugging): grants every permission, turns on the accessibility service, notification access, device admin, and sets NEO PLUS as default home in one go.
3. **`sh grant-all.sh --kiosk`**: makes NEO PLUS the device owner. Android then really hides its status and navigation bars in every app opened from the launcher, and auto-grants permissions to all apps. Say "turn off full-phone launcher mode" to the assistant to pause it, or "remove device owner" to give up device-owner status completely (do this before uninstalling). On a rooted phone, "turn on full-phone launcher mode" sets it up without a computer. `--kiosk` also switches to gesture navigation so no Android buttons remain. Needs a phone with no accounts signed in during setup. Add `--gesture` for gesture navigation.

## Setup without a computer
See `setup-on-phone.txt`: install the free LADB app, pair it with Wireless debugging (Android 11+), and paste the listed lines. It does everything `grant-all.sh` does, including full-phone mode.

## Enable assistant button (v3.1)
Open the assistant: if something is still off, an **Enable assistant** button appears above the text box. Each tap opens the next page you need: Display over other apps, then (Android 13+) App info > ⋮ > **Allow restricted settings**, then Accessibility > NEO PLUS iOS Controls, then Device admin. The button disappears once all are on. Android 13+ greys out Accessibility for apps installed from a file until "Allow restricted settings" is chosen; `grant-all.sh` does it for you.

## Rooted phones (v3.2)
NEO PLUS is built to run on a rooted phone. On first launch it asks for root once (Magisk / KernelSU prompt: allow) and then does everything itself: all permissions, WRITE_SECURE_SETTINGS (direct Dark mode / Battery saver), overlay + restricted settings, accessibility service, notification access, Do Not Disturb access, device admin, default home app, gesture navigation, and (if no accounts are signed in) device owner for full-phone launcher mode. The **Enable assistant** button also re-runs it. Without root the old adb / settings-page route still works.

## v3.4 additions: floating assistant, SMS delivery, notification reading, power off / unlock

**Floating assistant (orb).** Tap the wave icon in the assistant header, or say/type "start listening". A round NEO PLUS logo
floats above every app and the home screen, listens in the background and answers out loud. What it says appears as subtitles
directly under the logo. Needs "Display over other apps" and the microphone.
- ✕ on the logo removes it. "Goodbye" / "stop listening" or the Stop button in its notification also remove it.
- Tap the logo to mute / un-mute the microphone (tap while it talks to interrupt). Drag it to move it; the position is remembered.
- Grey ring = muted, green = listening, amber = thinking, blue = speaking.

**Text messages.** "Send a message to Mum saying I am on my way" sends the SMS for real (SEND_SMS permission) and then tells you
"sent", "delivered", or what went wrong. "Delivered" depends on the carrier sending delivery reports; if none arrives within
90 seconds you are told the message was sent but delivery is not confirmed.

**Notifications.** While the orb is running, a new notification makes it ask "New notification from X. Do you want me to read it?".
Say yes ("yes", "read it", "inde", "ndiyo") and it reads it, say no ("no", "skip", "ayi") and it moves on. Chichewa text is detected
automatically. Phones rarely have a Chichewa voice, so it is read with a Swahili voice if installed (understandable, not perfect).
To stop the questions without closing the orb, set `notif_ask` to false in the `neo_assistant` preferences.

**Power off.** "Turn off the phone". Order tried: root (Magisk `reboot -p`), system-app permission (SHUTDOWN / REBOOT, see `rom/`),
then the Android power menu (you tap Power off). Plain Android does not allow more than the power menu.

**Unlock.** "Unlock the phone" wakes the screen and shows the launcher over the lock screen. With no screen lock set the phone opens.
With a PIN / pattern / fingerprint, Android's own unlock prompt appears and you unlock it yourself. No app can type your PIN for you.

Speech recognition language follows the phone's language setting; Android's recogniser does not support Chichewa, so spoken
Chichewa is best-effort (typed Chichewa works in the chat panel).

## v3.5 changes
- Added the OpenRouter backend (alongside Claude and Gemini), chosen in the AI model settings.
- New assistant actions: `whatsapp`, `calendar` (read upcoming events), `weather` (here or any city, via Open-Meteo) and `lookup` (quick answers from Wikipedia, falling back to DuckDuckGo).
- The floating orb now speaks the result of calendar, weather and lookup requests.
- Live web search for all three AI backends (Gemini Google Search grounding, Claude web search tool, OpenRouter web plugin). If search isn't available for a key or model, it answers without it.
- Orb: two ways to talk, switched by the TAP/WAKE badge on the orb. TAP = tap the logo, then speak (mic is off otherwise). WAKE = say "Hey Neo" then your request (or tap the logo to talk right away).
- Follow-up actions: the assistant can fetch information first (lookup, calendar, weather, news, stock, sports, read_sms, call_log, screen) and then answer or act on it, up to 3 rounds.
- New actions: read_sms, call_log, screen (reads on-screen text via accessibility), news, stock, sports, remember, forget.
- Several things in one message: the assistant answers every question and does every action in one reply; simple multi-command sentences ("open Camera and turn on the torch") also work without an AI key.
- Voice: choose Female or Male in the AI model settings, with a "Hear this voice" button. The natural human-sounding voice uses Google's Gemini speech (needs a Gemini key; if your AI provider is not Gemini there is an optional Gemini key field). Without a key it uses the best installed phone voice of that gender.
- Messages: when a chat notification arrives the orb asks to read it; after reading it asks "Do you want to reply?", takes the reply by voice, reads it back and sends it into the same chat (WhatsApp, Messages and other apps with a built-in Reply) once confirmed. Also by voice any time: "read my messages", "reply to Mum saying I'm on my way".
- Screen sharing: the SEE badge on the orb (or "share my screen") sends a fresh screenshot with every request so the assistant can help with what is displayed; `look` takes a one-off screenshot. Needs the "NEO PLUS iOS Controls" accessibility service and Android 11+ for screenshots (older versions use on-screen text only). Sharing resets to off whenever the orb starts.

## v3.5.1 fix
- Multi-part questions ("who was the first president of Malawi and 2 + 2") are now answered part by part. The assistant answers general knowledge and maths itself, uses at most specific lookups, and never shows raw lookup text as the answer.

## v3.5.2 fix
- Live information: if web search fails for your key or model (quota, unsupported model), the assistant now says it could not check live information instead of silently answering from old knowledge. The prompt also tells it never to answer recent events from memory and to avoid Wikipedia lookups for anything current.

## v3.5.3: read ALL notifications
- Say or type "read my notifications" (works without an AI key) or "read my WhatsApp notifications": the assistant reads notifications from every app (email, news, system apps, chats) straight from the notification listener, without opening the notification panel. Recently dismissed ones are remembered too (last 100). New action: `read_notifications {count, app}`.
- Needs Notification access for NEO PLUS (the panel shows a button for it).

## v3.5.4: reply to any notification + last message
- Replies now work for every notification that has a Reply box (chat apps, email apps, etc.), not only chats: "reply to <name or app> saying ...". Notifications you can answer are marked [can reply] when read. If there is no Reply box, a text message is sent to the contact instead.
- "Check my last message" (also works without an AI key) reads the most recent text sent or received in Messages from the phone's SMS store. "Last message from Mum" gives the latest in that conversation, plus the last one in the other direction. Needs the SMS permission. Messages that exist only in RCS/chat features are not in the SMS store; those are reached through their notifications.

## v3.5.5: working without an AI key
- With no key, each part of a sentence is handled separately: phone commands, "read my last message", "read my notifications", maths ("what is 2 + 2" -> "2 + 2 is 4."), and short Wikipedia facts. If it cannot find a real answer it says so and suggests adding a free Gemini key, instead of pasting unrelated articles. Works in both the chat and the floating orb.

## v3.5.6: Google first
- Without an AI key, questions now go to Google first (answer box or top snippets, shown as "From Google: ..."), then Wikipedia, and it says so honestly if neither has an answer. Google can block automated requests, so this is best-effort; a free Gemini key gives real Google Search answers every time (Gemini's built-in Google Search).

## v3.5.7: search without opening Google
- The assistant's `web_search` action no longer opens the browser: it finds the answer on Google in the background and tells you. Opening a page happens only when you ask ("open google.com").

## v3.5.8: build fix
- Fixed the compile errors from the GitHub build: an extra closing brace in MainActivity.kt, Java classes (CallLog.Calls, Phone) used as values in Assistant.kt, and `return` inside expression-bodied functions (stock, sports).

## v3.5.9
- Fixed the last compile error (setSpeak name clash in AssistantViewModel).

## v3.9.1 changes
- **Bottom edge removed**: the iOS home bar overlay is gone; only the top status bar is drawn over other apps.
- **Dual SIM**: the status bar (home screen and over apps) shows one signal row per SIM, two stacked rows on a dual-SIM phone.
- **Volume up to 200%**: at full media volume, Volume Up keeps going (10% per press, orange bar + percentage) up to 200%; Volume Down lowers the boost first. The Control Center slider is now a 0-200% scale (left half = phone volume, right half = boost). Implemented as a +6 dB loudness gain on the audio mix and on apps that announce their audio session. Very loud: protect your hearing; some phones/apps ignore it.
- **Voice**: the hiss / radio sound at the end of the assistant's voice is cut, and the audio ends on silence.

## v3.9.2
- Reading messages: chats already in the notification shade are now picked up when the listener connects and on every "read my messages"; the listener asks Android to restart itself if it is stopped; clearer messages say whether access is off or there are simply no notifications to read.

## v3.9.3
- Voice: one voice only. With a Gemini key, every spoken answer (chat, phone commands, announcements) uses the chosen Gemini voice (male = Charon). The phone-voice fallback no longer picks female voices for "male" (the old check matched "female" as "male"), and uses a lower pitch when no male voice is installed.
- Voice: stronger removal of the hiss / radio tail at the end of the voice.
- Messages: say "read Gemini messages" to hear notifications from the Gemini app (needs notification access).

## v3.9.4: read messages inside the apps
Say "read WhatsApp", "check Messenger", "read my Instagram messages", "check TikTok inbox", "read Facebook", "check my email / Gmail", "read Google Messages", or add a name: "read WhatsApp from Mum". NEO PLUS reads the waiting notifications, then opens the app and reads what it shows (the chat list, or that chat) through the accessibility service, then returns Home. SMS is also read from the phone. Needs the accessibility service ("NEO PLUS iOS Controls") and you must be signed in to the app.

## v3.9.5: offline model
Assistant settings > Offline model > Choose model file: pick a Gemma 3 1B `.task` file (MediaPipe LLM format, e.g. Gemma3-1B-IT int4 from the litert-community page on Hugging Face). It is copied into the app, then used when the phone is offline, when there is no Gemini key, or always if you switch it on. GGUF files do not work with MediaPipe. Phone calls, messages, app control and message reading need no model at all. Voice: stricter cut of the hiss at the end (nothing is kept more than 140 ms after the last voiced sound).

- v3.11.7: volume buttons now control media volume inside other apps (ringer only on the home screen); saved 200% boost is reset when the accessibility service starts.

## Time lock (v3.11.8)
- Say or type **"lock my phone until 6:30"** or **"lock my phone for 2 hours"** (max 24 h). The command is enough: it starts at once (no confirmation) and there is no cancel afterwards.
- Until then a black "Phone locked" cover sits over everything (Settings too) and the screen re-locks every time it is woken or unlocked. The voice assistant can only do harmless things (alarm, timer, weather, news...).
- Emergency calls (button on the cover and on the Android lock screen) and incoming / ongoing calls still work. The end time survives a restart.
- Needs: device admin + the "NEO PLUS iOS Controls" accessibility service. On a device-owner phone, safe mode, factory reset and uninstalling are blocked while it runs.
- Limits: holding the power button can still switch the phone off (the lock returns a few seconds after it starts again), an alarm that rings during the lock can't be dismissed, and without device owner, safe mode / recovery are not blocked.

## Wake code "147" (v3.11.9)
- Type or say **147** (or "one four seven" / "one forty seven") to the assistant: the floating assistant starts with the microphone on and listens at once, no tap needed.
- While the floating assistant is in **WAKE** mode, saying "147" works like "Hey Neo": alone it starts listening, or add the request ("147 open Camera").
- A phone can only hear a spoken code while the microphone is already on, so a cold start by voice needs the floating assistant running in WAKE mode.


## Best photo + matched song (new)
Say: “Find the best pic on my phone and post it on my Instagram story with a good caption and the matching song.”
- **Best photo**: `BestPhoto.kt` scans recent camera photos (no screenshots / WhatsApp received / PNG), drops blurry, dark or burnt-out ones on the phone, then Gemini compares the best 12 as one numbered contact sheet. No Gemini key = best local score wins.
- **Caption + song**: `CaptionWriter.kt` writes the caption first, then picks a real song that matches that caption.
- **Story**: `StoryEditor` (in `SocialPost.kt`) taps Text (caption) and Stickers > Music (searches the song, picks the first match) on Instagram and Facebook stories. Each step is best effort and the reply says what was and was not added. Other posts keep the song in the caption text.
- You still get the OK card (Send / Skip) before anything is posted, and it pins the exact photo shown.
- Button labels (“Stickers”, “Music”, “Text”, “Done”) are matched by name, so an app update can break a step: edit the label lists in `StoryEditor`.
