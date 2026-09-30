import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Shared conventions for every published langgraph-kt library module:
 * JVM toolchain, compiler strictness, linting, coverage and API docs.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
}

kotlin {
    // Build with JDK 17, but emit Java 11 bytecode so Android and older JVM consumers can use the library.
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
