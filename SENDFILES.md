# Sending files to the TV

Google's TV Remote Service, which Libremote's remote talks to, cannot receive files. Since 0.3.0, Libremote sends
files only to its own app on the TV, **Libremote Receiver** (`tv/`, `app.libremotereceiver`), over an encrypted
channel that only phones paired with the TV can use. This file describes that channel, what it protects against,
and why it is built the way it is.

Up to 0.2.0, Libremote also spoke the plaintext protocol of the **Send files to TV** app
(`com.yablio.sendfilestotv`), and its receiver accepted that app's transfers. **Libremote no longer speaks that
protocol**: it has no encryption and no pairing. What was worked out about it is kept at the end of this file as
history ([The Send files to TV protocol](#history-the-send-files-to-tv-protocol)). The new channel reuses its packet
framing, inside TLS.

## Libremote Receiver's channel

Code: `protocol/…/ReceiverChannel.kt` (the link, the proof, the code, TLS settings), `ReceiverClient.kt` (phone),
`ReceiverServer.kt` (TV), `PhoneRegistry.kt` (which phones the TV accepts, and what it asks), tested by
`ReceiverChannelTest` and `PhoneConfirmationTest`; in the apps, `app/…/net/Receivers.kt` and `SendController.kt`,
and `tv/…/ReceiverHost.kt`, `PairedPhones.kt` and `ConfirmPhone.kt`.

### In short: what pairing with the receiver covers

- **Sending files, updating the receiver and the app list** take a phone paired with the receiver: its TLS key is
  on the receiver's list. A phone gets there only when the user allows it on the TV, which shows a code the phone
  shows too ([Allowing a new phone](#allowing-a-new-phone)). A phone removed in the receiver's Settings stays out
  until the user allows it again there.
- **Updates of the receiver** must also be the same package, a higher version, and signed by every certificate the
  installed receiver is signed with (a newer certificate may be added, for key rotation). Android's installer asks
  on the TV before installing ([Updating the receiver](#updating-the-receiver)).
- **Opening apps** (`libremote://launch`) doesn't involve the receiver's pairing at all, by design: the link comes
  through the TV's remote service, and any app on the TV may start another app anyway. Removing a phone from the
  receiver doesn't stop it; unpairing it from the TV's remote service does (on most TVs, clearing that service's
  data in Settings › Apps, which unpairs every phone).
- **Interrupted transfers** don't resume: a file shows up only once it arrived whole, a partial one is deleted, and
  whatever a transfer cut short by the TV's end left behind (the receiver killed, the TV unplugged) is removed the
  next time the receiver starts (only what is older than that start, so a transfer under way is left alone).

### Threat model

**What it protects against:** anyone else on the local network, passive or active (a guest's laptop, a compromised
device, someone who redirects traffic with ARP or DNS spoofing or a rogue access point). They can't:

- send files to the TV, see its app list, or open apps through the receiver;
- read files in transit, or change them;
- pose as the TV's receiver to collect a phone's files.

**What it trusts:**

- **The phone's pairing with the TV.** Only a phone paired with the TV's remote service (with the code the TV
  shows) can send the TV an app link through it, and the link is what pairs a phone with the receiver.
- **The person at the TV.** The receiver doesn't pair a phone it doesn't know just because a link arrived: any app
  on the TV can start that link. The TV asks first, with a code that only the phone that made the link can show
  ([Allowing a new phone](#allowing-a-new-phone)).
- **The TV itself:** Android on it, Google's remote service, and the apps installed on it. The link travels from
  the phone over its encrypted remote connection, which is pinned to the TV's key, then through the remote service
  and Android's activity manager to the receiver.

**Limits, accepted on purpose:**

- Any app on the TV can start the receiver's link with a key of its choosing, and a name of its choosing, which
  makes the TV ask. It can't make the TV show the code the user's phone shows (it doesn't know that link's secret),
  and while one question is open, links from other new keys are ignored. Such an app could keep the TV asking, which
  is a nuisance and nothing more; it can already write to Downloads, start apps and list launchable ones by itself.
- An app on the TV that registered the same link could get a phone's invitation instead of the receiver (alone,
  or chosen by the user from Android's chooser) and then act as the receiver for that phone.
- A phone removed in the receiver's Settings stays removed: its next link only makes the TV ask again, saying it
  was removed before. It can still use the TV as a remote and open apps through it until it is unpaired from the
  TV itself.
- Libremote before 1.0.1 shows no code and can't wait for an answer: the TV asks all the same (saying the phone
  shows no code), turns that phone away until the user allows it, and the phone's retries, or its next send, get in.
- The secret passes through Google's remote service. It is useful only to someone who can also intercept that
  phone's next connection on the local network, and only for that connection. The key hash in the link is not
  secret: the remote service already has the phone's whole certificate.
- Someone on the network sees that the phone talks to port 6469, when, and roughly how much. Over TLS 1.2 (when
  either end runs Android 8 or 9) the certificates also travel in clear; they are self-signed, named
  `libremote/<random>` and `libremote-receiver/<random>`. Over TLS 1.3 they are encrypted.
- Received files are stored as any download: unencrypted, in Download/Libremote, where other apps on the TV with
  storage access can read them.

### Pairing: the link

When the phone needs the receiver and the receiver doesn't answer, doesn't accept the phone, or presents a key the
phone hasn't pinned, the phone sends an app link over its remote connection (APP_LINK_LAUNCH_REQUEST with no
package; see PROTOCOL.md, App links):

```
libremote://receive?v=2&k=<phone key hash>&s=<secret>&n=<phone name>
libremote://sync?v=2&k=<phone key hash>&s=<secret>&n=<phone name>
```

- `v` is the link's version: 2 from Libremote 1.0.1, which can wait while the TV asks; 1 (or none) before.
- `k` is the SHA-256 of the SubjectPublicKeyInfo of the phone's certificate, in lowercase hex. It is the identity
  the phone already uses with the TV's remote service (`ClientIdentity`, `IdentityStore`): RSA-2048, self-signed,
  named `libremote/<16 hex digits>`.
- `s` is 32 random bytes in hex, new for every link.
- `n` is the phone's device name, percent-encoded, at most 60 characters, for the TV to show when it asks. Anyone
  can write any name there; the code is what identifies the phone.
- `receive` opens the receiver's screen; `sync` opens it only for a moment (see Opening the receiver).

For a phone it has paired, the receiver keeps `s` in memory for that phone's next connection, replacing any earlier
one. For any other key it pairs nothing yet, and asks ([Allowing a new phone](#allowing-a-new-phone)). Secrets are
never written to disk on either side. On Android
14 the activity manager logs the link as `libremote://receive/...` (**verified**); older versions may log all of
it, in logs only the system and adb can read.

### Allowing a new phone

When a link comes from a key the receiver hasn't paired (a new phone, or one the user removed), the TV asks:
"Allow <name>?", or "Allow <name> again?" with "You removed this phone before.", over whatever screen the link
opened, with **Don't allow** focused. It shows a six-digit code:

```
code = first 4 bytes, as an unsigned big-endian number, of
       HMAC-SHA256(key = secret, "libremote confirm code v1" ‖ 0x00 ‖ SHA-256(phone SPKI))
       mod 1 000 000, written as "123 456" (with a no-break space)
```

The phone computes the same code from its own link and shows it ("Allow this phone on <TV>: choose Allow there if it
shows the code 123 456"). Only the phone that made the link knows its secret, so a link forged by another app shows
a code the user's phone doesn't. The answer:

- **Allow** pairs the key (with the name from the link, until the phone gives its own), and clears a removed mark.
- **Don't allow**, Back, leaving the screen, or 60 s without an answer pair nothing. A removed phone stays marked.
- One question at a time: while it is open, links from other new keys are ignored, and a new link from the same
  phone replaces the question (and its code); a connection waiting on the old question waits for the answer to
  the new one. An answer counts only for the question it was given to.

On the channel ([Requests](#requests)): while the TV asks about a phone that sent a version 2 link, the receiver
accepts that phone's key in the handshake, and sends a first hello `{"receiver":"Libremote Receiver","version":"1",
"confirm":"1"}` with no proof. It then reads nothing until the user answers: on Allow it sends the usual hello,
with the proof; otherwise an ERROR ("This phone wasn't allowed on the TV.") and it closes the connection. The phone
shows the code when it gets the first hello, waits up to 70 s (checking every second whether the user cancelled),
and reports an ERROR as "the TV didn't allow this phone". A connection that brought no secret (the phone tried its pin) can't show a
code: it hangs up on a "confirm" hello and sends a link instead. A phone with a version 1 link is turned away in the
handshake until the user allows it, as any unpaired phone.

### Transport

- **TCP port 6469**: next to the remote service's 6466 and 6467, and below the range that Linux and Android give
  outgoing connections (32768–60999), so no outgoing connection on the TV ever holds it.
- **TLS 1.3** when both ends have it: Android 10 and later, Java 11 and later. **Android 8 and 9 have only TLS 1.2**;
  with them it is TLS 1.2 limited to forward-secret AEAD suites, `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`,
  `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` and `TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256`. Two ends that both
  have TLS 1.3 can't be pushed down to 1.2: TLS 1.3's downgrade protection (RFC 8446, 4.1.3) catches it.
- Both ends present self-signed RSA-2048 certificates; neither checks names or chains, only keys.

### How each end knows the other

**The receiver accepts only paired phones.** It requires a client certificate, and accepts it only if the SHA-256
of its SubjectPublicKeyInfo is among its paired phones, or is the phone the TV is asking about right now (which then
gets nothing but the "confirm" hello until the user allows it). Any other phone is turned away during the handshake
(under TLS 1.3 the phone notices at its first read). **Verified** on the Android TV 14 emulator: `certificate_unknown`.

**The phone accepts the receiver by its pin, or by a proof.** During the handshake the phone takes any server
certificate; before it sends anything, it checks it:

- If the key hash equals the pin it keeps for this TV, the receiver is accepted.
- Otherwise, the receiver's first message must prove the secret of the link the phone just sent:

  ```
  proof = HMAC-SHA256(key = secret,
                      "libremote receiver proof v1" ‖ 0x00 ‖ SHA-256(phone SPKI) ‖ SHA-256(receiver SPKI))
  ```

  where both hashes are of the certificates in this TLS session. The phone recomputes it and compares in constant
  time. If it matches, the phone pins the receiver's key for this TV; if not, it gives up and tells the user that
  nothing was sent.
- The receiver adds a proof only when it holds a secret for the phone at the other end, and forgets the secret once
  used.

**Why that is enough.** The TLS handshake already proves that each end holds the private key of the certificate it
presented (CertificateVerify and Finished, over the whole handshake). So:

- An attacker in the middle presents a key of its own. It can't make a valid proof without the secret, which went
  to the TV only, over the phone's TV-pinned remote connection. A proof made by the real receiver is no use to it:
  the phone hashes the key it actually sees.
- It can't relay the phone to the real receiver instead: the receiver answers only paired phones, and the attacker
  doesn't have the phone's private key.
- Another paired phone gains nothing: the receiver makes proofs only with the secret of the phone it is talking to,
  bound to that phone's key.
- A replayed proof names an old pair of keys, and each secret is used once.

A TLS exporter value (RFC 8446, 7.5) would tie the proof to the session as well, but Android has no public API for
exporters on Android 8 and 9, nor does Java 21. Binding both keys gives the guarantee needed here, because each
key's possession is proven by the handshake itself.

**Later connections** need only the pin. When the receiver's key changes (reinstalled, or its data cleared), the pin
fails; the phone sends a fresh link, which also pairs it again with the new install, and the new key must prove the
new secret (**verified**: Try again after reinstalling the receiver re-pinned it).

### Requests

Inside TLS, every message uses the framing described under [Packets](#packets): the payload length plus one as a
little-endian int32, a type byte (0 SUCCESS, 1 ERROR, 2 JSON, 3 BINARY), the payload. Numbers in JSON are strings.

1. Receiver to phone, right after the handshake:
   `{"receiver":"Libremote Receiver","version":"1","app":"<versionCode>","proof":"<hex>"}`, the proof only when the
   receiver has a secret for this phone. (For a phone the TV is asking about, a "confirm" hello comes first; see
   [Allowing a new phone](#allowing-a-new-phone).) `version` is the channel's version; `app` is the receiver's own
   versionCode (MAJOR × 10000 + MINOR × 100 + PATCH, so 1.0.0 is `10000`), which the phone uses to offer updates
   ([Updating the receiver](#updating-the-receiver)). Receivers before 1.0.0 don't send `app`: the phone then treats
   the version as unknown and offers no update. From 1.0.1 the hello also has `"arrived":"1"`: the receiver can
   confirm each file of a send (below).
2. Phone to receiver, only once the receiver is verified: `{"request":"hello","name":"<the phone's device name>"}`.
   The receiver lists the phone under that name. (Google's remote service is only ever told "Libremote".)
3. Then any number of requests, each answered before the next:
   - **send**: `{"request":"send","count":"<files>","size":"<total bytes>"}`, then for each file
     `{"type":"file","name":"<path>","size":"<bytes>","last_modified":"<ms>"}` and its BINARY packets of 64 KiB. The
     receiver answers SUCCESS, or ERROR with a reason. The phone reads the answer while still sending, so a refusal
     stops the transfer at once; lack of space is checked before any data. After an ERROR the receiver closes the
     connection. One transfer runs at a time; another phone is told the TV is busy.
     With `"update":"1"` in the request, the batch is a new version of the receiver itself: see
     [Updating the receiver](#updating-the-receiver). Such a request must have `"count":"1"`, or it gets an ERROR.
     With `"arrived":"1"` in the request, which the phone adds only when the receiver's hello offered it, the
     receiver also sends `{"arrived":"<index>"}` (from 0) as each file is saved and visible in Download/Libremote,
     then SUCCESS. When the connection breaks, the phone counts as arrived only the files confirmed this way, says
     which file was cut off and which weren't sent, and Try again sends only the rest. Bytes the phone wrote are no
     proof: they may still have been on their way. With a receiver before 1.0.1, which doesn't offer it, the phone
     can say only which file it was sending, and Try again sends the whole batch again. A phone before 1.0.1
     doesn't ask, and gets only the SUCCESS it expects. Updates don't ask for confirmations.
   - **apps**: the answer is `{"apps":[{"package":"…","label":"…","icon":"<PNG size>"},…]}`, then one BINARY packet
     per icon that isn't empty, in the same order: the TV's launchable apps (those with a TV launcher entry, then
     those with only a phone one), each with a 128 px PNG icon.
   - **close**: SUCCESS, then a receiver opened with `libremote://sync` closes.
   - Anything else gets an ERROR, and the connection stays usable (for later versions).
4. Timeouts: 10 s for the handshake and 30 s of silence between requests on the receiver; 30 s without progress or
   confirmation on the phone.

### Opening the receiver, and the other links

- The remote service starts links with ACTION_VIEW and no package: it can't see the receiver's package (PROTOCOL.md,
  App links). A link nothing handles makes it answer error 7 and drop the remote connection, which Libremote then
  re-establishes.
- The phone first connects with its pin. It sends a link only when that fails, waits for the link's result (about
  1.5 s, since the TV reports only failures), then retries the connection for up to 10 s while the receiver starts.
- When the link fails, the phone remembers that this TV has no receiver and sends it no more links (each failure
  drops the remote for a moment) until the user selects Try again.
- `libremote://receive?…` opens the receiver's screen; a link that arrives while it is open reaches the same screen.
  It listens only while the screen is open.
- `libremote://sync?…` shows a small card over whatever the TV shows, in a task of its own. It listens until the
  phone sends close (at most 20 s), then finishes, and the TV is back where it was (**verified** over YouTube).
- `libremote://launch?id=<package>&name=<name>` starts that app's TV launcher entry, else its launcher entry, from
  an activity with no window, which finishes at once. It needs no pairing: any app may start another app's launcher
  entry. The receiver declares `<queries>` for MAIN with LEANBACK_LAUNCHER and MAIN with LAUNCHER, so it sees every
  launchable app without QUERY_ALL_PACKAGES. When the app isn't installed, the TV shows its Play Store page, or, on a
  TV without the Play Store, a card naming it (`name`, at most 60 characters) for 5 s. The phone sees which came to
  the front (the foreground package in the IME message) and says that the app isn't on the TV.

### On the TV

- **Paired phones:** key hash, name and pairing date, in the receiver's private storage, and the key hashes of the
  phones the user removed. Removing one in Settings also drops its connections.
- **The receiver's identity:** like the phone's, RSA-2048 and self-signed, its private key encrypted with an
  AndroidKeyStore AES key, in no-backup storage.
- **Files:** Download/Libremote, keeping the sender's folders; names can't leave the folder (`safeTransferPath`).
  From Android 10 they go through MediaStore and show up once complete. On Android 8 and 9 (storage permission,
  asked when the receiver opens) they are written under a hidden temporary name, renamed when complete and added to
  the media index, and other apps open them through the receiver's own ContentProvider. Partial files are deleted;
  when the receiver itself was cut off mid-transfer, it removes the leftovers (hidden `.part` files on Android 8
  and 9, entries still marked pending from Android 10, a partial update in its cache) the next time it starts
  (only what is older than that start).
- **Installing:** an APK opens the system installer, which asks before installing anything (and, the first time,
  asks to let the receiver install apps). With "Open the installer when an app arrives" on, a batch that arrived
  whole with an APK opens it by itself.

### Updating the receiver

The receiver never goes online: Libremote on the phone keeps it up to date (standard build only; the Google Play
build has no updater). Code: `app/src/standard/…/update/` on the phone, `tv/…/SelfUpdate.kt` on the TV, tested by
`ReceiverChannelTest` (the version in the hello, the marked request, its refusals) and, for the release rules,
`ReleasesTest` in `:app`.

1. The phone learns the receiver's versionCode from the hello (`app`), on every connection (sending files, fetching
   the app list), and keeps it per TV.
2. When the phone checks for updates (by hand, or once a day if the user turned that on), the latest GitHub release
   may carry `libremote-receiver-<version>.apk`. If that version is newer than the TV's, the phone offers "Update
   Libremote Receiver" in About this TV, and once in the TV menu (dismissable). An unknown version gets no offer.
3. When the user accepts, the phone downloads the APK and checks it before anything reaches the TV: its size and
   SHA-256 against the release's asset digest (when GitHub gives one), its package (`app.libremotereceiver`), that
   it is newer than the TV's, and that it is signed with Libremote's release certificate. Any failure stops there.
4. It opens the receiver as for a send (`libremote://receive`), and sends the APK as one send request marked
   `"update":"1"`, with the name `libremote-receiver-<version>.apk`.
5. The receiver writes a marked request to its own cache (not Download/Libremote, and with no storage permission
   needed), then checks it again: the package must be its own, the versionCode higher than its own, and the
   signing certificates must include its own. A refusal goes back to the phone as ERROR with the reason. Only then
   does it answer SUCCESS and open the system installer at once, whatever "Open the installer when an app arrives"
   says, since the user asked on the phone. Android asks to confirm on the TV (and, the first time, to let the
   receiver install apps), and closes the receiver while it updates.
6. The phone waits up to two minutes for the receiver to come back and reads the new version from its hello. The
   user has to open it again: Android TV's installer greys out Open for an app with only a TV launcher entry
   (**verified** on the Android TV 14 emulator), and an app can't restart itself after its own update. Otherwise the
   phone sees the new version at the next connection.

A receiver before 1.0.0 ignores `update` and saves the APK in Download/Libremote like any file (the phone never
offers it an update, since it doesn't know its version). The receiver's own first screen after an update says
"Libremote Receiver is updated to <version>".

## What Libremote 0.2.0 did

Before 0.3.0, Libremote sent files the way Send files to TV does, in plain TCP to port 40818, and asked the TV to
open a receiver when nothing listened: `libremote://receive`, then `sfttv://transfer/`. Its receiver announced
`_nitroshare._tcp` and took transfers from anyone on the network while its screen was open. All of this is gone.

## History: the Send files to TV protocol

What follows describes that app's protocol as it was established for Libremote 0.2.0. It is kept as a record, not
as something Libremote does.

### How this was established

- **Static analysis.** The source was the APKMirror bundle `sendfilestotv.apkm` (SHA-256 `af371943…2c861`):
  version 1.4.22, versionCode 69, minSdk 23, targetSdk 35. Its `base.apk` (SHA-256 `7ecf0b96…7c36bf`) was examined
  with [REA](https://github.com/morluto/rea) (JADX provider) and a JADX decompilation. The app's own classes keep
  their names (`transfer.Packet`, `transfer.TransferServer`, `bundle.FileItem`…); only the libraries are renamed.
  Names below in `code` are the app's own.
- **Live tests.** The bundle (base plus the x86, xhdpi and English splits) was installed on the Android TV 14
  emulator. Hand-made packets from the host, `protocol/src/test/.../LiveReceiverTest.kt` (at the time), and Libremote on a phone
  emulator were sent to its receiver through `adb emu redir add tcp:40818:40818`. Where a fact below was checked
  this way, it says **verified**.
- Decompiled code is **not** part of this repository. Only the facts needed for interoperability are recorded here.

REA evidence IDs (all for `base.apk`, same digest):

| What | Evidence |
|---|---|
| Package, manifest, permissions, SDK components | `ev_cf0ea3678ab8307652df50774d5b686d89ab41c914ca54effdaceedca91272e3` |
| App classes | `ev_705a52cc438d6f4a63cb8569c8e01eb1982498c57f09e1bb2a58e9c6ca0b9f43` |
| Packet framing, writing (`Packet.<init>(int, byte[], int)`) | `ev_31b92f7aa9351122cc9db11b10df20a9c011dbc495ed0409cc7722e39bc5705f` |
| Packet framing, reading and size limit (`Packet.read`) | `ev_f5ecbb92d6144ea1c89a11aa4f0f4857566426bd6259e6c51f3aa3f5f0fdf9d5` |
| Transfer header (`Transfer.sendTransferHeader`) | `ev_b6a3e1ecbc96ad0b3373e553029cb422142ecf6f453898754442f3bdf9e72926` |
| Item headers as the phone sends them (`FileItem.<init>(AssetFileDescriptor, String)`) | `ev_32a7ead74269b736ae37b6ed8359a11f317cecae960396f0208e1e6bcaf4739f` |
| Receiving: packet order, SUCCESS, ERROR (`Transfer.processNextPacket`) | `ev_1aa2f05683653369f022fa5e0a440c778362537ff3e7d49f7ab05db8efc8f170` |
| Receiving: item types, empty files (`Transfer.processItemHeader`) | `ev_b363b8f1383a4d37454be184d79b1cf43e67f5c3f6ba15a340ea85d3a7bdb75c` |
| Sizes must be strings (`Item.getLongProperty`) | `ev_7560e0e1940ec45fb117eb175d053a32c7ae24f547e01b03dbfe81d42f99b402` |
| Where a received file goes, renaming (`FileItem.<init>(String, Map, boolean)`) | `ev_13e023de1ec29b54c6f0aaed9e8a10e41a762892e8b40d8132cbb552e96772c5` |
| Connection loop, no timeouts (`Transfer.run`) | `ev_8993f0de721aab69d3bdc7d216156f176a785e205f6a2d5d7eaeede8185ea56e` |
| Server: port, mDNS, no checks on who connects (`TransferServer.run`) | `ev_d8696c007eeb4cc804626b53c512aec4b300ffaa21395a981e498371acabb5fb` |
| mDNS service (`Device.toServiceInfo`) | `ev_e1854bd8502e1e9fc25e1aefc9313c95dbf1daabd2453afa22121b28dccbd87b` |
| "Pairing Id" (`IPEncode.encode`, `ShareActivity.addPairingDevice`) | `ev_76c743002f9541ca0dbbc1e6b92c76494e1b6d4732b97086cff92eec9fcd7bbf`, `ev_0d7fce77c1715777da9c506c86f6f617c461908e9ff09910f7b2a78288b96a5c` |
| What the phone can send (`TransferService.createBundle`) | `ev_a715ac20be5af068b0bfb3247062958a5103d26fbb15a26c552b9bd4f77ecf68` |
| Boot receiver does nothing (`StartReceiver.onReceive`) | `ev_c37298cd986a39ff7a452931257d0dee8b73247e59df9130d1b1bb683adbcf85` |
| Analytics events and their contents (`myApplication.logEvent`, `getBundleAnalytics`) | `ev_3798622fbc292557d2abeefba0484d4aefac7392c923d1d154b639ab12243c0f`, `ev_d0e80acde11ee1ba8dc8b6726da4e61b2457c8813952ed5e9a7b2310a80ee495` |
| Ads on the Receive screen (`TransferActivity.lambda$onCreate$0`) | `ev_217696bdc061b55acf7a2801e58f266b2de279eccf551d849c0f9604aa6bf7e1` |

### What the app is

- **One app for phone and TV.** The same APK has a phone launcher entry and an Android TV (leanback) one. Every
  device that runs it can send and receive; "the TV side" is that app with its Receive screen open.
- **Sending:** files from the system picker or another app's share menu (`content:` URIs), and files and folders
  from its own file browser (`file:` paths; a folder is sent as its files, named by their path inside it).
- **Receiving:** files go to Download by default (a setting). Received files can be opened from the app; an APK
  opens the system installer, for which the app holds `REQUEST_INSTALL_PACKAGES`.
- **Links:** the receiver accepts a link as an item (`type: "url"`) and shows it as a notification. The app's own
  sender never creates one.
- There is no web upload page: the transfer server is the app's only server.
- **The protocol is NitroShare's** (inferred): the mDNS service type is `_nitroshare._tcp`, and the class layout
  (`bundle.*`, `transfer.*`, `discovery.Device`) matches NitroShare's Android app.

### Discovery

- The receiver announces the mDNS service `_nitroshare._tcp` on port 40818, named after the device (its own
  "device name" setting, which defaults to `Build.MODEL`), with no TXT records (**verified**, `dumpsys
  servicediscovery` on the TV). The sender browses for the same type, resolves each service and connects to the
  address and port it gets, skipping its own name.
- **"Pairing Id"**, for networks where mDNS fails, is not pairing: there is no secret. The receiver shows four
  letters that spell the last two octets of its IPv4 address in hex, each hex digit replaced by the letter at the
  same place in `ERYUPDFGHKLJNWXC` (0 → E … F → C). Only private addresses (10/8, 172.16/12, 192.168/16) get one.
  The sender takes the first two octets from its own address, pings the result with `/system/bin/ping`, and
  connects to port 40818. The emulator, at 10.0.2.15 (2.15 → `020F`), showed **EYEC** (**verified**).

### Transport

- **Plain TCP on port 40818**, no TLS. There is no authentication, pairing or confirmation on the TV: the receiver
  takes a transfer from anyone who connects (**verified** from the host).
- **When it listens.** A foreground service starts listening when the app's main screen or its Receive screen opens.
  It stops after 480 000 ms (8 minutes) without a transfer (`TransferService.INACTIVITY_TIMEOUT`), or when the user
  exits; it had stopped within ten minutes on the emulator (**verified**). The boot receiver only writes a log line,
  so nothing listens after a restart until the app is opened.
- **Opening it remotely.** The Receive screen (`TransferActivity`, exported) handles `VIEW` of `sfttv://transfer/`;
  opening it starts the service. Through Google's remote service (an app link, PROTOCOL.md) this works when the link
  names no package (**verified**); see below for why a package can't be named.

### Packets

Every packet, in both directions:

| Bytes | Content |
|---|---|
| 0–3 | payload length + 1, int32, **little-endian** |
| 4 | type: 0 SUCCESS, 1 ERROR, 2 JSON, 3 BINARY |
| 5… | payload: UTF-8 JSON, UTF-8 text (ERROR), or file data (BINARY) |

The receiver refuses a payload over 4 194 304 bytes (4 MiB). The app sends file data in 64 KiB packets.

### A transfer

1. The sender connects and sends a JSON **transfer header**: `{"name": <the sender's device name>, "count": "<number
   of items>", "size": "<total bytes>"}`. The numbers are strings.
2. For each item, a JSON **item header**, then BINARY packets carrying exactly `size` bytes; none when the size is 0.
   For a `content:` URI the app sends `{"type": "file", "name": <file name>, "size": "<bytes>", "created": "0",
   "last_read": "0", "last_modified": "0", "directory": false}`. For a `file:` path it also sends `read_only` and
   `executable` (booleans) and the real `last_modified` in milliseconds. `name` may contain `/` for folders.
3. After the last byte the receiver answers **SUCCESS** (an empty packet: `01 00 00 00 00`, **verified**) and the
   sender closes the connection.

The receiver also accepts an ERROR packet from the sender at any point, and ends the transfer with its text as the
reason. It never sends one itself.

**What the receiver requires** (all **verified** on the emulator, which showed each failure on screen):

- `size` must be a JSON string. A number fails the item: "cannot read "size" property".
- `last_modified` must be present, as a string. Without it the data is written, then the item fails: "missing
  "last_modified" property". A value other than "0" becomes the file's modification time.
- `read_only` and `executable` are optional booleans. A missing `type` means a file.
- The transfer header's `name` is shown on the TV as "From <name> : <file>".

**How it stores files:**

- The item's `name` is joined to the receiving folder as it is. A `/` makes subfolders (**verified**), and `..` is
  not removed: a name with `../` lands outside the folder (**verified**: `../Download/x`, sent to a receiver saving
  in Download, went up to the parent folder and back down into Download).
- When a name is taken, the file becomes `name_2.ext`, `name_3.ext`… (**verified**), unless overwriting is turned on.
- Empty files are not created at all (**verified**).
- A failed item keeps what was written.

**When something fails, the receiver neither answers nor closes the connection** (**verified**): its thread ends,
and the socket stays open. The app's sender has no timeouts, so it would wait for ever. Inferred from the code, not
tested: a sender that closes the connection without finishing makes the receiver's non-blocking loop spin, since
the closed socket stays readable. Libremote 0.2.0 therefore never opened a connection without sending a whole transfer.

### Telemetry, ads and trackers in the app

All from the manifest and code (static; the app's network traffic was not captured):

- **Ads:** Google Mobile Ads (AdMob, app ID `ca-app-pub-3956846923941225~1381256433`) with Google's consent
  library (UMP). The Receive screen shows a banner and, unless opened for sending, an **interstitial**; the share
  screen shows a banner. There are also house ads for the developer's other apps: a list of images and links
  fetched from `res.cloudinary.com/yablio`.
- **Analytics:** Firebase Analytics (to `app-measurement.com`) and Firebase Installations, plus Google's data
  transport (CCT). Collection is off at start and turned on once the consent library reports consent obtained or
  **not required** (outside regions that need consent, without asking). Events: `device_found`,
  `device_trouble`, `pairing_id`, `transfer_ok_<Send|Receive>`, `transfer_fail_…`, `transfer_err…`, each with the
  device's maker, model and Android version.
- **Advertising ID and attribution:** `AD_ID`, `ACCESS_ADSERVICES_AD_ID`, `…_ATTRIBUTION`, `…_TOPICS`, and the Play
  Install Referrer.
- **Billing:** Play Billing 8.0.0, for a "remove ads" purchase.
- **Other permissions:** storage, including `MANAGE_EXTERNAL_STORAGE`; `REQUEST_INSTALL_PACKAGES`; notifications;
  foreground service; Wi-Fi and multicast locks; TV program guide (EPG) read and write, for a home-screen channel
  whose card image comes from Cloudinary. Cleartext traffic is allowed app-wide.

Libremote used none of this; it only needed the protocol, and no longer uses that either.

### Verified and inferred, in short

| Fact | Status |
|---|---|
| Port 40818, plain TCP, framing, SUCCESS reply | Verified |
| Header and item fields, string sizes, required `last_modified` | Verified (and static) |
| `/` makes folders, `..` not removed, `_2` renaming, no empty files | Verified |
| No reply and no close on failure | Verified |
| mDNS `_nitroshare._tcp`, named after the device, no TXT | Verified |
| "Pairing Id" encoding | Verified on one address; static for the rest |
| `sfttv://transfer/` starts the receiver, also through the remote service | Verified |
| 8-minute inactivity stop | Static; consistent with what the emulator did |
| Spinning on an unfinished connection | Inferred from the code, not tested |
| Telemetry, ads, permissions | Static only |
| The app's phone sender found and sent to Libremote Receiver 0.2.0 | Inferred: same service type and protocol; not tested |
