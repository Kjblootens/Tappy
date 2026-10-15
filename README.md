# Tappy

A Brick-style app blocker for Android. Pick the apps that distract you, tap your NFC tag, and
they're locked. Tap the same tag again to get them back. While a block is active, Tappy can't
be uninstalled or switched off.

## How it works

| Piece | What it does |
| --- | --- |
| **NFC tag** | Paired by its UID. On pairing Tappy also writes a `tappy://toggle` record + Android Application Record to the tag (if writable) so a tap reaches Tappy from the home screen or any other app (the phone must be unlocked). |
| **Accessibility service** (`BlockerService`) | Watches the foreground window. When a blocked app comes to the front it sends you home and shows a "blocked" screen. |
| **Device admin** (`TappyAdminReceiver`) | An active device admin can't be uninstalled until it's deactivated. |
| **Self-protection** | While blocking, any Settings / package-installer screen that mentions Tappy (app info, uninstall dialog, accessibility toggle, device-admin deactivation) is bounced back to home. So you can't turn off the service, deactivate the admin, force-stop or uninstall Tappy until you tap your tag. |
| **Emergency unlock** | 3 one-time unlocks in case you lose the tag. They never recharge. |

A block won't start until the blocker service and uninstall protection are both on.

## Build & install

Requires Android 8.0+ (API 26) and a phone with NFC.

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Every push also builds the APK in GitHub Actions (`Build APK` workflow); download it from the
run's artifacts.

## Setup on the phone

1. Open Tappy, turn on NFC if needed.
2. **Pair** a tag and hold it to the back of the phone. Any NTAG213/215/216 sticker or keyfob works.
3. **App blocker service** → *Open* → Accessibility → Tappy → on.
   On Android 13+ a sideloaded app's accessibility toggle is greyed out at first: go to
   *Settings → Apps → Tappy → ⋮ → Allow restricted settings*, then try again.
4. **Uninstall protection** → *Enable* → Activate.
5. Tick the apps to block, then tap your tag.

## Optional: device-owner mode (strongest)

As a regular device admin Tappy relies on the accessibility service to guard its own settings.
If you make Tappy the **device owner**, it additionally:

- hard-blocks its own uninstall (`setUninstallBlocked`),
- suspends the blocked apps at the OS level (greyed out, can't be opened at all),
- disables safe-mode boot during a block (safe mode would otherwise disable Tappy).

Android only allows setting a device owner when **no accounts** are on the device, so you'll
need to temporarily remove your Google (and other) accounts under *Settings → Accounts*, then:

```sh
adb shell dpm set-device-owner app.tappy/.TappyAdminReceiver
```

Re-add your accounts afterwards. To undo it, use **Remove owner** in Tappy (only available
while not blocking).

## Limits worth knowing

- Without device-owner mode, booting into **safe mode** disables third-party accessibility
  services, so the app block is off there; the device admin still prevents uninstalling, but
  it can be deactivated in safe mode. A factory reset always wins.
- The settings guard matches Tappy's name on screen. OEM settings apps with very different
  package names may slip through; the device admin still stops an uninstall outright.
- Tags are matched by UID, which is unique per tag but not cryptographically secure. It's a
  self-control tool, not a security product.
