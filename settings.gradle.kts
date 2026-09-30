pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "langgraph-kt"

include("langgraph-kt-core")
include("langgraph-kt-checkpoint-file")
include("langgraph-kt-langchain4j")
