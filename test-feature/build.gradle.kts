plugins { id("com.android.dynamic-feature") }
android {
    namespace = "dev.multiclone.fixture.feature"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(project(":test-app")) }
