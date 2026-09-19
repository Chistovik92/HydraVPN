package ru.gidravpn.hydra.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ru.gidravpn.hydra.ui.theme.ThemeMode

/**
 * Ярлык на рабочем столе — вариант, который выбирает пользователь
 * (Настройки → Тема → Иконка приложения).
 */
enum class LauncherIconChoice {
    /** Иконка следует за темой: Stealth → багровая, остальные → изумрудная. */
    FOLLOW_THEME,
    AMBIENT,
    STEALTH,
    OCEAN,
    AMBER,
}

/**
 * Переключение иконки на рабочем столе через activity-alias.
 *
 * Осознанные ограничения (поэтому по умолчанию иконка не меняется, а меняется
 * только по явному выбору): на многих лаунчерах — в т.ч. HyperOS — ярлык при
 * смене пересоздаётся: он может на мгновение пропасть, «уехать» в конец списка
 * приложений, а ярлыки/виджеты, вручную вынесенные на рабочий стол, слетают.
 * Само приложение при этом может быть перезапущено системой.
 *
 * Всегда включён ровно один alias. Так как alias-ов теперь четыре, переключение
 * идёт по схеме «сначала включить нужный, потом погасить остальные»: если
 * сделать наоборот, в промежутке не остаётся ни одного LAUNCHER-компонента и
 * приложение пропадает из списка приложений.
 */
object LauncherIcon {

    private const val ALIAS_AMBIENT = "ru.gidravpn.hydra.LauncherAmbient"
    private const val ALIAS_STEALTH = "ru.gidravpn.hydra.LauncherStealth"
    private const val ALIAS_OCEAN = "ru.gidravpn.hydra.LauncherOcean"
    private const val ALIAS_AMBER = "ru.gidravpn.hydra.LauncherAmber"

    private val ALL_ALIASES = listOf(ALIAS_AMBIENT, ALIAS_STEALTH, ALIAS_OCEAN, ALIAS_AMBER)

    /** Какой alias должен быть включён при данном выборе и теме. */
    internal fun aliasFor(choice: LauncherIconChoice, theme: ThemeMode): String = when (choice) {
        LauncherIconChoice.AMBIENT -> ALIAS_AMBIENT
        LauncherIconChoice.STEALTH -> ALIAS_STEALTH
        LauncherIconChoice.OCEAN -> ALIAS_OCEAN
        LauncherIconChoice.AMBER -> ALIAS_AMBER
        LauncherIconChoice.FOLLOW_THEME ->
            if (theme == ThemeMode.STEALTH) ALIAS_STEALTH else ALIAS_AMBIENT
    }

    /**
     * Приводит включённый alias в соответствие выбору. Ничего не делает, если
     * нужный ярлык уже активен, — лишнее переключение заставило бы лаунчер
     * пересоздать иконку на пустом месте.
     */
    fun apply(context: Context, choice: LauncherIconChoice, theme: ThemeMode) {
        val wanted = aliasFor(choice, theme)
        val pm = context.packageManager
        if (isEnabled(pm, context, wanted) && ALL_ALIASES.none { it != wanted && isEnabled(pm, context, it) }) return

        setState(pm, context, wanted, enabled = true)
        ALL_ALIASES.filter { it != wanted }.forEach { setState(pm, context, it, enabled = false) }
    }

    private fun isEnabled(pm: PackageManager, context: Context, alias: String): Boolean {
        val state = pm.getComponentEnabledSetting(ComponentName(context.packageName, alias))
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true
        // DEFAULT = то, что записано в манифесте: включён только Ambient.
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && alias == ALIAS_AMBIENT
    }

    private fun setState(pm: PackageManager, context: Context, alias: String, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, alias),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
