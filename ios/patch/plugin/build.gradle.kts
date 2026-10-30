plugins { id("org.jetbrains.kotlin.jvm") }

kotlin {
    jvmToolchain(21)
    compilerOptions { optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi") }
}

dependencies {
    // the embeddable compiler: the one Gradle runs for the JVM and, since Kotlin 1.9.20, for Kotlin/Native too
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0")
}
