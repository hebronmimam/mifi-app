# Changelog

All notable changes to this app. Versions follow [semantic versioning](https://semver.org).

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

[1.0.0]: https://github.com/hebronmimam/mifi-app/releases/tag/v1.0.0
