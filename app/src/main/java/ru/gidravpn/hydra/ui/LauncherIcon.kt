package ru.gidravpn.hydra.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ru.gidravpn.hydra.ui.theme.ThemeMode

/**
 * Переключение иконки на рабочем столе через activity-alias.
 *
 * Осознанные ограничения (поэтому по умолчанию выключено, см. тумблер в
 * Настройках): на многих лаунчерах — в т.ч. HyperOS — ярлык при смене
 * пересоздаётся: он может на мгновение пропасть, «уехать» в конец списка
 * приложений, а ярлыки/виджеты, вручную вынесенные на рабочий стол, слетают.
 * Само приложение при этом может быть перезапущено системой.
 */
object LauncherIcon {

    private const val ALIAS_AMBIENT = "ru.gidravpn.hydra.LauncherAmbient"
    private const val ALIAS_STEALTH = "ru.gidravpn.hydra.LauncherStealth"

    /**
     * Приводит включённый alias в соответствие теме.
     * Ничего не делает, если нужный ярлык уже активен, — лишнее переключение
     * заставило бы лаунчер пересоздать иконку на пустом месте.
     */
    fun apply(context: Context, mode: ThemeMode) {
        val wanted = if (mode == ThemeMode.STEALTH) ALIAS_STEALTH else ALIAS_AMBIENT
        val other = if (wanted == ALIAS_STEALTH) ALIAS_AMBIENT else ALIAS_STEALTH
        val pm = context.packageManager
        if (isEnabled(pm, context, wanted)) return

        // Сначала включаем нужный, затем гасим прежний: если сделать наоборот,
        // в промежутке не остаётся ни одного LAUNCHER-компонента и приложение
        // пропадает из списка приложений.
        setState(pm, context, wanted, enabled = true)
        setState(pm, context, other, enabled = false)
    }

    /** Возврат к ярлыку по умолчанию (Cyber Emerald) — при выключении тумблера. */
    fun reset(context: Context) = apply(context, ThemeMode.AMBIENT)

    private fun isEnabled(pm: PackageManager, context: Context, alias: String): Boolean =
        pm.getComponentEnabledSetting(ComponentName(context.packageName, alias)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

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
