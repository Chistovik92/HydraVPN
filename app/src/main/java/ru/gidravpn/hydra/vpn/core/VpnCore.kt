package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile

/** Состояние соединения, наблюдаемое из UI. */
enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR,
    /** Туннель упал, сервис сам пробует поднять его снова (Фаза 7b). */
    RECONNECTING,
}

/** Живая статистика соединения. */
data class TrafficStats(val downBytes: Long = 0, val upBytes: Long = 0)

/**
 * Абстракция ядра, работающего поверх tun-интерфейса.
 * Реализации (выбираются по flavor):
 *  - SingBoxCore (native, libbox.aar)  — VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard
 *  - XrayCore    (native, libXray.aar) — альтернативный движок Xray
 *  - AmneziaWgCore (native, amneziawg-go.aar)
 *  - SstpCore / L2tpCore (native, userspace PPP на Kotlin — без .aar)
 *  - PptpCore    (native) — честный отказ (GRE → root)
 *  - NoopCore    (stub)   — симуляция для разработки/CI
 */
interface VpnCore {
    val name: String

    /**
     * Канал «ядро умерло» (Фаза 7a). Ставится сервисом ДО [start] и зовётся
     * ядром, когда туннель развалился уже ПОСЛЕ успешного запуска: закрылся
     * PPP, сервер прислал CALL_ABORT/StopCCN, остановился sing-box, умер
     * процесс `:xray`.
     *
     * До 7a такие события уходили только в лог (`onLog`), и состояние
     * оставалось CONNECTED навсегда: в UI «Туннель зашифрован», трафика нет,
     * Kill Switch не срабатывает — он жил только внутри `doConnect()`.
     *
     * Реализация по умолчанию — no-op: ядро, которое не умеет отличить смерть
     * от штатной остановки (stub, AmneziaWG-заглушка, PptpCore), просто не
     * переопределяет этот метод. Контракт: зовётся не более одного раза за
     * запуск и НЕ зовётся из [stop] — штатное отключение смертью не считается.
     */
    fun setDeathListener(listener: (reason: String) -> Unit) {}

    fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    )

    fun stop()
}

/**
 * Фабрика ядра. Класс с этим же именем и пакетом определён в обоих flavor-sourceSet'ах
 * (src/stub и src/native); в конкретную сборку попадает ровно один.
 */
interface CoreFactory {
    fun create(profile: ServerProfile): VpnCore
}
