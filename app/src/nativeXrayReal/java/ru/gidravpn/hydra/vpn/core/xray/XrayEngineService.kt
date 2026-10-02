package ru.gidravpn.hydra.vpn.core.xray

import android.app.Service
import android.content.Context
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

    private val binder = object : IXrayEngine.Stub() {
        override fun setProtector(p: IXraySocketProtector?) {
            val rt = XrayRuntime.get(applicationContext)
            rt.protector = p
            rt.registerControllerOnce()
        }

        override fun runXray(xrayJson: String): String =
            XrayRuntime.get(applicationContext).invoke("runXray", JSONObject().put("xrayJson", xrayJson))

        override fun stopXray(): String =
            XrayRuntime.get(applicationContext).invoke("stopXray", JSONObject())
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * Загруженный libXray — ОДИН на процесс `:xray`. Это принципиально (исправлено в 0.7.3): сервис пересоздаётся при каждом
 * переподключении (unbind → bind), а процесс живёт дальше; classloader и `libgojni.so` в нём можно загрузить только раз
 * (`UnsatisfiedLinkError: Shared library … already opened by ClassLoader`, из-за чего процесс падал и Xray не поднимался
 * со второго подключения). Поэтому classloader, рефлексия и регистрация защиты сокетов живут здесь, а не в экземпляре сервиса.
 */
private class XrayRuntime private constructor(context: android.content.Context) {

    @Volatile var protector: IXraySocketProtector? = null
    private var controllerRegistered = false

    // Не "classLoader" — коллидирует по JVM-сигнатуре с Context.getClassLoader().
    private val xrayClassLoader: ClassLoader = run {
        val dexFile = File(context.codeCacheDir, "libxray.dex")
        if (!dexFile.exists() || dexFile.length() == 0L) {
            dexFile.setWritable(true, true)
            context.assets.open("libxray.dex").use { input ->
                dexFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // ART отказывается грузить dex, который сам процесс ещё может записать («Writable dex file … is not allowed»).
        dexFile.setReadOnly()
        // parent = null (только boot-classloader): иначе go.Seq резолвился бы в libbox'овский (см. описание класса выше).
        DexClassLoader(dexFile.absolutePath, context.codeCacheDir.absolutePath, context.applicationInfo.nativeLibraryDir, null)
    }

    private val libXrayClass = xrayClassLoader.loadClass("libXray.LibXray")
    private val dialerControllerClass = xrayClassLoader.loadClass("libXray.DialerController")
    private val apiVersion = libXrayClass.getField("LibXrayAPIVersion").get(null) as Long
    private val invokeMethod = libXrayClass.getMethod("invoke", String::class.java)
    private val registerDialerControllerMethod = libXrayClass.getMethod("registerDialerController", dialerControllerClass)

    @Synchronized fun registerControllerOnce() {
        if (controllerRegistered) return
        val proxy = Proxy.newProxyInstance(xrayClassLoader, arrayOf(dialerControllerClass), Protector())
        registerDialerControllerMethod.invoke(null, proxy)
        controllerRegistered = true
    }

    fun invoke(method: String, payload: JSONObject): String =
        invokeMethod.invoke(null, JSONObject().put("apiVersion", apiVersion).put("method", method).put("payload", payload).toString()) as String

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

    companion object {
        @Volatile private var instance: XrayRuntime? = null
        fun get(context: android.content.Context): XrayRuntime =
            instance ?: synchronized(this) { instance ?: XrayRuntime(context.applicationContext).also { instance = it } }
    }
}
