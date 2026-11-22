# The launcher uses only the Android framework (no reflection, no third-party libraries);
# the default optimized rules from AGP are enough, plus the JNI bridge rules below.

# Rust köprüsü: JNI sembolleri sınıf/metot adlarına bağlıdır (libatvnative.so → Java_com_ATVBasic_launcher_Native_*)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.ATVBasic.launcher.Native { *; }
