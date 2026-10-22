package com.neo.plus.iosluancher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var current: MainActivity? = null
    }

    private val vm: LauncherViewModel by viewModels()
    private val assistantVm: AssistantViewModel by viewModels()
    private lateinit var widgets: WidgetController
    private var homeTick by mutableIntStateOf(0)
    private var ccTick by mutableIntStateOf(0)
    private var assistTick by mutableIntStateOf(0)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // runtime prompts done -> walk through the special-access screens one by one
            // onResume runs right after this and opens the first page
            getSharedPreferences("neo_launcher", MODE_PRIVATE).edit().putInt("special_step", 0).apply()
        }

    // Keeps the launcher in sync when apps are installed / removed / updated
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vm.reload(400)
            val app = applicationContext
            Thread { Kiosk.configure(app) }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        BeepMute.recover(this)
        current = this
        Kiosk.configure(this)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        if (intent?.getBooleanExtra("open_cc", false) == true) ccTick++
        if (isAssistIntent(intent)) assistTick++
        widgets = WidgetController(this)
        OrbBridge.apps = { vm.apps }
        if (intent?.getBooleanExtra("unlock", false) == true) unlockScreen()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        setContent {
            MaterialTheme {
                LauncherRoot(vm, assistantVm, widgets, homeTick, ccTick, assistTick, ::setBlur)
            }
        }
        askPermissionsOnce()
        val p0 = getSharedPreferences("neo_launcher", MODE_PRIVATE)
        if (!p0.getBoolean("root_setup_v1", false)) {
            // Rooted phone: grant everything and switch the services on automatically (one root prompt)
            val app = applicationContext
            Thread {
                if (RootSetup.applyAll(app)) p0.edit().putBoolean("root_setup_v1", true).apply()
                else SecureAccess.ensure(app)
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        widgets.start()
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        nextSpecialAccess()
        startKiosk()
    }

    /** Device-owner kiosk: hides Android's bars in every app opened from here. */
    fun startKiosk() {
        if (Kiosk.enabled(this) && Kiosk.permitted(this)) {
            try { startLockTask() } catch (e: Exception) { }
        }
    }

    fun stopKiosk() {
        try { stopLockTask() } catch (e: Exception) { }
    }

    /** The system asks the assistant to open (assist gesture / voice command on phones that route it to the launcher). */
    /**
     * Shows the launcher over the lock screen and asks Android to dismiss it. With no screen lock set the phone opens;
     * with a PIN / pattern / fingerprint Android shows its own unlock prompt for the user.
     */
    fun unlockScreen() {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km.isKeyguardLocked) km.requestDismissKeyguard(this, null)
        } catch (e: Exception) { }
    }

    private fun isAssistIntent(i: Intent?): Boolean =
        i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND || i?.action == "android.intent.action.SEARCH_LONG_PRESS"

    /** The launcher draws its own status bar, so Android's bar stays hidden; a swipe from the top edge still brings Android's shade / quick settings. */
    private fun hideStatusBar() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideStatusBar()
        }
    }

    /** One-time walk through the special access pages that cannot be granted with a normal prompt. */
    private fun nextSpecialAccess() {
        val prefs = getSharedPreferences("neo_launcher", MODE_PRIVATE)
        val step = prefs.getInt("special_step", -1)
        if (step == 3) {
            if (!prefs.getBoolean("acc_asked_v1", false)) {
                prefs.edit().putBoolean("acc_asked_v1", true).apply()
                val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.contains(packageName) == true
                if (!on) { safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
            }
            if (!prefs.getBoolean("admin_asked_v1", false)) {
                prefs.edit().putBoolean("admin_asked_v1", true).apply()
                if (!DeviceAdmin.isActive(this)) safeStart(DeviceAdmin.requestIntent(this))
            }
            return
        }
        if (step < 0 || step > 2) return
        prefs.edit().putInt("special_step", step + 1).apply()
        when (step) {
            0 -> if (!Settings.System.canWrite(this))
                safeStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                else nextSpecialAccess()
            1 -> if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
                safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                else nextSpecialAccess()
            2 -> {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted)
                    safeStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
    }

    override fun onStop() {
        widgets.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (current === this) current = null
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }

    // Home button pressed while the launcher is already open -> back to the first home page
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_cc", false)) ccTick++
        if (isAssistIntent(intent)) assistTick++
        if (intent.getBooleanExtra("unlock", false)) unlockScreen()
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) homeTick++
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        widgets.onActivityResult(requestCode, resultCode, data)
    }

    /** Blurs the wallpaper behind overlays on devices that support window blur (Android 12+). */
    private fun setBlur(radius: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val lp = window.attributes
            if (radius > 0) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            lp.blurBehindRadius = radius
            window.attributes = lp
        } catch (e: Exception) { }
    }

    private fun askPermissionsOnce() {
        val prefs = getSharedPreferences("neo_launcher", MODE_PRIVATE)
        if (prefs.getBoolean("perms_asked_v3", false)) return
        prefs.edit().putBoolean("perms_asked_v3", true).apply()
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,           // assistant sends texts and reports delivery
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,   // SIM signal + network type in the iOS status bar
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.READ_MEDIA_AUDIO
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT <= 28) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        permLauncher.launch(list.toTypedArray())
    }
}
