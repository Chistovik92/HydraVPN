package ru.gidravpn.hydra.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RuAppsPresetTest {
    @Test fun picksOnlyInstalledRussianBankingApps() {
        val installed = listOf("ru.rostel", "ru.sberbankmobile", "ru.sberbank.business", "org.telegram.messenger",
            "com.android.chrome", "ru.vtb24.mobilebanking.android", "com.example.ruler")
        assertEquals(setOf("ru.rostel", "ru.sberbankmobile", "ru.sberbank.business", "ru.vtb24.mobilebanking.android"),
            RuAppsPreset.presentIn(installed))
    }

    @Test fun emptyWhenNothingMatches() = assertEquals(emptySet<String>(), RuAppsPreset.presentIn(listOf("com.google.android.gm")))
}
