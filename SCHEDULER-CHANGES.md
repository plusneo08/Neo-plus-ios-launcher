# Scheduler (new)
Say "send Mum a WhatsApp at 5pm", "turn Wi-Fi off in 30 minutes", "every day at 8 read my weather".
- New file: Scheduler.kt (+ SchedulerReceiver, SchedulerBootReceiver)
- Assistant.kt: actions `schedule` and `schedules` (list / history / cancel) + prompt docs
- AndroidManifest.xml: two receivers
When a task runs, a notification says Done or what went wrong; "what did you do?" reads the history.
Not schedulable: money, sleep/time lock, power-off, queue/approvals.
Limits: tasks due while the phone is off are reported as missed, not run late; screen-based actions need the phone able to wake/unlock.
