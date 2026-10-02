package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Профиль OpenFlux → аргументы клиента `openflux --role client --inbound socks5 …` (как OpenFluxCore на Android).
 * [keyFile] — путь к файлу с общим ключом шифрования (его пишет вызывающий и удаляет после работы); null — без шифрования.
 *
 * `direct` — обычный TCP до своего exit-узла (`openflux --role exit --transports direct:100 --direct-listen host:port
 * --encryption-key-file …`). У апстрима это режим «нескольких транспортов»: флаг `--transport direct` не существует,
 * а ключ обязателен — проверено на настоящем узле (см. OpenFluxLiveTest).
 */
object OpenFluxArgs {
    fun build(profile: ServerProfile, socksPort: Int, keyFile: String?): List<String> {
        val extra = runCatching { JSONObject(profile.extra) }.getOrDefault(JSONObject())
        val transport = profile.transport
        return buildList {
            add("--role"); add("client")
            add("--inbound"); add("socks5")
            add("--socks5"); add("127.0.0.1:$socksPort")
            when {
                transport == "direct" -> { add("--transports"); add("direct:100"); add("--direct-dial"); add(profile.address) }
                transport == "oneme" -> {
                    add("--transport"); add(transport)
                    add("--maxToken"); add(extra.optString("maxToken"))
                    add("--maxUid"); add(extra.optString("maxUid"))
                }
                else -> {
                    add("--transport"); add(transport)
                    if (profile.address.isNotEmpty()) { add("--url"); add(profile.address) }
                }
            }
            extra.optString("codec").takeIf { it == "legacy" }?.let { add("--codec"); add(it) }
            if (keyFile != null && profile.uuidOrPassword.isNotEmpty()) {
                add("--encryption-key-file"); add(keyFile)
            }
        }
    }
}
