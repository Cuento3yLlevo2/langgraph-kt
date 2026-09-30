plugins {
    id("langgraph.root")
}

dependencies {
    dokka(project(":langgraph-kt-core"))
    dokka(project(":langgraph-kt-langchain4j"))
    kover(project(":langgraph-kt-core"))
    kover(project(":langgraph-kt-langchain4j"))
}
