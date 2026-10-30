pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tubehaus-app"
include(":android", ":desktop")
// the iPad app needs Xcode, so it is only part of the build on a Mac
if (System.getProperty("os.name").startsWith("Mac") && file("ios/build.gradle.kts").exists()) {
    include(":ios")
    // TubePatch's compiler plugin, used by the iPad build (ios/patch)
    include(":tubepatch-plugin")
    project(":tubepatch-plugin").projectDir = file("ios/patch/plugin")
}
