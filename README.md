# MiFi

An Android app for pocket MiFis and 4G home routers. It shows the battery and live speed, lets you read and send the box's texts, records which devices used how much data, and changes the box's settings, all without opening its web page.

It works with:

- ZTE-based pocket MiFis, such as the MTN U10LS
- Tozed ZLT home routers

It talks to the box over the box's own Wi-Fi, through the same web API that the box's admin page uses. Nothing is changed on the box itself. No firmware is touched and nothing is installed on it.

## What it does

- **Now.** A live dial with the download speed, the battery (or signal, for a router), a 60-second speed trace, signal, delay and today's data.
- **Battery in the status bar.** The MiFi's percentage is the notification icon. The app checks every 5 minutes, every minute while the battery is low or the internet is down, and every 15 minutes in battery saver. You get one alert at 20%.
- **Messages.** New texts on the box pop up as Android notifications, and tapping one opens it. You can reply, write a new message, delete one, and send balance codes (USSD) and read the reply.
- **Usage.** It records every device that joins, every hour, and keeps the record until you clear it, at about 2 MB a year. This phone's data is exact. The box counts the other devices together, so each one shows the least and the most it could have used. You can export the record as a CSV file.
- **Settings.** Wi-Fi sleep and range, network mode, connect and disconnect, UPnP, restart, shut down and factory reset. A setting the app can't yet change safely opens the box's own page.
- **Stays signed in.** You type the box's admin password once. It is kept only on this phone, encrypted with a key held in the Android Keystore, and it is never logged or backed up.

## Updates

The screens are HTML in `app/src/main/assets/web`, and they update over the air. The app checks this repository's releases once a day. When it finds a newer `web-<n>.zip`, it checks the zip's signature against `keys/web-bundle.pub` and swaps the screens in without asking. If the signature doesn't match, the zip is never loaded.

A new app version is an APK on a `v<version>` release. The app downloads it, and then Android asks you to tap Install.

## Building

You need JDK 17 and the Android SDK (API 35).

```bash
./gradlew assembleDebug testDebugUnitTest
```

To work on the screens in a desktop browser, serve `app/src/main/assets/web` and open `index.html`. `dev.js` stands in for the phone with made-up data. Add `?s=router`, `signin`, `wifi`, `low` or `dropped` to see the other states. `dev.js` is left out of the APK.

## Releasing

Signing keys live outside the repository:

- Set `mifi.signing` in your user `gradle.properties` to a properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- Point `MIFI_WEB_KEY` at the PKCS#8 private key whose public half is `keys/web-bundle.pub`.

```bash
node tools/release.mjs --publish            # app + screens, tagged v<versionName>
node tools/release.mjs --web-only --publish # screens only, tagged web-<n>
```

Bump `web` in `assets/web/version.json` for new screens. Bump `versionCode` and `versionName` in `app/build.gradle.kts` for a new app.

## Fonts

The screens use Panchang and Switzer from [Fontshare](https://www.fontshare.com). They are loaded from Fontshare at run time and are not included in this repository. Offline, the phone's system font stands in.

## Licence

MIT. See [LICENSE](LICENSE).
