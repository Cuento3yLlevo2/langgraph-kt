import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Conventions for Kotlin Multiplatform library modules.
 *
 * The JVM artifact also serves Android. Apple targets can only be tested on macOS hosts;
 * CI runs them on a macOS runner.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("langgraph.quality")
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js {
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
