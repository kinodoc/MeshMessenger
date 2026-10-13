# Internet Messenger Redesign

## Goal and branch policy

The redesign is developed directly on `main`. Do not continue active development in `Concept` or feature branches. All changes go directly to `main`. Bluetooth transport is explicitly out of scope and must be removed completely. The only supported messaging transport is internet relay; no Bluetooth fallback, permissions, discovery, or RFCOMM code may remain.

## Product direction

- Make internet messaging the primary product experience.
- Replace the transport/debug dashboard with a familiar messenger: chat list, conversation screen, message states, contacts, attachments, and settings.
- Use a clean black/dark visual system with restrained TRON-cyan accents, readable sans-serif typography, and minimal glow.
- Add recorded voice messages and push-to-talk (PTT) rooms with explicit invitations, acceptance/decline, and group participation.
- Keep microphone transmission user-initiated; receiving an invitation must never activate the microphone.
- Retain working identity, contact exchange, QR onboarding, message history, delivery acknowledgements, diagnostics, and update behavior while migrating.
- Rebuild all screens and visual styling as a cohesive messenger, not a dashboard with a messenger screen added on top.

## Current architecture observed

- Android client is written in Kotlin using Android Views; much of the current UI is assembled programmatically in `MainActivity.kt`.
- The app has an existing internet relay transport (`MeshRelayTransport`) using TLS with certificate pinning, encrypted packet envelopes, local pending-message storage, retry behavior, delivery acknowledgements, and peer presence.
- The relay is a text/packet store-and-forward service, not yet a media-upload API or WebRTC signaling/media service.
- Local chat history, contacts, identity, packet routing, and the foreground service are already separated into classes and should be preserved where possible.
- The current main screen is primarily a transport/debug control panel, not a chat-first messenger.
- Current identity encryption and contact verification need a focused security review before extending them to attachments or group voice.
- The app's update workflow is tied to GitHub Releases. Stable releases must not be overwritten by every intermediate main commit; publish only from an explicit matching version tag.

## Implementation sequence

### Phase 0 — Preserve and map
1. Record the current baseline and keep changes small.
2. Map the relay server protocol, authentication/identity binding, queue limits, TLS pin renewal, and existing VPS deployment before changing transport contracts.
3. Remove Bluetooth transport code and all Bluetooth-related UI, permissions, services, diagnostics, and resources; verify the internet relay is the sole messaging transport.

### Phase 1 — Messenger UI
1. Establish readable typography, dark surfaces, consistent spacing, and accessible controls.
2. Replace the home screen with a chat list and clear navigation.
3. Build a conventional conversation view with message bubbles, timestamps, delivery states, and attachment/voice controls.
4. Keep contact onboarding and diagnostics reachable without dominating the main screen.

### Phase 2 — Internet text messaging
1. Reuse the existing relay only after confirming server-side behavior and authentication.
2. Preserve offline queueing, acknowledgements, retry, duplicate suppression, and local history.
3. Validate two-device sending, receiving, offline recovery, and reconnect behavior.

### Phase 3 — Recorded voice messages
- Evaluate Opus in a suitable container for efficient speech audio.
- Add record, preview/playback, cancel, send, progress, retry, local storage, integrity checking, and media retention/cleanup.
- Add authenticated media transfer and explicit size/rate limits before introducing a media endpoint.

### Phase 4 — PTT
- Evaluate WebRTC audio with Opus, authenticated signaling, and STUN/TURN for difficult NAT/mobile networks.
- For group calls, choose a topology based on expected room size and VPS resource limits; an SFU may be more predictable than a full mesh.
- Implement invitation, accept, decline, expiry, participant list, explicit hold-to-talk, mute/leave, and add-participant actions.
- Review encryption, authorization, membership changes, and server access before making end-to-end security claims.

### Phase 5 — migration and cleanup
- Bluetooth is removed by explicit product decision. Validate internet text messaging first, then implement voice messages and PTT as separate phases.
- Build and verify through GitHub Actions.
- Keep intermediate main commits from overwriting a stable release; publish a release only with an explicit matching `v*` tag.

## Acceptance criteria

- The app feels like a conventional messenger and remains readable on small Android screens.
- No Bluetooth permissions, transport classes, transport-mode selector, or Bluetooth-only startup dependency remain.
- Text, recorded voice messages, and PTT are designed as first-class product features, with microphone use always explicitly user-initiated.
- Existing contacts, QR exchange, history, and delivery state are not silently lost.
- Failed sends remain visible and can retry without duplicate messages.
- Media is authenticated, bounded in size, and cleaned up according to a documented retention policy.
- Declining a PTT invitation does not join a room; invitations never activate the microphone.
- Reconnects, offline queues, permission denial, room authorization, and membership changes are tested.
- All development changes are made directly on `main`, in small commits verified by CI.
