import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Live test against a real TV (or the Android TV emulator): -Plibremote.tv=host[:remotePort[:pairingPort]].
    // It pairs once (write the code the TV shows to build/live/code.txt), then sends keys.
    // -Plibremote.receiver=host[:port] pairs with Libremote Receiver on the TV and sends it test files instead
    // (LiveReceiverTest); -Plibremote.send=path,path… sends those files too, and -Plibremote.cutUpdate=path sends
    // that APK as an update of the receiver, cut off halfway.
    for (name in listOf("libremote.tv", "libremote.keys", "libremote.stay", "libremote.text", "libremote.submit", "libremote.receiver", "libremote.send", "libremote.cutUpdate")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    systemProperty("libremote.work", layout.buildDirectory.dir("live").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
