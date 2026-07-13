plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "tech.capullo.audio.harness"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.capullo.audio.harness"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":capullo-audio"))
    // The engine pulls this transitively; declared here too so the .so is unambiguously packaged.
    implementation(pins.lib.snapcast.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
