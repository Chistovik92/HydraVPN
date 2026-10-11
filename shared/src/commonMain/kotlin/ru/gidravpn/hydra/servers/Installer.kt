package ru.gidravpn.hydra.servers

import java.security.MessageDigest

/** Результат команды на сервере. */
data class ExecResult(val exit: Int, val out: String) {
    val ok get() = exit == 0
}

/**
 * Сеанс SSH, который видит установщик. Реализации: JSch (`JschSshSession`) и поддельная в тестах.
 * Все методы блокирующие; вызывать из фонового потока.
 */
interface SshSession : AutoCloseable {
    /** Выполнить команду; построчный вывод — в [onLine]. */
    fun exec(command: String, onLine: (String) -> Unit = {}): ExecResult
    /** Записать файл (SFTP) с правами [mode] (восьмеричными, напр. 0x1A4 = 0644). */
    fun upload(data: ByteArray, remotePath: String, mode: Int = 420)
}

/** Один шаг установки: показывается пользователю до запуска. */
class Step(
    val id: String,
    val title: String,
    /** Команда, как она будет выполнена (секреты — в [secrets], в журнал не попадают). */
    val command: String? = null,
    /** Загрузка файла вместо команды (путь на сервере и содержимое). */
    val upload: Upload? = null,
    /** Считать ли результат успешным (по умолчанию — код 0). */
    val accept: (ExecResult) -> Boolean = { it.ok },
    /** Команда отката шага (для «откатить»). */
    val rollback: String? = null,
    val failHint: String = "",
) {
    class Upload(val path: String, val data: ByteArray, val mode: Int = 420, val sha256: String? = null)
}

sealed interface InstallEvent {
    data class StepStart(val index: Int, val total: Int, val step: Step) : InstallEvent
    data class Output(val line: String) : InstallEvent
    data class StepOk(val step: Step) : InstallEvent
    data class StepFailed(val step: Step, val reason: String) : InstallEvent
    data object Done : InstallEvent
}

/** Вырезает секреты из строк журнала (пароли, закрытые ключи, PSK). */
class Redactor(private val secrets: Collection<String>) {
    fun clean(s: String): String {
        var r = s
        for (x in secrets) if (x.length >= 6) r = r.replace(x, "•••")
        return r.replace(Regex("(?i)(PrivateKey|PresharedKey|HeaderProtectionKey|password)(\\s*[=:]\\s*)\\S+"), "$1$2•••")
    }
}

/**
 * Выполняет план по шагам: останавливается на первой ошибке (с понятной причиной), поддерживает отмену и откат.
 * Идемпотентность обеспечивает сам план (команды вида `mkdir -p`, `systemctl enable --now`).
 */
class Installer(
    private val ssh: SshSession,
    secrets: Collection<String> = emptyList(),
    private val onEvent: (InstallEvent) -> Unit,
) {
    private val redactor = Redactor(secrets)
    @Volatile var cancelled = false

    /** @return true, если все шаги выполнены. */
    fun run(plan: List<Step>): Boolean {
        plan.forEachIndexed { i, step ->
            if (cancelled) { onEvent(InstallEvent.StepFailed(step, "Остановлено пользователем")); return false }
            onEvent(InstallEvent.StepStart(i + 1, plan.size, step))
            val err = try { exec(step) } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            if (err != null) { onEvent(InstallEvent.StepFailed(step, redactor.clean(err))); return false }
            onEvent(InstallEvent.StepOk(step))
        }
        onEvent(InstallEvent.Done)
        return true
    }

    /** Откат выполненных шагов в обратном порядке (ошибки отката не прерывают). */
    fun rollback(done: List<Step>) {
        for (s in done.asReversed()) {
            val cmd = s.rollback ?: continue
            runCatching { ssh.exec(cmd) { onEvent(InstallEvent.Output(redactor.clean(it))) } }
        }
    }

    private fun exec(step: Step): String? {
        step.upload?.let {
            // во временный файл и атомарный mv: работающий бинарь поверх не перезаписать («Text file busy»)
            val tmp = it.path + ".hydra-new"
            ssh.upload(it.data, tmp, it.mode)
            if (it.sha256 != null) {
                val r = ssh.exec("sha256sum ${shq(tmp)}")
                val got = r.out.trim().substringBefore(' ')
                if (!got.equals(it.sha256, true)) {
                    ssh.exec("rm -f ${shq(tmp)}")
                    return "Контрольная сумма ${it.path} не совпала (ожидалась ${it.sha256.take(12)}…, на сервере ${got.take(12)}…)"
                }
            }
            val mv = ssh.exec("mv -f ${shq(tmp)} ${shq(it.path)}")
            if (!mv.ok) return "Не удалось записать ${it.path}: ${mv.out.trim()}"
        }
        val cmd = step.command ?: return null
        val r = ssh.exec(cmd) { onEvent(InstallEvent.Output(redactor.clean(it))) }
        return if (step.accept(r)) null
        else (step.failHint.ifEmpty { "Команда завершилась с кодом ${r.exit}" } + (r.out.trim().lines().lastOrNull()?.let { ": $it" } ?: ""))
    }

    companion object {
        fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** Что нашли на сервере до установки. */
data class Preflight(
    val arch: String,        // amd64 / arm64 / armv7
    val osName: String,
    val root: Boolean,
    val systemd: Boolean,
    val portFree: Boolean,
    val internet: Boolean,
    val firewall: String,    // ufw / firewalld / none
    val issues: List<String>,
) {
    val ok get() = issues.isEmpty()
}

object PreflightCheck {
    /** Одна команда, чтобы не делать много обращений; разбор — в [parse]. */
    fun command(port: Int) = buildString {
        append("echo ARCH=\$(uname -m); ")
        append("echo OS=\$(. /etc/os-release 2>/dev/null && echo \"\$PRETTY_NAME\"); ")
        append("echo UID=\$(id -u); ")
        append("echo SYSTEMD=\$([ -d /run/systemd/system ] && echo 1 || echo 0); ")
        append("echo PORTBUSY=\$( (ss -lunH 2>/dev/null; ss -ltnH 2>/dev/null) | grep -c \":$port \"); ")
        append("echo NET=\$( (curl -fsS -m 6 -o /dev/null https://github.com 2>/dev/null || wget -q -T 6 -O /dev/null https://github.com 2>/dev/null) && echo 1 || echo 0); ")
        append("echo FW=\$(if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q active; then echo ufw; ")
        append("elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then echo firewalld; else echo none; fi)")
    }

    fun parse(out: String): Preflight {
        val m = out.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
        val arch = when (m["ARCH"]) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            "armv7l", "armv7" -> "armv7"
            else -> m["ARCH"] ?: "?"
        }
        val root = m["UID"] == "0"
        val systemd = m["SYSTEMD"] == "1"
        val free = (m["PORTBUSY"]?.toIntOrNull() ?: 0) == 0
        val net = m["NET"] == "1"
        val issues = buildList {
            if (arch !in setOf("amd64", "arm64", "armv7")) add("Неподдерживаемая архитектура: $arch")
            if (!root) add("Нужен root (или вход под root)")
            if (!systemd) add("Нужен systemd")
            if (!free) add("Порт занят другой службой")
            if (!net) add("С сервера нет доступа в интернет (github.com)")
        }
        return Preflight(arch, m["OS"].orEmpty(), root, systemd, free, net, m["FW"] ?: "none", issues)
    }
}
