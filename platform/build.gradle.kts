plugins { id("com.android.library") }
android {
    namespace = "dev.multiclone.platform"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { api(project(":core")); implementation("androidx.core:core-ktx:1.17.0") }
