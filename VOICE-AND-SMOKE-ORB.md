# NEO PLUS voice and smoke-orb update

## Implemented in this branch
- Replaced the central HUD face illustration with a soft, animated cyan/purple smoke orb.
- Removed the decorative waveform bars; the orb is the only central voice-style visual.
- Kept the existing task focus/rail behavior and the existing streaming assistant reply path.

## Already present in the parent feature branch
- User speech partial results are shown while recognition is active in the chat UI.
- Assistant text is streamed into the live reply bubble as response chunks arrive.
- Natural speech can start while the response is being generated when the configured provider supports it.

## Voice cloning: deliberately not activated yet
The current project has a selectable built-in voice gender and a voice preview, but no verified personal-voice cloning provider, consent capture, secure enrollment, or model lifecycle. This branch does not pretend that a clone exists and does not upload a voice recording anywhere. A real implementation needs a selected provider, explicit recording consent, encrypted transport/storage, account-bound management, deletion, and anti-impersonation safeguards. The cloned voice must be a speaking style only: it grants no command authority and cannot authenticate sensitive actions.

## Follow-up validation
- Build on GitHub Actions.
- Test smoke animation performance on the target Vivo Y35 / Android 14.
- Test live user captions and streaming assistant captions with microphone permission granted.
- Verify actual voice cloning only after a provider and its data handling are reviewed and selected by the owner.

## Dark virtual-only HUD presentation
- The full-screen HUD canvas is now fully opaque deep navy/black, so the underlying app window is not visible through the interface.
- The status panel identifies the display as `VIRTUAL TASKS ONLY`; it does not show the foreground app name.
- Apps may still run behind the HUD when a task requires an app/API, but their physical UI is not presented as the NEO PLUS interface. Task status and progress are shown in NEO PLUS panels.


## Virtual workspace and task execution notes
- The split-task engine gives each task its own pane, request history, status, and pending follow-ups.
- Independent work that does not drive the phone screen can run concurrently.
- Tasks that must operate the single Android screen share a fair screen lock and take turns; this prevents two tasks from tapping or typing into the same app at once.
- The HUD task-feed registry currently retains up to 9 feeds at a time. The task rail scrolls through all retained feeds; it does not mean the registry or Android can run an unlimited number of resource-intensive tasks.
- Minimizing or focusing a feed changes its presentation, not the worker's execution state.

## GitHub build validation
- GitHub Actions run [45028370129](https://github.com/plusneo08/Neo-plus-ios-launcher/actions/runs/45028370129) completed successfully on 2026-11-22 for commit `8d7216e296308605d6d1a0a4f46ed6a9cc7c4827`.
- The `Build debug APK` step and artifact upload both succeeded.
- The `NEO-PLUS-apk` artifact was created (about 19.6 MB). It is a debug build, not a release-signed production APK.
- Vivo Y35 / Android 14 installation, performance, microphone, and real-device interaction tests remain pending.


## Website automation safety hardening
- Website permission preferences are now persisted in Android app storage instead of returning hard-coded values.
- The default website policy is strict: a task must start on a site explicitly enabled in SiteVault. The old fallback that treated an unlisted domain as authorised has been removed.
- Cross-domain navigation is blocked by default. The owner can explicitly choose an allowlist or opt into any site in settings; allowlist matching is host-based.
- Purchases, payments, transfers, deletion/deactivation, messaging, publishing, sharing, and uploads are blocked when the sensitive-actions setting is off.
- When sensitive actions are enabled, each classified action pauses for a separate Continue approval. The approval is one-shot and bound to the action; it cannot authorize a later different action.
- CAPTCHA and verification-code steps still pause for the user; NEO PLUS does not bypass them.
- These safeguards are code changes pending successful CI validation and later on-device testing. The app cannot yet claim real-device verification.


## Open website browsing
- Website tasks can now start on any valid HTTP or HTTPS website; a SiteVault entry is no longer required for general browsing.
- Navigation across different HTTP or HTTPS domains is open by default. The website allowlist controls have been removed from the assistant settings.
- Saved website credentials remain scoped to the exact saved website and are not carried to unrelated domains.
- Sensitive side effects remain separate from browsing: payments, transfers, deletion, messaging, publishing, sharing and uploads are still blocked when disabled and require a fresh approval for each action when enabled.
- CAPTCHA and verification-code steps still require the user; NEO PLUS does not solve or bypass them.


## Message sending authorization
- A clear instruction in the current website task to send, reply, or respond to a recipient authorizes that specific requested message; NEO does not ask for a second approval for the same message.
- This is not blanket permission: NEO must stay within the requested recipient and content, and cannot infer permission for unrelated or future messages.
- Drafting or preparing a message without an explicit send/reply instruction does not authorize sending it.
- Payments, transfers, deletion, publishing, sharing, and uploads remain separately gated; CAPTCHA and verification steps still require the user.
