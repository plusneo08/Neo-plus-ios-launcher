# No Shizuku, no root
REMOVED  Shizuku library + provider + permission (build.gradle.kts, AndroidManifest.xml), RootSetup.kt, ShizukuSetup
CHANGED  Shell.kt        now a no-op (never runs a command)
CHANGED  EnableAssistant "Enable" walks the normal screens, then asks Android for the Home role (createRequestRoleIntent ROLE_HOME / Home settings)
CHANGED  MainActivity    asks once after install to become the default Home app

# Parallel tasks with split-screen tiles (replaces the old SplitTasks.kt panes)
REMOVED  SplitTasks.kt   (its "take turns on one screen" panes and voice commands are gone)
CHANGED  TaskLog.kt      one TaskFeed per task (up to 9) + tile grid: TaskTiles (home, under the weather card) and TaskSplitScreen (full screen)
CHANGED  WebTask.kt      every task has its own session, feed, cancel / continue flags and page picture; runAsync() starts tasks in the background;
                         each task sends only ITS OWN page picture to Gemini. Your backend / API mode is kept.
CHANGED  Assistant.kt    web_task / web_batch start in the background ("wait":true = old blocking behaviour); stop/continue take "task":N
CHANGED  OrbService.kt   waking the orb (Hey Neo / 147 / tap) while tasks run opens the split screen and keeps the mic on until "close tasks"
CHANGED  Home.kt, Money.kt, MoneySteps.kt, AssistantUi.kt   tiles under the weather card; transfers keep their own tile
Say: "task 2 continue", "stop task 3", "status", "close tasks".   NOT compiled here: send me the first build error.

# Contacts, home widgets, chat notifications (new)
NEW      ContactEditor.kt   in-app contact editor: name, number, email, save to Phone / SIM 1 / SIM 2 / each email account
CHANGED  ContactEdit.kt     the dialog opens the in-app editor instead of the system contacts app
CHANGED  HomePanel.kt       new layout: clock card (day bar, chips, seconds ring) on top; weather tile + footsteps ring tile below
NEW      NotifChat.kt       pop-up reply card on incoming chat notifications, full chat screen, typing watcher
CHANGED  Notifications.kt   hook + "Open chat" and typing text in the Notification Centre
CHANGED  AndroidManifest.xml  WRITE_CONTACTS, GET_ACCOUNTS, NotifChatActivity
