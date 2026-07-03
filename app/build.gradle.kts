import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifact
import com.android.build.api.variant.FilterConfiguration
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
        versionCode = 3
        versionName = "1.0.26"
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
        create("upload") {
            (keystoreProperties["uploadKeyAlias"] as String?)?.let { keyAlias = it }
            (keystoreProperties["uploadKeyPassword"] as String?)?.let { keyPassword = it }
            (keystoreProperties["uploadStoreFile"] as String?)?.let { storeFile = file(it) }
            (keystoreProperties["uploadStorePassword"] as String?)?.let { storePassword = it }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            // ABI selection is handled by the splits { abi } block; specifying
            // abiFilters here conflicts with it.
            signingConfig = signingConfigs.getByName("release")
        }
        create("play") {
            dimension = "distribution"
            signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Distinct package id so debug builds install alongside the Play/release
            // app (which is signed with a different key) instead of colliding.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
        }
    }

    buildFeatures {
        compose = true
    }

    // Split the sideload APK per-ABI so each artifact ships only one libvlc.so
    // (~40 MB each) instead of both, making each APK smaller. Ignored for AAB
    // (bundle) builds, where Play splits per device.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    if (!keystorePropertiesFile.exists()) {
        logger.warn("Warning: keystore.properties file not found. Skipping signing configuration for withGPlay.")
    }
}

// Name APK outputs directly (e.g. openbu-direct-debug-1.0.26.apk) so there's a single
// correctly-named artifact and no post-build file moves — those broke installDirectDebug
// and corrupted incremental builds. Uses the public Artifacts transform API (no internal
// AGP classes); submit() rewrites the BuiltArtifacts metadata so install/test tasks still
// resolve the renamed APK.
abstract class RenameApkTask : DefaultTask() {
    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outFolder: DirectoryProperty

    @get:Input
    abstract val artifactName: Property<String>

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<RenameApkTask>>

    @TaskAction
    fun taskAction() {
        // Clear stale APKs (e.g. from a previous naming scheme like openbu-direct-*)
        // so the output folder only ever holds the current build's artifacts.
        outFolder.get().asFile.listFiles { f -> f.extension == "apk" }
            ?.forEach { it.delete() }
        transformationRequest.get().submit(this) { builtArtifact: BuiltArtifact ->
            // With ABI splits there's one APK per architecture; append the ABI so
            // the artifacts don't overwrite each other under a single name.
            val abi = builtArtifact.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val suffix = abi?.let { "-$it" } ?: ""
            val dest = outFolder.get().file("${artifactName.get()}$suffix.apk").asFile
            File(builtArtifact.outputFile).copyTo(dest, overwrite = true)
            dest
        }
    }
}

androidComponents {
    onVariants { variant ->
        val vName = android.defaultConfig.versionName ?: "unknown"
        val apkName = if (variant.buildType == "debug") {
            "openbu-${variant.flavorName}-${variant.buildType}-$vName"
        } else {
            "openbu-$vName"
        }
        val renameTask = tasks.register<RenameApkTask>(
            "rename${variant.name.replaceFirstChar { it.uppercase() }}Apk",
        ) {
            artifactName.set(apkName)
        }
        val request = variant.artifacts.use(renameTask)
            .wiredWithDirectories(RenameApkTask::apkFolder, RenameApkTask::outFolder)
            .toTransformMany(SingleArtifact.APK)
        renameTask.configure { transformationRequest.set(request) }
    }
}

tasks.register("renameAabs") {
    val appName = "openbu"
    val vName = android.defaultConfig.versionName ?: "unknown"
    val bundleDir = layout.buildDirectory.dir("outputs/bundle")
    doLast {
        bundleDir.get().asFile.walkTopDown()
            .filter { it.extension == "aab" }
            .filter { !it.name.startsWith(appName) }
            .forEach { aab ->
                val variantDir = aab.parentFile.name
                val dest = File(aab.parentFile, "${appName}-${variantDir}-${vName}.aab")
                aab.copyTo(dest, overwrite = true)
            }
    }
}

tasks.matching {
    it.name == "bundle" ||
        (it.name.startsWith("bundle") && (it.name.endsWith("Release") || it.name.endsWith("Debug")))
}.configureEach {
    finalizedBy("renameAabs")
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
