import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Chiave di firma delle release: fuori dal repository, in ~/.cubetto-signing/keystore.properties
 * (o nel file indicato da CUBETTO_KEYSTORE) con storeFile, storePassword, keyAlias, keyPassword.
 * Se manca, assembleRelease produce un APK non firmato.
 */
val signingProps = Properties().apply {
    val file = File(System.getenv("CUBETTO_KEYSTORE") ?: "${System.getProperty("user.home")}/.cubetto-signing/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "it.cubetto.app"

    signingConfigs {
        if (!signingProps.isEmpty) create("release") {
            storeFile = File(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    compileSdk = 35

    defaultConfig {
        applicationId = "it.cubetto.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.1"
        ndk { abiFilters += "arm64-v8a" }
        // Lingue in cui FABA™ è distribuito (vedi TAGS.md) + inglese come fallback
        resourceConfigurations += listOf("en", "it", "es", "fr", "nl", "ca")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // Genera locales_config per la scelta della lingua per-app (Android 13+)
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // youtubedl-android estrae Python e ffmpeg dalle librerie native
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("me.jahnen.libaums:core:0.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.navigation:navigation-testing:2.8.5")
    testImplementation("androidx.test:core:1.6.1")
}
