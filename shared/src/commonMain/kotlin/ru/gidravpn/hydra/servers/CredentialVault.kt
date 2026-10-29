package ru.gidravpn.hydra.servers

import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Данные входа на сервер, которые пользователь попросил запомнить (0.8.0-rc4). */
data class SavedLogin(val password: String? = null, val privateKey: String? = null, val keyPassphrase: String? = null) {
    fun target(s: MyServer) = SshTarget(s.host, s.sshPort, s.sshUser, password, privateKey, keyPassphrase)
    val viaKey get() = !privateKey.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        password?.let { put("password", it) }
        privateKey?.let { put("key", it) }
        keyPassphrase?.let { put("passphrase", it) }
    }

    companion object {
        fun of(t: SshTarget) = SavedLogin(t.password, t.privateKey, t.keyPassphrase)
        fun fromJson(o: JSONObject) = SavedLogin(
            o.optString("password").takeIf { o.has("password") }, o.optString("key").takeIf { o.has("key") },
            o.optString("passphrase").takeIf { o.has("passphrase") },
        )
    }
}

/**
 * Хранилище данных входа на серверы. Пароль/ключ лежат **только зашифрованными** ([VaultCipher]):
 * на Android — ключом из Android Keystore (не покидает устройство), на ПК с Windows — DPAPI учётной записи,
 * на Linux/macOS — ключом в файле профиля с правами 0600. В JSON списка серверов и в резервные копии не попадают.
 */
interface CredentialVault {
    fun load(id: String): SavedLogin?
    fun save(id: String, login: SavedLogin)
    fun remove(id: String)
    fun ids(): Set<String>
}

/** Шифрование хранилища; реализация — платформенная. */
interface VaultCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** В памяти — для тестов и как запасной вариант, если шифрование на устройстве недоступно (тогда ничего не пишется на диск). */
class MemoryVault : CredentialVault {
    private val m = java.util.concurrent.ConcurrentHashMap<String, SavedLogin>()
    override fun load(id: String) = m[id]
    override fun save(id: String, login: SavedLogin) { m[id] = login }
    override fun remove(id: String) { m.remove(id) }
    override fun ids(): Set<String> = m.keys.toSet()
}

/**
 * Файл `{ "<id сервера>": "<base64 шифротекста>" }`. Запись атомарная (временный файл + переименование).
 * Запись, которую не удалось расшифровать (сменился ключ Keystore, перенос профиля), считается отсутствующей — приложение
 * просто снова спросит пароль.
 */
class FileVault(private val file: File, private val cipher: VaultCipher) : CredentialVault {
    private val lock = Any()

    /** Для чтения: нечитаемый файл — как пустой (приложение спросит пароль заново). */
    private fun read(): JSONObject = runCatching { if (file.isFile) JSONObject(file.readText()) else JSONObject() }.getOrDefault(JSONObject())

    /**
     * Для записи: временный сбой чтения (файл занят антивирусом) пробрасывается — иначе пустой список записался бы поверх
     * сохранённых входов всех серверов. Повреждённый JSON откладывается рядом (`.corrupt`), хранилище начинается заново.
     */
    private fun readForWrite(): JSONObject {
        if (!file.isFile) return JSONObject()
        val text = file.readText()   // IOException — наверх, запись не выполняется
        return try {
            JSONObject(text)
        } catch (_: org.json.JSONException) {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
            JSONObject()
        }
    }

    private fun write(o: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.delete()
        // Права ставятся при создании, без окна, когда файл с шифротекстами читаем всем.
        runCatching {
            java.nio.file.Files.createFile(tmp.toPath(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        }
        java.io.FileOutputStream(tmp).use { it.write(o.toString().toByteArray(Charsets.UTF_8)); it.flush(); runCatching { it.fd.sync() } }
        runCatching { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) }
        // Замена без окна «файла нет»: раньше file.delete() шёл перед повторным переименованием.
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun load(id: String): SavedLogin? = synchronized(lock) {
        val enc = read().optString(id).takeIf { it.isNotEmpty() } ?: return null
        runCatching { SavedLogin.fromJson(JSONObject(String(cipher.decrypt(Base64.getDecoder().decode(enc)), Charsets.UTF_8))) }.getOrNull()
    }

    override fun save(id: String, login: SavedLogin) = synchronized(lock) {
        val o = readForWrite()
        o.put(id, Base64.getEncoder().encodeToString(cipher.encrypt(login.toJson().toString().toByteArray(Charsets.UTF_8))))
        write(o)
    }

    override fun remove(id: String) = synchronized(lock) {
        val o = readForWrite()
        if (o.has(id)) { o.remove(id); write(o) }
    }

    override fun ids(): Set<String> = synchronized(lock) { read().keys().asSequence().toSet() }
}

/** AES-256-GCM ключом из файла (создаётся при первом обращении, права 0600). ПК без DPAPI: Linux, macOS. */
class KeyFileCipher(private val keyFile: File) : VaultCipher {
    private val key: javax.crypto.SecretKey by lazy {
        // Файл ключа не 32 байта — недописан после сбоя: пользоваться им нельзя (SecretKeySpec упал бы на каждом вызове).
        if (!keyFile.isFile || keyFile.length() != 32L) {
            keyFile.parentFile?.mkdirs()
            val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            // Создаём сразу с правами 0600 и заменяем целиком — без окна, когда ключ читаем всем или записан наполовину.
            val tmp = File(keyFile.parentFile, keyFile.name + ".tmp").also { it.delete() }
            runCatching {
                java.nio.file.Files.createFile(tmp.toPath(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            }.onFailure { tmp.createNewFile(); tmp.setReadable(false, false); tmp.setReadable(true, true) }
            tmp.writeBytes(k)
            java.nio.file.Files.move(tmp.toPath(), keyFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        javax.crypto.spec.SecretKeySpec(keyFile.readBytes(), "AES")
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}
