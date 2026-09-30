plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":langgraph-kt-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    implementation("dev.langchain4j:langchain4j:0.31.0")
    
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}

tasks.test {
    useJUnitPlatform()
}
