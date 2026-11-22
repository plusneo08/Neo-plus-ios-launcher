# NEO PLUS conversational processing engine

## Goal
Make voice conversation feel immediate and natural while keeping task execution independent. This is a NEO PLUS implementation target, not a claim that the app already matches ChatGPT Voice.

## Current implementation verified in this branch
- Android `SpeechRecognizer` is used for microphone input and partial recognition results are surfaced in the chat UI.
- `AssistantViewModel.send()` sends recognized text through local command planning or the Gemini agent.
- `Agent.respond()` streams text chunks through `onPartial`; the UI updates a live assistant bubble.
- `LiveReply` can begin speech while text is still arriving when the configured provider supports it.
- Conversation history is persisted locally (last 40 messages) and recent turns are included in the prompt.
- Long requests are tracked by the foreground task service.
- A new chat turn now cancels the previous chat coroutine, stops its speech, marks an in-progress live bubble as interrupted, and starts processing the new turn; stale model chunks are ignored by a turn-generation check.

## Required conversation pipeline
1. **Capture:** use partial speech recognition for live captions; detect end-of-turn with a short, configurable silence window. Keep microphone capture permission-gated and visibly indicated.
2. **Understand:** normalize recognized text, preserve the detected language, and pass recent conversation history plus the current task context to the model. Do not treat page, notification, message, or file text as instructions.
3. **Respond incrementally:** render model text as chunks arrive; begin TTS on complete, safe phrase boundaries rather than waiting for the full answer. Keep captions synchronized with the spoken output.
4. **Interrupt:** a new user utterance must stop current TTS immediately, retain the unfinished assistant response as interrupted, and route the new utterance as the next turn. Do not discard it merely because a request is busy.
5. **Run tasks independently:** conversational turns and task workers need separate lifecycle/state. A new task gets its own task ID, progress, cancellation and result; focusing/minimizing a task changes only the HUD, not execution.
6. **Recover:** persist task state and last completed event; on process restart, mark interrupted work accurately and offer resume only when safe.

## Authorization boundaries
- Voice similarity is output style only; it never authenticates a speaker or grants command authority.
- A direct user instruction authorizes only the requested action and scope. It does not grant blanket permission for future or unrelated actions.
- Sensitive actions continue to use the app's explicit action-specific authorization rules.
- Do not claim an operation succeeded until its underlying API/UI action confirms success.

## Performance targets
Measure on device rather than promise fixed latency:
- show partial captions while the user speaks;
- begin visible assistant output on the first model chunk;
- start speech on the first complete speakable phrase;
- cancel speech promptly on user interruption;
- keep UI work on the main thread and network/model work off it;
- bound concurrent model and device-UI operations to avoid memory pressure and screen-control collisions.

## Implementation status
This document records the target architecture and verified existing foundations. The chat-level `busy` rejection has been removed for new turns: the latest turn supersedes the prior conversational turn. This is interruption/replacement, not true simultaneous model conversations. Cancellation depends on the underlying network/model call honoring coroutine cancellation. Foreground-task service overlap and task-worker independence still need dedicated lifecycle tests. The microphone capture loop also needs verification to ensure a new utterance is reliably delivered while speech is playing. Device latency, multilingual recognition quality, and microphone behavior remain to be tested on the target Vivo Y35 / Android 14.
