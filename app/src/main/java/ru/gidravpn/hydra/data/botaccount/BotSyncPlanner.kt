package ru.gidravpn.hydra.data.botaccount

import ru.gidravpn.hydra.data.model.Subscription

/**
 * Сопоставление подписок из бота «Радар» с подписками в приложении — без БД и сети, для JVM-тестов.
 *
 * Связь — по `botPanel` (идентификатор панели в боте), а не по URL: у панели `url` может смениться,
 * и без привязки приложение завело бы вторую подписку, оставив старую мёртвой. Подписки, заведённые
 * до 0.6.27.2 (botPanel пуст), подхватываются по совпадению URL и привязываются.
 * Ничего не удаляется: решение об этом остаётся за человеком.
 */
object BotSyncPlanner {

    /** Пометка в [Subscription.lastError]: подписку отключили в боте (`enabled = false`). */
    const val DISABLED_MARK = "отключена в боте"

    sealed interface Step {
        /** Подписки ещё нет — завести. */
        data class Add(val item: BotSubscription) : Step
        /** Подписка есть (по панели или по URL) — привязать, при смене `url` переписать, обновить. */
        data class Update(val sub: Subscription, val item: BotSubscription) : Step
        /** В боте подписка выключена — остановить автообновление и показать причину. */
        data class Disable(val sub: Subscription) : Step
    }

    fun plan(existing: List<Subscription>, items: List<BotSubscription>): List<Step> {
        val steps = mutableListOf<Step>()
        val claimed = mutableSetOf<Long>()
        for (item in items) {
            val sub = existing.firstOrNull { it.id !in claimed && it.botPanel.isNotEmpty() && it.botPanel == item.panel }
                ?: existing.firstOrNull {
                    it.id !in claimed && it.botPanel.isEmpty() && item.url.isNotBlank() && it.url.trim() == item.url.trim()
                }
            if (sub != null) claimed += sub.id
            when {
                item.importable -> steps += if (sub != null) Step.Update(sub, item) else Step.Add(item)
                sub != null && !item.enabled && item.linkKind == "subscription" && sub.lastError != DISABLED_MARK ->
                    steps += Step.Disable(sub)
                // panel_error / no_link / missing — временное состояние бота, подписку не трогаем.
            }
        }
        return steps
    }
}
