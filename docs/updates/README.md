# App updates — v0.1.4

Settings → Check for updates requests the public latest stable release from AriyenG1/MultiClone. It compares numeric versions, selects exactly `MultiClone-vVERSION.apk`, requires GitHub's SHA-256 digest, limits the download to 256 MiB and validates length/hash. HTTPS redirects are allowed only to GitHub and its release-asset hosts, with bounded redirects and connection/read timeouts. No credentials, clone metadata, signing keys or telemetry are sent.

Before installation, the APK must match the installed package, have a strictly higher Android versionCode and pass cryptographic APK verification with exactly the same signer set as the installed app. The app revalidates the cached APK when Install update is tapped. Signing-key rotation is rejected. Android receives a read-only URI grant for the private cached APK and asks the user to confirm the update. No uninstall, data clearing or database replacement is performed. Generation must be paused/finished before the installer is opened.

A failed or cancelled download deletes the partial file. The installed app remains unchanged if the user cancels Android's confirmation. Cache eviction/process death may require another check/download. A permission-settings round trip requires the user to tap Install update again. Update checks are on demand, without background polling.

## Data continuity

This release keeps Room schema 2, the package name and the release certificate. Android retains the app's private storage on an accepted in-place upgrade; no export/import transfer is required. The updater does not touch preferences, clone artifacts, clone keys or database contents. Existing Settings backups are still useful before major app changes.

[Measured retention test](retention-test.txt): a v0.1.3 → v0.1.4 debug upgrade preserved all 16 recorded database/preferences/signing-key files byte-for-byte. The release certificate matches v0.1.3 in the [signed release inspection](release-inspection-v0.1.4.json). Physical Samsung behavior and future database migrations are not certified by this emulator test.

[Settings screenshot](settings-updates.png) · [Android Update popup](android-update-confirmation.png)

## Verification

- Debug/release builds and lint pass with zero lint errors.
- [Eight Android regressions](android-tests.txt): numeric versions, release URL/digest/size/stability rejection, wrong package/same-version rejection, live GitHub metadata, collapsed warnings, real generation dialogs, queue/backup recovery and installer feedback.
- [Higher-version installer proof](installer-handoff.txt): a temporary same-certificate debug APK with versionCode 6 / versionName 0.1.5 passes verification, opens the real Android Update dialog through FileProvider, and is cancelled. A modified copy is rejected. That proof APK is not a release artifact.
- The optional installer proof test expects `cache/updates/proof.apk` in the target app. Build the ordinary app with a temporary Gradle init script registering `androidComponents.finalizeDsl` and overriding only the app version, save that debug APK, restore normal build outputs, copy the proof into the target app's cache using adb/run-as, then run `AppUpdaterTest#verifiedHigherVersionOpensAndroidUpdateConfirmation`. Ordinary regression runs skip this optional host-prepared fixture test.

Users on v0.1.3 must manually install v0.1.4 once to obtain the updater. Subsequent properly signed, higher-version releases can use Settings. Device installation policies and user confirmation still apply.

Sources: [GitHub releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release), [Android package installation](https://developer.android.com/reference/android/content/pm/PackageInstaller).
