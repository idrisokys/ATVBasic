plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.bare.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bare.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "3.0.0"
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
