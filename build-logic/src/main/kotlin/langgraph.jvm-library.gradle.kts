import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Conventions for JVM-only library modules (e.g. integrations with Java libraries).
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("langgraph.quality")
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
