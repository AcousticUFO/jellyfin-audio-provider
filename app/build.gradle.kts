plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.github.jellyfin_saf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.jellyfin_saf"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0-dev"
    }

    signingConfigs {
        create("release") {
            val releaseKeystore = file("${project.rootDir}/release.keystore")
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "jellyfin-saf-release"
                keyAlias = System.getenv("KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "jellyfin-saf-release"
            }
        }
        getByName("debug") {
            val userHome = System.getProperty("user.home")
            val keystoreFile = file("$userHome/.android/debug.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Android Keystore-backed EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking & Serialization
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
