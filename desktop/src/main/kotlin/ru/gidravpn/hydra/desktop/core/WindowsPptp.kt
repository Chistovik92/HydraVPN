package ru.gidravpn.hydra.desktop.core

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import java.util.concurrent.TimeUnit

/**
 * PPTP на Windows через встроенный клиент RAS (0.7.19): Hydra создаёт VPN-подключение «Hydra PPTP» (`Add-VpnConnection`),
 * поднимает его `rasdial` и снимает при отключении. GRE обрабатывает сама Windows - raw-сокеты Hydra не нужны.
 *
 * Ограничения: это системное VPN-подключение, весь трафик ОС идёт через него, правила маршрутизации и ядра Hydra не
 * участвуют. Пароль передаётся `rasdial` аргументом - его на мгновение видят другие процессы того же пользователя.
 * PPTP небезопасен (MS-CHAPv2, MPPE/RC4); шифрование «Required» - соединение без него не поднимется.
 * На Linux и macOS встроенного клиента нет (macOS убрала PPTP в 10.12), поэтому [available] только для Windows.
 */
object WindowsPptp {
    const val CONNECTION = "Hydra PPTP"

    val available: Boolean get() = Platform.os == Os.WINDOWS

    @Volatile var active = false
        private set

    /** Создаёт подключение и поднимает его. Ошибка - понятный текст для интерфейса. */
    fun connect(p: ServerProfile): Result<Unit> = runCatching {
        check(available) { "PPTP на ПК поддержан только в Windows (встроенный клиент RAS)" }
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
        val user = extra.optString("username")
        check(user.isNotBlank() && p.uuidOrPassword.isNotBlank()) { "PPTP: нужны имя и пароль (pptp://user:pass@host)" }
        val level = if (extra.optString("mppe").equals("off", true)) "Optional" else "Required"
        val server = p.address.takeIf { it.matches(Regex("^[A-Za-z0-9.:-]{1,253}$")) } ?: error("PPTP: недопустимый адрес сервера")

        // Старое подключение с тем же именем заменяется: параметры могли измениться.
        run(15, "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "Remove-VpnConnection -Name '$CONNECTION' -Force -ErrorAction SilentlyContinue; " +
                "Add-VpnConnection -Name '$CONNECTION' -ServerAddress '$server' -TunnelType Pptp -EncryptionLevel $level " +
                "-AuthenticationMethod MSChapv2 -RememberCredential:\$false -Force")
        val out = run(60, "rasdial", CONNECTION, user, p.uuidOrPassword)
        check(out.exitCode == 0) { "PPTP: ${rasError(out.text)}" }
        active = true
    }

    /** Разрывает подключение и удаляет его (пароль нигде не остаётся). */
    fun disconnect() {
        if (!available) return
        runCatching { run(30, "rasdial", CONNECTION, "/disconnect") }
        runCatching {
            run(15, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Remove-VpnConnection -Name '$CONNECTION' -Force -ErrorAction SilentlyContinue")
        }
        active = false
    }

    /** Подключение всё ещё поднято (для слежения за обрывом). */
    fun isConnected(): Boolean = available && runCatching {
        run(10, "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-VpnConnection -Name '$CONNECTION' -ErrorAction SilentlyContinue).ConnectionStatus").text.trim() == "Connected"
    }.getOrDefault(false)

    private class Out(val exitCode: Int, val text: String)

    private fun run(timeoutSec: Long, vararg cmd: String): Out {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); error("${cmd.first()}: превышено время ожидания") }
        return Out(p.exitValue(), text)
    }

    /** Коды RAS, которые встречаются чаще всего. */
    private fun rasError(text: String): String {
        val code = Regex("\\b(\\d{3,4})\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return when (code) {
            691 -> "сервер отклонил имя или пароль (ошибка 691)"
            800 -> "не удалось связаться с сервером PPTP (ошибка 800): порт 1723 или GRE заблокированы"
            619 -> "порт закрыт (ошибка 619): часто блокируется GRE (протокол 47) у провайдера или роутера"
            741, 742 -> "сервер не поддерживает требуемое шифрование (ошибка $code)"
            else -> text.trim().lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "ошибка rasdial"
        }
    }
}
