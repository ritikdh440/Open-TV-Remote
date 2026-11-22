# Open TV Remote

A free, open-source, ad-free remote control app for **Android TV / Google TV** devices, using ADB over Wi-Fi.
Built for TVs where the usual remote apps fail, including budget brands such as Foxsky, and for phones with no IR blaster.

## Features
- D-pad with OK, Back, Home, Menu
- Power (smart wake/sleep), Input, Mute
- Volume and channel controls, media keys (play/pause, rewind, fast-forward)
- One-tap app shortcuts (YouTube, Netflix, Prime Video, Hotstar, Play Store)
- Type text to the TV from your phone keyboard
- Remembers your TV's IP and auto-reconnects
- No ads, no tracking, no account

## How it works
The app speaks the ADB protocol over your local network (via [dadb](https://github.com/mobile-dev-inc/dadb)) and
sends commands like `input keyevent`. No cloud, nothing leaves your Wi-Fi.

## Requirements
- Android TV / Google TV with **network (wireless) debugging** available
- Android 7.0+ phone (the TV and phone must be on the same Wi-Fi)

## Setup
1. On the TV: Settings > Device Preferences > About, tap **Build** 7 times to enable Developer options.
2. Settings > Device Preferences > Developer options > turn on **USB debugging** and **Network / Wireless debugging** (wording varies by TV).
3. Find the TV's IP: Settings > Network.
4. Open the app, enter the IP, tap **Connect**, and accept the prompt on the TV (tick "Always allow").

## Install
Download `OpenTVRemote.apk` from the [Releases](../../releases) page and install it
(allow "Install unknown apps" if asked).

## Build from source
- **Android Studio:** open the folder and run the `app` module.
- **Command line:** `gradle assembleDebug` (Gradle 8.7, JDK 17). The APK is in `app/build/outputs/apk/debug/`.
- **GitHub Actions:** push to `main` and download the APK from the Actions artifacts. Push a tag like `v1.0.0` to publish a Release.

## Desktop / web remote (optional)
`tools/tv_remote.py` is a small Python script that serves a phone-friendly web remote from a PC using the same ADB commands:
`python tools/tv_remote.py <TV_IP>`

## Security note
ADB gives full control of the TV. Only enable network debugging on a trusted home network, and never expose port 5555 to the internet.

## Roadmap
- [ ] App icon
- [ ] Light theme
- [ ] Multiple saved TVs and auto-discovery
- [ ] Touchpad / swipe mode
- [ ] Android TV Remote Protocol v2 (no developer options needed)

## License
[MIT](LICENSE). Not affiliated with any TV manufacturer. Third-party libraries keep their own licenses (dadb: Apache-2.0).
