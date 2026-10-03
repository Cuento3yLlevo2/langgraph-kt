/*
 * Runnable examples. Not published. Run one with, for example:
 *   ./gradlew :samples:runQuickStart
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":langgraph-kt-core"))
    implementation(project(":langgraph-kt-serialization"))
    implementation(project(":langgraph-kt-checkpoint-file"))
    implementation(project(":langgraph-kt-langchain4j"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

listOf("QuickStart", "HumanInTheLoop", "ReviewLoop", "ParallelResearch", "ChatAgent").forEach { sample ->
    tasks.register<JavaExec>("run$sample") {
        group = "samples"
        description = "Runs the $sample sample."
        mainClass.set("org.langgraphkt.samples.${sample}Kt")
        classpath = sourceSets.main.get().runtimeClasspath
        standardInput = System.`in`
    }
}

// The levels of the tutorial in docs/, for example: ./gradlew :samples:runLevel1
(1..10).forEach { level ->
    tasks.register<JavaExec>("runLevel$level") {
        group = "tutorial"
        description = "Runs level $level of the tutorial in docs/."
        mainClass.set("org.langgraphkt.samples.tutorial.level$level.Level${level}Kt")
        classpath = sourceSets.main.get().runtimeClasspath
        standardInput = System.`in`
    }
}
