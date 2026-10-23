plugins { id("com.android.library") }
android {
    namespace = "dev.multiclone.signing"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(project(":core"))
    implementation("com.github.MuntashirAkon:apksig-android:4.4.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80.2")
    testImplementation("junit:junit:4.13.2")
}
