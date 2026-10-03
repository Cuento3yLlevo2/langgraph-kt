/*
 * Quality gates shared by every published module: formatting, coverage and API docs.
 */
plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
}

kover {
    reports {
        verify {
            rule {
                // `check` fails if line coverage of a module drops below this.
                minBound(90)
            }
        }
    }
}
