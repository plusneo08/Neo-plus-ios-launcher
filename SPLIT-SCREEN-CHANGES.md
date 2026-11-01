# No Shizuku, no root
REMOVED  Shizuku library + provider + permission (build.gradle.kts, AndroidManifest.xml), RootSetup.kt, ShizukuSetup
CHANGED  Shell.kt        now a no-op (never runs a command)
CHANGED  EnableAssistant "Enable" walks the normal screens, then asks Android for the Home role (createRequestRoleIntent ROLE_HOME / Home settings)
CHANGED  MainActivity    asks once after install to become the default Home app

# Parallel tasks
The split screen (task tiles, split view, SplitTasks.kt) was removed. Tasks still run in parallel (up to 9, each with its own feed);
steps show in the assistant chat's task view and the HUD overlay. Stop / continue by number still works ("stop task 3", "task 2 continue", "status").

# Contacts, home widgets, chat notifications (new)
NEW      ContactEditor.kt   in-app contact editor: name, number, email, save to Phone / SIM 1 / SIM 2 / each email account
CHANGED  ContactEdit.kt     the dialog opens the in-app editor instead of the system contacts app
CHANGED  HomePanel.kt       new layout: clock card (day bar, chips, seconds ring) on top; weather tile + footsteps ring tile below
NEW      NotifChat.kt       pop-up reply card on incoming chat notifications, full chat screen, typing watcher
CHANGED  Notifications.kt   hook + "Open chat" and typing text in the Notification Centre
CHANGED  AndroidManifest.xml  WRITE_CONTACTS, GET_ACCOUNTS, NotifChatActivity
