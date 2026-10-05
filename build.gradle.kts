plugins {
    id("langgraph.root")
}

dependencies {
    dokka(project(":langgraph-kt-core"))
    dokka(project(":langgraph-kt-serialization"))
    dokka(project(":langgraph-kt-checkpoint-file"))
    dokka(project(":langgraph-kt-checkpoint-browser"))
    dokka(project(":langgraph-kt-agent"))
    dokka(project(":langgraph-kt-anthropic"))
    dokka(project(":langgraph-kt-langchain4j"))
    kover(project(":langgraph-kt-core"))
    kover(project(":langgraph-kt-serialization"))
    kover(project(":langgraph-kt-checkpoint-file"))
    kover(project(":langgraph-kt-agent"))
    kover(project(":langgraph-kt-anthropic"))
    kover(project(":langgraph-kt-langchain4j"))
}
