import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val appVersion = providers.gradleProperty("app.version").get()

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
    // the shared code (engine, library, screens) is compiled into this module as plain sources
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin", "../common/src", "../common/jvm")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("com.squareup.okio:okio:3.10.2")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("net.java.dev.jna:jna:5.17.0")
}

compose.desktop {
    application {
        mainClass = "app.tubehaus.desktop.MainKt"
        jvmArgs += listOf("-Xmx512m")
        nativeDistributions {
            // only the formats of the system it is built on (Compose refuses e.g. AppImage on macOS)
            val os = System.getProperty("os.name")
            when {
                os.startsWith("Windows") -> targetFormats(TargetFormat.Msi, TargetFormat.Exe)
                os.startsWith("Mac") -> targetFormats(TargetFormat.Dmg)
                else -> targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            }
            packageName = "TubeHaus"
            packageVersion = appVersion
            description = "YouTube on your own terms"
            vendor = "TubeHaus"
            copyright = "GPL-3.0"
            // libmpv-2.dll goes in resources/windows (not in git: see README)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.net.http", "jdk.unsupported", "jdk.httpserver")
            windows {
                menuGroup = "TubeHaus"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1d1c8e-3c43-4a8e-9a55-1b6a8f2a7c11"
                iconFile.set(project.layout.projectDirectory.file("icon.ico"))
            }
            // macOS uses Homebrew's mpv; the app isn't signed or notarized (right-click → Open the first time)
            macOS {
                bundleID = "io.github.biolend83.tubehaus"
                appCategory = "public.app-category.video"
                // macOS wants the first number above 0: 0.3.3 becomes 1.3.3 inside the bundle only (files keep 0.3.3)
                packageVersion = "1." + appVersion.substringAfter('.')
                project.layout.projectDirectory.file("icon.icns").asFile.takeIf { it.exists() }?.let { iconFile.set(it) }
            }
            // Linux uses the system's libmpv (libmpv2 on Debian/Ubuntu), so nothing is bundled
            linux {
                packageName = "tubehaus"
                debMaintainer = "biolend83 <258010694+biolend83@users.noreply.github.com>"
                menuGroup = "AudioVideo"
                appCategory = "Video"
                rpmLicenseType = "GPL-3.0-only"
                shortcut = true
                iconFile.set(project.layout.projectDirectory.file("src/main/resources/icon.png"))
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// runs every engine call against YouTube: ./gradlew :desktop:engineCheck
tasks.register<JavaExec>("engineCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.tubehaus.desktop.EngineCheckKt")
}
