# LoRa FieldOps Config (Android)

Android app for configuring [LoRa FieldOps APRS trackers](https://github.com/KJ7NYE/LoRa_FieldOps_APRS_Tracker)
over USB-OTG serial or Bluetooth LE.

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
  `beacon rate 120 → 60 s` when you save settings. Passwords, PINs, passcodes and
  unlock tokens are never included.
- The upload key is stored encrypted on the phone. If the website rejects the key
  or the device, uploads stop and the logger screen shows why. They start again
  after you change a setting.
