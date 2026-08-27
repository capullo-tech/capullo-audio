import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.net.URI
import java.security.MessageDigest

// commons-compress + xz (the fetchCloudflared unpack path) come from the ROOT build file's
// buildscript classpath - see the comment there for why they cannot live in this module.

plugins {
    alias(libs.plugins.android.library)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin support (see the :capullo-audio engine module).
    id("maven-publish")
}

// cloudflared (Cloudflare quick tunnel) powers the public-link feature. Stock cloudflared
// builds are static Go binaries whose pure-Go resolver needs /etc/resolv.conf (absent on
// Android → no DNS); Termux's package is a GOOS=android (bionic) build of the same
// Apache-2.0 sources, verified working on-device. Pinned by version + per-ABI sha256 -
// bump deliberately.
val cloudflaredVersion = "2026.8.2"
val cloudflaredAbis = mapOf(
    "arm64-v8a" to ("aarch64" to "7ecda51a05326f34a832be6e763eb7c6f71edf4ad49f096b291fa6f8ec5a5377"),
    "armeabi-v7a" to ("arm" to "d2177a6b0724885842d3ec56176aef08ceb7b2ab9d43465054e710d41a583cc9"),
    "x86" to ("i686" to "9e63f8f5dc24c4d31fa4bc9f8ef5cf02bf072c6e1243d0538a34a8f18688fc4f"),
    "x86_64" to ("x86_64" to "33a0d6e69fbc738b98de03d51e3de7bf5de1b28e0b6501ed6cba0cc74ab8cd0e"),
)

// A typed task with a DirectoryProperty output, not a plain `tasks.register {}`: AGP 9 refuses a
// Provider in the SourceSet API and wants the Variant API instead (see androidComponents below),
// and that wiring needs an output property it can point at.
abstract class FetchCloudflaredTask : DefaultTask() {

    /** cloudflared release, e.g. "2026.8.2". */
    @get:Input abstract val version: Property<String>

    /** Android ABI -> "<termux arch>:<sha256 of the .deb>". */
    @get:Input abstract val abis: MapProperty<String, String>

    /** Downloaded .debs, kept across builds so a clean output doesn't re-download 4 packages. */
    @get:Internal abstract val debCacheDir: DirectoryProperty

    /** jniLibs layout: <abi>/libcloudflared.so. */
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val ver = version.get()
        abis.get().forEach { (abi, archAndSha) ->
            val (termuxArch, sha256) = archAndSha.split(":", limit = 2)
            val deb = debCacheDir.get().asFile.resolve("cloudflared-$ver-$termuxArch.deb")
            if (!deb.exists()) {
                deb.parentFile.mkdirs()
                val url = "https://packages.termux.dev/apt/termux-main/pool/main/c/cloudflared/" +
                    "cloudflared_${ver}_$termuxArch.deb"
                URI(url).toURL().openStream().use { input ->
                    deb.outputStream().use { input.copyTo(it) }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            deb.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha256) {
                deb.delete()
                throw GradleException(
                    "cloudflared $termuxArch sha256 mismatch: $actual (expected $sha256) - refusing to package",
                )
            }
            // .deb = ar archive containing data.tar.xz; pull usr/bin/cloudflared out of it.
            ArArchiveInputStream(deb.inputStream().buffered()).use { ar ->
                generateSequence { ar.nextEntry }
                    .firstOrNull { it.name.startsWith("data.tar") }
                    ?: throw GradleException("data.tar missing in $deb")
                // ar is now positioned at the data.tar.xz payload.
                TarArchiveInputStream(XZCompressorInputStream(ar, true)).use { tar ->
                    generateSequence { tar.nextEntry }
                        .firstOrNull { it.name.endsWith("usr/bin/cloudflared") }
                        ?: throw GradleException("cloudflared binary missing in $deb")
                    val out = outputDir.get().asFile.resolve("$abi/libcloudflared.so")
                    out.parentFile.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                    out.setExecutable(true)
                }
            }
        }
    }
}

val fetchCloudflared = tasks.register<FetchCloudflaredTask>("fetchCloudflared") {
    version.set(cloudflaredVersion)
    abis.set(cloudflaredAbis.mapValues { (_, archAndSha) -> "${archAndSha.first}:${archAndSha.second}" })
    debCacheDir.set(layout.buildDirectory.dir("cloudflared/deb"))
    outputDir.set(layout.buildDirectory.dir("cloudflared-jnilibs"))
}

// Public-link tunnel, independent of any one app: the Kotlin above plus the cloudflared
// binaries the AAR carries in jni/<abi>/. Kept out of :capullo-audio so an app opts into
// ~8 MB per ABI only when it wants a public link. No Hilt (see TunnelManager).
android {
    namespace = "tech.capullo.audio.tunnel"
    compileSdk = 36

    defaultConfig {
        // 23 to match :capullo-audio and stay ≤ every consuming app.
        minSdk = 23
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

// cloudflared lands in jniLibs as libcloudflared.so, so the AAR carries jni/<abi>/ and the app's
// merge puts it in nativeLibraryDir - the same mechanism the engine uses for snapserver. The Variant
// API (not sourceSets.jniLibs.srcDir) is what AGP 9 accepts for a generated directory, and it is
// what carries the task dependency into the AAR bundling.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            fetchCloudflared,
            FetchCloudflaredTask::outputDir,
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "tech.capullo.audio"
            artifactId = "capullo-tunnel"
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
