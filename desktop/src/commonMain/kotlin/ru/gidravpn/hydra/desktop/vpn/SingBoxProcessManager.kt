package ru.gidravpn.hydra.desktop.vpn

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder.GeoCountry
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder.GeoRouting
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.TlsFragmentMode

/**
 * Управляет процессом sing-box на Desktop (Windows/Linux/macOS).
 * Вместо JNI/gomobile интеграции запускаем sing-box как отдельный процесс:
 * `sing-box run -c config.json`
 *
 * sing-box сам умеет поднимать tun на всех трёх ОС из коробки.
 * Hydra на Desktop генерирует конфиг (готово в data-слое) и управляет
 * процессом/статусом через тот же CommandClient/clash_api, что уже сделано
 * для Android (SingBoxRuntime, 0.6.1).
 */
class SingBoxProcessManager {

    private val processRef = AtomicReference<Process?>(null)
    private val configFileRef = AtomicReference<File?>(null)
    private val mutex = Mutex()
    private val statusChannel = Channel<SingBoxStatus>(10)

    /** Текущий статус sing-box процесса */
    data class SingBoxStatus(
        val isRunning: Boolean,
        val profile: ServerProfile? = null,
        val error: String? = null,
        val trafficUp: Long = 0,
        val trafficDown: Long = 0,
    )

    /** Поток статусов для UI */
    val statusFlow: ReceiveChannel<SingBoxStatus> = statusChannel

    /**
     * Запускает sing-box с заданным профилем.
     * sing-box должен быть доступен в PATH или по указанному пути.
     */
    suspend fun start(
        profile: ServerProfile,
        singBoxPath: String = "sing-box",
        splitTunnel: SplitTunnel = SplitTunnel(),
        dns: DnsEndpoint? = DnsEndpoint.doh("1.1.1.1"),
        geoRouting: GeoRouting? = null,
        mtu: Int = MtuPreset.AUTO.value,
        tlsFragment: TlsFragmentMode = TlsFragmentMode.OFF,
        hotspot: HotspotSettings? = null,
    ): Result<Unit> = mutex.withLock {
        if (processRef.get() != null) {
            return Result.failure(IllegalStateException("sing-box уже запущен"))
        }

        return try {
            // Генерируем конфиг sing-box
            val config = SingBoxConfigBuilder.build(
                profile = profile,
                splitTunnel = splitTunnel,
                dns = dns,
                geoRouting = geoRouting,
                mtu = mtu,
                tlsFragment = tlsFragment,
                hotspot = hotspot,
            )

            // Сохраняем конфиг во временный файл
            val configFile = File.createTempFile("hydra-singbox-", ".json")
            configFile.deleteOnExit()
            configFile.writeText(config.toString())
            configFileRef.set(configFile)

            // Запускаем sing-box процесс
            val pb = ProcessBuilder(singBoxPath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)

            val process = pb.start()
            processRef.set(process)

            // Читаем stdout/stderr в фоне
            startLogReader(process)

            // Ждём немного, чтобы убедиться что процесс запустился
            Thread.sleep(500)

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                val error = "sing-box завершился с кодом $exitCode"
                statusChannel.trySend(SingBoxStatus(false, profile, error))
                processRef.set(null)
                configFile.delete()
                Result.failure(Exception(error))
            } else {
                statusChannel.trySend(SingBoxStatus(true, profile))
                Result.success(Unit)
            }
        } catch (e: Exception) {
            statusChannel.trySend(SingBoxStatus(false, profile, e.message))
            Result.failure(e)
        }
    }

    /** Останавливает sing-box процесс */
    suspend fun stop(): Result<Unit> = mutex.withLock {
        val process = processRef.getAndSet(null)
        val configFile = configFileRef.getAndSet(null)

        if (process == null) {
            return Result.success(Unit)
        }

        return try {
            // Мягкое завершение
            process.destroy()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

            if (!finished) {
                // Принудительное завершение
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }

            configFile?.delete()
            statusChannel.trySend(SingBoxStatus(false))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Перезапускает с новым профилем */
    suspend fun restart(
        profile: ServerProfile,
        singBoxPath: String = "sing-box",
        splitTunnel: SplitTunnel = SplitTunnel(),
        dns: DnsEndpoint? = DnsEndpoint.doh("1.1.1.1"),
        geoRouting: GeoRouting? = null,
        mtu: Int = MtuPreset.AUTO.value,
        tlsFragment: TlsFragmentMode = TlsFragmentMode.OFF,
        hotspot: HotspotSettings? = null,
    ): Result<Unit> {
        stop()
        return start(profile, singBoxPath, splitTunnel, dns, geoRouting, mtu, tlsFragment, hotspot)
    }

    /** Проверяет, запущен ли процесс */
    fun isRunning(): Boolean {
        val process = processRef.get()
        return process != null && process.isAlive
    }

    /** Получает текущий профиль */
    fun currentProfile(): ServerProfile? {
        return processRef.get()?.let { _ -> null } // TODO: сохранить профиль
    }

    private fun startLogReader(process: Process) {
        Thread {
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    // Парсим логи sing-box для статистики трафика
                    parseLogLine(line)
                }
            }
        }.start()
    }

    private fun parseLogLine(line: String) {
        // sing-box логирует трафик через clash_api
        // Пример: {"type":"traffic","up":1024,"down":2048}
        if (line.contains("traffic")) {
            try {
                val json = org.json.JSONObject(line)
                if (json.has("up") && json.has("down")) {
                    val up = json.getLong("up")
                    val down = json.getLong("down")
                    statusChannel.trySend(SingBoxStatus(true, trafficUp = up, trafficDown = down))
                }
            } catch (_: Exception) {
                // игнорируем ошибки парсинга
            }
        }
    }

    /** Закрывает канал статусов при завершении работы приложения */
    fun shutdown() {
        statusChannel.close()
    }
}