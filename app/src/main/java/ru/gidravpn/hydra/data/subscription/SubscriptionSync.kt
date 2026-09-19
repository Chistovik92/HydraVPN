package ru.gidravpn.hydra.data.subscription

import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Синхронизация подписки с тем, что отдал сервер (0.6.21): «как сказал хозяин подписки».
 *  - сервер пропал из ответа → удаляем молча;
 *  - появился новый → добавляем;
 *  - остался → обновляем параметры, но сохраняем id (выбор в UI и последний сервер для
 *    автоподключения не слетают) и измеренный пинг, если адрес не менялся.
 *
 * Совпадение — по устойчивому ключу (протокол + адрес + порт + учётные данные), а не по имени:
 * панели часто меняют названия (флаги, остаток трафика в имени), а сам сервер тот же.
 * При нескольких одинаковых ключах они сопоставляются по порядку.
 */
object SubscriptionSync {

    data class Plan(
        val toInsert: List<ServerProfile>,
        val toUpdate: List<ServerProfile>,
        val toDelete: List<ServerProfile>,
    )

    fun key(p: ServerProfile) = listOf(p.protocolId, p.address.lowercase(), p.port, p.uuidOrPassword).joinToString("|")

    fun plan(subId: Long, existing: List<ServerProfile>, incoming: List<ServerProfile>): Plan {
        val pool = existing.groupBy(::key).mapValues { ArrayDeque(it.value) }
        val insert = mutableListOf<ServerProfile>()
        val update = mutableListOf<ServerProfile>()
        for (fresh in incoming) {
            val old = pool[key(fresh)]?.removeFirstOrNull()
            if (old == null) {
                insert += fresh.copy(id = 0, subscriptionId = subId)
            } else {
                val next = fresh.copy(id = old.id, subscriptionId = subId, pingMs = old.pingMs, flag = old.flag)
                if (next != old) update += next
            }
        }
        val delete = pool.values.flatten()
        return Plan(insert, update, delete)
    }
}
