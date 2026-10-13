# Internet Messenger Redesign — Concept

## Goal

Turn the experimental `Concept` build into a conventional, internet-connected messenger for a small trusted circle. Keep the stable `main` branch untouched. This document is the implementation plan; it does not remove Bluetooth or change runtime behavior.

## Product decisions

- Internet is the primary transport for messaging and calls.
- A full UI redesign: black/dark surfaces, restrained TRON-cyan accents, clear hierarchy, readable typography, real chat rows and message bubbles. Avoid a busy grid background.
- Standard text messages, delivery/read state, contacts, attachments, recorded voice messages, and live push-to-talk (PTT).
- A visible `Concept` app label on experimental builds.
- Do not merge experimental work into `main` without a separate, explicit decision.

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

## Architecture review before implementation

Inspect the current Android project and existing VPS services before selecting dependencies or changing protocols:

1. Map current identity/contact storage, message persistence, delivery state, QR/contact exchange, notifications, foreground-service behavior, update manager, and bug-report flow.
2. Determine whether an authenticated internet messaging API already exists. Do not assume the current relay is a general-purpose internet backend.
3. Identify hosting constraints and existing reverse-proxy/TLS setup. Keep services private where possible; do not expose an unauthenticated port or create a new public endpoint as a shortcut.
4. Decide message API, delivery/retry semantics, media storage/expiry, signaling, STUN/TURN, and group-call topology.
5. Check Android minimum SDK, dependency/license compatibility, microphone permissions, foreground-service restrictions, battery use, and background behavior on Android 11+.
6. Add tests for authorization, reconnects, duplicate delivery, offline queues, declined/expired invitations, room membership, and permission denial.

## Incremental delivery plan

### Phase 0 — Inventory and baseline
- Record the current `Concept` commit and inspect build workflows and app structure.
- Preserve current behavior as a reference; do not delete BLE code yet.
- Produce a concise architecture map and backend gap list.

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
