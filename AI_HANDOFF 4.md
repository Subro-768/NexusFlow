# Cross-Platform Device A / Device B Architecture

## Objective

Extend the existing application into a cross-platform resumable file-transfer system.

The fundamental concept should no longer be:

```text
Phone → PC
```

or:

```text
Phone → Phone
```

Instead, both endpoints should simply be:

```text
DEVICE A  ⇄  DEVICE B
```

Either device can send or receive.

The system should support:

```text
Android Phone ⇄ Android Phone
Android Phone ⇄ Linux Laptop
Linux Laptop ⇄ Android Phone
Linux Laptop ⇄ Linux Laptop
```

No cloud storage or third-party server should be required.

The existing resumable-transfer protocol is already working and must be preserved.

---

# 1. Core Architecture

Use a peer-to-peer/local-network architecture:

```text
┌─────────────────┐
│    DEVICE A     │
│                 │
│ Send / Receive  │
└────────┬────────┘
         │
         │ Local Network
         │
         │ Existing Transfer Protocol
         │
┌────────▼────────┐
│    DEVICE B     │
│                 │
│ Send / Receive  │
└─────────────────┘
```

Either device may initiate a transfer.

There should be no conceptual "phone", "PC", "server", or "client" in the user-facing interface.

Use:

```text
Device A
Device B
```

for the two endpoints.

Internally, the implementation may still use client/server terminology where technically necessary.

---

# 2. Android UI

Redesign the Android home screen around the Device A / Device B concept.

Example:

```text
┌─────────────────────────────────────┐
│                                     │
│          RESUMABLE TRANSFER         │
│                                     │
│       Device-to-device sharing      │
│                                     │
│                                     │
│      ┌─────────────────────────┐    │
│      │                         │    │
│      │      DEVICE A           │    │
│      │                         │    │
│      │     [ Send ]            │    │
│      │     [ Receive ]         │    │
│      │                         │    │
│      └─────────────────────────┘    │
│                                     │
│             ↕                       │
│                                     │
│      ┌─────────────────────────┐    │
│      │                         │    │
│      │      DEVICE B           │    │
│      │                         │    │
│      │   Connect / Pair        │    │
│      │                         │    │
│      └─────────────────────────┘    │
│                                     │
│          Recent Transfers           │
└─────────────────────────────────────┘
```

However, do not force users to understand the technical meaning of Device A/B.

The UX should make the action obvious.

A simpler preferred flow is:

```text
This Device

[ Send Files ]
[ Receive Files ]

Connected Devices
```

The underlying architecture should still treat the endpoints symmetrically.

---

# 3. Pairing / Device Discovery

Create a clean device connection flow.

Preferred:

```text
Device A

Searching for nearby devices...

● Galaxy S23
● Linux-Laptop
```

The user selects the target device.

On the other device:

```text
Incoming connection

Galaxy S23 wants to connect.

[ Reject ]    [ Accept ]
```

If automatic discovery is difficult with the existing architecture, provide a fallback:

```text
Enter device IP
```

or:

```text
Pairing code
```

or:

```text
QR code
```

Do not destabilize the existing transfer implementation solely to implement advanced discovery.

---

# 4. Transfer Direction

Once two devices are connected, either device can initiate a transfer.

Example:

```text
DEVICE A
    │
    ├── Send file ────────→ DEVICE B
    │
    └── Receive file ←──── DEVICE B
```

The UI should clearly identify:

```text
Sending to Device B
```

or:

```text
Receiving from Device A
```

---

# 5. Android Transfer UI

Use the same polished transfer interface regardless of direction.

Example:

```text
┌─────────────────────────────────────┐
│ ← Transfer                          │
│                                     │
│       example.zip                   │
│       2.14 GB                       │
│                                     │
│      ████████████░░░░░░             │
│               64%                   │
│                                     │
│      1.37 GB / 2.14 GB              │
│                                     │
│  Speed                  ETA         │
│  28.4 MB/s              27 sec      │
│                                     │
│       Device B                      │
│       ● Connected                   │
│                                     │
│          [ Pause ]                  │
│          [ Cancel ]                 │
└─────────────────────────────────────┘
```

---

# 6. Interruption UI

This is a key product feature.

When connectivity disappears:

```text
┌─────────────────────────────────────┐
│                                     │
│               ⚠                     │
│                                     │
│       Connection Interrupted        │
│                                     │
│       Your progress is preserved.   │
│                                     │
│       1.37 GB / 2.14 GB             │
│                                     │
│       Reconnecting...               │
│                                     │
└─────────────────────────────────────┘
```

When connectivity returns:

```text
┌─────────────────────────────────────┐
│               ✓                     │
│                                     │
│       Connection Restored           │
│                                     │
│       Resuming from 1.37 GB         │
│                                     │
│       Previous progress preserved   │
│                                     │
└─────────────────────────────────────┘
```

These values must come from the actual transfer state.

---

# 7. Linux Desktop Application

Create a Linux desktop application providing the same core functionality and visual language as Android.

The Linux application must be downloadable and runnable independently.

Preferred UI:

```text
┌──────────────────────────────────────────────────┐
│  Resumable Transfer                         ⚙    │
├──────────────────────────────────────────────────┤
│                                                  │
│             DEVICE A                             │
│                                                  │
│  This device                                    │
│  Linux Laptop                                    │
│  ● Ready                                         │
│                                                  │
│  ┌───────────────────┐  ┌────────────────────┐  │
│  │                   │  │                    │  │
│  │    ↑ SEND         │  │    ↓ RECEIVE       │  │
│  │                   │  │                    │  │
│  └───────────────────┘  └────────────────────┘  │
│                                                  │
│  Nearby Devices                                  │
│                                                  │
│  ● Galaxy S23                    Connected       │
│  ● Device B                      Available       │
│                                                  │
│  Recent Transfers                                │
│  ─────────────────────────────────────────────   │
│  ✓ example.zip                 2.1 GB            │
│  ✓ photos.zip                  840 MB            │
│                                                  │
└──────────────────────────────────────────────────┘
```

---

# 8. Linux Technology

First inspect the existing repository and determine the most appropriate desktop technology.

Prefer an approach that:

* works reliably on Linux
* can produce a distributable application
* can reuse the existing transfer protocol
* does not require rewriting the protocol
* is reasonably lightweight
* supports a polished UI

Possible approaches include:

* Python + GTK
* Python + Qt
* Tauri
* Electron
* another appropriate cross-platform framework

Choose based on the existing project rather than arbitrarily introducing a technology.

Do NOT rewrite the Android application just to force a shared UI framework.

---

# 9. Shared Protocol

The Android and Linux applications MUST communicate using the same transfer protocol.

Do not create:

```text
Android protocol
+
Linux protocol
```

Create:

```text
              Shared Transfer Protocol
                       │
              ┌────────┴────────┐
              │                 │
           Android            Linux
              │                 │
           Device A          Device B
```

The Linux application must understand:

* transfer IDs
* file metadata
* chunking
* offsets
* resume requests
* transfer state
* completion
* SHA-256 verification

---

# 10. Linux Transfer Screen

Match the Android visual language.

Example:

```text
┌──────────────────────────────────────────────────┐
│  ← Sending to Galaxy S23                        │
├──────────────────────────────────────────────────┤
│                                                  │
│  example.zip                                     │
│  2.14 GB                                         │
│                                                  │
│  ███████████████████░░░░░░░  72%                │
│                                                  │
│  1.54 GB / 2.14 GB                               │
│                                                  │
│  Speed: 31.2 MB/s          ETA: 19 sec           │
│                                                  │
│  ● Connected                                     │
│                                                  │
│               [ Pause ]    [ Cancel ]            │
│                                                  │
└──────────────────────────────────────────────────┘
```

---

# 11. Linux Interruption

The Linux application must show the same resumability state:

```text
Connection interrupted.

1.54 GB already transferred.

Your progress is preserved.

Waiting for Device B...
```

After reconnection:

```text
Connection restored.

Resuming from 1.54 GB...
```

---

# 12. Linux Completion

```text
┌──────────────────────────────────────────────────┐
│                                                  │
│                     ✓                            │
│                                                  │
│              Transfer Complete                  │
│                                                  │
│               example.zip                       │
│                  2.14 GB                        │
│                                                  │
│            ✓ SHA-256 verified                   │
│                                                  │
│              [ Open File ]                      │
│              [ Done ]                            │
│                                                  │
└──────────────────────────────────────────────────┘
```

---

# 13. Linux Installation / Distribution

Create a simple distributable Linux build.

At minimum, provide a runnable package or binary appropriate for the development environment.

Prefer one of:

```text
AppImage
```

or another easily downloadable Linux package.

The user should be able to:

```text
Download
↓
Run
↓
Application opens
```

without manually configuring the Python environment or installing project dependencies.

If AppImage is practical, prefer it.

Also provide the normal developer run instructions.

---

# 14. Linux Permissions / Networking

The Linux application must be able to:

* discover devices on the local network where supported
* open the required network port
* accept incoming connections
* initiate outgoing connections
* access files selected by the user

Do not request unnecessary privileges.

Do not require root/sudo to run the application unless absolutely unavoidable.

---

# 15. File Picker

Linux should use a native file-selection mechanism appropriate to the chosen UI framework.

The user should never need to type a filesystem path manually for normal transfers.

---

# 16. Cross-Platform Feature Parity

Both platforms should support the same conceptual functionality:

| Feature               | Android | Linux |
| --------------------- | ------- | ----- |
| Send                  | ✓       | ✓     |
| Receive               | ✓       | ✓     |
| Device discovery      | ✓       | ✓     |
| File selection        | ✓       | ✓     |
| Chunked transfer      | ✓       | ✓     |
| Resume                | ✓       | ✓     |
| Pause                 | ✓       | ✓     |
| Cancel                | ✓       | ✓     |
| Progress              | ✓       | ✓     |
| Speed                 | ✓       | ✓     |
| ETA                   | ✓       | ✓     |
| SHA-256               | ✓       | ✓     |
| Transfer history      | ✓       | ✓     |
| Interruption recovery | ✓       | ✓     |

If a feature cannot reasonably be implemented on one platform, document the limitation instead of faking it.

---

# 17. Visual Consistency

Android and Linux should feel like the same application.

Use consistent:

* terminology
* icons
* status indicators
* colors
* typography hierarchy
* transfer states
* progress representation
* error messages

The exact UI layout can adapt to each platform.

Do not make the Linux application look like an unrelated developer tool.

---

# 18. Important Terminology

Use these terms throughout the product:

```text
Device A
Device B
Send
Receive
Connected
Connecting
Reconnecting
Transfer interrupted
Resuming
Transfer complete
Integrity verified
```

Avoid exposing technical terms such as:

```text
HTTP client
HTTP server
upload endpoint
download endpoint
```

to ordinary users.

Those belong in technical documentation.

---

# 19. Security

Maintain the existing security protections.

Both Android and Linux implementations must:

* validate transfer IDs
* validate offsets
* validate file sizes
* sanitize filenames
* prevent path traversal
* restrict received files to the configured destination
* reject malformed requests

Do not weaken existing security to simplify cross-platform communication.

---

# 20. Testing Matrix

After implementation, test at minimum:

### Android → Android

```text
Device A Android
       ↓
Device B Android
```

### Android → Linux

```text
Android
   ↓
Linux
```

### Linux → Android

```text
Linux
   ↓
Android
```

### Linux → Linux

If a second Linux device is available, test it.

---

# 21. Resumability Matrix

Test interruption for at least:

```text
Android → Android
Android → Linux
Linux → Android
```

For each:

```text
Start transfer
↓
Interrupt around 40%
↓
Restore connection
↓
Resume
↓
Complete
↓
Verify SHA-256
```

Do not claim a combination works until it has actually been tested.

---

# 22. Preserve Existing Working Functionality

The existing implementation already successfully resumes a transfer after the server is restarted.

This is critical.

Do NOT replace the existing working transfer mechanism merely to implement the Linux client.

First understand the current protocol and build the Linux client against it.

If architectural changes are genuinely required, explain them before making destructive changes.

---

# 23. Documentation

Update README.md to show:

```text
             RESUMABLE TRANSFER

      ┌──────────────┐
      │   Android    │
      │   Device A   │
      └──────┬───────┘
             │
             │ Local Network
             │
      ┌──────▼───────┐
      │    Linux     │
      │   Device B   │
      └──────────────┘
```

Also show:

```text
Android ⇄ Android
Android ⇄ Linux
Linux ⇄ Linux
```

Explain that either device can send or receive.

---

# 24. Definition of Done

The feature is complete when:

* [ ] Android uses Device A / Device B terminology.
* [ ] Android can send.
* [ ] Android can receive.
* [ ] Linux client exists.
* [ ] Linux client has a polished UI.
* [ ] Linux can send.
* [ ] Linux can receive.
* [ ] Android and Linux use the same transfer protocol.
* [ ] Existing resumability remains functional.
* [ ] Linux can resume interrupted transfers.
* [ ] Android can resume interrupted transfers.
* [ ] SHA-256 verification works across platforms.
* [ ] Large files use streaming/chunked I/O.
* [ ] File paths are safe.
* [ ] UI accurately reflects real transfer state.
* [ ] Linux application can be packaged for download.
* [ ] README contains installation instructions.
* [ ] Cross-platform testing has been performed.

---

# 25. Implementation Order

Do not attempt everything simultaneously.

Implement in this order:

### Phase 1

Refactor terminology/UI from:

```text
Phone / PC / Server
```

to:

```text
Device A / Device B
```

where appropriate.

### Phase 2

Ensure the protocol is platform-neutral.

### Phase 3

Build the Linux client using the existing protocol.

### Phase 4

Implement Linux Send.

### Phase 5

Implement Linux Receive.

### Phase 6

Implement Linux resumability.

### Phase 7

Match the Android UI/UX.

### Phase 8

Package the Linux application.

### Phase 9

Run cross-platform tests.

### Phase 10

Update README and documentation.

---

# Final Instruction

Before writing the Linux application, inspect the current Android/server code and understand the existing protocol.

Do not duplicate the protocol unnecessarily.

Do not rewrite working resumability.

Build the Linux application as another endpoint of the same system.

At the end, provide:

1. Architecture summary
2. Files created/modified
3. Linux technology chosen and why
4. Linux build instructions
5. Downloadable package location
6. Cross-platform test results
7. Known limitations

