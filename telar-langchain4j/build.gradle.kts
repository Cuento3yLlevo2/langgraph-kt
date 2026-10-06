import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("langgraph.jvm-library")
    id("langgraph.publishing")
    // The tests declare a @Serializable tool input.
    id("org.jetbrains.kotlin.plugin.serialization")
}

// LangChain4j 1.x is compiled for Java 17, so this module cannot run on Java 11 like the others.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":telar-core"))
    api(project(":telar-agent"))
    api(libs.langchain4j)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
