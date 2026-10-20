import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * Release signing details, from keystore.properties at the repository root (never committed) or, for CI, from
 * LIBREMOTE_KEYSTORE_FILE / LIBREMOTE_KEYSTORE_PASSWORD / LIBREMOTE_KEY_ALIAS / LIBREMOTE_KEY_PASSWORD. Without them, release
 * builds fall back to the debug key so they still install for testing.
 */
val releaseKey: Map<String, String?> = run {
    val props = Properties()
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    fun value(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    mapOf(
        "storeFile" to value("storeFile", "LIBREMOTE_KEYSTORE_FILE"),
        "storePassword" to value("storePassword", "LIBREMOTE_KEYSTORE_PASSWORD"),
        "keyAlias" to value("keyAlias", "LIBREMOTE_KEY_ALIAS"),
        "keyPassword" to value("keyPassword", "LIBREMOTE_KEY_PASSWORD"),
    )
}
val hasReleaseKey = releaseKey.values.all { !it.isNullOrBlank() }
if (!hasReleaseKey) logger.lifecycle("Libremote: no release key configured; release builds are signed with the debug key.")

/** Libremote's GitHub repository: its releases (updates) and its issues (Settings › Report a problem). */
val repository = "sonalder-darlene/libremote-android-tv"
/** Where the standard build looks for updates; -Plibremote.updateUrl=… points a test build (or a fork) elsewhere. */
val updateUrl: String = providers.gradleProperty("libremote.updateUrl")
    .getOrElse("https://api.github.com/repos/$repository/releases/latest")
/**
 * SHA-256 of the release certificate: only copies signed with it are offered updates, since only they can take one,
 * and both downloads (Libremote, and Libremote Receiver for the TV) must carry it.
 */
val releaseCert: String = providers.gradleProperty("libremote.releaseCert")
    .getOrElse("37d436a201c759fd35f485daab3bbd3f8d54156fea673a23bbcf72a6f38e6ee4")

android {
    namespace = "app.libremotetvservice"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.libremotetvservice"
        minSdk = 26
        targetSdk = 36
        // MAJOR * 10000 + MINOR * 100 + PATCH, so 1.2.3 is 10203. Keep both literal for F-Droid.
        versionCode = 10000
        versionName = "1.0.0"
        buildConfigField("String", "ISSUES_URL", "\"https://github.com/$repository/issues/new\"")
        // Plain HTTP stays off, except in a test build pointed at an http:// update server (-Plibremote.updateUrl).
        manifestPlaceholders["cleartext"] = "false"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // GitHub releases, F-Droid and other app stores all ship this APK. Its update checker is opt-in and stays
        // hidden when an app store installed Libremote; it also keeps Libremote Receiver on the TV up to date.
        create("standard") {
            dimension = "distribution"
            buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
            buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCert\"")
            manifestPlaceholders["cleartext"] = updateUrl.startsWith("http://").toString()
        }
        // Google Play forbids apps that update themselves, so this build has no updater at all.
        create("play") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getValue("storeFile")!!)
                storePassword = releaseKey["storePassword"]
                keyAlias = releaseKey["keyAlias"]
                keyPassword = releaseKey["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dependenciesInfo {
        // No Google-encrypted dependency metadata blob in the APK.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // The TV protocols: plain Kotlin, no Android or protobuf dependencies.
    implementation(project(":protocol"))

    testImplementation("junit:junit:4.13.2")
}
