// Build-time-only classpath for :capullo-tunnel's fetchCloudflared task (a .deb is an ar archive
// holding data.tar.xz, unpacked in pure JVM so the Windows release host needs no ar/tar). It lives
// HERE, not in that module: AGP's own classpath already carries commons-compress at the root scope,
// build script classloaders are parent-first, so a module-level buildscript entry never wins and
// xz has to sit beside the commons-compress that does.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.commons:commons-compress:1.27.1")
        classpath("org.tukaani:xz:1.10")
    }
}

plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    // No kotlin.android: AGP 9.0+ provides built-in Kotlin (see RadioCapullo, same toolchain).
}

// Org code style, same baseline as the apps and the other libraries: Spotless drives ktlint from the
// shared build-conventions versions. `./gradlew spotlessApply` formats, `spotlessCheck` (CI) verifies.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_annotation" to "disabled",
                    "max_line_length" to 100,
                    // Same two relaxations as QuantumCast: ktlint can't auto-expand wildcard
                    // imports or wrap long lines headlessly, so they'd block CI on existing code.
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_max-line-length" to "disabled",
                ),
            )
    }
}
