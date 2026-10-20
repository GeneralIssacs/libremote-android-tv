plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles the app's Kotlin itself; these select the Kotlin version and build the plain-JVM protocol module.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
