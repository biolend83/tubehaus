plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val appVersion = providers.gradleProperty("app.version").get()
val appVersionCode = providers.gradleProperty("app.versionCode").get().toInt()

android {
    namespace = "app.tubehaus.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.biolend83.tubehaus"
        minSdk = 25
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        // -PsabrOnly=true: a test build that plays every video the SABR way
        val sabrOnly = (findProperty("sabrOnly") ?: "false").toString()
        manifestPlaceholders["sabrOnly"] = sabrOnly
        // the test build installs beside the real app: its own id and name
        manifestPlaceholders["appLabel"] = if (sabrOnly == "true") "TubeHaus-test" else "TubeHaus"
        if (sabrOnly == "true") applicationIdSuffix = ".test"
        // -Parm64=true: an APK for 64-bit ARM phones only (most phones; much smaller)
        if (findProperty("arm64") == "true") ndk.abiFilters += "arm64-v8a"
    }
    // the same build with its native libraries compressed in the APK (smaller to send; unpacked once at install)
    if (findProperty("arm64") == "true") packaging.jniLibs.useLegacyPackaging = true
    defaultConfig {
    }

    // V8 for the SABR engine (arm64): built only when android/v8 holds the v8-build branch's library
    if (file("v8/lib/libv8_monolith.a").exists()) {
        ndkVersion = "28.2.13676358"
        defaultConfig.externalNativeBuild.cmake { arguments += "-DANDROID_STL=c++_static"; abiFilters += "arm64-v8a" }
        externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    // The shared code (engine, library, screens) is compiled into this module as plain sources.
    sourceSets["main"].java.srcDirs("src/main/kotlin", "../common/src", "../common/jvm")
    // Pretendard, the type face of the web app 1.0 (one copy for every platform: common/fonts)
    sourceSets["main"].assets.srcDirs("../common/fonts", "../common/ejs", "../common/sabr")
    // not common/sabr/lite: the sources sabr-lite.js is built from (and its node_modules)
    androidResources.ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!lite"

    signingConfigs {
        // the release key lives outside the repository; without it the APK is signed with the debug key
        val ks = providers.environmentVariable("TUBEHAUS_KEYSTORE").orNull
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = providers.environmentVariable("TUBEHAUS_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("TUBEHAUS_KEY_ALIAS").getOrElse("tubehaus")
            keyPassword = providers.environmentVariable("TUBEHAUS_KEY_PASSWORD").orNull ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // one APK per CPU is not needed: the app has no native code of its own
    dependenciesInfo {
        // IzzyOnDroid/F-Droid can't read Google's encrypted dependency block
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.2.0"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.14.0")

    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QuickJS, the JavaScript engine SABR can run in without a WebView (JsSabr)
    implementation("io.github.dokar3:quickjs-kt:1.0.5")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("com.squareup.okio:okio:3.10.2")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
}
