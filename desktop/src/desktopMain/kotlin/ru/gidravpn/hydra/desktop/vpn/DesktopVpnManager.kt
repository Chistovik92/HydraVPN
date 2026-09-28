package ru.gidravpn.hydra.desktop.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import java.io.File
import java.util.concurrent.TimeUnit

class DesktopVpnManagerImpl : DesktopVpnManager {
    private var process: Process? = null
    private var configFile: File? = null
    private val trafficChannel = Channel<Pair<Long, Long>>(10)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

    override fun start(
        profile: ServerProfile,
        splitTunnel: SplitTunnel,
        dns: DnsEndpoint?,
        onTrafficUpdate: (up: Long, down: Long) -> Unit,
    ) {
        if (process != null && process!!.isAlive) {
            return
        }

        scope.launch {
            try {
                // Генерируем конфиг sing-box
                val config = SingBoxConfigBuilder.build(
                    profile = profile,
                    splitTunnel = splitTunnel,
                    dns = dns,
                )

                // Сохраняем конфиг во временный файл
                configFile = File.createTempFile("hydra-singbox-", ".json")
                configFile!!.deleteOnExit()
                configFile!!.writeText(config.toString(2))

                // Находим sing-box исполняемый файл
                val singBoxPath = findSingBoxExecutable()

                val pb = ProcessBuilder(singBoxPath, "run", "-c", configFile!!.absolutePath)
                    .redirectErrorStream(true)

                process = pb.start()

                // Читаем вывод в фоне для статистики
                readProcessOutput(process!!, onTrafficUpdate)

                // Ждём немного для проверки запуска
                Thread.sleep(1000)

                if (!process!!.isAlive) {
                    val exitCode = process!!.exitValue()
                    throw RuntimeException("sing-box завершился с кодом $exitCode")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException("Ошибка запуска VPN: ${e.message}", e)
            }
        }
    }

    override fun stop() {
        process?.destroy()
        process?.waitFor(5, TimeUnit.SECONDS)
        process?.destroyForcibly()
        process = null
        configFile?.delete()
        configFile = null
    }

    override fun isRunning(): Boolean {
        return process != null && process!!.isAlive
    }

    private fun findSingBoxExecutable(): String {
        val separator = if (isWindows) ";" else ":"
        val exeName = if (isWindows) "sing-box.exe" else "sing-box"

        // 1. Проверяем в PATH
        val pathDirs = System.getenv("PATH")?.split(separator) ?: emptyList()
        for (dir in pathDirs) {
            val exe = File(dir, exeName)
            if (exe.exists() && (!isWindows || exe.canExecute())) return exe.absolutePath
        }

        // 2. Проверяем рядом с приложением
        val appDir = File(".").absoluteFile.parentFile
        val localExe = File(appDir, exeName)
        if (localExe.exists() && (!isWindows || localExe.canExecute())) return localExe.absolutePath

        // 3. Fallback - ожидаем в PATH
        return exeName
    }

    private fun readProcessOutput(process: Process, onTrafficUpdate: (up: Long, down: Long) -> Unit) {
        scope.launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    // Парсим логи sing-box для статистики трафика (clash_api)
                    if (line.contains("traffic") || line.contains("clash_api")) {
                        try {
                            val json = org.json.JSONObject(line)
                            if (json.has("up") && json.has("down")) {
                                val up = json.getLong("up")
                                val down = json.getLong("down")
                                onTrafficUpdate(up, down)
                            }
                        } catch (e: Exception) {
                            // Игнорируем ошибки парсинга
                        }
                    }
                }
            }
        }
    }
}

class DesktopVpnManagerFactoryImpl : DesktopVpnManagerFactory {
    override fun create(): DesktopVpnManager = DesktopVpnManagerImpl()
}