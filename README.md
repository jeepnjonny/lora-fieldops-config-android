# LoRa FieldOps Config (Android and iPhone)

App for configuring [LoRa FieldOps APRS trackers](https://github.com/KJ7NYE/LoRa_FieldOps_APRS_Tracker)
over USB-OTG serial or Bluetooth LE on Android, and over Bluetooth LE on iPhone.

## iPhone

The iPhone app is the same app, built from the same code. Differences:

- **Bluetooth only.** iPhones can't talk to the tracker's USB serial chip, so there is no
  USB section and no Log tab. Bluetooth setup needs the tracker's "Require PIN" option on,
  as on Android. iOS asks for the PIN the first time you connect.
- **Coverage logger** works while a tracker is connected, including with the screen off.
  Queued entries that couldn't be sent are retried while the app is running, not after it
  has been closed (iOS has no equivalent of Android's background upload).

### Installing (no Apple Developer account)

Each release has an unsigned `LoRaFieldOpsConfig-vX.Y.Z-unsigned.ipa` attached (builds from
other branches are under the repo's Actions tab → iOS → the run → Artifacts). Install it with
[SideStore](https://sidestore.io) or [AltStore](https://altstore.io), which sign it with your
own free Apple ID:

1. Set up SideStore (or AltStore) on the iPhone once. Their setup guides need a Mac or
   Windows PC for this step.
2. Download the `.ipa` to the iPhone, open SideStore → My Apps → **+**, and pick the file.
3. On first launch, allow Bluetooth.

A free Apple ID's signature lasts **7 days**. Refresh the app in SideStore before then
(SideStore can do it on the phone; AltStore needs its computer on the same Wi-Fi). An app that
expired stops opening until it is refreshed; its settings are kept. A free Apple ID can
have 3 sideloaded apps at a time.

### Building

The app logic and screens live in `shared/` (Kotlin Multiplatform, Compose Multiplatform).
`app/` is the Android app and `iosApp/` is the small iOS shell. The iOS app is built by the
`iOS` GitHub workflow on a macOS runner. To build locally on a Mac with Xcode:

```sh
brew install xcodegen
cd iosApp && xcodegen generate && open LoRaFieldOps.xcodeproj
```

Xcode runs Gradle to build the Kotlin framework. Most of `shared/`, including the iOS code,
also type-checks on Linux: `./gradlew :shared:compileKotlinIosArm64`.

## Coverage logger

An optional logger that records what the connected tracker transmits and hears, and
uploads it to the [APRS Coverage](https://github.com/jeepnjonny/aprs-coverage) website
(see its README, "Device log uploads"). The site uses these logs to compare two trackers
exactly instead of estimating. It is off by default and changes nothing about
configuring a tracker. While it is off, the app sends nothing anywhere.

**Setup**

1. On the website, open the Account tab, then Upload keys, and create a key (it starts with `acv_`).
   Register the tracker as one of your devices.
2. In the app: connect screen → *Coverage logger settings* (or the *Logger* tab while
   connected over Bluetooth). Turn it on and enter the key. Leave the device callsign
   blank to use the connected tracker's own: its callsign, or `CALLSIGN:TACTICAL` when a
   tactical callsign is set (the tracker then beacons as an object). Type one to override it.
3. Connect to the tracker over Bluetooth. Logging starts automatically.

**How it works**

- It listens on the tracker's Bluetooth KISS service alongside the setup link, so it
  works only over Bluetooth, and only with trackers set up for Bluetooth configuration
  ("Require PIN" on).
- **The tracker allows one Bluetooth phone at a time.** APRSDroid can't be connected
  to the tracker while the logger is.
- Each packet is stamped with the phone's clock. Your own packets are uploaded as `tx`.
  Everything heard is uploaded as `rx`, including your own packets repeated by a digipeater.
  When the firmware reports RSSI/SNR, those values are attached to the `rx` entries.
- Entries are queued on the phone, so they survive the app being closed. They are uploaded
  every minute while connected, and in the background with retries once the phone is online.
- While logging, a notification ("Logging K7ABC-9: N sent, M heard, Q queued") keeps it
  running with the screen off. If the link drops, the app keeps reconnecting to the same tracker.
- Optional: "Record setting changes as device notes" adds a note such as
  `beacon rate 120 → 60 s` when you save settings. Passwords, PINs, passcodes,
  unlock tokens, the fixed position, Wi-Fi networks, the APRS-IS server and filter
  and the Bluetooth name are never included.
- The upload key is stored encrypted on the phone. If the website rejects the key
  or the device, uploads stop and the logger screen shows why. They start again
  after you change a setting.

## Credits

APRS symbol icons are from [hessu/aprs-symbols](https://github.com/hessu/aprs-symbols)
by Heikki Hannikainen, OH7LZB, the set used by aprs.fi. Licensing varies by symbol:
Hessu's original drawings are CC BY-SA 2.0, many are vectorized from the classic
WA8LMF / G4IDE / KH4G set, and a few are company logos owned by their companies. See
that repository's [COPYRIGHT.md](https://github.com/hessu/aprs-symbols/blob/master/COPYRIGHT.md)
for each symbol.
