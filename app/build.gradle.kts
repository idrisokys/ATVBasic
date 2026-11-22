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
        versionCode = 35
        versionName = "5.0.0"
        // Rust yerel kütüphanesi (native/): Android TV kutularının çoğu 32 bit (armeabi-v7a) olduğundan ikisi de derlenir.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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

// ---- Rust yerel kütüphanesi (native/ → libatvnative.so) ----
// Arayüz dışındaki iş mantığı (paylaşım, dosya listeleme, duvar kağıdı işleme) Rust'tadır; yedek Kotlin kodu YOKTUR.
// cargo-ndk derleme öncesi .so'ları src/main/jniLibs altına koyar. CI ortamında (CI=true) cargo-ndk zorunludur;
// yerelde yoksa görev atlanır, APK yine derlenir ama paylaşım ve duvar kağıdı özellikleri çalışmaz (Native.ok == false).
val rustAbis = listOf("arm64-v8a", "armeabi-v7a")
val rustDir = rootProject.file("native")
val jniOut = layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath

// cargo-ndk varlığı yapılandırma zamanında DEĞİL, görev çalışırken kabukta denetlenir (yapılandırma önbelleği, yapılandırma
// sırasında süreç başlatmayı yasaklar). Yoksa: CI'da (CI ortam değişkeni) derleme durur, yerelde uyarıyla atlanır.
val buildRust = tasks.register<Exec>("buildRust") {
    group = "build"
    description = "native/ Rust kütüphanesini cargo-ndk ile derler"
    workingDir = rustDir
    val abiArgs = rustAbis.joinToString(" ") { "-t $it" }
    commandLine(
        "sh", "-c",
        "if ! cargo ndk --version >/dev/null 2>&1; then " +
            "echo 'cargo-ndk bulunamadi: cargo install cargo-ndk --locked (ve rustup target add aarch64-linux-android armv7-linux-androideabi)'; " +
            "if [ -n \"\$CI\" ]; then exit 1; fi; " +
            "echo 'Rust kutuphanesi derlenmedi: paylasim ve duvar kagidi calismaz.'; exit 0; fi; " +
            "exec cargo ndk $abiArgs -o '$jniOut' build --release",
    )
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRust) }

// Rust dışında üçüncü parti kütüphane yoktur: yalnızca Android framework'ü ve Kotlin standart kütüphanesi.
dependencies {
}
