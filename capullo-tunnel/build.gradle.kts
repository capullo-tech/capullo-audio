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
// bump deliberately. NOTE: Termux's apt pool keeps ONLY the newest cloudflared release,
// so every upstream release prunes the pinned .deb - a fresh build (jitpack, CI, new
// machine) then fails at fetchCloudflared with a 404 until this is bumped to the
// current pool version with fresh checksums. 2026.8.2 -> 2026.8.3 is exactly that:
// no cloudflared behaviour change (cmd/cloudflared/tunnel/quick_tunnel.go is identical
// between the two tags, and the quick-tunnel log lines match the parser's fixtures).
val cloudflaredVersion = "2026.8.3"
val cloudflaredAbis = mapOf(
    "arm64-v8a" to ("aarch64" to "64f5f4096afcc9234eda7384bf6e59516646285f46ce2ec84f52a4399b082820"),
    "armeabi-v7a" to ("arm" to "3fcf8db38eb3e7ffee3590e6c3b18fc47e8c1b43bf2b642d74ae4426eaf83248"),
    "x86" to ("i686" to "438fc6a96f4da65f3f344cb6d29f9988c2a97932817bf1f4b5adc1e9c7d08047"),
    "x86_64" to ("x86_64" to "dbd7600352ae509c208936cdddb9ff36025e0f4c7ae540f616c534c832686574"),
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
