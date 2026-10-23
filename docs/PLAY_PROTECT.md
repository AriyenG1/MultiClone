# Google Play Protect warning

The user reports “Harmful app blocked” and “This app can put your device or data at risk” for MultiClone itself and generated clones. This is a reported risk classification, distinct from a first-time unknown-app scan recommendation. The wording does not establish its underlying cause, and it should not be labelled a false positive without further review. The emulator images used for development do not include Google Play services, so their successful installations cannot reproduce or clear a Play Protect verdict.

Google recommends reviewing permissions, APIs, included SDKs, security behavior and APK/ZIP headers, and then requesting a classification review if appropriate. No app-side setting can guarantee suppression of that warning. MultiClone does not automate “Install anyway”, change Play Protect settings, disguise its identity or bypass verification.

## Local review

The main manifest and first-party source paths were inspected. MultiClone declares permissions for installed-app discovery, prompted installation/removal, foreground processing and progress notifications. AndroidX adds a signature permission for its own internal dynamic receivers. MultiClone declares no INTERNET, SMS-reading, accessibility-service or notification-listener permission. Its first-party processing code has no networking, shell execution or downloaded-code loader. It signs clones locally and uses Android confirmation flows for installation/removal.

Run `python3 scripts/inspect-release.py artifacts/MultiClone-v0.1.2.apk` for a reproducible inspection of the actual release's permissions, package, debug flag, ZIP headers/CRC/entries, certificate, signature and alignment. The packaged report is [release inspection](verification/v0.1.2-release-inspection.json). These checks establish limited packaging and permission facts; they do not certify the app or its dependencies free of security issues. A pre-existing lint warning identifies an empty trust-manager method in the Bouncy Castle dependency; MultiClone has no network permission or first-party caller of that TLS helper. This observation is not evidence that the helper caused Google's warning.

Generated clones contain the selected source app's code, permissions and integrations, with a changed package and signing certificate. Reviewing MultiClone does not review every generated app, and a classification review of MultiClone does not automatically cover its clones. A source app may also reject a changed identity at runtime.

## Requesting a review

Official guidance: [Developer guidance for Play Protect warnings](https://developers.google.com/android/play-protect/warning-dev-guidance).

Official submission form: [Play Protect Appeals Submission Form](https://support.google.com/googleplay/android-developer/contact/protectappeals).

The form currently requests an email address, package name, SHA-256 of the APK uploaded to VirusTotal, and supporting information. The exact flagged APK is relevant; a new build has a different hash. The release-inspection report and `artifacts/SHA256SUMS.txt` provide local hashes. No APK, source, credentials or appeal has been uploaded or submitted by Codex. Uploading an APK to a review service discloses that APK to that service; generated third-party clones require a separate decision about sharing them.

Suggested description to adapt after reviewing the exact flagged release:

> Please review the Play Protect classification for MultiClone (`dev.multiclone.app`), a locally built Android APK-processing utility. The user reports “Harmful app blocked” / “This app can put your device or data at risk”. Processing and signing run locally. The application uses Android's supported user-confirmed installation and removal flows. It requests installed-package visibility, package-install/removal permissions, foreground processing and notifications; it does not request Internet, SMS, accessibility-service or notification-listener access. It does not collect source-app credentials, copy app-private login data, disable Play Protect, or bypass integrity/authentication checks. The attached exact APK hash and source/inspection results identify the build for review. Please assess the classification; the reason is not exposed in the installer result.

The review may require further changes or may uphold the classification. The v0.1.2 library cleanup does not claim to resolve it.
