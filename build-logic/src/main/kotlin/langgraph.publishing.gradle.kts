/*
 * Maven Central publishing conventions. Module-specific name and description
 * come from the `POM_NAME` / `POM_DESCRIPTION` Gradle properties.
 */
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    // Sign only when a key is configured (CI release job), so local publishToMavenLocal keeps working.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set(providers.gradleProperty("POM_NAME"))
        description.set(providers.gradleProperty("POM_DESCRIPTION"))
        inceptionYear.set("2026")
        url.set("https://github.com/Cuento3yLlevo2/langgraph-kt")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("hermosotech")
                name.set("hermosotech")
                url.set("https://github.com/Cuento3yLlevo2")
            }
        }
        scm {
            url.set("https://github.com/Cuento3yLlevo2/langgraph-kt")
            connection.set("scm:git:git://github.com/Cuento3yLlevo2/langgraph-kt.git")
            developerConnection.set("scm:git:ssh://git@github.com/Cuento3yLlevo2/langgraph-kt.git")
        }
    }
}
