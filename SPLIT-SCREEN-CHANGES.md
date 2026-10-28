# Split-screen tasks (new)
NEW      SplitTasks.kt   one pane per task, grid re-divides on every new task (1 full, 2 stacked, 3 rows, 4 = 2x2, 7-9 = 3x3, 10+ keeps going)
CHANGED  OrbService.kt   voice hook before process(); raiseToFront() keeps the orb above the panes
CHANGED  Assistant.kt    typed commands ignored while split is on; app_task / web_task / web_batch take turns on the one real screen
CHANGED  AssistantUi.kt  mic paths marked voice = true, typed path stays false

Say (after "Hey Neo" / 147): "new task <request>", "also <request>", "close task 2", "close all tasks", "close finished tasks", "split screen on/off".
Orb starts by itself in hidden standby the first time a pane opens. NOT compiled here: send me the first build error.

# Contacts, home widgets, chat notifications (new)
NEW      ContactEditor.kt   in-app contact editor: name, number, email, save to Phone / SIM 1 / SIM 2 / each email account
CHANGED  ContactEdit.kt     the dialog opens the in-app editor instead of the system contacts app
CHANGED  HomePanel.kt       new layout: clock card (day bar, chips, seconds ring) on top; weather tile + footsteps ring tile below
NEW      NotifChat.kt       pop-up reply card on incoming chat notifications, full chat screen, typing watcher
CHANGED  Notifications.kt   hook + "Open chat" and typing text in the Notification Centre
CHANGED  AndroidManifest.xml  WRITE_CONTACTS, GET_ACCOUNTS, NotifChatActivity
