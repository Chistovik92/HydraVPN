package ru.gidravpn.hydra.vpn.core.xray

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Xray-core (`libXray.aar`) в отдельном процессе (`:xray`, см. AndroidManifest.xml).
 *
 * `libXray.LibXray`/`libXray.DialerController` НЕ подключены как обычная
 * Gradle-зависимость и не компилируются напрямую этим модулем — они грузятся
 * в рантайме через ИЗОЛИРОВАННЫЙ [DexClassLoader] (свой `classes.dex`,
 * собран из нетронутого `classes.jar` через `d8` — см. `app/build.gradle.kts`,
 * задача `libXrayToDex`).
 *
 * Почему так сложно (проверено на практике, 0.6.6 → 0.6.7): libbox.aar и
 * libXray.aar — независимые `gomobile bind`, у каждого своя обвязка gobind
 * (`go.Seq`/`go.Universe`/`go.error`). Она не взаимозаменяема (у каждой свой
 * `System.loadLibrary` — "box" vs "gojni") и НЕ переименовывается (JNI у
 * gomobile — implicit linking, `libgojni.so` экспортирует символы вида
 * `Java_go_Seq_init`, привязанные к ИСХОДНОМУ имени класса; переименование
 * пакета ломает эту привязку, а не чинит её). Единственный рабочий вариант —
 * не пускать классы libXray в общий classpath приложения вообще: свой
 * classloader → свой `go.Seq`, не пересекается с libbox'овским, грузит
 * именно свою `.so` по своим же JNI-символам. `.so` для этого classloader'а
 * лежат в jniLibs как обычно (`extractLibXrayNativeLibs`), путь передаётся
 * явно через `librarySearchPath` конструктора [DexClassLoader].
 */
class XrayEngineService : Service() {

    private var protector: IXraySocketProtector? = null
    private var controllerRegistered = false

    // Не "classLoader" — коллидирует по JVM-сигнатуре с Context.getClassLoader().
    private val xrayClassLoader: ClassLoader by lazy {
        val dexFile = File(codeCacheDir, "libxray.dex")
        if (!dexFile.exists() || dexFile.length() == 0L) {
            dexFile.setWritable(true, true)
            assets.open("libxray.dex").use { input ->
                dexFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // ART отказывается грузить dex, который сам процесс ещё может
        // записать ("Writable dex file ... is not allowed" — защита от
        // подмены кода в рантайме): без этого DexClassLoader ниже падает
        // именно с этой ошибкой сразу при первом обращении к его классам.
        dexFile.setReadOnly()
        // parent = null (только boot-classloader), а НЕ javaClass.classLoader:
        // обычный classloader процесса :xray всё равно видит libbox.aar'ный
        // go.Seq (тот тоже зависимость этого модуля, просто добавлен иначе) —
        // а Java-делегация классов всегда сначала спрашивает родителя, так что
        // с обычным parent'ом изоляция была фиктивной: `go.Seq.touch()` внутри
        // LibXray.<clinit> резолвился в libbox'овскую версию (та грузит
        // "box", а не "gojni") — LibXray._init() после этого не находит
        // нужных JNI-символов (UnsatisfiedLinkError). С parent=null достать
        // чужой go.Seq неоткуда — свой classes.dex остаётся единственным
        // источником.
        DexClassLoader(
            dexFile.absolutePath,
            codeCacheDir.absolutePath,
            applicationInfo.nativeLibraryDir,
            null,
        )
    }

    private val libXrayClass by lazy { xrayClassLoader.loadClass("libXray.LibXray") }
    private val dialerControllerClass by lazy { xrayClassLoader.loadClass("libXray.DialerController") }
    private val apiVersion by lazy { libXrayClass.getField("LibXrayAPIVersion").get(null) as Long }
    private val invokeMethod by lazy { libXrayClass.getMethod("invoke", String::class.java) }
    private val registerDialerControllerMethod by lazy {
        libXrayClass.getMethod("registerDialerController", dialerControllerClass)
    }

    private val binder = object : IXrayEngine.Stub() {
        override fun setProtector(p: IXraySocketProtector?) {
            protector = p
            if (!controllerRegistered) {
                val proxy = Proxy.newProxyInstance(xrayClassLoader, arrayOf(dialerControllerClass), Protector())
                registerDialerControllerMethod.invoke(null, proxy)
                controllerRegistered = true
            }
        }

        override fun runXray(xrayJson: String): String =
            invokeMethod.invoke(null, invokeRequest("runXray", JSONObject().put("xrayJson", xrayJson))) as String

        override fun stopXray(): String =
            invokeMethod.invoke(null, invokeRequest("stopXray", JSONObject())) as String
    }

    /** Реализация `libXray.DialerController` через динамический прокси — интерфейс сам загружен изолированным classloader'ом. */
    private inner class Protector : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any = when (method.name) {
            "protectFd" -> {
                val pfd = ParcelFileDescriptor.fromFd((args!![0] as Long).toInt())
                try {
                    protector?.protect(pfd) ?: false
                } finally {
                    runCatching { pfd.close() }
                }
            }
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            else -> "DialerController@xray"
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun invokeRequest(method: String, payload: JSONObject): String =
        JSONObject().put("apiVersion", apiVersion).put("method", method).put("payload", payload).toString()
}
