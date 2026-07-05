plugins {
    alias(libs.plugins.android.library)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin support (see RadioCapullo).
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
}

android {
    namespace = "tech.capullo.audio"
    compileSdk = 36

    defaultConfig {
        // 23 to match lib-snapcast-android and stay ≤ every consuming app (Telecloud is 24).
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // The contract-validation driver test (EnginePlaybackContractTest) is pure JVM - it drives
        // QueuePlaybackLoop against a FakeMediaPlayback, so Android framework calls return defaults.
        unitTests.isReturnDefaultValues = true
    }
    // New DSL for Kotlin 2.3 / AGP 9.x (mirrors RadioCapullo, the known-good reference).
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
    // Layer 1 SPI (the only thing apps also see through this engine's api surface).
    api(libs.capullo.audio.contracts)

    // Media3 delivery pipeline. FFmpeg decoder (lib-media3-ffmpeg-android) is loaded reflectively
    // by DefaultRenderersFactory when present on the app classpath - not a compile dependency here.
    // exoplayer + common are `api`: the public surface exposes their types (CapulloAudioEngine is
    // @UnstableApi, toMediaItem() returns MediaItem, FifoRenderersFactory : DefaultRenderersFactory).
    api(libs.media3.exoplayer)
    api(libs.media3.common)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource)

    // Snapcast native binaries (libsnapserver.so / libsnapclient.so / libsnapcontrol.so).
    implementation(libs.lib.snapcast.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // JSON-RPC (SnapcontrolPlugin/JSONRPC) + the remote-control websocket client (SnapcastControlClient).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "tech.capullo.audio"
            artifactId = "capullo-audio"
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
