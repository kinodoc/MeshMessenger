# Briar Bluetooth transport audit and MeshMessenger rebuild plan

Date: 2026-10-12  
Reference source: Briar/Bramble `release-1.5.20`  
MeshMessenger baseline: `Concept` at `bc27d066aed839755ba8c9f01f30f6e55000581e`; `main` at `9dfa5464fc72933707003a945788ed15a1a41b02` (v0.24.97 / versionCode 120).

## Scope and guardrails

- Research/rebuild work belongs on an isolated branch derived from `Concept`.
- Do not change or merge into `main` during this investigation.
- Do not change the VPS/Relay server during this investigation.
- Preserve the current Relay, message packet format, identity key generation, and chat storage unless a separate, evidenced issue requires changes.
- MeshMessenger's current Bluetooth implementation is **Bluetooth Classic RFCOMM**, not BLE GATT. Keep this distinction explicit in code, diagnostics, and tests.
- Briar's Bluetooth plugin is part of Bramble and is not a drop-in Kotlin class. This plan adapts architectural principles; it does not copy Briar source files.

## What Briar 1.5.20 does

Relevant source files:
- `bramble-core/.../plugin/bluetooth/AbstractBluetoothPlugin.java`
- `bramble-android/.../plugin/bluetooth/AndroidBluetoothPlugin.java`
- `bramble-android/.../plugin/bluetooth/AndroidBluetoothTransportConnection.java`
- `bramble-core/.../plugin/bluetooth/BluetoothConnectionLimiterImpl.java`
- `bramble-api/.../plugin/duplex/DuplexTransportConnection.java`
- `bramble-core/.../plugin/PluginManagerImpl.java`

Briar's transport lifecycle includes:
1. Explicit plugin lifecycle/state and adapter permission/availability checks.
2. A long-lived RFCOMM server socket with a managed accept loop.
3. A discovery/connect path that serializes discovery with a semaphore, cancels discovery before connection attempts, filters out LE-only devices, and tries discovered addresses with backoff.
4. Transport properties for a peer: Bluetooth address plus a persistent local service UUID advertised to contacts. The UUID is generated/stored as a transport property rather than hard-coded as one shared app-wide UUID.
5. Accepted and outgoing sockets are wrapped as duplex transport connections. Reads have timeout monitoring; the connection acquires/releases a wake lock and reports closure to the connection limiter.
6. Connection admission is coordinated with Bramble's sync/setup lifecycle. The limiter can temporarily prevent regular contact connections and tracks open/closed connections.
7. Transport connections are handed to the common connection manager/synchronization stack; the Bluetooth socket layer does not implement Briar's authentication and message sync itself.

Important: Briar also uses insecure RFCOMM sockets at the Android socket API layer. That does **not** mean the full Briar connection is unauthenticated: security/authentication and synchronization belong to higher Bramble layers.

## MeshMessenger current design (source inspection)

Relevant files:
- `MeshBluetoothNode.kt`
- `BriarBluetoothTransport.kt`
- `MeshProtocol.kt`
- `MeshRelayTransport.kt`
- `MeshRouter.kt`
- `ContactCard.kt`
- `ContactStore.kt`
- `MeshForegroundService.kt`

Current flow:
1. `MeshBluetoothNode` starts an RFCOMM listener and starts a bonded-device pass plus Bluetooth discovery.
2. It uses a fixed RFCOMM service UUID (`7d2a1004-8b4f-4f10-9d3e-8b7d6a2f0001`).
3. It tries to prevent simultaneous outgoing attempts using a lexicographic comparison of the local adapter Bluetooth address and the discovered address, plus a single `activeConnectAddress` gate.
4. `BriarBluetoothTransport` manages raw sockets, frame length prefixes, read loops, serialized writes, a ready set, and exponential retry.
5. A custom `MESH_HELLO_V1` exchanges NodeID/name/public key. The node validates NodeID against the public key before marking the peer ready.
6. MeshMessenger packet encryption, routing, message acknowledgements, and Relay are implemented outside the socket transport.

## Gap / risk table

| Area | Briar pattern | MeshMessenger now | Finding / rebuild requirement |
|---|---|---|---|
| Ownership of connection state | Plugin lifecycle and connection objects with explicit state | State spread across node sets, transport maps, callbacks, and service watchdog | Give each peer one authoritative state machine; make transitions and cleanup atomic |
| Discovery/connect coordination | Discovery semaphore, cancellation, address list, bounded sequential attempts | Node-level single active attempt, while transport-level retry can call `connect()` independently | Route initial attempts and retries through one coordinator; no hidden second connection scheduler |
| Simultaneous inbound/outbound | Both accepted through common connection path; higher layer manages connection/session lifecycle | Incoming socket may appear while blocking outgoing `connect()` is in progress | Define deterministic duplicate-socket arbitration and cancel/close losing attempts; never let a stale connect overwrite or disrupt the winner |
| Multi-peer discovery | Polling continues under plugin/backoff policy | `maybeStartDiscovery()` defers discovery whenever any peer is ready; bonded pass can be gated by first active attempt | Verify and fix discovery starvation. A connected peer must not permanently prevent discovery of additional peers |
| Initiator selection | Discovery/connect policy does not depend on comparing the two adapter MAC strings | `shouldInitiate()` compares local adapter address with peer address | Remove dependency on a readable local adapter MAC; use deterministic peer/session arbitration that works if the adapter address is unavailable |
| Service identity | Persistent per-install service UUID is included in transport properties | One fixed shared UUID | Decide whether fixed UUID is sufficient for MeshMessenger's intended pairing model; don't copy Briar's UUID scheme blindly, but document the trade-off |
| Connection lifecycle | Wrapped connection owns streams, timeout monitoring, wake lock, and limiter close callback | Socket/read/write ownership is spread across maps and callback paths | A single session object should own socket, streams, reader, writer lock, cancellation, close reason, and cleanup |
| Read/write failure | Shared connection abstraction and close propagation | Local map removal and callbacks | Make failure/close idempotent; report explicit reasons; ensure queue resend occurs only after a real READY transition |
| Presence/readiness | Transport active state is distinct from authenticated/synced peer state | `connected`, `ready`, and UI peer presence can be conflated across callbacks | Keep SOCKET_CONNECTED, IDENTITY_VERIFIED, and READY distinct; never show a peer online from a stale socket/HELLO alone |
| Frame protocol | Bramble reader/writer framing and timeouts | Custom 4-byte length prefix and custom payload frames | Retain framing only if tests prove it; add strict bounds, truncated-frame, malformed-frame and disconnect tests |
| Identity/contact import | Briar has its own contact/transport-property lifecycle | MM1 card validates NodeID against public key; old stored contacts are loaded as-is | Keep transport rewrite separate from contact migration. Independently test stale stored NodeID and Relay destination behavior |
| Relay | Not the Bluetooth transport's responsibility | Separate `MeshRelayTransport` and `MeshRouter` | Leave Relay server/code untouched during BT rebuild; diagnose destination-ID mismatch separately |
| Diagnostics | State/lifecycle logs around plugin, discovery and connection manager | Rich diagnostics exist but states are distributed | Emit one structured event per state transition with peer token, direction, attempt ID, elapsed time and close reason |

## Concrete findings from branch history

1. Current `main` commit `9dfa5464` (“restore build #354 RFCOMM baseline”, v0.24.97) removes the duplicate-connection guards that were present in its parent `93a34d2` (“restore tested BLE socket guard”, v0.24.96). Removed checks covered a socket/READY state appearing before or during a blocking outgoing connect, and cancellation of the redundant outgoing task after an inbound socket wins.
2. The saved `backup/Concept-before-ble-restore-2026-10-11` transport has those additional guards. This is a concrete regression candidate, not yet proof that it explains the Oct 8 working checkpoint. Compare it with bugreport #175 and the exact successful Actions artifact before selecting any code as the baseline.
3. The current node's `maybeStartDiscovery()` returns when it has any ready peer or any pending connect. Meanwhile `connectBondedPeers()` iterates bonded devices while `activeConnectAddress` permits only one attempt. That combination can starve discovery/connection to additional peers. Confirm expected multi-peer behavior in tests and fix in the new coordinator.
4. The node's initiator policy depends on `adapter.address`. The app already logs `address_known`; if the address is unavailable, neither side may initiate via this policy. A rebuild must handle this explicitly.
5. The latest BT transport's retry scheduler calls its own `connect()` directly. That bypasses the node's `activeConnectAddress` gate and creates two places that decide when an attempt starts. The replacement must have one authority for attempts and retries.
6. The app class named `BriarBluetoothTransport` is MeshMessenger's own implementation. It does not implement the Bramble transport-connection API and does not contain Briar's authentication/sync stack.

## Proposed replacement architecture

Keep the replacement isolated and small, with explicit interfaces:

- **DiscoveryCoordinator**: owns adapter events, discovery start/stop, bonded-device candidates, discovery cancellation before connect, and retry scheduling. No socket writes or message routing.
- **PeerConnectionManager**: the sole authority for outgoing attempts and retries. One per-peer state record with states `IDLE → CONNECTING → SOCKET_CONNECTED → IDENTITY_VERIFIED → READY`, plus `CLOSING/FAILED`. Every attempt has a generation/attempt ID so late callbacks cannot mutate a newer session.
- **RfcommListener**: owns exactly one server socket and accept loop. It only delivers accepted sockets to the connection manager.
- **PeerSession**: owns one socket, one reader, serialized writer, bounded frame parser, close-once semantics and explicit close reason. Incoming and outgoing sockets go through the same registration path.
- **Handshake**: preserve current identity/key validation initially, but formalize a versioned handshake. Decide simultaneous-connect winners deterministically after both NodeIDs are known: lower NodeID prefers its outgoing socket; higher NodeID prefers its incoming socket. Both ends should then select the same physical socket. If only one candidate exists, keep it. Do not mark READY until the identity handshake succeeds.
- **MeshBluetoothNode adapter**: maps verified READY peers to existing `MeshRouter` / `PendingMessageStore`; flushes queued messages only on READY and requeues safely on session loss.
- **Relay and contact-card code**: unchanged in the first BT replacement milestone.

No Briar source files should be copied into MeshMessenger as part of this plan. The implementation should be independently written against Android's public Bluetooth APIs. If future work copies GPLv3 source, assess licensing obligations first.

## Test gates before replacing the old implementation

1. Unit-test the state machine: duplicate connect request, incoming socket during outgoing connect, late connect success after cancellation, repeated close, adapter disabled mid-connect, retry after failure, and service stop/start.
2. Frame tests: empty, max-sized, oversized, truncated header/body, malformed packet, and peer disconnect during read/write.
3. Handshake tests: valid identity, NodeID/public-key mismatch, malformed HELLO, duplicate HELLO, and two simultaneous cross-connections selecting the same physical socket.
4. Discovery tests: two peers, three peers, already-connected peer plus a new peer, no readable adapter MAC, failed attempt with retry, discovery canceled before connect.
5. Integration gate on UMIDIGI + Honor: BT-only send both directions; kill/restart Bluetooth; background/reopen app; queue while peer offline then deliver; verify ACK; test BT+Relay without changing Relay.
6. Capture comparable bugreports from both devices and require the same transition sequence: discovery candidate → connect attempt → socket connected → identity verified → READY → packet TX/RX → delivery ACK.
7. Build and install APK only from GitHub Actions. No Debian build and no VPS changes.

## Immediate next step

Before implementing the replacement, compare bugreport #175 and the successful GitHub Actions build/commit against the current `Concept`, backup and `main` histories. Select the proven app-level integration point, then implement the replacement behind the existing node callback contract on this isolated branch. Do not delete the old transport until the new one passes the two-device test gate.
