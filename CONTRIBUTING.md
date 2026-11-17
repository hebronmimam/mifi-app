# Contributing

Thanks for helping. Bug reports, reports from boxes that aren't listed yet, and pull requests are all welcome.

## Reporting a bug

[Open a bug report](https://github.com/hebronmimam/mifi-app/issues/new?template=bug.yml). Say which box you have, the app version (Settings, App updates), your Android version, and what you did.

**Never paste your admin password, Wi-Fi password, IMEI or phone numbers.** Blur them in screenshots too.

## Trying it on another box

If your MiFi or router isn't listed, open a [device report](https://github.com/hebronmimam/mifi-app/issues/new?template=device.yml) with the model, the firmware version from the box's own web page, and what the app showed.

## Pull requests

1. Fork, then branch from `main`.
2. Keep each pull request to one change.
3. Run `./gradlew assembleDebug testDebugUnitTest` and make sure it passes.
4. For screen changes, check them in a desktop browser with `dev.js` (see the README), and on a real phone if you can.
5. Say what you changed and how you checked it.

### House rules

- Nothing on the box changes unless the person asks for it in the app. No firmware changes, nothing installed on the box.
- Passwords are never logged, written to a file in plain text, or sent anywhere but the box.
- The screens stay dark, flat and calm: no gradients, 8-point spacing, 6px corners on buttons.
- Writing in the app is plain English in sentence case. A button says what it does.

## Licence

By contributing you agree that your work is released under the [MIT licence](LICENSE).
