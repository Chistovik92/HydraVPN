package ru.gidravpn.hydra.data.log

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LogModelTest {

    @Test fun levelsFromRealLogLines() {
        assertEquals(LogLevel.ERROR, LogLevel.of("Ошибка: DeadObjectException"))
        assertEquals(LogLevel.ERROR, LogLevel.of("FATAL[0000] initialize DNS server[0]: invalid server address"))
        assertEquals(LogLevel.ERROR, LogLevel.of("ERROR[0012] outbound/vless[proxy]: dial tcp: timeout"))
        assertEquals(LogLevel.WARN, LogLevel.of("WARN[0000] missing `route.default_domain_resolver`"))
        assertEquals(LogLevel.WARN, LogLevel.of("Kill Switch: трафик заблокирован (тоннель не поднялся)"))
        assertEquals(LogLevel.DEBUG, LogLevel.of("DEBUG[0003] dns: exchange example.com"))
        assertEquals(LogLevel.INFO, LogLevel.of("INFO[0000] inbound/tun[tun-in]: inbound packet connection to 1.1.1.1:53"))
        assertEquals(LogLevel.INFO, LogLevel.of("✓ Соединение установлено"))
        // Слово внутри домена — не уровень.
        assertEquals(LogLevel.INFO, LogLevel.of("INFO connection to errorlogs.example.com:443"))
    }

    @Test fun retentionKeepsTodayPlusPreviousDays() {
        val today = LocalDate.parse("2026-09-19")
        val names = listOf(
            "hydra-2026-09-19.log", "hydra-2026-09-18.log", "hydra-2026-09-17.log",
            "hydra-2026-09-16.log", "hydra-2026-08-01.log", "notes.txt", "hydra-bad.log",
        )
        assertEquals(
            listOf("hydra-2026-09-16.log", "hydra-2026-08-01.log"),
            LogFiles.expired(names, today, keepDays = 3),
        )
        assertEquals(names.filter { it.startsWith("hydra-2026") && it != "hydra-2026-09-19.log" },
            LogFiles.expired(names, today, keepDays = 1))
    }

    @Test fun defaultsArePrivacyFriendly() {
        assertEquals(LogPersistMode.ERRORS, LogPersistMode.fromId(null))
        assertEquals(LogRetention.D3, LogRetention.fromId("garbage"))
    }
}
