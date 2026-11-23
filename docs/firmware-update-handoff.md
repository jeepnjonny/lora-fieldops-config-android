# Handoff: firmware update over Bluetooth (app side)

From the firmware session of 2026-11-22. The tracker firmware side is built and
hardware-tested; this repo needs the updater. Everything below was verified on
real hardware unless marked otherwise.

## Goal

From the app, update a connected tracker's firmware over BLE:

- **ESP32 boards** (Heltec V3, LoRanger V1/V2, T-Beam 1W, LilyGo T3-S3, Wireless
  Tracker): a custom GATT protocol that streams `<board>_firmware.bin`.
- **Heltec T114 (nRF52840)**: Nordic legacy DFU through the Adafruit bootloader,
  with `heltec_t114_dfu.zip`.

## Firmware source of truth

Repo `KJ7NYE/LoRa_FieldOps_APRS_Tracker`, branch
`claude/ble-firmware-updates-w5sljy` (not merged or released yet). Read these:

| File | What |
|---|---|
| `include/ble_ota.h` | **Normative ESP32 protocol spec** (GATT layout, opcodes, flow, error codes) |
| `tools/ble_ota_client.py` | Working reference client (Python/bleak); port its logic |
| `docs/developers/ble-ota-plan.md` | Plan, test results, and "T114 DFU rules" |
| `src/ble_ota.cpp`, `src/ble_utils.cpp` | Tracker-side implementation, if behaviour is unclear |

If anything below disagrees with `ble_ota.h`, the header wins.

## Preconditions and capability detection

- BLE updates exist only when the tracker's **Require PIN** is on, and only for
  a PIN-paired (bonded, MITM) phone. That's the same link `BleTransport` already
  opens for the config CLI (`ensureBonded()`), so the updater can reuse it.
- **Detect support by GATT service presence, not by version number:**
  - ESP32: service `62970001-5b20-49c7-ae44-96d63f6d50e8` present. It is absent
    on single-slot boards (`tbeam_433_aprs`, `lilygo_t3_433_aprs`: tell the user
    to use the USB web flasher), with the PIN off, and on older firmware.
  - T114: Nordic DFU service `00001530-1212-efde-1523-785feabcd123` present.
    Older firmware never registered it; there the fallback is the CLI command
    `otadfu`, then step 3 of the T114 sequence below.
- **Board id:** the CLI `version` command answers two lines:
  ```
  version.date=v1.4.7-7-g4d54093
  version.board=heltec_v3_433_aprs
  ```
  The app currently only reads `version.date` (`ConfigViewModel.kt` ~line 671,
  which strips the prefix from the whole response). Parse `version.board` too:
  it selects the image and must equal the board id sent in START. Values match
  the PlatformIO env names: `heltec_t114`, `heltec_v3_433_aprs`,
  `tbeam_433_aprs`, `tbeam_433_1w_aprs`, `lilygo_t3_433_aprs`,
  `lilygo_t3s3_433_aprs`, `LoRanger_V1`, `LoRanger_V2`,
  `heltec_wireless_tracker_433_aprs`. `unknown` means a dev build: refuse.

## Getting the image

- GitHub Releases of `KJ7NYE/LoRa_FieldOps_APRS_Tracker` publish
  `<board>_firmware.bin` (ESP32) and `heltec_t114_dfu.zip` (T114). **Never**
  use `*_web_factory.bin` or `*_ota_update.bin`: those are USB-flasher images
  (the tracker would reject them anyway).
- A release manifest with per-board version, size and SHA-256 is planned
  (firmware Phase 4) but **does not exist yet**. Until it does, compute the
  SHA-256 of the downloaded file in the app and send that in START.
- Also offer a local file picker for offline/field use.

## ESP32 protocol (summary of `include/ble_ota.h`)

GATT (both characteristics need the authenticated link):

| UUID | Properties | Use |
|---|---|---|
| `62970001-5b20-49c7-ae44-96d63f6d50e8` | service | |
| `62970002-…` | write (with response) + notify | commands in, status out |
| `62970003-…` | write without response | data: `[u32 offset LE][payload]` |

Commands (phone → `…0002`), little-endian:

- `0x01 START [u8 protocol=1][u32 imageSize][32 B SHA-256][u8 n][board id][u8 m][version string]`
- `0x02 END`
- `0x03 ABORT`

Notifications (tracker → `…0002`):

- `0x81 READY [u8 protocol][u32 window][u32 ackInterval]`. Currently a 12288 B window and a 4096 B ACK interval.
- `0x82 ACK [u32 bytesWritten]`: bytes committed to flash.
- `0x83 RESYNC [u32 expectedOffset]`: resend from there.
- `0x84 DONE`: verified and installed; the tracker reboots in ~1.5 s.
- `0x85 ERROR [u8 code][UTF-8 text]`. The session is closed. Show the text.
- `0x86 ABORTED`

Error codes: 1 bad request, 2 protocol version, 3 wrong board, 4 image size,
5 busy (a web UI update is running), 6 no memory, 7 flash, 8 verify (SHA or
image validation), 9 timeout, 10 flow (window exceeded), 11 incomplete.

Flow:

1. Subscribe to notifications on `…0002` **before** writing START.
2. START → wait for READY (or ERROR). Don't send data before READY.
3. Send data packets in order from offset 0, payload = negotiated MTU − 3 − 4.
   Keep `sent − lastAck ≤ window`. On RESYNC, continue from `expectedOffset`.
4. After `ACK == imageSize`, write END → DONE (or ERROR).
5. Treat DONE as success. The link then drops because the tracker reboots. Reconnect
   after ~10 s and confirm with `version` if you want.
6. If no data arrives for 10 s, or the link disconnects, the tracker aborts. The old
   firmware always keeps running after any failure. Retrying is safe.

Android notes:

- Request MTU 247 (already `REQUESTED_MTU`) and
  `requestConnectionPriority(HIGH)`. The tracker itself requests a
  7.5–15 ms interval, 251-byte link-layer packets and 2M PHY while a session
  runs, and relaxes the link afterwards.
- Write-without-response pacing: on Android 13+ use the `writeCharacteristic`
  return code / `onCharacteristicWrite` callback to avoid overrunning the
  stack's queue. The window protocol protects the tracker, not the phone's own
  queue.
- Measured with the Windows reference client: **18.7 KB/s, a 1.6 MB image in
  80–89 s**. Android should match or beat that. Show progress from ACKs.
- While a session runs, the tracker is radio-silent: no beacons, digi, iGate or
  KISS. It shows "Updating nn%" if it has a display. A KISS session on the same
  link stalls until the update ends.
- Gotcha seen on Windows: the MTU reads 23 just after connect and only later
  reaches the negotiated value. Use the MTU from `onMtuChanged`, not a value
  read before it fires.

## T114 (nRF52) DFU: rules found the hard way

Use Nordic's Android DFU library (`no.nordicsemi.android:dfu`). For iOS, the
Nordic iOS DFU library offers the same. The bootloader on every T114 in the field is
**Adafruit UF2 Bootloader 0.9.0 (Jul 2024)**. It won't be updated: the
user has no SWD probe, and a failed bootloader flash bricks the board. Design for it:

1. **Packet Receipt Notification ≤ 8. Use 6.**
   `DfuServiceInitiator.setPacketsReceiptNotificationsEnabled(true)` +
   `setPacketsReceiptNotificationsValue(6)`. The library/nRF Connect default
   (10) overruns the bootloader's 8 RX buffers. The failure is "Remote DFU error:
   OPERATION FAILED" (`10-03-06`) about 0.6 s into the transfer.
2. **Refresh the GATT cache after the jump to the bootloader.** The bootloader
   reconnects at the **same address with the same bond**. Android then reuses the
   application's cached GATT table. Because the device is bonded and Service Changed
   indications are on, the library waits for a Service Changed indication
   instead of rediscovering. None takes effect, and the first read fails with
   `GATT INVALID HANDLE (0x01)`. Turning Service Changed indications off beforehand
   did **not** help.
3. **Keep bond on** (`setKeepBond(true)`). The bootloader needs the bond to
   accept the encrypted reconnect.

Sequence that worked (by hand in nRF Connect; implement it as):

1. On the existing bonded connection, enable notifications on the DFU control
   point `00001531-1212-efde-1523-785feabcd123`, then write `01 04` (start DFU,
   application). Alternative on any firmware with the CLI: send `otadfu`. The
   tracker reboots into the bootloader and the link drops.
2. Close that `BluetoothGatt`. Reconnect to the **same address**. The link
   encrypts with the bond.
3. Call the hidden `BluetoothGatt.refresh()`. `BleTransport.refreshGattCache()`
   already does this by reflection. Wait ~500 ms (`GATT_REFRESH_SETTLE_MS`),
   discover services, and confirm that **only** the DFU service (plus GAP/GATT/
   Device Information) is listed, and that `00001534` reads `08 00` (bootloader).
4. Close, then start the Nordic DFU library on that address with the zip. It
   sees a bootloader (version 0.8), so no second jump, and uploads.
5. Measured: **~1.1 KB/s, about 5 min for the 312 KB zip** (MTU stays 23 on this
   bootloader). Warn the user and keep the screen awake.

Failure handling:

- If the transfer fails after it starts, the application area is already
  erased. The T114 stays in its bootloader and drops Bluetooth after a few
  minutes, falling back to USB mode with a drive named `HT-n5262`. Offer a
  **retry right away** while the bootloader is still advertising (same
  address: reconnect, refresh, DFU). Otherwise tell the user to recover over
  USB (web flasher or UF2 drag-and-drop). Pressing RST does not exit the
  bootloader once the app is erased.
- After success the tracker boots the new firmware at the same address. The
  bond survives, so the normal config connection works again. Expect a stale
  cache once more: call refresh on that first reconnect too.

## Open questions for the app session

- Where it lives in the UI (Device screen?) and how releases are discovered
  (GitHub Releases API vs the future manifest).
- iOS port parity (`feature/ios-port`): CoreBluetooth has no public cache
  refresh. iOS invalidates the cache through Service Changed / `didModifyServices`.
  The T114 flow on iOS is **untested**.
- Firmware not yet released: the updater can only be tested against a tracker
  flashed from the branch above until a release carries it.

## Test checklist (app)

ESP32 (Heltec V3 is the reference):

- Full update, then `version` confirms the new firmware.
- Wrong board image → ERROR 3 shown.
- Cancel mid-way → ABORTED; old firmware runs.
- Phone Bluetooth off mid-way → tracker aborts; retry works.
- PIN off → updater hidden or explained.
- Single-slot board → "use USB".

T114:

- Full update with PRN 6.
- Failure mid-transfer → retry path; recovery message if the retry fails.
- Config connection works after the update.
