# Meta Ray-Ban V1 Media Transfer Protocol Specification
## Reverse-Engineered from Meta View APK (com.facebook.stella) v2024

> **Status**: Phase 1 complete — protocol architecture identified, encryption layer blocks direct replay.

---

## 1. Architecture Overview

The media transfer from Ray-Ban Meta glasses uses a **multi-layer stack**:

```
┌─────────────────────────────────────┐
│  MediaExchange / ImportStatus       │  ← High-level import orchestration
├─────────────────────────────────────┤
│  WifiFetchManager                   │  ← WiFi connection lifecycle
│  ├─ WifiDirectStrategy             │  ← WiFi Direct P2P group creation
│  ├─ WifiOwnerManager               │  ← P2P group owner management
│  └─ WifiPeer                       │  ← Peer connection/disconnection
├─────────────────────────────────────┤
│  DataX (DataTransferService)        │  ← Message framing & routing
│  ├─ RemoteChannel / LocalChannel    │  ← Bidirectional channels
│  └─ TypedBuffer                     │  ← Serialized message buffers
├─────────────────────────────────────┤
│  Airshield (StreamSecurer)          │  ← E2E ENCRYPTION LAYER
│  ├─ Preamble (key exchange)         │  ← Handshake / link setup
│  ├─ LinkSetup (ECDH + HKDF)        │  ← Key agreement
│  ├─ Framing (encrypt/decrypt)       │  ← Per-frame encryption
│  └─ VOPRF (ed25519/ristretto)       │  ← Auth verification
├─────────────────────────────────────┤
│  WiFi Direct (TCP port 20203)       │  ← Transport layer
│  └─ 192.168.49.x subnet            │
├─────────────────────────────────────┤
│  Bluetooth LE (GATT 0xFD5F)        │  ← Control channel
│  └─ FlatBuffers (stella/srvs/*)     │  ← Command serialization
└─────────────────────────────────────┘
```

## 2. BLE Control Channel

### 2.1 GATT Service
- **Service UUID**: `0xFD5F` (Meta Platforms registered)
- **Manufacturer ID**: `0x01AB`

### 2.2 Key Characteristics (from GATT exploration)
| Handle | UUID Prefix | Purpose |
|--------|-------------|---------|
| 40 | Notify CCC | Notification enable (write 0x0100) |
| 41 | 05acbe9f | Command/Notify characteristic [AUTH REQUIRED] |
| 44 | c53673dd | Status (read: 0x8100 = connected) |
| 46 | f9fbf15d | Flags (read) |
| 48 | - | Firmware Revision |
| 50 | - | Firmware version string |

### 2.3 BLE Commands (FlatBuffers serialized)
The app sends commands via FlatBuffers schema `stella/srvs/*`:

- **`StartWebserverRequest`** → tells glasses to start the HTTP(S) media server
- **`StartWebserverResponse`** → glasses confirm server started
- **`StaModeConnectRequest`** → request STA mode WiFi connection
- **`StaModeConnectResponse`** → response with connection status
- **`StaModeConnectType`** → connection type enum
- **`StartWebserverStatus`** → server status enum

BLE log commands observed:
```
stella:soc:start_webserver   → Start media webserver on glasses
stella:soc:stop_webserver    → Stop media webserver
```

## 3. WiFi Direct Connection

### 3.1 Group Creation
- Phone creates WiFi Direct P2P group as **Group Owner**
- SSID pattern: `DIRECT-FB-*`
- Uses `WifiP2pManager.createGroup()` via `WifiDirectStrategy`
- Glasses connect as client to the phone's P2P group

### 3.2 Network Topology (from PCAP)
```
Phone (Group Owner):  192.168.49.1
Glasses (Client):     192.168.49.66
TCP Port:             20203
```

### 3.3 Connection Flow
1. `WifiDirectStrategy.createGroup()` → creates P2P group
2. `WifiDirectGroupOwnerImpl` manages the group
3. `WifiPeer` connects to glasses peer
4. `WifiLease` system manages connection lifecycle
5. Glasses connect → phone gets `WifiPeerInfo(ssid=..., peerIPAddress=192.168.49.66)`

## 4. Airshield Encryption Layer

### 4.1 Overview
**Airshield** is Meta's proprietary E2E encryption for wearable communication.
It wraps all DataX traffic with authenticated encryption.

### 4.2 Components
- **`Preamble`**: Initial handshake (key exchange parameters)
- **`LinkSetup`**: Full key agreement using ECDH
- **`StreamSecurerImpl`**: Encrypts/decrypts data frames
- **`Framing`**: Frame-level encrypt (`buildEncryptionFraming`) / decrypt (`buildDecryptionFraming`)
- Native implementation: `libairshield_jni.so`

### 4.3 Crypto Primitives
- **ECDH** (Elliptic Curve Diffie-Hellman) for key agreement
- **HKDF** for key derivation
- **SHA-256** for hashing
- **HMac** for message authentication
- **VOPRF** (Verifiable Oblivious Pseudo-Random Function) using:
  - ed25519 (`libvoprf_ed25519_so`)
  - ristretto (`libvoprf_ristretto_so`)
- Public/Private key pairs for identity verification
- `EndLinkSetupMessage` marks end of handshake

### 4.4 Handshake Observed (from PCAP)
```
Phone → Glasses: 0x80 prefix + 64 bytes (likely ECDH public key)
Glasses → Phone: 0x80 prefix + 64 bytes (likely ECDH public key response)
[Key derivation via HKDF]
Phone → Glasses: 0x40 prefix (encrypted commands)
Glasses → Phone: 0x40 prefix (encrypted media data)
```

## 5. DataX Transport Layer

### 5.1 Architecture
DataX is Meta's internal message routing framework for wearable devices.

- **`Connection`**: Manages the transport socket
- **`RemoteChannel`**: Channel to/from glasses
- **`LocalChannel`**: Local processing channel
- **`TypedBuffer`**: Serialized message container
- **`Service`**: Service registration/discovery
- **`ProtocolException`**: Protocol-level errors

### 5.2 Framing
- `SecurityFramingReaderRunnable` / `SecurityFramingWriterRunnable`
- `WIS.BLEHeaderFramingDataX` for BLE path
- WiFi path uses Airshield framing

## 6. Media Import Flow

### 6.1 Orchestration Classes
```
ImportStatusViewModel
├─ runImport() → triggers full import flow
├─ StellaImportStatusViewModel (Stella-specific)
├─ CompositeImportStatusStateReducer
└─ ImportStatusReport
```

### 6.2 Full Flow
1. **Trigger**: User taps "Import" or auto-import triggers
   - `MediaExchangeService` orchestrates
   - `auto_import_trigger` / `media_import_trigger` events

2. **WiFi Setup**:
   - `WifiFetchManagerImpl.MaybeActivate` → decides if WiFi needed
   - `WifiDirectStrategy.createGroup()` → creates P2P group
   - `WifiOwnerManager` → manages group ownership
   - Glasses connect as WiFi peer

3. **BLE Command**:
   - `StartWebserverRequest` sent to glasses via BLE (FlatBuffers)
   - Glasses start internal HTTP/media server

4. **Airshield Handshake**:
   - `Preamble` exchange over TCP 20203
   - `LinkSetup` with ECDH key agreement
   - `StreamSecurer` established for encrypted channel

5. **Media Transfer**:
   - `StellaDeviceMediaSource.fetchMedia()` → request media list
   - `MediaDownloadManager` → download individual files
   - Files received through Airshield encrypted channel
   - `MediaItemImportStatus` tracks per-file progress

6. **Storage**:
   - Files saved via `MediaFileCreatorImpl`
   - Destination: DCIM/MetaRayBan/ (or similar)
   - `MediaGarbageCollectorImpl` cleans up

7. **Cleanup**:
   - `StopWebserver` command sent via BLE
   - `WifiFetchManager.MaybeDeactivate` → tear down WiFi
   - `WifiLease.expire()` → release WiFi resources

### 6.3 Error Handling
- `WifiPeerConnectException` / `WifiPeerConnectTimeout`
- `ConnectionTimeoutException` / `DeviceTimeoutException`
- `FetchMediaException` / `MediaImportException`
- `StartWebserverException`
- "Timed out while connecting to the wifi hotspot"
- "Unable to connect to the hotspot device, cause: "

## 7. Key Findings for Reimplementation

### 7.1 What We Can Replicate
✅ WiFi Direct P2P group creation (Android `WifiP2pManager` API)
✅ TCP connection to glasses on port 20203
✅ FlatBuffers command serialization (schema can be reconstructed)
✅ BLE GATT write commands to handle 41

### 7.2 What Blocks Us
❌ **Airshield encryption**: Native `libairshield_jni.so` implements proprietary crypto
❌ **VOPRF authentication**: `libvoprf_ed25519_so` / `libvoprf_ristretto_so` verify device identity
❌ **Key material**: Derived from device-specific secrets during BLE bonding

### 7.3 Possible Approaches
1. **Hook Airshield JNI** (Frida/Xposed): Intercept `buildEncryptionFraming` / `buildDecryptionFraming` to get plaintext
2. **Reverse native libs**: `libairshield_jni.so` is only 394KB — feasible to reverse with Ghidra
3. **Reuse bonding keys**: If BLE bonding keys persist, the Airshield handshake might accept our client
4. **FlatBuffers schema extraction**: Extract `StartWebserver*` schemas from DEX constants

## 8. Native Libraries
| Library | Size | Purpose |
|---------|------|---------|
| `libairshield_jni.so` | 394 KB | Airshield encryption (CRITICAL) |
| `libsuperpack-jni.so` | 236 KB | Compression |
| `libbreakpad.so` | 394 KB | Crash reporting |
| `libc++_shared.so` | 521 KB | C++ runtime |
| `libsigx.so` | 378 KB | Signal handling |

## 9. FlatBuffers Schemas Identified
```
stella/srvs/StartWebserver
stella/srvs/StartWebserverRequest
stella/srvs/StartWebserverResponse
stella/srvs/StartWebserverStatus
stella/srvs/StaModeConnect
stella/srvs/StaModeConnectRequest
stella/srvs/StaModeConnectResponse
stella/srvs/StaModeConnectStatus
stella/srvs/StaModeConnectType
```

## 10. Next Steps

### Phase 2A: Airshield Reverse Engineering
1. Extract `libairshield_jni.so` from APK
2. Load in Ghidra/IDA for static analysis
3. Identify JNI function signatures
4. Map to known crypto primitives (ECDH, HKDF, AES-GCM?)

### Phase 2B: FlatBuffers Schema Reconstruction
1. Extract FlatBuffer constants from DEX
2. Reconstruct .fbs schemas for `stella/srvs/*`
3. Build Java/Kotlin FlatBuffer classes

### Phase 2C: Frida Interception (Alternative)
1. Hook `StreamSecurerImpl` methods
2. Dump plaintext media data post-decryption
3. Identify media listing protocol
4. Reconstruct transfer without encryption
