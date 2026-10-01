package ru.gidravpn.hydra.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.router.RouterLink
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Сопряжённые роутеры HydraVPN for Router. Токен управления даёт полный контроль над роутером,
 * поэтому в DataStore он лежит только зашифрованным (AES-GCM, ключ в Android Keystore, не
 * покидает устройство). Если ключ пропал (сброс блокировки экрана, восстановление на другой
 * телефон), такой роутер пропускается — его нужно сопрячь заново.
 */
class RouterRepository(private val context: Context) {

    private val KEY = stringPreferencesKey("routers_v1")

    suspend fun load(): List<RouterLink> {
        val raw = context.routersStore.data.first()[KEY] ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val token = decrypt(o.optString("token")) ?: return@mapNotNull null
            RouterLink(
                host = o.optString("host"),
                port = o.optInt("port"),
                token = token,
                tls = o.optBoolean("tls"),
                fingerprint = o.optString("fp").takeIf { it.isNotEmpty() },
                name = o.optString("name").ifEmpty { o.optString("host") },
            ).takeIf { it.host.isNotEmpty() && it.port in 1..65535 }
        }.distinctBy { it.baseUrl }
    }

    suspend fun save(list: List<RouterLink>) {
        val arr = JSONArray()
        list.forEach { l ->
            val enc = encrypt(l.token) ?: return@forEach
            arr.put(JSONObject().put("host", l.host).put("port", l.port).put("tls", l.tls)
                .put("fp", l.fingerprint.orEmpty()).put("name", l.name).put("token", enc))
        }
        context.routersStore.edit { it[KEY] = arr.toString() }
    }

    // ------------------------------------------------------------------ Keystore
    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
    }.getOrNull()

    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key() ?: return null) }
        Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(enc: String): String? = runCatching {
        val b = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key() ?: return null, GCMParameterSpec(128, b, 0, IV_LEN))
        }
        String(c.doFinal(b, IV_LEN, b.size - IV_LEN))
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hydra_router_tokens"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
