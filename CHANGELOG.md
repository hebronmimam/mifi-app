# Changelog

All notable changes to this app. Versions follow [semantic versioning](https://semver.org).

## [1.1.1] - 2026-11-17

- The update notice now stands on its own in the notification shade. Android 15 had folded it in under the battery notice.
- Opening the app always looks for a new version if it hasn't found one yet, even after the phone restarts.
- After you allow updates from this app in Android's settings, the update carries on by itself when you come back.
- Expand the battery notification to see who is connected right now: you first, then one more by the name you gave it, and how many others. Addresses are never shown there.
- Button labels no longer break onto two lines with large phone text. Two that don't fit side by side stack instead.

## [1.1.0] - 2026-11-17

- New USSD tab. Check balance is its first option, then codes you sent before, then any code. Set your network's balance code once if it isn't MTN.
- Messages: pick several texts (tap Select, or long-press one) to mark them read, mark them unread, or delete them. Read all marks every text read. A single text can be marked unread too. Unread marks are kept on your phone, because neither box can set one.
- Block a device from its record in Usage, and unblock it. The app writes the box's own Wi-Fi block list and reads it back before it says it worked. On a router this restarts the Wi-Fi for a few seconds and turns off WPS, which the router keeps off while blocking.
- Usage: this month's total now comes from the box's own counter, so it includes everything used before the app started recording.
- Usage: other devices show how long they were on, and "at least" an amount when it can be told apart. They no longer all show the same range.
- Usage: the period picker glides between Day, Week, Month, Year and All, like the tab bar.
- New texts stay in the notification shade in their own group. Before, Android tucked them under the battery notification.
- Joining another box's Wi-Fi shows that box straight away. A MiFi and a router can share one address, so the app now notices the Wi-Fi itself changed.
- Coming back to the app no longer hides the tabs while the box's session renews.
- Updates: a new version shows as a bar on Now and as a notification with an Update button. One tap downloads it, with progress in the status bar, and Android asks you to confirm once. The app looks every 6 hours, and when you open it.

## [1.0.0] - 2026-11-17

The first release.

- MiFi battery percentage as the status-bar icon, checked every 5 minutes, with one alert at 20%.
- Now: a battery dial (signal on a router), live download and upload averaged over 3 seconds, the last minute as a chart, signal, delay and today's data.
- Messages: the box's texts as Android notifications, with an unread badge. Reply, write, delete, and send balance codes (USSD).
- Usage: day, week, month, year and all time. Exact for this phone, a least-to-most range for other devices. Kept until you clear it, and exportable as CSV.
- Settings: Wi-Fi sleep, network mode, connect and disconnect, UPnP, restart, shut down and factory reset.
- Signs in once and stays signed in. The password is encrypted with the Android Keystore.
- The screens update themselves from signed releases. A new app version installs with one tap.
- Supports ZTE goform MiFis (such as the MTN U10LS) and Tozed ZLT home routers.

[1.1.1]: https://github.com/hebronmimam/mifi-app/releases/tag/v1.1.1
[1.1.0]: https://github.com/hebronmimam/mifi-app/releases/tag/v1.1.0
[1.0.0]: https://github.com/hebronmimam/mifi-app/releases/tag/v1.0.0
