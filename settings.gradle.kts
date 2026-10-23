pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.MuntashirAkon") } }
    }
}
rootProject.name = "MultiClone"
include(":app", ":core", ":apk-engine", ":signing", ":platform", ":storage", ":test-app")
include(":test_feature")
project(":test_feature").projectDir = file("test-feature")
