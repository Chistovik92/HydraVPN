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

# AmneziaWG: JNI-символы libwg-go.so жёстко называют класс и методы — не трогать.
-keep class org.amnezia.awg.GoBackend { *; }

# «Мои серверы» (0.8.0): JSch подгружает алгоритмы (обмен ключами, шифры, подписи) по ИМЕНАМ классов из своей таблицы настроек.
# Без этих правил R8 в release-сборке переименовывает или вырезает классы, и вход по SSH падает на согласовании алгоритмов
# («не ответил по SSH») — в debug-сборке минификации нет, поэтому там всё работало.
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
