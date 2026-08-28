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
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    // No kotlin.android: AGP 9.0+ provides built-in Kotlin (see RadioCapullo, same toolchain).
}
