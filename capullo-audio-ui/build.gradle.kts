plugins {
    alias(libs.plugins.android.library)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin support (see the :capullo-audio engine module).
    alias(libs.plugins.kotlin.compose)
    id("maven-publish")
}

// Shared client-facing Compose UI (the Snapcast control sheet today; the now-playing surface later).
// Depends on the headless :capullo-audio engine for its transport data types (Group/Client), so the
// engine itself stays UI-free. Consumed by every app so a UI fix lands in all of them at once.
android {
    namespace = "tech.capullo.audio.ui"
    compileSdk = 36

    defaultConfig {
        // 24 = ≤ every consuming app (Telecloud is 24, QuantumCast 26); Compose floor is well below.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // New DSL for Kotlin 2.3 / AGP 9.x (mirrors the engine module + RadioCapullo).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // The engine - `api` because the composables' public signatures expose its transport types
    // (SnapcastControlSheet(groups: List<Group>, ...)). Pulls the whole engine graph transitively;
    // apps already depend on the engine directly too.
    api(project(":capullo-audio"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.core) // QR encoding for ListenQrDialog

}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "tech.capullo.audio"
            artifactId = "capullo-audio-ui"
            version = "0.1.0-SNAPSHOT"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/capullo-tech/capullo-audio")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
