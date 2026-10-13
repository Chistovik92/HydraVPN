package ru.gidravpn.hydra.servers

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.util.Base64

/** Куда и как подключаемся. Пароль/ключ живут только в памяти сеанса, пока их не «запомнили» отдельно (Keystore). */
class SshTarget(
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val password: String? = null,
    /** Закрытый ключ в PEM/OpenSSH (текст) и, при наличии, пароль к нему. */
    val privateKey: String? = null,
    val keyPassphrase: String? = null,
)

class HostKeyMismatch(val expected: String, val actual: String) :
    Exception("Отпечаток сервера изменился (был $expected, сейчас $actual) — возможна подмена, соединение остановлено")

/**
 * SSH на JSch (форк mwiede). Отпечаток хоста **закрепляется**: при первом подключении его показывают пользователю
 * ([fingerprint]), дальше соединение разрешено только с тем же отпечатком.
 */
object SshConnector {
    private const val CONNECT_TIMEOUT_MS = 15_000

    private class Pinned(private val expected: String?) : HostKeyRepository {
        @Volatile var seen: String? = null
        override fun check(host: String?, key: ByteArray): Int {
            val fp = fingerprint(key)
            seen = fp
            return if (expected == null || expected == fp) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "hydra-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** `SHA256:<base64 без =>` — тот же вид, что показывает OpenSSH. */
    fun fingerprint(key: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(key))

    private val androidInit: Unit by lazy {
        // На Android нет встроенных ed25519/curve25519/chacha20: используем реализации JSch на BouncyCastle (лёгкий API,
        // без регистрации провайдера, поэтому не конфликтует с урезанным BC системы). На ПК (JDK 15+) не трогаем.
        val android = System.getProperty("java.vendor")?.contains("Android", true) == true || System.getProperty("java.vm.name")?.contains("Dalvik", true) == true
        if (android && runCatching { Class.forName("com.jcraft.jsch.bc.XDH") }.isSuccess) {
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("chacha20-poly1305@openssh.com", "com.jcraft.jsch.bc.ChaCha20Poly1305")
        }
    }

    private fun newSession(t: SshTarget, repo: HostKeyRepository): Session {
        androidInit
        val jsch = JSch()
        jsch.hostKeyRepository = repo
        t.privateKey?.let { jsch.addIdentity("hydra", it.toByteArray(), null, t.keyPassphrase?.toByteArray()) }
        val s = jsch.getSession(t.user, t.host, t.port)
        t.password?.let { s.setPassword(it) }
        s.setConfig("StrictHostKeyChecking", "yes")
        // без интерактивных запросов: пароль/ключ уже заданы, остальное — отказ
        s.setConfig("PreferredAuthentications", if (t.privateKey != null) "publickey,password" else "password,keyboard-interactive")
        s.userInfo = object : UserInfo {
            override fun getPassphrase() = t.keyPassphrase
            override fun getPassword() = t.password
            override fun promptPassword(m: String?) = t.password != null
            override fun promptPassphrase(m: String?) = t.keyPassphrase != null
            override fun promptYesNo(m: String?) = false
            override fun showMessage(m: String?) {}
        }
        s.setServerAliveInterval(20_000)
        // ПК в режиме «прокси»: SSH идёт через локальный SOCKS5 ядра, иначе сервер, закрытый без VPN, не открылся бы и с VPN.
        AppProxy.port.takeIf { it > 0 }?.let { s.setProxy(com.jcraft.jsch.ProxySOCKS5("127.0.0.1", it)) }
        return s
    }

    /** Узнать отпечаток, не входя: для показа пользователю при первом подключении. */
    fun fingerprint(t: SshTarget): String {
        val repo = Pinned(null)
        val s = newSession(SshTarget(t.host, t.port, t.user), repo)
        var failure: Throwable? = null
        try { s.connect(CONNECT_TIMEOUT_MS) } catch (e: JSchException) { failure = e /* ключ хоста виден ещё до входа: ошибка входа здесь ожидаема */ } finally { s.disconnect() }
        // Настоящая причина (согласование алгоритмов, сеть, время ожидания) — в сообщении, а не просто «не ответил»: по ней видно, чинить сервер, сеть или приложение.
        return repo.seen ?: throw JSchException("Сервер ${t.host}:${t.port} не ответил по SSH" + (failure?.let { " (${rootMessage(it)})" } ?: ""))
    }

    private fun rootMessage(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return (if (t === e) e.message else "${e.message}: ${t.javaClass.simpleName} ${t.message}").orEmpty().take(300)
    }

    /** Подключиться, сверив отпечаток с [pinned]. */
    fun connect(t: SshTarget, pinned: String): SshSession {
        val repo = Pinned(pinned)
        val s = newSession(t, repo)
        try { s.connect(CONNECT_TIMEOUT_MS) } catch (e: JSchException) {
            repo.seen?.takeIf { it != pinned }?.let { throw HostKeyMismatch(pinned, it) }
            throw e
        }
        return JschSshSession(s)
    }
}

class JschSshSession internal constructor(private val s: Session) : SshSession {
    override fun exec(command: String, onLine: (String) -> Unit): ExecResult {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        ch.setErrStream(System.err, false)
        val input = ch.inputStream
        ch.connect()
        val all = StringBuilder()
        input.bufferedReader().useLines { lines -> lines.forEach { all.appendLine(it); onLine(it) } }
        while (!ch.isClosed) Thread.sleep(20)
        val code = ch.exitStatus
        ch.disconnect()
        return ExecResult(code, all.toString())
    }

    override fun upload(data: ByteArray, remotePath: String, mode: Int) {
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(15_000)
        try {
            ch.put(ByteArrayInputStream(data), remotePath, ChannelSftp.OVERWRITE)
            ch.chmod(mode, remotePath)
        } finally { ch.disconnect() }
    }

    override fun close() = s.disconnect()
}
