import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifact
import com.android.build.api.variant.FilterConfiguration
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties
import java.io.File
import javax.inject.Inject

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
        // Base version only. The "-dirty" marker for builds from a tree with uncommitted changes
        // is applied to the APK/AAB *filenames* at execution time (see DirtyAwareTask), so it
        // can't go stale under the configuration cache and stays out of the user-visible manifest
        // version. A "-dirty" build is instead visible in the artifact filename (no automated
        // release guard exists yet).
        versionName = "1.0.27"
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

// Base for the artifact-renaming tasks. The "-dirty" tag is computed in the task action
// (execution time) rather than during configuration, so it reflects the working tree at build
// time and stays correct under the configuration cache — a configuration-time git check is
// captured once in the cached configuration and goes stale when that configuration is reused.
// ExecOperations is injected so the git call is configuration-cache compatible.
abstract class DirtyAwareTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    // Directory the git check runs from, pinned to the repo root so the result doesn't depend on
    // the process working directory the build was launched from. Internal: it's a fixed path, not
    // a tracked input.
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    // "-dirty" when tracked files have uncommitted changes, else "". Untracked files are ignored
    // so stray local files don't flag an otherwise-clean build. A missing git binary or non-repo
    // checkout yields "" — isIgnoreExitValue plus a swallowed stderr keep it quiet instead of
    // failing the build or printing "not a git repository".
    protected fun gitDirtySuffix(): String = try {
        val stdout = ByteArrayOutputStream()
        val result = execOps.exec {
            repoRoot.orNull?.asFile?.let { workingDir = it }
            commandLine("git", "status", "--porcelain", "--untracked-files=no")
            standardOutput = stdout
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0 && stdout.toString().trim().isNotEmpty()) "-dirty" else ""
    } catch (ignored: Exception) {
        ""
    }
}

// Name APK outputs directly (e.g. openbu-direct-debug-1.0.26.apk) so there's a single
// correctly-named artifact and no post-build file moves — those broke installDirectDebug
// and corrupted incremental builds. Uses the public Artifacts transform API (no internal
// AGP classes); submit() rewrites the BuiltArtifacts metadata so install/test tasks still
// resolve the renamed APK.
abstract class RenameApkTask : DirtyAwareTask() {
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
        val dirty = gitDirtySuffix()
        // Clear stale APKs (from a previous naming scheme like openbu-direct-*, or a "-dirty"
        // name left by an earlier dirty build) so the output folder only ever holds the current
        // build's artifacts.
        outFolder.get().asFile.listFiles { f -> f.extension == "apk" }
            ?.forEach { it.delete() }
        transformationRequest.get().submit(this) { builtArtifact: BuiltArtifact ->
            // With ABI splits there's one APK per architecture; append the ABI so
            // the artifacts don't overwrite each other under a single name.
            val abi = builtArtifact.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val abiSuffix = abi?.let { "-$it" } ?: ""
            val dest = outFolder.get().file("${artifactName.get()}$dirty$abiSuffix.apk").asFile
            File(builtArtifact.outputFile).copyTo(dest, overwrite = true)
            dest
        }
    }
}

// The git repo root is the Gradle root project directory; the dirty-check tasks run git from
// here so the result is independent of where the build was launched.
val repoRootDir = rootProject.layout.projectDirectory

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
            repoRoot.set(repoRootDir)
            // Always re-run so the git dirty check reflects the current tree. The task's other
            // inputs (base name, packaged APK) don't change when only the commit state does, so
            // without this a "-dirty" name could linger after committing (or vice versa).
            outputs.upToDateWhen { false }
        }
        val request = variant.artifacts.use(renameTask)
            .wiredWithDirectories(RenameApkTask::apkFolder, RenameApkTask::outFolder)
            .toTransformMany(SingleArtifact.APK)
        renameTask.configure { transformationRequest.set(request) }
    }
}

abstract class RenameAabTask : DirtyAwareTask() {
    @get:Internal
    abstract val appName: Property<String>

    @get:Internal
    abstract val versionName: Property<String>

    @get:Internal
    abstract val bundleDir: DirectoryProperty

    @TaskAction
    fun taskAction() {
        val dir = bundleDir.get().asFile
        if (!dir.exists()) return
        val dirty = gitDirtySuffix()
        dir.walkTopDown()
            .filter { it.extension == "aab" }
            .filter { !it.name.startsWith(appName.get()) }
            .forEach { aab ->
                val variantDir = aab.parentFile.name
                val dest = File(aab.parentFile, "${appName.get()}-$variantDir-${versionName.get()}$dirty.aab")
                aab.copyTo(dest, overwrite = true)
            }
    }
}

tasks.register<RenameAabTask>("renameAabs") {
    appName.set("openbu")
    versionName.set(android.defaultConfig.versionName ?: "unknown")
    bundleDir.set(layout.buildDirectory.dir("outputs/bundle"))
    repoRoot.set(repoRootDir)
    // Renames AAB outputs in place from the current git state — nothing to cache, and it must run
    // whenever a bundle task runs. doNotTrackState disables up-to-date/output tracking (there are
    // no outputs to track), so it always executes without a "task has no outputs" note.
    doNotTrackState("Renames AAB outputs in place based on live git state; always re-run")
}

tasks.matching {
    it.name == "bundle" ||
        (it.name.startsWith("bundle") && (it.name.endsWith("Release") || it.name.endsWith("Debug")))
}.configureEach {
    finalizedBy("renameAabs")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
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
