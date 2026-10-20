# The Android TV remote protocol

Google TV and Android TV devices run **Android TV Remote Service** (`com.google.android.tv.remote.service`). It is
the TV half of Google's phone remote. Libremote speaks the same protocol. This file describes the protocol as
the service implements it, so the app can be checked against it and kept up to date.

## How this was established

- **Static analysis.** The source was the service APK, version 7.00.999235495 (versionCode 95855584, SHA-256
  `0b2cc948…1b36c`). It was examined with [REA](https://github.com/morluto/rea) (JADX provider) and a JADX
  decompilation. The build is obfuscated with R8, but Flogger log strings keep the original class and method names
  (`TcpPairingServer`, `ConnectedClient.handleConfigure`, `KeyboardFeature`…). Message layouts come from the
  protobuf-lite schema strings, decoded field by field; enum values come from the code that maps them. Names
  below in `code` are the obfuscated classes in that build.
- **Live tests.** `protocol/src/test/.../LiveTvTest.kt` was run against the Android TV 14 emulator, which ships
  the service at version 5.99.551144881. It covered pairing, connection, keys, typing into a text field,
  volume and power state. The older service behaved as the 7.00 analysis predicts.
- Decompiled Google code is **not** part of this repository. Only the facts needed for interoperability are
  recorded here.

REA evidence IDs (same artifact digest):

| What | Evidence |
|---|---|
| Package, manifest, permissions | `ev_0b1306036932bf6a6543fc50e13f20359126e851e380f05a9f8d7d0c1594fb3a` |
| Pairing secret hash (`cej.b`) | `ev_f049d1d5e0f38989bcf5b1e08799b0542068fcf014e4cd23af6bdd698e5a7b46` |
| Code generation and check (`bpx.a`) | `ev_a063e2add07bf294c631b4c0e486e00586ebcfc010736bb67f471106c7cbe764` |
| Key injection (`btd.d`) | `ev_26777d27aad5aacc3474029e317042c5a1e828398e535c4cd4581fcf20d6034f` |

## Discovery

The TV registers the mDNS service `_androidtvremote2._tcp` on port 6466, named after the device
(`DiscoveryService`). Its TXT records may carry `bt` (a Bluetooth address), `id0`–`id2`, and, in standby on
some devices, `wp=6465` and `isDeviceInStandbyMode`. Libremote only needs the name and the address.

## Transport

- Two TLS servers: **6467** for pairing and **6466** for the remote. Both require a client certificate.
  TLS 1.0, TLS 1.1 and every `_CBC_` suite are disabled.
- The TV's certificate is self-signed (RSA, generated on the TV). The pairing port accepts any client
  certificate. The remote port accepts only certificates stored by a successful pairing, so an unpaired phone
  fails the handshake. Under TLS 1.3 that failure can show up only on the first read.
- Connections from the TV's own addresses (loopback or local interfaces) are refused. On the emulator,
  `adb forward` therefore does not work; `adb emu redir add tcp:16466:6466` does.
- Every message, on both ports, is a protobuf message preceded by its length as a varint (`writeDelimitedTo`).

## Pairing (port 6467, "Polo")

Each message is `OuterMessage{1: protocol_version, 2: status, <one payload field>}`. The TV accepts versions 2–9
and sends 2. Status 200 means OK. On failure the TV sends a status with no payload and closes the connection:
400 for errors, 401 for a bad configuration, 402 for a bad secret.

| Field | Message | Content |
|---|---|---|
| 10 | PairingRequest | `1: service_name` (required), `2: client_name`, which the TV shows ("…on your *Libremote*…") |
| 11 | PairingRequestAck | `1: server_name` (the TV sends none) |
| 20 | Options | `1: input_encodings[]`, `2: output_encodings[]`, `3: preferred_role` |
| 30 | Configuration | `1: Encoding`, `2: client_role` |
| 31 | ConfigurationAck | empty |
| 40 | Secret | `1: bytes` |
| 41 | SecretAck | `1: bytes` |

`Encoding{1: type, 2: symbol_length}`. Encoding types: 0 unknown, 1 alphanumeric, 2 numeric, 3 hexadecimal,
4 QR code. Roles: 0 unknown, 1 input, 2 output.

Sequence: the phone sends PairingRequest, Options, then Configuration with `{HEXADECIMAL, 6}` and
`client_role = INPUT`. Any other encoding gets "Unsupported encoding type". The TV, as the output role, then shows
the code.

**The code.** The TV draws two random bytes (the nonce). It computes

    hash = SHA-256(client_modulus ‖ client_exponent ‖ server_modulus ‖ server_exponent ‖ nonce)

over the two certificates' RSA public keys, in big-endian form with leading zero bytes removed, phone first. It
shows `hex(hash[0] ‖ nonce)` in upper case. The phone recomputes the hash from the typed code and sends it as the
Secret; the TV compares all 32 bytes. Because the first byte is a checksum, the phone catches almost every typo
before sending anything. The TV keeps waiting, so the user can correct the code.

**The TV's side effects.**

- On success, the TV stores the phone's certificate. It first **deletes any trusted certificate with the same
  subject DN**, so every install needs a unique certificate subject.
- Every code shown counts as an attempt, and success resets the count. After failed attempts the TV waits
  before it accepts the next pairing connection: 30 s at the 5th attempt and from the 10th on, then doubling
  every ten failures after the 30th, and a day after 140. During that wait the TCP connection is open but
  TLS does not start.

## Remote (port 6466)

Every message is a RemoteMessage with exactly one field set. The service's own names for the phone-to-TV cases
survive in the build (`bzk`):

| # | Phone → TV | Content |
|---|---|---|
| 1 | CONFIGURE | `1: features`, `2: DeviceInfo{1: model, 2: manufacturer, 3: os (1 Android, 2 iOS), 4: os_version, 5: app_name, 6: app_version}` |
| 2 | SET_ACTIVE | `1: features` |
| 8 / 9 | PING_REQUEST / PING_RESPONSE | `1: value` (echo the TV's value) |
| 10 | KEY_INJECT | `1: Android key code`, `2: direction` (1 down, 2 up, 3 press). 0 is rejected |
| 11 | MOTION_INJECT | eight floats (gamepad axes) |
| 20 | IME_KEY_INJECT | `1: int` |
| 21 | IME_BATCH_EDIT | `1: field id`, `2: text counter`, `3: edits[]`; see below |
| 22 | IME_SHOW_REQUEST | `1: show`, `2: flags` |
| 30–32 | VOICE_BEGIN / VOICE_PAYLOAD (raw bytes) / VOICE_END | voice search sessions (PCM or LC3) |
| 50 | SET_VOLUME_LEVEL | `1: audio device id`, `2: level`, `3: muted` |
| 51 | ADJUST_VOLUME_LEVEL | `1: audio device id`, `2: 0 raise, 1 lower, 2 toggle mute` |
| 60 / 61 | SET / RESET_PREFERRED_AUDIO_DEVICE | `1: audio device id` / empty |
| 70 | FMR_TRIGGER | `1: 1 activate, 2 deactivate` (Find My Remote) |
| 90 | APP_LINK_LAUNCH_REQUEST | `1: url` (required), `2: package`, `3: open the Play Store page when not installed`, `4: start playback` |

| # | TV → phone | Content |
|---|---|---|
| 1 | Configure | `1: features offered`, `2: DeviceInfo` (the TV's; the service names itself as the app in `5` and `6`, e.g. `com.google.android.tv.remote.service`, `5.99.551144881`), `3: [1]` when it can decode LC3 voice |
| 2 | SetActive | empty: "the features are ready" |
| 3 | Error | `1: code`, `2: the offending message`. The TV then closes the connection |
| 8 | PingRequest | `1: sequence`, `2: TV uptime` |
| 20 | IME field | `1: EditorInfo`, `2: TextStatus`; see below |
| 21 | IME show | `1: show`, `2: flags`: the TV's keyboard opens or closes |
| 22 | IME text | `1: this phone made the change`, `2: TextStatus` |
| 23 | IME selection | `2: start`, `3: end` |
| 24 | IME reset | empty: another source typed, so blind edits are stale |
| 29 | Completions | `1: counter`, `2: [{1: id, 2: position, 3: text, 4: label}]` |
| 30 / 31 | Voice session start / ready | session handling |
| 40 | Power | `1: screen on` |
| 50 | Volume | `1: device id`, `2: device type`, `3: device name`, `4: control (0 none, 1 relative, 2 absolute)`, `5: min`, `6: max`, `7: level`, `8: muted` |
| 70 | Find My Remote | `1: available` |

Error codes: 0 unspecified, 1 already configured, 2 not ready, 3 feature not available, 4 feature not
requested, 5 unknown client message, 6 generic failure, 7 invalid argument.

**Handshake.** The TV sends Configure. The phone answers Configure with the features it wants, which must be a
subset of those offered: any other bit gets error 3 and a disconnect. Once every feature is prepared, the TV sends
SetActive and the phone answers SetActive. Sending a command for a feature that was not requested gets error 4
and a disconnect.

**Features.** 1 ping, 2 keys, 4 IME, 8 voice (24 with the Google Assistant), 32 power, 64 volume, 256 gamepad,
512 app links, 2048 Find My Remote (offered only when the TV has a remote that can beep).

**Pings.** While a phone is active, the TV pings every 5 s (every 120 s otherwise). It expects the answer within
1.5 s (5 s), and drops the phone after three late answers.

**Keys** are injected through a virtual input device, as from a remote. A held key (down, then up later)
long-presses and auto-repeats on the TV. Power goes through a second device when there is one. D-pad and
gamepad key codes go to the gamepad handler instead when the gamepad feature is active.

**Typing (IME).**

- While a phone has the IME feature active, the TV switches its input method to a bridge IME. It switches back
  when the last such phone leaves.
- On focus, the TV sends field 20:
  - `EditorInfo{1: field id, 2: inputType, 3: imeOptions, 4: privateImeOptions, 5: actionLabel, 6: actionId,
    7–8: initial selection, 9: caps mode, 10: hint, 11: label, 12: package, 13: fieldId, 16: locales,
    17: MIME types}`
  - `TextStatus{1: counter, 2: text, 3: selection start, 4: selection end, 5: flags, 6: hint}`
- With no editable field (`inputType` 0), only the package of the app in front is sent.
- Edits are `IME_BATCH_EDIT{1: field id, 2: text counter, 3: edits}`:
  - The TV applies a batch only when the field id matches its current field.
  - It also checks the text counter, unless the phone was the last editor.
  - Each edit is one of: `1: {2: start, 3: end}` set the selection; `2: {1: start, 2: end, 3: text}` replace
    the range (`setComposingRegion` and `commitText`); `5: {1: action}` perform the editor action.
- Field id 0 means "blind": for each replace, the TV presses Delete `end − start` times, then types the text.

**Volume.** Requests must carry the current audio device id from the latest Volume message. Absolute levels need
control 2.

**App links.** The TV starts `ACTION_VIEW(url)`, restricted to the package when one is given. Two limits, seen on
the emulator's 5.99 service:

- It only finds packages that Android lets it see (package visibility). It reports others as not installed, even
  when they are, so a link to an app that only it opens (such as a file receiver, SENDFILES.md) should name no package.
- A link that no app on the TV handles gets error 7 (invalid argument), and the TV drops the connection.
- With a package it doesn't see and field 3 false, the TV does nothing and reports nothing. With field 3 true it
  opens that package's Play Store page, which fails with error 7 on a TV without the Play Store.

So Libremote opens an app on the TV in one of these ways (seen working on the emulator):

- `https://play.google.com/apps/launch?id=<package>` with package `com.android.vending` and field 3 true: the Play
  Store opens the app, or shows its page when it isn't installed. On a TV without the Play Store (or with it
  disabled) this gets error 7, which is how Libremote learns that the TV has none.
- An app's own scheme, with no package, such as `vnd.youtube://` for YouTube.
- Libremote Receiver's link, `libremote://launch?id=<package>&name=<name>`, on TVs that have it (SENDFILES.md).

`market://launch` no longer exists (error 7), `intent:` URIs and bare package names get error 7, and an `https`
link without a package opens in a browser on Android 12 and later.

## Telemetry on the TV

The service bundles Google's Clearcut logging, Primes performance monitoring, Phenotype remote flags and GMS usage
reporting. When a phone becomes active, it logs a remote-session event (`EnabledClearcutLogger`, event 304) that
includes the phone's DeviceInfo. Those events are uploaded only if usage reporting is on in the TV's settings; the
TV drops them otherwise. Libremote cannot change what the TV does, but it sends the TV no phone details: its
DeviceInfo just says "Libremote".
