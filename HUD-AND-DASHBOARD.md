# Dashboard + live HUD
- Swipe left on the Neo Plus Assistant chat -> NEO PLUS command center (NeoDashboard.kt). Live data: tasks, feed, battery, RAM, storage, connection.
- HudOverlay.kt: sci-fi HUD over any app while tasks run or the assistant opens/does things. Real steps from TaskLog + HudState.
- Turn the HUD off: SharedPreferences neo_assistant, key "hud" = false.
- Needs "Display over other apps" (or Neo Plus accessibility controls on).
