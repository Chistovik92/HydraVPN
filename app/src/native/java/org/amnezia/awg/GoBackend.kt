package org.amnezia.awg

/**
 * JNI-мост к `libwg-go.so` (amneziawg-go, сборка из github.com/amnezia-vpn/amneziawg-android,
 * `tunnel/tools/libwg-go`). Имя пакета и класса **нельзя менять**: JNI-символы в
 * библиотеке жёстко названы `Java_org_amnezia_awg_GoBackend_*`.
 *
 * Это обычная C-shared библиотека, а не gomobile-биндинг, поэтому она не тащит свою
 * копию `go.Seq` и не конфликтует с libbox/libXray (см. app/build.gradle.kts, «libbox.aar и
 * libXray.aar собраны двумя независимыми gomobile bind»).
 */
object GoBackend {
    /** @return handle туннеля (>= 0) либо -1. `tunFd` передаётся во владение Go (detachFd). */
    @JvmStatic external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int
    @JvmStatic external fun awgTurnOff(handle: Int)
    /** Сокеты, которые нужно вывести из-под VPN (`VpnService.protect`); -1 — нет. */
    @JvmStatic external fun awgGetSocketV4(handle: Int): Int
    @JvmStatic external fun awgGetSocketV6(handle: Int): Int
    /** Текущее состояние устройства в формате UAPI (`rx_bytes=`, `tx_bytes=`, `last_handshake_time_sec=`…). */
    @JvmStatic external fun awgGetConfig(handle: Int): String?
    @JvmStatic external fun awgVersion(): String
}
