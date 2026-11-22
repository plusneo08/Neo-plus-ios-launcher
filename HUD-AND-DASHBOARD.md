# NEO PLUS live task HUD

- `HudOverlay.kt` renders the full-screen JARVIS-inspired NEO PLUS HUD while tasks are active.
- The main panel shows the currently focused task, its real status, progress (when the task reports a total), and the newest task-feed steps.
- When a new task starts, `TaskLog.begin()` focuses that new feed. The previously focused task remains active and appears in the minimized task rail.
- Up to five other task cards are shown in the rail at once. Each card shows its own title, state and progress when available.
- Tapping a minimized card changes only `TaskLog.focusId`. It does not stop, pause, restart or otherwise alter the task worker.
- The full-screen visual layer is click-through. Only the narrow task-card rail receives touch input, so the rest of the phone remains usable.
- The HUD polls at 250ms rather than the previous 50ms interval to reduce unnecessary redraw work.
- Device battery and connectivity are read from Android. Task steps and progress come from each task's own `TaskFeed`; the HUD does not invent task results.
- The HUD remains tied to the existing `TaskLog` and `TaskService`; hiding the HUD does not stop background work.
- Turn the HUD off: SharedPreferences `neo_assistant`, key `hud` = `false`.
- Showing it over other apps requires "Display over other apps" or the Neo Plus accessibility overlay service.
