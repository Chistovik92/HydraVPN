# Нативные ядра, вызываемые через JNI/gomobile — не переименовывать
-keep class io.nekohasekai.libbox.** { *; }
-keep class libXray.** { *; }
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
