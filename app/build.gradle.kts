plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ATVBasic.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ATVBasic.launcher"
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
