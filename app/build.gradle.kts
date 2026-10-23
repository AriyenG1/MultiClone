plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.multiclone.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.multiclone.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES") }
}
dependencies {
    implementation(project(":core")); implementation(project(":apk-engine"))
    implementation(project(":signing")); implementation(project(":platform")); implementation(project(":storage"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
val fixtureAssets = tasks.register<Copy>("prepareFixtureAssets") {
    dependsOn(":test-app:assembleDebug")
    from(project(":test-app").layout.buildDirectory.file("outputs/apk/debug/test-app-debug.apk"))
    into(layout.buildDirectory.dir("generated/fixtureAssets"))
    rename { "fixture.apk" }
}
val jetchatAssets = tasks.register<Copy>("prepareJetchatAssets") {
    from(rootProject.file(".tools/compose-samples/Jetchat/app/build/outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("generated/jetchatAssets"))
    rename { "jetchat.apk" }
}
val splitAssets = tasks.register<Copy>("prepareSplitAssets") {
    from(rootProject.file(".tools/fixture-splits.apks"))
    into(layout.buildDirectory.dir("generated/splitAssets"))
    rename { "fixture.apks" }
}
val updateAssets = tasks.register<Copy>("prepareUpdateAssets") {
    from(rootProject.file(".tools/fixture-v2.apk"))
    into(layout.buildDirectory.dir("generated/updateAssets"))
    rename { "fixture-v2.apk" }
}
android.sourceSets.getByName("androidTest").assets.directories.add(layout.buildDirectory.dir("generated/fixtureAssets").get().asFile.absolutePath)
android.sourceSets.getByName("androidTest").assets.directories.add(layout.buildDirectory.dir("generated/jetchatAssets").get().asFile.absolutePath)
android.sourceSets.getByName("androidTest").assets.directories.add(layout.buildDirectory.dir("generated/splitAssets").get().asFile.absolutePath)
android.sourceSets.getByName("androidTest").assets.directories.add(layout.buildDirectory.dir("generated/updateAssets").get().asFile.absolutePath)
tasks.matching { it.name == "mergeDebugAndroidTestAssets" || it.name.contains("AndroidTest") && it.name.contains("lint", true) }.configureEach { dependsOn(fixtureAssets, jetchatAssets, splitAssets, updateAssets) }
