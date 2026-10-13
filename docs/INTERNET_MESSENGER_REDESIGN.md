# Internet Messenger Redesign — Concept

## Goal

Turn the experimental `Concept` build into a conventional, internet-connected messenger for a small trusted circle. Keep the stable `main` branch untouched. This document is the implementation plan; it does not remove Bluetooth or change runtime behavior.

## Product decisions

- Internet is the primary transport for messaging and calls.
- A full UI redesign: black/dark surfaces, restrained TRON-cyan accents, clear hierarchy, readable typography, real chat rows and message bubbles. Avoid a busy grid background.
- Standard text messages, delivery/read state, contacts, attachments, recorded voice messages, and live push-to-talk (PTT).
- A visible `Concept` app label on experimental builds.
- Do not merge experimental work into `main` without a separate, explicit decision.

## Initial repository audit

Observed on the `Concept` branch at commit `0d799861e3a01041692d36e47e116b9cbea46bf7`:

- The app is a Kotlin Android Views application (minSdk 26, targetSdk 36), not Jetpack Compose. Most screens and dialogs are assembled programmatically in a large `MainActivity.kt`; therefore UI changes should be extracted incrementally instead of replacing the entire activity in one risky rewrite.
- The app already has an internet relay transport, `MeshRelayTransport`, connecting to a fixed VPS endpoint over TLS with a SHA-256 certificate pin. It sends encrypted `MeshPacket` envelopes, has a pending-message queue, retry logic, delivery acknowledgements, and peer presence events.
- Text history is stored locally by `ChatStore`; identity/contact storage, encrypted packet routing, and the foreground service are already separate classes and should be reused where appropriate.
- The relay is a text/packet store-and-forward transport, not yet a complete media upload service or WebRTC signaling/media service. Do not assume it can carry voice recordings or live audio without a designed protocol and capacity limits.
- Current home screen is a transport/debug control panel with BT / BLE+Relay / Relay choices, rather than a chat-first messenger. The existing style uses black surfaces and cyan accents but monospace type and strong glow effects; the redesign should retain the palette while improving readability and reducing visual noise.
- The manifest currently declares Bluetooth as a required hardware feature and contains Bluetooth permissions. These must not be removed until the internet-only path has been tested and the final migration scope is approved.
- The Concept manifest label is already `Concept`; the current update manager reads a Concept-specific update manifest from the VPS.

### Risks and questions to resolve

1. The relay host and port are compiled into the Android client. Before altering connectivity, map the deployed relay protocol/server implementation, its authentication and identity-binding rules, queue limits, and TLS certificate renewal procedure.
2. Current encrypted messages use ECDH P-256 and AES-GCM. Review key derivation, identity verification/contact exchange, replay behavior, and group-key design before reusing this scheme for group voice or media.
3. Current chat history stores text only. Voice attachments need a versioned message model, local media files, upload/download states, integrity checks, quotas, and retention/cleanup rules.
4. Live PTT requires a separate authenticated signaling flow and reliable connectivity across NAT/mobile networks. Evaluate WebRTC with Opus and STUN/TURN; for group calls, decide whether a small SFU is justified by the expected participant count and VPS budget.
5. Current foreground-service lifecycle is tied to mesh/Bluetooth watchdog logic. Rework service responsibilities deliberately for internet messaging and call states; do not simply disable the watchdog without replacement behavior.

## Proposed screen map

1. **Chats** — searchable conversation list, contact avatar/initials, last message, timestamp, unread count, delivery state, and a clear new-chat action.
2. **Conversation** — familiar message bubbles, timestamps, delivery state, attachment and voice-message controls, and a distinct PTT invitation card.
3. **Voice-message composer** — press/hold to record, lock-to-record option if feasible, elapsed time/waveform, preview/playback, cancel, and send. Never send before the user confirms by releasing/sending.
4. **PTT invitation** — invitation appears as a message with Accept and Decline. A room is created/joined only after acceptance.
5. **PTT room** — large hold-to-talk control, participant list, muted/connecting/reconnecting states, leave-room control, and an explicit add-participant action.
6. **Settings and privacy** — microphone permission explanation, PTT invitation policy (off / contacts / selected contacts), notification preferences, data usage, and diagnostics/privacy controls.

## Voice media direction

- **Recorded voice messages:** evaluate Opus in an appropriate container (for example Ogg/Opus) for speech quality at modest file sizes. Store locally, show upload/send progress, retry safely, and authenticate access to remote media.
- **Live PTT:** evaluate WebRTC audio with Opus, using a signaling service and STUN/TURN where direct connectivity fails. Group rooms need an explicit topology decision; a small SFU is usually more predictable than a full mesh as participant count grows.
- Do not claim end-to-end encryption until key management, signaling authentication, media encryption, server access, and group membership changes have been reviewed and tested.
- The app must never transmit microphone audio merely because a room invitation was received. The user must accept the invitation and deliberately press the talk control.

## Incremental delivery plan

### Phase 0 — Inventory and baseline
- Record the current `Concept` commit and inspect build workflows and app structure.
- Preserve current behavior as a reference; do not delete BLE code yet.
- Inspect the deployed relay server and confirm its protocol/security contract, then produce a concise backend gap list.

### Phase 1 — UI foundation
- Establish the new dark visual system and navigation/screen shells.
- Keep the `Concept` label and separate update channel.
- Avoid mixing UI work with transport rewrites.

### Phase 2 — Internet text messaging
- Implement or adapt authenticated internet transport only after the backend contract is established.
- Preserve local persistence, offline queueing, acknowledgements, duplicate suppression, and clear connection states.
- Validate two-device text exchange before proceeding.

### Phase 3 — Recorded voice messages
- Add Opus recording/playback, upload/download progress, retry, cancellation, and retention limits.
- Test quality and file size at several speech bitrates on real devices.

### Phase 4 — One-to-one PTT, then groups
- Build invitation/accept/decline/expiry states.
- Implement authenticated signaling and WebRTC audio with explicit mic controls.
- Validate network transitions, reconnects, permissions, and room authorization before adding group membership.

### Phase 5 — cleanup and release checks
- Remove BLE only after internet text, voice messages, PTT, contact onboarding, and recovery behavior pass acceptance tests and the user agrees the fallback is no longer needed.
- Keep all work on this experimental line until explicitly approved.
- Build and distribute via GitHub Actions; do not make unreviewed VPS changes.

## Initial acceptance criteria

- `main` is unchanged.
- The first PR changes documentation only and triggers no risky infrastructure changes.
- Every later phase is independently reviewable and buildable.
- Text and media are authenticated; unauthorized users cannot read messages or join a room.
- Declining an invitation does not join a room; a received invitation never opens the microphone.
- Failed or interrupted sends remain visible and retry without silently duplicating messages.
- Network and microphone states are understandable to a non-technical user.
