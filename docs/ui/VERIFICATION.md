# v0.1.6 navigation and Create verification — 23 October 2026

- Project-local `:app:connectedDebugAndroidTest test lint`: successful. Eighteen Android checks executed successfully. One unchanged optional self-updater check requires a host-prepared higher-version APK and was not run (its JUnit assumption is rendered as a failure element by the XML writer; the Gradle run succeeds).
- Four JVM signing/archive tests pass. Repository-wide lint passes with zero errors; existing warnings remain. Final debug build/lint and signed release packaging/inspection also pass after copy and saved-details refinements.
- New UI coverage checks that a previously stored import preference does not populate a fresh Create screen, there is no repeated header/chip/import-history control, and the accessible Settings cog is at the bottom of the tablet rail without visible text.
- The real Create controls cover empty fields, five individually named copies versus six using a prefix, exact labels after generating two copies, original batch icons, draft preservation after resizing and reset after submission.
- New queue coverage verifies five individual names survive interrupted-job recovery, invalid larger named batches are rejected, and batch icon overrides are ignored. The migration check runs the 1→2→3 path and verifies old records/jobs survive with an empty names default. Existing engine/splits/Jetchat, backup, cleanup, update/cancellation and data-retention regressions pass.
- Installer feedback checks require an empty success banner. The clone update test verifies that its floating notice disappears after 5.5 seconds without pressing dismiss.

Evidence: [full Gradle log](v0.1.6/gradle-validation.txt), [raw Android results](v0.1.6/android-tests.xml), [focused Create workflow](v0.1.6/create-workflow.txt), [final build/lint](v0.1.6/final-check.txt), [signed release inspection](v0.1.6/release-inspection.json).

Screenshots: [fresh Create](v0.1.6/create-empty.png), [individual names after resizing](v0.1.6/create-individual-names.png), [five-second Update notice](v0.1.6/update-notice.png), [signed release dark theme](v0.1.6/create-dark.png), [narrow 150% text](v0.1.6/create-compact-large-text.png). Empty/light/dark/compact captures use the signed APK; naming/notice captures use the test build. Samsung hardware policy, S Pen and live DeX behavior still require a physical-device pass.

Room schema 3 adds only the jobs' default-empty names column. The release certificate matches v0.1.5; existing clone package/signing identities are retained. No dependency locks, verification metadata or signing credentials were changed.

---

# v0.1.5 Library and installation verification — 23 October 2026

- Project-local `:app:connectedDebugAndroidTest test lint`: successful. Sixteen Android checks executed successfully, including the new original-to-clone update/cancellation/retry/data-retention flow, all engine/split/Jetchat, library, workflow and installer-feedback regressions. The optional self-updater test requiring a host-prepared higher-version APK was not run; its assumption is recorded as a failure element by the test XML writer even though the Gradle run succeeds. This unchanged optional handoff was verified for v0.1.4.
- Four JVM signing/archive tests pass. Repository-wide lint passes with zero errors (existing warnings remain). Added the fixture's missing French feature label discovered by this broader check.
- The follow-up focused UI check taps the actual Library Install/Update buttons and verifies that uninstalled clones have no Update action. It also checks cancellation, cached retry, no redundant up-to-date job, stable package/certificate and retained counter/provider/mock account data across version 1→2.
- No database reset/migration, dependency lock change or signing-key change. Android confirms each installation/update; the app does not uninstall clones for updates.

Evidence: [full Gradle run](v0.1.5/gradle-validation.txt), [raw Android results](v0.1.5/android-tests.xml), [button workflow](v0.1.5/button-workflow.txt), [signed release inspection](v0.1.5/release-inspection.json). Samsung hardware/One UI policy behavior remains a physical-device check.

Screenshots: [single Update action after cancellation](v0.1.5/library-cancelled.png), [retained counter, provider and mock account after updating](v0.1.5/clone-data-retained.png).

The installed original must be updated through its store first. The combined clone Update action reads that app's local APK set; it does not download originals from Play or an APK mirror.

---

# v0.1.3 UI verification — 23 October 2026

## Results

- Debug and signed release APK builds: passed.
- Final debug lint: zero errors; existing dependency/SDK/third-party and space-estimation warnings remain.
- Four signing/archive JVM tests: passed through the project-local Gradle task graph.
- Five Android 16/API 36 instrumented checks: passed together, zero failures. [Raw results](ui-regression-results.txt).
- Signed release inspection: passed signature, ZIP alignment, expected permissions, CRC/path/duplicate-entry and non-debuggable checks. The certificate matches v0.1.2. [Inspection JSON](release-inspection-v0.1.3.json).

The two new UI checks cover initial warning collapse and expansion, blocking-source rejection at the generation boundary, real clone publication opening the install dialog, Later suppressing further generation popups, reopening the prompt, and reaching Android's native install confirmation. Dialog state actions use the live Activity's ViewModel; warning expansion uses the UI control. These checks do not certify every pointer or keyboard interaction.

The three existing regressions cover foreground batches, later batches, lazy huge-count cancellation, interruption recovery, encrypted signing backup/restoration, stable-identity updates, repeated installer failure feedback and status interpretation.

The earlier full ten-test engine/library acceptance suite was not rerun for this UI update. Its historical evidence remains in STATUS.md.

## Reproduce

```sh
scripts/gradle-local.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
scripts/gradle-local.sh :signing:testDebugUnitTest :apk-engine:testDebugUnitTest
.tools/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
.tools/android-sdk/platform-tools/adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
.tools/android-sdk/platform-tools/adb shell am instrument -w -e class dev.multiclone.app.TabletUiTest,dev.multiclone.app.WorkflowTest,dev.multiclone.app.InstallFeedbackTest dev.multiclone.app.test/androidx.test.runner.AndroidJUnitRunner
scripts/package-release.sh
python3 scripts/inspect-release.py artifacts/MultiClone-v0.1.3.apk
```

Use a development emulator for instrumentation: tests create clones and signing keys, and the backup test temporarily removes and restores a test key. UI test setup resets stale emulator package-installer windows and pending sessions. It uses real analyzer-generated source UUIDs so backup validation remains representative.

## Screenshot matrix

These are actual emulator captures, with density 160 so logical pixels correspond to dp. ADB window overrides simulate available sizes; the captures are not photographs of Samsung hardware. The layout captures restart the app at each configuration; the design also uses saveable state during real configuration changes.

| Configuration | Evidence |
| --- | --- |
| 1280 × 800, light, tablet landscape | [Create](tablet-landscape.png) |
| 800 × 1280, light, tablet portrait | [Create](tablet-portrait.png) |
| 800 × 1280, dark | [Dark theme](tablet-dark.png) |
| 420 × 900, light, 150% text | [Narrow window with large text](compact-large-text.png) |
| 800 × 400, light | [Short window and rail](short-window.png) |
| Generated copy ready | [Install window](install-window.png) |
| Android confirmation after explicit install request | [Android confirmation](android-install-confirmation.png) |

Physical Galaxy Tab S9 FE+/One UI testing, S Pen, external keyboard/trackpad and live DeX multi-window interactions remain device checks. This UI update does not establish new third-party app runtime compatibility.

## Release

APK: `artifacts/MultiClone-v0.1.3.apk` (version code 4).

SHA-256: `2c4b0e2bb803dddc508e13a0bdb6d84b395d583945fb6ee8e4dd1a89425cb6b7`.

The existing release certificate is retained, allowing an Android in-place update from v0.1.2. No database migration or reset was introduced by the UI redesign.
