import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // The conformance test runs a few hundred million emulated cycles.
    maxHeapSize = "1g"

    // BootTest switches a whole machine on, which needs a ROM set that cannot live in this
    // repository. Point BREADBIN_ROMS at a directory holding one and those tests run; without it
    // they are skipped, and they print the emulated screen either way.
    System.getenv("BREADBIN_ROMS")?.let { environment("BREADBIN_ROMS", it) }
    testLogging { showStandardStreams = true }
}
