plugins {
    id("telar.root")
}

dependencies {
    dokka(project(":telar-core"))
    dokka(project(":telar-serialization"))
    dokka(project(":telar-checkpoint-file"))
    dokka(project(":telar-checkpoint-browser"))
    dokka(project(":telar-agent"))
    dokka(project(":telar-anthropic"))
    dokka(project(":telar-langchain4j"))
    dokka(project(":telar-typesafe"))
    kover(project(":telar-core"))
    kover(project(":telar-serialization"))
    kover(project(":telar-checkpoint-file"))
    kover(project(":telar-agent"))
    kover(project(":telar-anthropic"))
    kover(project(":telar-langchain4j"))
    kover(project(":telar-typesafe"))
}
