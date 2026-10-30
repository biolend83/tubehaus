plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    // the plugin rewrites this module's @Patchable functions as it compiles them
    kotlinCompilerPluginClasspath(project(":plugin"))
}

application { mainClass.set("app.tubehaus.patch.DemoKt") }
