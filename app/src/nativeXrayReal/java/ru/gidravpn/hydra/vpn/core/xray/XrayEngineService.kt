package ru.gidravpn.hydra.vpn.core.xray

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject

/**
 * Xray-core (`libXray.aar`) в отдельном процессе (`:xray`, см. AndroidManifest.xml).
 *
 * Каждый gomobile-собранный `.aar` тащит свою копию Go-рантайма — libbox
 * (sing-box, главный процесс) и libXray не могут сосуществовать в одном
 * процессе (см. docs/BUILD.md, раздел 2.2; README github.com/XTLS/libXray:
 * «Go does not support loading multiple independently built Go runtimes
 * into one process»). Отсюда — отдельный процесс и AIDL-мост ([IXrayEngine]),
 * через который ходят только строки/ParcelFileDescriptor.
 *
 * protect() сокетов Xray-core сам сделать не может (нет доступа к
 * VpnService, который живёт в главном процессе) — поэтому [DialerController]
 * здесь лишь оборачивает fd в [ParcelFileDescriptor] (не берёт владение —
 * `fromFd`, не `adoptFd`) и синхронно зовёт [IXraySocketProtector] через
 * Binder; Binder сам дублирует fd в главный процесс на уровне ядра, поэтому
 * закрытие локальной обёртки после вызова не трогает исходный fd — Xray
 * продолжает пользоваться тем же сокетом.
 */
class XrayEngineService : Service() {

    private var protector: IXraySocketProtector? = null
    private var controllerRegistered = false

    private val binder = object : IXrayEngine.Stub() {
        override fun setProtector(p: IXraySocketProtector?) {
            protector = p
            if (!controllerRegistered) {
                LibXray.registerDialerController(object : DialerController {
                    override fun protectFd(fd: Long): Boolean {
                        val pfd = ParcelFileDescriptor.fromFd(fd.toInt())
                        return try {
                            protector?.protect(pfd) ?: false
                        } finally {
                            runCatching { pfd.close() }
                        }
                    }
                })
                controllerRegistered = true
            }
        }

        override fun runXray(xrayJson: String): String =
            LibXray.invoke(invokeRequest("runXray", JSONObject().put("xrayJson", xrayJson)))

        override fun stopXray(): String =
            LibXray.invoke(invokeRequest("stopXray", JSONObject()))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun invokeRequest(method: String, payload: JSONObject): String =
        JSONObject().put("apiVersion", LibXray.LibXrayAPIVersion).put("method", method).put("payload", payload).toString()
}
