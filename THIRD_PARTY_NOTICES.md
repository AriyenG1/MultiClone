# Third-party dependencies

Direct dependencies are pinned in Gradle. A dependency verification manifest will record downloaded artifact hashes.

| Component | Version | License / upstream |
|---|---|---|
| ARSCLib | 1.4.0 | Apache-2.0 — https://github.com/REAndroid/ARSCLib |
| Google smali-dexlib2 | 3.0.10 | BSD-3-Clause — https://github.com/google/smali |
| apksig-android | 4.4.0 | Apache-2.0 — https://github.com/MuntashirAkon/apksig-android |
| Bouncy Castle | 1.80.2 | MIT — https://www.bouncycastle.org/licence.html |
| AndroidX / Jetpack Compose / Room / DataStore | See Gradle | Apache-2.0 — https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / kotlinx.coroutines | See Gradle | Apache-2.0 — https://github.com/JetBrains/kotlin |
| JUnit | 4.13.2 | EPL-1.0 — https://github.com/junit-team/junit4 |

Jetchat, when used for compatibility testing, is from Google's Compose Samples repository, tag `v2026.08.00`, Apache-2.0. It is a test dependency, not bundled into the shipped MultiClone application.

Upstream license texts for runtime dependencies are retained under `licenses/`. MultiClone does not redistribute third-party commercial source APKs or credentials.
