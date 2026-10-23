plugins { id("com.android.library") }
android {
    namespace = "dev.multiclone.engine"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(project(":core"))
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
    testImplementation("junit:junit:4.13.2")
}
