plugins {
    alias(libs.plugins.android.application)
}

// API keys: -PPIXABAY_KEY=... / gradle.properties / environment variable (GitHub secret).
fun apiKey(name: String): String =
    (System.getenv(name)?.takeIf { it.isNotBlank() } ?: providers.gradleProperty(name).orNull ?: "")
        .replace(Regex("[^A-Za-z0-9_-]"), "")

android {
    namespace = "com.ATVBasic.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ATVBasic.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 32
        versionName = "3.1.0"

        buildConfigField("String", "PIXABAY_KEY", "\"${apiKey("PIXABAY_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
    }
}

// Zero third-party dependencies: the launcher uses only the Android framework.
dependencies {
}
