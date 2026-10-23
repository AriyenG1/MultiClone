plugins { id("com.android.application") }
android {
    namespace = "dev.multiclone.fixture"
    dynamicFeatures += setOf(":test_feature")
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.multiclone.fixture"
        minSdk = 28
        targetSdk = 36
        versionCode = providers.gradleProperty("fixtureVersion").orElse("1").get().toInt()
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
