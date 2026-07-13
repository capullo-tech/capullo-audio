pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // lib-snapcast-android + build-conventions catalog (public)
    }
    versionCatalogs {
        // Shared org toolchain, pinned by commit from jitpack.
        create("libs") { from("com.github.capullo-tech:build-conventions:b07e979") }
        // Local pins: inter-repo capullo/L0 coordinates versioned independently per release.
        create("pins") { from(files("gradle/pins.versions.toml")) }
    }
}

rootProject.name = "capullo-audio"
include(":capullo-audio")
include(":capullo-audio-ui") // shared client Compose UI (control sheet, later now-playing); depends on :capullo-audio
include(":app") // harness/demo app: exercises CapulloAudioEngine + packages the snapcast .so

// Dev/release toggle: when the SPI repo is checked out as a sibling (local co-development or the
// CI sibling-checkout), build it from source via a composite build and substitute it for the
// jitpack coordinate `com.github.capullo-tech:capullo-audio-contracts`. On jitpack (single repo,
// no sibling) this block is skipped and the coordinate resolves from jitpack.io instead.
if (file("../capullo-audio-contracts").exists()) {
    includeBuild("../capullo-audio-contracts") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-audio-contracts"))
                .using(project(":"))
        }
    }
}
