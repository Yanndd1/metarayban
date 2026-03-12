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
Version field: `airshieldVersion` (≥17 for modern features, "1.1+" for advertisement).

### 4.2 Class Hierarchy (from DEX analysis)

```
com.facebook.wearable.airshield.securer/
├── StreamSecurerImpl          ← Session controller (initialize/start/stop)
│   ├── onPreambleReady(Preamble)   ← Handshake complete callback
│   ├── onStreamReady(long, byte[]) ← Stream ready callback
│   ├── onSend(ByteBuffer)          ← Outgoing data callback
│   ├── receiveData(ByteBuffer)     ← Incoming data processing
│   └── receiveSingleFrame(ByteBuffer) ← Single frame processing
├── Preamble                   ← Handshake exchange
│   ├── getRxChallenge() → Hash     ← Receive challenge
│   ├── getTxChallenge() → Hash     ← Transmit challenge
│   ├── acceptAuthentication(linkKey, callback) ← Accept peer
│   ├── rejectAuthentication(code)  ← Reject peer
│   ├── createConnection() → Connection ← Create DataX connection
│   └── isEncrypted() → boolean     ← Encryption active?
├── EndLinkSetupMessage        ← Link setup completion
│   ├── setAsMain(boolean)          ← Set initiator role
│   └── setUserData(short, byte[])  ← Attach metadata
├── Stream                     ← Data stream
│   ├── send(ByteBuffer)            ← Send encrypted data
│   ├── enableEncryption()          ← Enable encryption
│   ├── disableEncryption()         ← Disable encryption
│   ├── getRxUUID()/getTxUUID()     ← Stream identifiers
│   └── reinitialize()              ← Re-key session
└── RelayStreamImpl            ← Relay support (multi-hop)

com.facebook.wearable.airshield.stream/
├── CipherBuilder              ← Cipher construction
│   ├── setPrivateKey(PrivateKey)         ← Local ECDH key
│   ├── setRemotePublicKey(PublicKey)     ← Peer ECDH key
│   ├── setInitializationVector(IV)       ← Nonce seed
│   ├── setSeed(byte[])                   ← Key derivation seed
│   ├── setChallenge(byte[])              ← Auth challenge
│   ├── buildEncryptionFraming(streamId, bool) → Framing
│   ├── buildDecryptionFraming(streamId, bool) → Framing
│   ├── buildRxChallenge() → Hash
│   └── buildTxChallenge() → Hash
└── Framing                    ← Frame-level crypto
    ├── pack(plainIn, cipherOut)    ← Encrypt frame
    ├── unpack(cipherIn, plainOut)  ← Decrypt frame
    ├── outerFrameSize(innerSize)   ← Calculate frame overhead
    └── cipherPayloadSize(buf)      ← Get payload size

com.facebook.wearable.airshield.security/
├── PrivateKey                 ← ECDH P-256 private key
│   ├── generate()                  ← Generate new keypair
│   ├── derive(PublicKey) → Hash    ← ECDH shared secret
│   ├── recoverPublicKey() → PublicKey
│   ├── sign(Hash) → Signature     ← Sign data
│   └── serialize() → byte[]
├── PublicKey                  ← ECDH P-256 public key (64 bytes)
│   ├── from(byte[]) → PublicKey    ← Deserialize
│   ├── serialize() → byte[]       ← 64 bytes (x||y uncompressed)
│   └── verifySignature(Hash, Signature) → boolean
├── HKDF                       ← Key derivation
│   └── calculateNative(handle1, handle2) → Hash
├── Cipher                     ← AES-256-GCM
│   ├── setup(long, boolean, long)  ← Init cipher context
│   ├── update(inBuf, outBuf)       ← Encrypt/decrypt block
│   └── size() → int                ← Key size
├── Hash                       ← SHA-256 digest (32 bytes)
├── SHA256                     ← SHA-256 hasher
├── HMac                       ← HMAC-SHA256
├── InitializationVector       ← GCM nonce (12 bytes)
│   └── generate()                  ← Random IV
├── Hint / HintMatcher         ← Device hint matching
├── Random                     ← Secure random
└── Signature                  ← Digital signature
```

### 4.3 Confirmed Crypto Parameters

| Parameter | Value | Source |
|-----------|-------|--------|
| Key Exchange | **ECDH P-256 (secp256r1)** | DEX: `Secp256r1`, `DHKEM_P256_SHA256` |
| Public Key Size | **64 bytes** (uncompressed x∥y) | PCAP: 0x80 + 64 bytes |
| HPKE Suite | DHKEM(P-256, HKDF-SHA256) | DEX: `"Only DHKEM_P256_SHA256 is supported"` |
| Key Derivation | **HKDF-SHA256** | DEX: `HKDF` class, `"Only HKDF-SHA256 is supported"` |
| Symmetric Cipher | **AES-256-GCM** | DEX: `AES/GCM/NoPadding`, `AES_256_GCM` |
| GCM Nonce | 12 bytes | Standard AES-GCM |
| GCM Tag | 128 bits | Standard AES-GCM |
| Hash | SHA-256 | DEX: `SHA256` class |
| HMAC | HMAC-SHA256 | DEX: `HMac` class |
| Auth | VOPRF-Ristretto | DEX: `VoprfRistretto`, `libvoprfmerged.so` |
| Signature | Ed25519/ECDSA | DEX: `PrivateKey.sign()`, `SHA256withECDSA` |

### 4.4 Handshake Protocol (Preamble Phase)

```
                Phone (PeerA/Main)              Glasses (PeerB)
                     │                               │
  StreamSecurerImpl  │                               │
    .start()         │                               │
                     │                               │
  CipherBuilder:     │                               │
    setPrivateKey()  │  0x80 + localPubKey (64B)     │
    ─────────────────│──────────────────────────────►│
                     │                               │  CipherBuilder:
                     │  0x80 + remotePubKey (64B)    │   setPrivateKey()
                     │◄──────────────────────────────│
                     │                               │
  setRemotePublicKey │                               │
  ECDH derive()      │                               │  ECDH derive()
  HKDF expand()      │                               │  HKDF expand()
                     │                               │
  onPreambleReady:   │                               │
    rxChallenge      │  Challenge/Auth exchange       │
    txChallenge      │◄─────────────────────────────►│
    acceptAuth()     │                               │
                     │                               │
  EndLinkSetupMsg    │  Link Setup Complete           │
    setAsMain(true)  │◄─────────────────────────────►│
                     │                               │
  onStreamReady()    │  0x40 + encrypted data        │  onStreamReady()
                     │◄─────────────────────────────►│
```

### 4.5 Data Frame Format

```
┌──────────┬──────────────┬────────────────────────────────┐
│ Prefix   │ Nonce (12B)  │ Ciphertext + GCM Tag (16B)     │
│ 0x40     │ AES-GCM IV   │ AES-256-GCM encrypted payload  │
└──────────┴──────────────┴────────────────────────────────┘

Total overhead: 1 (prefix) + 12 (nonce) + 16 (tag) = 29 bytes per frame
```

### 4.6 Transport: BLE L2CAP + WiFi Direct TCP

Airshield operates over two transports:
- **BLE L2CAP**: Direct Bluetooth connection using `airshield_psm` (Protocol Service Multiplexer)
- **WiFi Direct TCP**: Port 20203 for bulk data (media transfer)

Commands to initiate: `handleCommandFromA()`, `handleCommandFromB()` in `StreamSecurerImpl`

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
