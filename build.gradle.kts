plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("com.android.dynamic-feature") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
subprojects {
    if (name in setOf("app", "test-app")) {
        configurations.configureEach {
            if (name in setOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "debugAndroidTestRuntimeClasspath", "debugUnitTestRuntimeClasspath"))
                resolutionStrategy.activateDependencyLocking()
        }
    }
}
