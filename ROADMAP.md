# Roadmap

## Done

- **1.0.0**, the first public release: the remote, typing, app shortcuts for each TV, encrypted file sending to
  Libremote Receiver, and opt-in updates for both apps. CHANGELOG.md has the list.

## Next

- **Installing Libremote Receiver more easily:** a short code for the Downloader app on the TV.
- **Sending files, continued.**
  - Folders: the transfer carries paths, so a picked folder (ACTION_OPEN_DOCUMENT_TREE) can keep its layout.
  - A shared link (rather than a file) could open on the TV through the remote's app links.
  - The app list could carry only the icons that changed, instead of all of them on every sync.
- **Voice search.** The TV accepts voice from phones (feature 8, VOICE_BEGIN/PAYLOAD/END in PROTOCOL.md). It needs
  the microphone permission, asked for only on first use, and a check of the audio format the TV expects (PCM or
  LC3 frames).
- **Text suggestions.** The TV sends the input method's completions (message 29); they could appear as chips
  above the keyboard.
- **Quick Settings tile and lock-screen controls** for play/pause and volume without opening the app.

## Later

- Gamepad mode (feature 256, MOTION_INJECT).
- Custom quick-launch links, with a link and an optional package.
- Choosing the TV's audio output (SET_PREFERRED_AUDIO_DEVICE).
- Translations.
