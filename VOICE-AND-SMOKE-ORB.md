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
