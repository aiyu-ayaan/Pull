plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.hilt)
}

import java.util.Properties

// Release signing: env vars (CI) win over gitignored keystore.properties (local); neither = unsigned release.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun signingValue(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(property)?.takeIf { it.isNotBlank() }

val keystoreFile = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")?.let(rootProject::file)

// Version name lives in the root VERSION file, bumped by the release PR.
val appVersionName = rootProject.file("VERSION").readText().trim()

/**
 * Packs X.Y.Z[-alpha|beta.N] into a code that rises with every release: 1.4.2-beta.3 = 10402103, 1.4.2 = 10402200.
 * [build] (PULL_BUILD, set by a `!patch` rebuild) fills a stable release's free last two digits.
 */
fun versionCodeOf(name: String, build: Int = System.getenv("PULL_BUILD")?.toIntOrNull() ?: 0): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta)\.(\d+))?$""").matchEntire(name)
        ?: error("VERSION '$name' is not X.Y.Z or X.Y.Z-alpha|beta.N")
    val (major, minor, patch, label, number) = match.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100 && (number.toIntOrNull() ?: 0) < 100) {
        "VERSION '$name' overflows a version code slot"
    }
    require(build in 0..99) { "PULL_BUILD $build overflows a version code slot" }
    val stage = when (label) { "alpha" -> 0; "beta" -> 1; else -> 2 }
    val tail = if (label.isEmpty()) build else number.toInt()
    return major.toInt() * 10_000_000 + minor.toInt() * 100_000 + patch.toInt() * 1_000 + stage * 100 + tail
}

android {
    namespace = "com.aiyu.pull"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aiyu.pull"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
    }

    // libtorrent + Python + FFmpeg natives are most of the APK, once per ABI. One APK per ABI ships each phone
    // only its own; the updater picks the asset matching Build.SUPPORTED_ABIS (Releases.apkFor).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    // Pull is English-only: drop the translations every library ships.
    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        if (keystoreFile?.exists() == true) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // yt-dlp runs its Python/FFmpeg/aria2c binaries from nativeLibraryDir, so native libs are extracted on install.
        // The binaries themselves are 16 KB aligned since youtubedl-android 0.18.
        jniLibs.useLegacyPackaging = true
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Exported schemas are checked in: the baseline for every future migration.
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(project(":engine"))

    // Torrents: libtorrent (MIT) with natives for every Android ABI.
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.arm)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.x86)
    implementation(libs.libtorrent4j.x64)

    // Video sites: yt-dlp + Python + FFmpeg (GPL-3.0), as in Seal.
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.youtubedl.aria2c)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.graphics.shapes)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
