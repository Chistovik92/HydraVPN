package ru.gidravpn.hydra.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.gidravpn.hydra.ui.theme.ThemeMode

class LauncherIconTest {
    @Test fun explicitChoiceIgnoresTheme() {
        ThemeMode.entries.forEach { theme ->
            assertEquals("ru.gidravpn.hydra.LauncherOcean", LauncherIcon.aliasFor(LauncherIconChoice.OCEAN, theme))
            assertEquals("ru.gidravpn.hydra.LauncherAmber", LauncherIcon.aliasFor(LauncherIconChoice.AMBER, theme))
        }
    }

    @Test fun followThemeMapsStealthOnly() {
        assertEquals("ru.gidravpn.hydra.LauncherStealth", LauncherIcon.aliasFor(LauncherIconChoice.FOLLOW_THEME, ThemeMode.STEALTH))
        assertEquals("ru.gidravpn.hydra.LauncherAmbient", LauncherIcon.aliasFor(LauncherIconChoice.FOLLOW_THEME, ThemeMode.AMOLED))
        assertEquals("ru.gidravpn.hydra.LauncherAmbient", LauncherIcon.aliasFor(LauncherIconChoice.FOLLOW_THEME, ThemeMode.MATERIAL_YOU))
    }
}
