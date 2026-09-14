# Нативные ядра, вызываемые через JNI/gomobile — не переименовывать.
# libXray НЕ подключён как обычная зависимость (classes.jar конфликтует с
# libbox'овским go.* на уровне JNI-связывания, см. app/build.gradle.kts,
# extractLibXrayNativeLibs/libXrayToDex) — его классы грузятся в рантайме
# изолированным DexClassLoader (XrayEngineService), R8 их вообще не видит.
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ru.gidravpn.hydra.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
