package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * WDTT (beta) — WireGuard через TURN-релей облака VK.
 *
 * Движок: нативная библиотека `libclient.so` (JNI). Схема:
 *  1. VK-авторизация (OAuth через WebView) → TURN-credentials;
 *  2. WG-сессия заворачивается в TURN-релей (внешний трафик выглядит
 *     как обычный WebRTC/TURN к облаку ВК);
 *  3. нативный клиент сам поднимает туннель, JNI-мост отдаёт ему tun-fd.
 *
 * До сборки `libclient.so` и проверки лицензии upstream ядро честно
 * отказывает. См. docs/BUILD.md и docs/SERVICES.md (раздел WDTT).
 */
class WdttCore : VpnCore {

    override val name = "WDTT (VK TURN, beta)"

    @Volatile private var running = false

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        onLog("WDTT: движок beta — требуется libclient.so (см. docs/BUILD.md)")

        // TODO(libclient.so): JNI-интеграция после сборки:
        //   1. System.loadLibrary("client") — в companion/init;
        //   2. external fun nativeStart(tunFd: Int, turnUrl: String, user: String, cred: String): Int
        //   3. поток VK-авторизации: WebView OAuth → TURN-credentials
        //      (сохранять в profile.extra: turn_user / turn_credential);
        //   4. статистика: nativeGetStats() → TrafficStats.
        running = true
        throw NotImplementedError(
            "WDTT (beta): положите libclient.so в app/libs и подключите JNI-мост (docs/BUILD.md)"
        )
    }

    override fun stop() {
        running = false
        // TODO(libclient.so): nativeStop()
    }

    companion object {
        /** Безопасная попытка загрузки нативной библиотеки (может отсутствовать). */
        @Volatile private var libLoaded = false
        private fun ensureLib(onLog: (String) -> Unit) {
            if (libLoaded) return
            runCatching { System.loadLibrary("client") }
                .onSuccess {
                    libLoaded = true
                    onLog("WDTT: libclient.so загружена")
                }
                .onFailure { onLog("WDTT: libclient.so не найдена (${it.message})") }
        }
    }
}
