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
// so every upstream release prunes the pinned .deb. fetchCloudflared therefore tries Termux
// first and falls back to our own mirror: a GitHub release on this repo tagged
// cloudflared-<version> holding the same unmodified .debs (the sha256 below gates either
// source). A weekly workflow (.github/workflows/cloudflared-bump.yml) mirrors each new
// Termux release and opens a PR bumping the pin, so we track the latest while the mirror
// keeps every pinned version buildable. 2026.8.2 -> 2026.8.3 was a pure prune:
// no cloudflared behaviour change (cmd/cloudflared/tunnel/quick_tunnel.go is identical
// between the two tags, and the quick-tunnel log lines match the parser's fixtures).
// 2026.8.3 -> 2026.10.7: quick_tunnel.go did change, but only to add an opt-in protected
// (OTP) mode gated on --allowed-mail, which CloudflaredBackend never passes. The public
// path's startup banner (quickTunnelStartupLines with isProtected=false) emits the same two
// lines through the same cliutil.LogTable, and normalizeQuickTunnelURL is the same
// https://-prefixing, so parsePublicUrl's input is unchanged. New provisioning error
// messages carry no https:// host, so they can't false-match PUBLIC_URL_RE either.
val cloudflaredVersion = "2026.11.1"
val cloudflaredAbis = mapOf(
    "arm64-v8a" to ("aarch64" to "56ca76fe7222c050d99dfe85e4e316632436f93798bed98d705ca42d776e14cc"),
    "armeabi-v7a" to ("arm" to "b65e03d13e152d9c7627bea11c0adf8976cb16e0106a3a7f91a59a8f449481fd"),
    "x86" to ("i686" to "1e4d8c559b48b4a22fe71952ea02f350d66830d467ffc0da9d474466290a8eea"),
    "x86_64" to ("x86_64" to "07948ff99ba7e1aa9f25709c155f416a129dbad1cac5fd1035c529367d33a208"),
)

// A typed task with a DirectoryProperty output, not a plain `tasks.register {}`: AGP 9 refuses a
// Provider in the SourceSet API and wants the Variant API instead (see androidComponents below),
// and that wiring needs an output property it can point at.
abstract class FetchCloudflaredTask : DefaultTask() {

    /** cloudflared release, e.g. "2026.8.2". */
    @get:Input abstract val version: Property<String>

    /** Android ABI -> "<termux arch>:<sha256 of the .deb>". */
    @get:Input abstract val abis: MapProperty<String, String>

    /** Skip Termux and fetch only from the mirror - CI uses this to prove the pin is mirrored. */
    @get:Input abstract val mirrorOnly: Property<Boolean>

    /** Downloaded .debs, kept across builds so a clean output doesn't re-download 4 packages. */
    @get:Internal abstract val debCacheDir: DirectoryProperty

    /** jniLibs layout: <abi>/libcloudflared.so. */
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun sha256Of(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun fetch() {
        val ver = version.get()
        abis.get().forEach { (abi, archAndSha) ->
            val (termuxArch, sha256) = archAndSha.split(":", limit = 2)
            val deb = debCacheDir.get().asFile.resolve("cloudflared-$ver-$termuxArch.deb")
            // A cached file is only trusted if it still verifies; a stale/corrupt one is refetched.
            if (deb.exists() && sha256Of(deb) != sha256) deb.delete()
            if (!deb.exists()) {
                deb.parentFile.mkdirs()
                val fileName = "cloudflared_${ver}_$termuxArch.deb"
                val sources = buildList {
                    if (!mirrorOnly.get()) {
                        add("https://packages.termux.dev/apt/termux-main/pool/main/c/cloudflared/$fileName")
                    }
                    add("https://github.com/capullo-tech/capullo-audio/releases/download/cloudflared-$ver/$fileName")
                }
                val tmp = deb.resolveSibling("${deb.name}.part")
                val failures = mutableListOf<String>()
                for (url in sources) {
                    try {
                        URI(url).toURL().openStream().use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        }
                    } catch (e: java.io.IOException) {
                        failures += "$url -> ${e.javaClass.simpleName}: ${e.message}"
                        tmp.delete()
                        continue
                    }
                    val actual = sha256Of(tmp)
                    if (actual != sha256) {
                        failures += "$url -> sha256 $actual (expected $sha256)"
                        tmp.delete()
                        continue
                    }
                    tmp.renameTo(deb)
                    break
                }
                if (!deb.exists()) {
                    throw GradleException(
                        "cloudflared $ver $termuxArch unavailable from every source - bump the pin to " +
                            "Termux's current release or mirror this one:\n  " + failures.joinToString("\n  "),
                    )
                }
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
    mirrorOnly.set(providers.gradleProperty("cloudflaredMirrorOnly").map { it.toBoolean() }.orElse(false))
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
