# Install and test on your Samsung tablet

## First installation

1. Transfer `artifacts/MultiClone-v0.1.3.apk` to the Galaxy Tab S9 FE+.
2. Open it in My Files and complete Android's installation prompts. Allow that source to install APKs if requested.
3. Open MultiClone. After this point, cloning and management run locally on the tablet; no computer, root, Shizuku or work profile is required.
4. When you first install a generated clone, allow **MultiClone** under Android's **Install unknown apps** settings, then return and tap Install again. This permission is separate from My Files' permission.

Samsung Auto Blocker or device policy can block sideloading. Use the applicable settings and system instructions on your One UI version. MultiClone does not change or bypass those controls. If Android displays developer-verification requirements, follow the supported device flow; regional enforcement can change.

## Updating MultiClone itself

Install v0.1.3 over v0.1.0, v0.1.1 or v0.1.2 through Android's normal update prompt. They use the same release certificate. The library database migrates in place. Keep MultiClone installed and retain its storage so its remaining signing keys, clone records and APKs remain available. An encrypted signing backup is also available in Settings.

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
3. Review analysis, choose a name and optionally an icon, set the number of clones, and tap **Create 1 clone** (or the selected batch quantity).
4. Use the **Your clones are ready** window to install each generated copy. Confirm each Android installation. Choose **Later** to keep working; **Review & install** on Create or the **Library** destination lets you install later.
5. Open the original and two copies. Give them different counters and mock account names. Close and reopen them; their state should stay independent.

The test app's mock account is only a local text value. It is not real authentication.

## Updates and later batches

- Select the same imported source and generate another clone or batch whenever needed. Batches use a shared name prefix and numbered names; each clone has a separate package and signing key.
- For an individual update, choose **Update** on its Library card, then import/select the newer original APK or complete split set and generate the update. Install that generated artifact through Android. Its identity and signing key are retained.
- To test an actual upgrade, import the supplied `MultiClone-TestApp-v2.apk` when updating a version-1 fixture clone. Its counter and mock account should survive.
- Updates require the same original app identity and source certificate. Source-certificate rotation and downgrades are not supported in this release. MultiClone never uninstalls a clone to force an update.
- **Pause queue** stops active generation and preserves completed outputs. **Resume queue** continues paused requests. **Cancel batch** prevents the remaining copies; a copy already being processed may finish.

## Install, export, uninstall and testing status

- **Launch** opens an installed clone.
- **Export** saves one APK or a single-set `.apks` archive. It does not export signing keys.
- **Delete clone** removes its library record, all generated APK versions and its local signing key. For an installed clone, Android asks to uninstall first; cancellation keeps the clone. A successful uninstall removes that clone's private data and login session, then MultiClone removes the library entry/files/key. Already exported backups/files are not modified.
- Uninstalling a previously installed clone from Android Settings or the launcher also removes it from MultiClone when package changes are received or MultiClone next resumes. Updates preserve the entry. Completed/cancelled batch rows no longer clutter the library; queued, generating and paused jobs remain visible.
- Older **RECOVERED / Not installed** entries have a **Delete clone** action, even when no APK files remain. An identity restored from backup and never installed is retained until explicitly deleted so it can still be rebuilt.
- **Mark tested working** records your own test result for the installed version. An update or changed installed version needs fresh testing. Generated, installed and manually tested states are distinct.

## Signing-key backups

Use **Settings → Export encrypted backup** before resetting or uninstalling MultiClone. Choose a passphrase of at least 12 characters and keep it separately from the backup.

Backups contain clone signing identities and source metadata. They do not contain source APKs, generated APKs, custom icon files, clone login data or app-private storage. Restoring the backup recovers the identities needed to rebuild updates; import the original source APK again. Existing clone data remains under Android's control.

Resetting/uninstalling MultiClone without a signing backup can permanently prevent updating its existing clones. Uninstalling MultiClone does not itself uninstall those clones.

## Compatibility and Riot Mobile

Riot Mobile has not been tested. Select its installed installation or import its exact APK set, review findings, and test startup, official login, account persistence, notifications and updates. If an integrity or authentication dependency rejects the clone, that is a compatibility failure; MultiClone does not bypass it.

Android isolates private app data, but an external browser or account broker can share login state across applications. A separate package does not guarantee that every authentication flow permits independent accounts.

Split support is limited to complete, coherent APK sets available locally. Missing dynamic features and Play-delivered assets are not downloaded automatically. AAB, APKM and XAPK imports are unsupported. An `.apks` archive containing multiple alternative device variants is rejected instead of guessing which to install.

## Tablet layout

Create and Library are separate screens in landscape and portrait. Wide windows use side navigation; narrow split-screen windows use bottom navigation. Compatibility warnings start collapsed: tap **Show warnings** to read them. Blocking issues remain visible and disable creation. See [the UI audit](ui/TABLET_UI_AUDIT.md) for design and skill provenance.
