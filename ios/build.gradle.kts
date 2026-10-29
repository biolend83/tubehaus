// The iPad app: the shared sources (common/src) plus their iOS pieces (common/ios) and the iOS
// platform (src), compiled with Kotlin/Native into one static framework. Xcode (ios/app) only
// wraps it in an app; see .github/workflows/ios.yml.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "TubeHausKit"
            isStatic = true
        }
    }
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.cinterop.ExperimentalForeignApi",
            "kotlinx.cinterop.BetaInteropApi",
        )
    }
    sourceSets {
        iosMain {
            kotlin.srcDirs("../common/src", "../common/ios", "src")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("com.squareup.okio:okio:3.10.2")
                implementation("io.coil-kt.coil3:coil-compose:3.2.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
                implementation("io.ktor:ktor-client-darwin:3.1.3")
            }
        }
    }
}
