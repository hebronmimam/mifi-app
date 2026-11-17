# Security

## Reporting a problem

Please don't open a public issue for a security problem. Report it privately through [GitHub's private vulnerability reporting](https://github.com/hebronmimam/mifi-app/security/advisories/new), or message me on [WhatsApp](https://wa.me/2348149322169).

Say what you found, how to reproduce it, and which version. You'll get a reply within a few days.

## Supported versions

Only the latest release gets fixes. The app updates its screens by itself, and asks you to install a new app version.

## What the app protects

- The box's admin password is encrypted with a key in the Android Keystore, never logged, and left out of backups.
- Screen updates are only loaded when their ECDSA (SHA-256) signature matches `keys/web-bundle.pub`.
- App updates are APKs signed with the same key as the installed app, so Android refuses anything else.
