// TubePatch: a JSPatch for Kotlin. A compiler plugin (plugin/) puts a check at the start of every
// function marked @Patchable: when a patch file has a replacement for it, that runs instead.
// demo/ shows it on the desktop JVM: ./gradlew -p ios/patch :demo:run
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "tubepatch"
include(":plugin", ":demo")
