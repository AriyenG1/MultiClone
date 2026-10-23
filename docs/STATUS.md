# MultiClone v0.1.6 — implementation and verification

Build date: 23 October 2026. Signed APK: `artifacts/MultiClone-v0.1.6.apk`. Installation instructions: [TABLET_GUIDE.md](TABLET_GUIDE.md).

## v0.1.6 navigation, fresh Create and named batches

The repeated MultiClone header, On-device chip and decorative rail logo are removed. Settings uses a labelled-for-accessibility cog without visible text, anchored at the bottom of the tablet rail. Compact windows retain bottom navigation. Installation success updates the installed status without a banner or redundant success message.

Create starts without a selected source or old field values. It has no import-history chooser and does not reuse a saved source preference. A submitted batch resets the form; active drafts still survive destination changes and resizing/rotation. One clone can use a custom icon. Two to five copies get individual name fields; larger batches use a numbered prefix. Batches retain the original icon.

Room schema 3 adds a default-empty cloneNames column to jobs, with a 2→3 migration preserving all existing source/clone/job records. Individual names persist through queue interruption/recovery. Existing schema-2 batches continue with their original prefix behavior. Signing identities, clone package names and app-private data are unchanged.

Clone Update feedback uses a floating Material notice that expires after five seconds, including errors and repeated attempts. Installation/generation progress remains on the clone card while work continues. Details opened from a notice remain readable after the notice expires. See [the UI verification](ui/VERIFICATION.md).

## v0.1.5 clone updates and installation feedback

Installed clones now have a single Update action. It checks the installed original app, reimports its current APK set and uses the existing queue to build a same-identity update, then opens Android’s confirmation directly. A cancelled update reuses the verified generated APK on retry. Up-to-date originals do not create redundant jobs; the original must first be updated through its store. The successful-analysis banner, manual testing controls and routine untested status wording are removed. Detailed compatibility findings remain collapsed and blocked sources cannot generate.

Clone installation shows labelled progress for verification, APK transfer and Android’s pending confirmation. Session completion, cancellation and failures reset the progress state; session reconciliation restores pending state after reopening. Clone identity, keys and database schema remain unchanged. See [UI verification](ui/VERIFICATION.md) for v0.1.5 evidence.

## v0.1.4 app updates

Settings now checks the public GitHub latest stable release on request. Newer numeric versions download over bounded HTTPS with progress/cancellation, SHA-256 verification and package/version/signature validation. Android's normal APK installer confirms the in-place update. Active generation must be paused or finished first. INTERNET permission is now required solely for these user-requested checks/downloads.

The database schema remains 2. Existing clone files, keys and settings are retained in app storage. Eight relevant Android checks pass, including live GitHub metadata; an emulator upgrade from v0.1.3 retained all 16 recorded database/preferences/key files byte-for-byte. Additional installer handoff evidence and limitations are in [the updater verification](updates/README.md).

## v0.1.3 tablet interface

Create and Library remain separate at all window sizes. The Material 3 interface uses window-width navigation, centered bounded forms, grouped Create controls, collapsed compatibility findings and system light/dark colors. Short windows omit the rail's decorative header to keep destinations reachable. Form and scroll state use saveable destination state.

Successfully published new clones open an installation dialog. Choosing Later suppresses further automatic popups until the next user-submitted generation request; Review & install remains available on Create. Android's installation confirmation remains required. Long status messages and installation diagnostics use bounded previews and separate full-detail dialogs.

The two requested design skills are installed and configured in `AGENTS.md`, alongside Google's official adaptive-layout references. See [the tablet UI audit](ui/TABLET_UI_AUDIT.md), [current verification](ui/VERIFICATION.md), and [release inspection](ui/release-inspection-v0.1.3.json). The following v0.1.2 and earlier test results describe those historical releases.

## v0.1.2 clone library

Completed/cancelled batch rows are hidden. Clone cards use readable installed/ready/generating/rebuild states. Delete clone replaces Delete APKs and removes the record, all generated versions/staging files and local signing key. Installed clones use Android's uninstall confirmation first; cancelling keeps the entry. A previously installed clone that is removed outside MultiClone is cleaned up on reconciliation. Never-installed generated/restored clones stay available for installation or explicit deletion. Pending installer sessions and package-replacement broadcasts protect updates from premature removal. Removal and build publication share a per-clone processing lock, and deleting a clone cancels its queued/paused updates. Room schema 1 migrates to 2 without resetting the library.

The user also reports Play Protect's “Harmful app blocked” warning for MultiClone and generated clones, with generic device/data-risk wording. The underlying classification is not exposed. [PLAY_PROTECT.md](PLAY_PROTECT.md) records the local inspection scope and official review route. No warning suppression or claim of a resolved classification is included.

All ten Android 16/API 36 instrumented acceptance checks passed, including complete generated/restored-entry deletion, all artifact versions and key cleanup, cancellation of pending clone updates, hidden completed jobs, uninstall cancellation, outside-app uninstall reconciliation, pending-install preservation, and schema-1-to-2 migration with retained records. The six prior cloning/workflow/installer-feedback checks also pass. Results: [v0.1.2 library verification](verification/v0.1.2-library.txt).

The signed release was also updated in place on Android 17/API 37 with the migrated installed clone retained. Its final Delete clone flow reached Android's uninstall prompt; confirming left an empty library with no completed job row or stale confirmation banner. The release inspection verifies expected permissions, signature, alignment and ZIP structures. Play Protect is absent from these emulator images; the physical tablet's classification remains unresolved.

## v0.1.1 installer feedback

The physical Samsung report for Riot Mobile version 508001003 (four APKs) shows `INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed`, installer status 3, and no separate warning. Android's package verifier rejected installation; the responsible verifier/rule is unknown. No runtime compatibility result is available.

This update replaces the stale installation-request banner with the callback result, uses error styling for failed actions, preserves optional package-manager/session/related-package details and adds Copy details to clone cards. It interprets the detailed verification error before the broad aborted status. The installation session, APK processing and security controls are unchanged. The release certificate and database schema are retained for an in-place application update.

All six instrumented tests passed on Android 16/API 36 for v0.1.1: the four existing acceptance tests plus two installer-feedback regression tests. The new checks cover the reported status-3/verifier-failure combination, repeated identical failures, persisted clone details, success feedback and distinct aborted/blocked/invalid explanations. Debug lint has no errors. The signed v0.1.1 APK passes signature and ZIP alignment checks; its certificate digest matches v0.1.0. An in-place Android 17/API 37 update retained the previous installed clone record and exposed Copy details. Evidence: [v0.1.1 verification](verification/v0.1.1-install-feedback.txt). This emulator verification does not resolve the physical tablet's verifier rejection.

## Implemented

- Installed user-app discovery and root-free copying of readable base/split APKs.
- Document import of ordinary APKs, manually selected split sets and coherent single-set `.apks` archives.
- Signature verification, compatibility findings and bounded binary manifest/resource inspection.
- Unique clone identities/certificates, custom names and adaptive icons, isolated Android app storage.
- Conservative self-provider DEX URI transformation, preserving implementation class names.
- Consistent base/configuration/feature split rewriting and signing; one installer session per clone.
- Persistent sequential batches without a product clone-count cap; pause/resume/cancellation and interruption recovery.
- Dashboard, installation, launch, individual updates, Android-confirmed removal and artifact export/deletion.
- Android Keystore-wrapped signing keys and authenticated password-encrypted key/metadata backups.
- On-demand GitHub updates; no telemetry or automatic cloud/device-transfer backup.

## Verified

The v0.1.0 engine's four instrumented tests passed on both ARM64 Android 16/API 36 and Android 17/API 37 emulators. Raw results: [Android 16](verification/android16.txt), [Android 17](verification/android17.txt).

| Scenario | Result |
|---|---|
| Compiled APK transformed on Android; original plus two clones installed | Passed |
| Relative components, dedicated labels/resources and adaptive icons | Passed |
| Deliberately hardcoded self-provider URI and private provider access | Passed |
| Different persistent counters/mock accounts and restart persistence | Passed |
| Upgrade from version 1 to version 2 preserving app state | Passed |
| Uninstall one clone while another retains its account/data | Passed |
| Google Jetchat sample cloned, installed and conversation rendered | Passed |
| Base + French configuration + separate install-time feature split and feature launch | Passed |
| Read installed base/split paths from another app UID and reanalyze | Passed |
| Batch of seven, later batch, distinct certificates and package IDs | Passed |
| Lazy Long.MAX_VALUE request cancellation and interruption recovery | Passed |
| Encrypted signing-key restoration and stable-identity update | Passed |

Four JVM unit tests pass: backup randomization/round-trip/tamper/wrong-password handling, short-passphrase rejection, archive path traversal rejection, and false expanded-size metadata rejection. The final archive checks also passed the complete Android 16 suite.

Debug and release builds pass. Lint has no errors. Remaining warnings concern pinned dependency updates, the chosen target SDK, space-estimation suggestions and an unused third-party Bouncy Castle TLS helper. Versions through v0.1.3 had no network permission. Starting with v0.1.4, user-requested GitHub updates use HTTPS with the platform TLS implementation.

The signed release passes signature and ZIP alignment verification. A separate Android 17 release-UI smoke test imported an installed app, generated a clone, granted installation permission through Android Settings, installed through the system confirmation and reconciled the dashboard to Installed. The installed release is non-debuggable. Emitted APK runtime dependencies are version-locked; Gradle artifacts are checked against SHA-256 metadata. The project-local toolchain leaves global Java settings unchanged.

## Compatibility boundaries

The user attempted to install a Riot Mobile clone on the physical Samsung Galaxy Tab S9 FE+; Android verification rejected it as described above. Riot startup/login/data separation remain untested. No Riot-specific transformation or predefined clone list exists.

Original-certificate dependencies, integrity enforcement, OAuth registrations, native identity checks and server behavior may prevent operation. Static analysis cannot find every dependency. Certificate rotation, downgrades, shared-UID apps, privileged package types, AAB/APKM/XAPK imports, automatic on-demand delivery and Play asset delivery are unsupported.

The supplied feature/configuration split fixtures work; other layouts remain experimental until tested. A successful build or installation does not certify runtime compatibility. Browser/account-broker sessions can remain shared even when private app storage is isolated.

## Local toolchain

Temurin JDK 17.0.21; Gradle 9.6.0; AGP 9.4.0; Android platform 37.0; Build Tools 36.0.0; platform tools 37.0.1; native Apple Silicon emulators. Downloads/caches are under `.tools/`, excluded from source archives.

Preserve `private/` securely: its separate release key signs future MultiClone updates. Clone keys are generated on the tablet and recovered through the app's encrypted backup flow.
