package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.ExecResult
import ru.gidravpn.hydra.servers.SshConnector
import ru.gidravpn.hydra.servers.SshSession
import ru.gidravpn.hydra.servers.SshTarget

/**
 * 0.8.2-b: «SSH» к стенду без пароля — стенд это WSL этой машины, команды идут через `wsl -d <distro> -u root`, а на второй сервер
 * стенда (контейнер `nodeb`) — через `docker exec`. Загрузка файла — через stdin (`cat > путь`). Туннели не поддерживаются.
 * Именно `wsl -e` (без оболочки): с `--` wsl.exe склеивает аргументы и разбирает их ещё раз — `$s` и кавычки в команде ломаются.
 *
 * Включается переменной HYDRA_WSL_STAND=<distro>; адрес → где выполнять: HYDRA_WSL_HOST (по умолчанию 172.29.99.246) — сам WSL,
 * HYDRA_WSL_NODE (172.29.99.250) — `docker exec nodeb`. Ставится в [SshConnector.testSessions] через [install].
 */
class WslSession(private val distro: String, private val container: String?) : SshSession {

    private fun cmd(inner: List<String>): List<String> =
        listOf("wsl", "-d", distro, "-u", "root", "-e") + (if (container != null) listOf("docker", "exec", "-i", container) else emptyList()) + inner

    override fun exec(command: String, onLine: (String) -> Unit): ExecResult {
        val p = ProcessBuilder(cmd(listOf("bash", "-c", command))).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = StringBuilder()
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            val line = raw.replace("\u0000", "")
            // предупреждение самого wsl.exe о localhost-прокси (UTF-16 без перевода) — не вывод команды
            if ("localhost" in line && line.trim().startsWith("w s l") || line.startsWith("wsl:")) return@forEachLine
            out.append(line).append('\n'); onLine(line)
        }
        return ExecResult(p.waitFor(), out.toString())
    }

    override fun upload(data: ByteArray, remotePath: String, mode: Int) {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val dir = remotePath.substringBeforeLast('/', ".").ifEmpty { "/" }
        val p = ProcessBuilder(cmd(listOf("bash", "-c", "mkdir -p ${q(dir)} && cat > ${q(remotePath)} && chmod ${Integer.toOctalString(mode)} ${q(remotePath)}")))
            .redirectErrorStream(true).start()
        p.outputStream.use { it.write(data) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "upload $remotePath: $out" }
    }

    override fun close() {}

    companion object {
        /** Подменить сеансы SshConnector для адресов стенда; true — режим включён. */
        fun install(): Boolean {
            val distro = System.getenv("HYDRA_WSL_STAND") ?: return false
            val host = System.getenv("HYDRA_WSL_HOST") ?: "172.29.99.246"
            val node = System.getenv("HYDRA_WSL_NODE") ?: "172.29.99.250"
            SshConnector.testSessions = { t: SshTarget ->
                when (t.host) { host -> WslSession(distro, null); node -> WslSession(distro, "nodeb"); else -> null }
            }
            return true
        }
    }
}
