plugins {
    id("langgraph.kotlin-library")
    id("langgraph.publishing")
}

dependencies {
    api(project(":langgraph-kt-core"))
    api(libs.langchain4j)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
