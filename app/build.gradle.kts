plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ATVBasic.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.idrisokys.atvbasic"
        minSdk = 26
        targetSdk = 37
        versionCode = 34
        versionName = "4.0.0"
    }

    // Optional stable release signing. Without it the debug key is used, and a debug key that is
    // regenerated on every CI run makes each APK refuse to install over the previous one.
    // Provide ATV_KEYSTORE_FILE, ATV_KEYSTORE_PASSWORD, ATV_KEY_ALIAS and ATV_KEY_PASSWORD
    // (environment variables) to sign every build with the same key.
    signingConfigs {
        val keystore = providers.environmentVariable("ATV_KEYSTORE_FILE").orNull
        if (keystore != null && file(keystore).isFile) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("ATV_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ATV_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ATV_KEY_PASSWORD").orNull
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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

// No third-party dependencies: only the Android framework and the Kotlin standard library.
dependencies {
}
