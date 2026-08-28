package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile
import kotlin.concurrent.thread

/**
 * Заглушка ядра для flavor `stub`. Не устанавливает реальный туннель —
 * лишь имитирует подключение и генерирует трафик, чтобы можно было
 * разрабатывать/тестировать UI без нативных .aar (как в веб-макете).
 */
class NoopCore(private val engineName: String) : VpnCore {
    override val name = "$engineName (stub)"
    @Volatile private var running = false

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        running = true
        onLog("STUB: реальный туннель не поднимается (нужна native-сборка)")
        onLog("✓ ${profile.protocol?.displayName} туннель активен (симуляция)")
        thread(name = "noop-traffic") {
            var down = 0L; var up = 0L
            while (running) {
                Thread.sleep(2000)
                down += (0..5_000_000L).random()
                up += (0..2_000_000L).random()
                onStats(TrafficStats(down, up))
            }
        }
    }

    override fun stop() { running = false }
}

object StubCoreFactory : CoreFactory {
    override fun create(profile: ServerProfile): VpnCore {
        val engine = profile.protocol?.engine?.name ?: "SINGBOX"
        return NoopCore(engine)
    }
}

/** Провайдер фабрики для flavor `stub`. */
object CoreFactoryProvider {
    val factory: CoreFactory = StubCoreFactory
}
