import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * Libremote Receiver: the TV half of "Send files", a small receiver for the TV. Signed with the same release key
 * as the phone app, read the same way (keystore.properties at the repository root, or LIBREMOTE_KEYSTORE_* for CI).
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

android {
    namespace = "app.libremotereceiver"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.libremotereceiver"
        // Android TV 8.0. From Android 10, received files go to Downloads through MediaStore, with no permission;
        // on 8 and 9 they are written to the folder, with the storage permission (Downloads.kt).
        minSdk = 26
        targetSdk = 36
        // MAJOR * 10000 + MINOR * 100 + PATCH, as in the phone app. Keep both literal for F-Droid.
        versionCode = 10001
        versionName = "1.0.1"
    }

    buildFeatures {
        // BuildConfig.VERSION_CODE: the receiver tells paired phones its version, so Libremote can update it.
        buildConfig = true
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
    // The file-transfer protocol, shared with the phone app: plain Kotlin.
    implementation(project(":protocol"))
}
