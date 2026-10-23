# Install and test on your Samsung tablet

## First installation

1. Transfer `artifacts/MultiClone-v0.1.4.apk` to the Galaxy Tab S9 FE+.
2. Open it in My Files and complete Android's installation prompts. Allow that source to install APKs if requested.
3. Open MultiClone. After this point, cloning and management run locally on the tablet; no computer, root, Shizuku or work profile is required.
4. When you first install a generated clone, allow **MultiClone** under Android's **Install unknown apps** settings, then return and tap Install again. This permission is separate from My Files' permission.

Samsung Auto Blocker or device policy can block sideloading. Use the applicable settings and system instructions on your One UI version. MultiClone does not change or bypass those controls. If Android displays developer-verification requirements, follow the supported device flow; regional enforcement can change.

## Updating MultiClone itself

Install v0.1.4 over an existing signed MultiClone release through Android's normal update prompt. They use the same release certificate. The library database migrates in place. Keep MultiClone installed and retain its storage so its remaining signing keys, clone records and APKs remain available. An encrypted signing backup is also available in Settings.

## “INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed”

This means Android's package verifier rejected the installation. The temporary `/data/app/vmdl….tmp` path is Android's staging directory; it is not a missing source file. The result does not identify which protection or rule caused the rejection. Status 3 is Android's broad “aborted” category; the detailed verification message is more specific. It does not prove a Riot runtime integrity or login failure because the clone has not launched.

1. Check **Settings → Apps → Special access → Install unknown apps → MultiClone**. My Files having permission does not grant it to MultiClone.
2. Check whether Android, Google Play Protect or device management displays an explanation. On Samsung, inspect **Settings → Security and privacy → Auto Blocker**. Google Advanced Protection can also restrict new sideloaded applications. Do not assume any one control is responsible when Android gives no notice.
3. Clone and install the supplied **MultiClone Test App** through the same MultiClone flow. If it succeeds while Riot fails, the rejection is specific to the Riot clone or APK set. If both fail, investigate the common installation path and device restrictions first.
4. Tap **Copy details** on the clone card after retrying. Share that diagnostic text and the test-app result. It includes available installer/package-manager codes, session ID, related package, raw system message, and device/app version. Android may omit the responsible verifier or its reason.

v0.1.1 fixes the stale “Installation requested” banner and shows a failed result immediately. It improves diagnosis; it does not override a verifier decision. There is no automatic disabling of Play Protect, Samsung controls or device policy.

If Google specifically says **Harmful app blocked**, follow [the Play Protect review guide](PLAY_PROTECT.md). This update does not claim to clear that classification.

References: [Android installer statuses](https://developer.android.com/reference/android/content/pm/PackageInstaller), [AOSP verification result handling](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/VerificationUtils.java), [Samsung Auto Blocker](https://www.samsung.com/us/support/answer/ANS10003636/), [Google Advanced Protection](https://support.google.com/accounts/answer/9764949).

## Start with the supplied open-source fixture

1. Install `MultiClone-TestApp.apk`.
2. In MultiClone, choose **Installed apps → MultiClone Test App**.
3. Review the compatibility summary, set the quantity and fill in the empty name fields (or a prefix for more than five). A single copy may use a custom icon. Tap **Create 1 clone** or the selected batch quantity.
4. Use the **Your clones are ready** window to install each generated copy. Confirm each Android installation. Choose **Later** to keep working; **Review & install** on Create or the **Library** destination lets you install later.
5. Open the original and two copies. Give them different counters and mock account names. Close and reopen them; their state should stay independent.

The test app's mock account is only a local text value. It is not real authentication.

## Updates and later batches

- Create starts empty on a fresh launch and after submitting a batch. Choose an installed app or import its APKs for each new request. Draft values survive rotation/resizing and switching destinations.
- Set the quantity, then enter a name for each of 2–5 copies. For more than five copies, enter a batch prefix; numbers are added automatically. Single copies can use a custom icon; batches use the original icon. Each clone has a separate package and signing key.
- For an installed clone, first update the original app through its store. Choose the single **Update** action on the clone’s Library card. MultiClone checks the installed original, rebuilds from its current base/split APKs when newer, then opens Android’s Update confirmation. Its package, signing key and existing app storage are retained. If the original is already current, no build or installation starts.
- To test an actual upgrade, install the supplied `MultiClone-TestApp-v2.apk` over the original test app, then tap **Update** on a version-1 fixture clone. Its counter and mock account should survive.
- Updates require the same original app identity and source certificate. Source-certificate rotation and downgrades are not supported in this release. MultiClone never uninstalls a clone to force an update.
- **Pause queue** stops active generation and preserves completed outputs. **Resume queue** continues paused requests. **Cancel batch** prevents the remaining copies; a copy already being processed may finish.

## Install, export and uninstall

- **Launch** opens an installed clone.
- **Export** saves one APK or a single-set `.apks` archive. It does not export signing keys.
- **Delete clone** removes its library record, all generated APK versions and its local signing key. For an installed clone, Android asks to uninstall first; cancellation keeps the clone. A successful uninstall removes that clone's private data and login session, then MultiClone removes the library entry/files/key. Already exported backups/files are not modified.
- Uninstalling a previously installed clone from Android Settings or the launcher also removes it from MultiClone when package changes are received or MultiClone next resumes. Updates preserve the entry. Completed/cancelled batch rows no longer clutter the library; queued, generating and paused jobs remain visible.
- Older **RECOVERED / Not installed** entries have a **Delete clone** action, even when no APK files remain. An identity restored from backup and never installed is retained until explicitly deleted so it can still be rebuilt.
- Never-installed clones show **Install**; installed clones show one **Update** action. A labelled progress bar covers APK preparation and waiting for Android. Cancelled or failed installation clears the pending state; retry uses a verified prepared update when available.

## Signing-key backups

Use **Settings → Export encrypted backup** before resetting or uninstalling MultiClone. Choose a passphrase of at least 12 characters and keep it separately from the backup.

Backups contain clone signing identities and source metadata. They do not contain source APKs, generated APKs, custom icon files, clone login data or app-private storage. Restoring the backup recovers the identities needed to rebuild updates; import the original source APK again. Existing clone data remains under Android's control.

Resetting/uninstalling MultiClone without a signing backup can permanently prevent updating its existing clones. Uninstalling MultiClone does not itself uninstall those clones.

## Compatibility and Riot Mobile

Riot Mobile has not been tested. Select its installed installation or import its exact APK set, review findings, and test startup, official login, account persistence, notifications and updates. If an integrity or authentication dependency rejects the clone, that is a compatibility failure; MultiClone does not bypass it.

Android isolates private app data, but an external browser or account broker can share login state across applications. A separate package does not guarantee that every authentication flow permits independent accounts.

Split support is limited to complete, coherent APK sets available locally. Missing dynamic features and Play-delivered assets are not downloaded automatically. AAB, APKM and XAPK imports are unsupported. An `.apks` archive containing multiple alternative device variants is rejected instead of guessing which to install.

## Tablet layout

The tablet rail shows Create and Library at the top, with a Settings cog at the bottom. The cog has no visible label, and remains labelled for accessibility. Update notices float above the content and disappear after five seconds; opening Details lets you continue reading. Successful installation changes the clone status without showing a success banner.

Create and Library are separate screens in landscape and portrait. Wide windows use side navigation; narrow split-screen windows use bottom navigation. Compatibility warnings start collapsed: tap **Show warnings** to read them. Blocking issues remain visible and disable creation. See [the UI audit](ui/TABLET_UI_AUDIT.md) for design and skill provenance.

## Check for updates

Install v0.1.4 or later manually once to add the updater. Future updates can use Settings → Check for updates. A newer stable release downloads with progress, then a verified-update dialog offers Install update or Later. Android shows its own Update confirmation. If prompted, allow MultiClone to install apps, return, and tap Install update again.

Pause or finish clone generation before installation. Update in place: do not uninstall MultiClone or clear its storage. The updater checks the downloaded APK's SHA-256, package, higher Android version code and signing certificate. This release keeps the existing database schema, signing keys, generated files and preferences. Failed/cancelled downloads or installer confirmations leave the current installation intact. Device policy can still reject an update.

GitHub checks happen only on request. No account or GitHub token is required. Updates must use the existing signing certificate and a larger versionCode as well as a newer numeric versionName. Signing-certificate rotation is intentionally rejected by this updater.
