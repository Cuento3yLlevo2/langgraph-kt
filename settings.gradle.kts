pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "langgraph-kt"

include("langgraph-kt-core")
include("langgraph-kt-serialization")
include("langgraph-kt-checkpoint-file")
include("langgraph-kt-agent")
include("langgraph-kt-langchain4j")
include("samples")
