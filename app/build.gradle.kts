import java.io.FileInputStream
import java.util.Properties
import java.io.File

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.cygnusx1.openbu"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.cygnusx1.openbu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.24"
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
    }

    if (!keystorePropertiesFile.exists()) {
        logger.warn("Warning: keystore.properties file not found. Skipping signing configuration for withGPlay.")
    }
}

tasks.register("renameApks") {
    val appName = "openbu"
    val vName = android.defaultConfig.versionName ?: "unknown"
    val apkDir = layout.buildDirectory.dir("outputs/apk")
    doLast {
        apkDir.get().asFile.walkTopDown()
            .filter { it.extension == "apk" }
            .forEach { apk ->
                val buildTypeName = apk.parentFile.name
                val artifactName = if (buildTypeName == "debug") {
                    "${appName}-${buildTypeName}-universal-${vName}"
                } else {
                    "${appName}-universal-${vName}"
                }
                val dest = File(apk.parentFile, "${artifactName}.apk")
                if (apk.name != dest.name) {
                    apk.renameTo(dest)
                }
            }
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy("renameApks")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // TODO Update this to a non-release candidate version
    implementation("androidx.media3:media3-exoplayer:1.10.0-rc03")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.10.0-rc03")
    implementation("androidx.media3:media3-ui:1.10.0-rc03")

    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
