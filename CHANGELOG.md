# Changelog

## 1.0.1

An update for Libremote Receiver, the app on the TV. Libremote on the phone stays at 1.0.0.

- The TV asks before a new phone can use the receiver. It shows the phone's name, and Don't allow is selected
  until you choose. Before, any app on the TV could add a phone without asking.
- A phone you remove in the receiver's settings stays removed. If it tries again, the TV says you removed it
  before and asks you again. Removing a phone stops it sending files; it still works as a remote until you unpair
  it from the TV itself.
- A file cut off because the TV lost power or the receiver closed no longer stays behind in Downloads. The
  receiver cleans it up the next time it opens.
- Phones you already allowed stay paired after the update.

Coming in the next version of Libremote on the phone: a six-digit code to compare with the one on the TV when it
asks, and when the connection drops in the middle of sending, it says which files arrived and Try again sends only
the rest.

## 1.0.0

First public release of Libremote for Android TV and Libremote Receiver.

- **Remote:** Full, D-pad and touchpad modes; volume on the left; Channel +, Menu and Channel − on a TV, or Search,
  Menu and Apps on a streaming box; playback, power, Back, Home and Find my remote; hold any button for the TV's
  long press.
- **Typing** on the TV from the phone's keyboard, with the TV's field, hint and action.
- **App shortcuts for each TV:** learned from the apps you open, most used first; pin, rename, hide and add apps;
  text, icons or tiles in three sizes and up to three rows. The TV says when an app isn't installed.
- **Send files to the TV** from the remote or any app's share menu, to Libremote Receiver only, encrypted, with a
  progress notification.
- **Libremote Receiver** for Android TV 8.0 and later: pairs with your phones by itself, accepts only them, opens
  any app for shortcuts and shares the TV's app list with icons.
- **About this TV** and a **Remote guide** that names every button.
- **Privacy:** no accounts, analytics, ads or crash reports; nothing about the phone is sent to the TV.
- **Updates:** opt-in update checks for copies installed from GitHub, for both apps.
