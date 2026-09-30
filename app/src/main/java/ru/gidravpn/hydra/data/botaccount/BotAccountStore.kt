package ru.gidravpn.hydra.data.botaccount

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Адрес бота и токен устройства. Токен — доступ к подпискам человека, поэтому лежит
 * зашифрованным ключом из Android Keystore (AES-GCM), а не открытым текстом; ключ не покидает
 * устройство. Файл отдельный и в [allStores] не входит: токен не попадает ни в резервную копию
 * настроек, ни в экспорт.
 */
class BotAccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val server: String get() = prefs.getString(KEY_SERVER, "").orEmpty()
    val username: String get() = prefs.getString(KEY_USER, "").orEmpty()

    /** Токен или null: не подключено либо Keystore не смог расшифровать (после сброса защиты экрана). */
    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.let { open(it) }

    /** Подключено ли устройство — без обращения к Keystore, чтобы не тормозить запуск экрана. */
    val linked: Boolean get() = server.isNotBlank() && prefs.contains(KEY_TOKEN)

    fun save(server: String, token: String, username: String = "") {
        prefs.edit().putString(KEY_SERVER, server).putString(KEY_TOKEN, seal(token))
            .putString(KEY_USER, username).apply()
    }

    fun saveUsername(username: String) {
        prefs.edit().putString(KEY_USER, username).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun open(sealed: String): String? = runCatching {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_SIZE))
        String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE))
    }.getOrNull()

    private companion object {
        const val FILE = "radar_bot_account"
        const val KEY_SERVER = "server"
        const val KEY_TOKEN = "token"
        const val KEY_USER = "username"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "hydra_radar_bot_account"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
