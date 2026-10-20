# Third-party notices

Libremote for Android TV and Libremote Receiver are licensed under the GNU General Public License, version 3 only (see LICENSE). It bundles the
following third-party material.

## Material Symbols (icons)

The icons in `app/src/main/res/drawable/ic_*.xml` and `tv/src/main/res/drawable/ic_*.xml`, including the glyphs in
the launcher icons and the receiver's TV banner, come from
[Material Symbols](https://github.com/google/material-design-icons) by Google. They are licensed under the Apache
License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).

## Names of apps and services

Android TV and Google TV are trademarks of Google LLC; "for Android TV" in the name says which TVs the app works
with. Libremote is not made, endorsed or sponsored by Google.

The app shortcuts name streaming apps (YouTube, Netflix, Prime Video, Disney+, Spotify, Plex and others) only to
identify them, and SENDFILES.md names the Send files to TV app the same way. These names are trademarks of their
respective owners. No logos are bundled: icons come from the TV itself.

## Interoperability

Libremote implements the protocol of Google's Android TV Remote Service independently, so that it can work
with TVs people own. [PROTOCOL.md](PROTOCOL.md) describes how the protocol was established. No Google code is
included.

Up to 0.2.0, Libremote and Libremote Receiver also implemented the file-transfer protocol of the Send files to TV
app (`com.yablio.sendfilestotv`, a protocol that comes from NitroShare), so that they worked with that app. They no
longer do; Libremote Receiver's encrypted channel keeps only its packet framing. [SENDFILES.md](SENDFILES.md) keeps
how it was established, as history. No code from that app is included.
