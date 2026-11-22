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
val isCi = providers.environmentVariable("CI").isPresent

fun hasCargoNdk(): Boolean = try {
    ProcessBuilder("cargo", "ndk", "--version").redirectErrorStream(true).start().waitFor() == 0
} catch (e: Exception) { false }

val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "native/ Rust kütüphanesini ${rustAbis.joinToString()} için derler"
    workingDir = rootProject.file("native")
    commandLine(
        listOf("cargo", "ndk") + rustAbis.flatMap { listOf("-t", it) } +
            listOf("-o", file("src/main/jniLibs").absolutePath, "build", "--release"),
    )
    onlyIf {
        val ok = hasCargoNdk()
        if (!ok && isCi) throw GradleException("cargo-ndk bulunamadı: `cargo install cargo-ndk --locked` (ve rustup target add aarch64-linux-android armv7-linux-androideabi)")
        if (!ok) logger.warn("cargo-ndk yok: Rust kütüphanesi derlenmedi; paylaşım ve duvar kağıdı çalışmaz.")
        ok
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRust) }

// Rust dışında üçüncü parti kütüphane yoktur: yalnızca Android framework'ü ve Kotlin standart kütüphanesi.
dependencies {
}
