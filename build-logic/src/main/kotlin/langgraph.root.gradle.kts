/*
 * Root-project conventions: aggregated API docs and coverage reports, and
 * binary-compatibility validation for every published module.
 */
plugins {
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

apiValidation {
    // Samples are not published, so they have no API to keep stable.
    ignoredProjects.add("samples")
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
