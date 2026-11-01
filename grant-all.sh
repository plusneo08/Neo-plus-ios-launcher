#!/bin/sh
# One-shot setup for NEO PLUS iOS Launcher. Run on a computer with the phone connected (USB debugging on):
#   sh grant-all.sh            -> grants every permission, enables services, sets NEO PLUS as default home
#   sh grant-all.sh --kiosk    -> (implies --gesture) also makes it device owner: Android's status + navigation bars are hidden in EVERY app
#                                 (phone must have no Google/other accounts signed in while this runs; re-add them after)
#   sh grant-all.sh --gesture  -> also switches Android to gesture navigation (thinnest system nav bar)
P=com.neo.plus.iosluancher
A="adb shell"
for perm in CAMERA READ_CONTACTS CALL_PHONE SEND_SMS READ_SMS READ_CALL_LOG READ_PHONE_STATE ANSWER_PHONE_CALLS RECEIVE_SMS READ_CALENDAR RECORD_AUDIO \
            ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION POST_NOTIFICATIONS READ_MEDIA_AUDIO \
            READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE BLUETOOTH_CONNECT; do
  $A pm grant $P android.permission.$perm 2>/dev/null
done
$A pm grant $P android.permission.WRITE_SECURE_SETTINGS
$A appops set $P MANAGE_EXTERNAL_STORAGE allow
$A appops set $P WRITE_SETTINGS allow
$A appops set $P SYSTEM_ALERT_WINDOW allow
$A appops set $P ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
$A appops set $P SCHEDULE_EXACT_ALARM allow 2>/dev/null
# never let Android take the permissions back ("remove permissions if app isn't used") and never put the app to sleep
$A appops set $P AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null
$A cmd deviceidle whitelist +$P 2>/dev/null
$A cmd notification allow_listener $P/$P.NeoNotificationService
$A cmd notification allow_dnd $P
$A dpm set-active-admin $P/$P.NeoDeviceAdminReceiver
# accessibility service (keeps any already-enabled services)
CUR=$($A settings get secure enabled_accessibility_services | tr -d '\r')
SVC=$P/$P.IosControlService
case "$CUR" in *"$SVC"*) ;; null|"") $A settings put secure enabled_accessibility_services $SVC ;; *) $A settings put secure enabled_accessibility_services "$CUR:$SVC" ;; esac
$A settings put secure accessibility_enabled 1
# default home app
$A cmd role add-role-holder android.app.role.HOME $P 2>/dev/null
GESTURE=0; KIOSK=0
for arg in "$@"; do
  case "$arg" in --gesture) GESTURE=1 ;; --kiosk) KIOSK=1; GESTURE=1 ;; esac
done
# gesture navigation = no Back/Home/Recents buttons, so Android's navigation bar shrinks to a thin line (kiosk needs it)
[ $GESTURE = 1 ] && $A cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
if [ $KIOSK = 1 ]; then
  if $A dpm set-device-owner $P/$P.NeoDeviceAdminReceiver | grep -qi "success"; then
    echo "Full-phone launcher mode is ON."
  else
    echo "Could not set device owner. Remove every account (Google, Samsung...) in Settings > Accounts, run again, then re-add them."
  fi
fi
echo "Done. Press Home on the phone."
