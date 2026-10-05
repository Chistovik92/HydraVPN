package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.TlsFragmentMode
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.HYDRA_DNS
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.Autostart
import java.awt.Component
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.swing.ButtonGroup
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane

/** Настройки: те же разделы, что в обычной Hydra. */
internal class SettingsPanel(private val c: AppController) : Renderable {
    private val quiet = Quiet()
    private val hint = note("Изменения применятся при следующем подключении.", Warn)

    // ---- режим
    private val proxy = Radio("Системный прокси — HTTP/SOCKS5 на 127.0.0.1, права администратора не нужны", quiet) { c.updateSettings { it.copy(mode = ConnectionMode.PROXY) } }
    private val tun = Radio("TUN — весь трафик через виртуальный адаптер", quiet) { c.updateSettings { it.copy(mode = ConnectionMode.TUN) } }
    private val tunNote = note(when (Platform.os) {
        Os.WINDOWS -> "Нужен запуск Hydra от имени администратора."
        Os.LINUX -> "При первом включении система один раз спросит пароль (права CAP_NET_ADMIN для ядра)."
        Os.MACOS -> "При подключении и отключении macOS спросит пароль администратора."
    })

    // ---- движки
    private val singBox = Check("sing-box — VLESS/REALITY, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard", quiet) { c.setEngine(EngineToggles.Kind.SINGBOX, it) }
    private val xray = Check("Xray-core — VLESS (XTLS Vision, REALITY, XHTTP), VMess, Trojan, Shadowsocks", quiet) { c.setEngine(EngineToggles.Kind.XRAY, it) }
    private val preferXray = Check("Предпочитать Xray для VLESS/VMess/Trojan/Shadowsocks", quiet) { v -> c.updateSettings { it.copy(preferXray = v) } }
    private val olcRtc = Check("olcRTC (BETA) — TCP поверх WebRTC через сервисы видеозвонков", quiet) { c.setEngine(EngineToggles.Kind.OLCRTC, it) }
    private val openFlux = Check("OpenFlux (BETA) — TCP-туннель через сервисы документов и чатов", quiet) { c.setEngine(EngineToggles.Kind.OPENFLUX, it) }
    private val enginesNote = note("")

    // ---- безопасность
    private val kill = Check("Kill switch — не пускать трафик мимо VPN при обрыве (режим прокси: системный прокси остаётся включённым)", quiet) { v -> c.updateSettings { it.copy(killSwitch = v) } }
    private val reconnect = Check("Переподключаться автоматически (через 2, 4, 8, 16, 30 с… до 10 попыток)", quiet) { v -> c.updateSettings { it.copy(autoReconnect = v) } }
    private val autoConnect = Check("Подключаться при запуске Hydra", quiet) { v -> c.updateSettings { it.copy(autoConnect = v) } }
    private val login = Check("Запускать Hydra при входе в систему (свёрнутой в трей)", quiet) { c.setLaunchAtLogin(it) }

    // ---- прокси
    private val port = Field(6, quiet) { t -> t.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> c.updateSettings { it.copy(proxyPort = p) } } }
    private val sysProxy = Check("Включать системный прокси автоматически", quiet) { v -> c.updateSettings { it.copy(setSystemProxy = v) } }

    // ---- раздача в сеть
    private val lan = Check("Раздавать VPN в локальную сеть (прокси с логином и паролем)", quiet) { c.setLanShare(it) }
    private val lanPort = Field(6, quiet) { t -> t.filter(Char::isDigit).toIntOrNull()?.takeIf { it in HotspotSettings.PORT_RANGE }?.let { p -> c.updateLanShare { it.copy(port = p) } } }
    private val lanUser = Field(12, quiet) { t -> if (t.isNotBlank()) c.updateLanShare { it.copy(username = t.trim().take(64)) } }
    private val lanPass = Field(16, quiet) { t -> c.updateLanShare { it.copy(password = t.take(128)) } }
    private val lanBox = column()
    private val lanInfo = note("")

    // ---- аккаунт бота
    private val botServer = Field(24, quiet)
    private val botCode = Field(10, quiet)
    private val botBox = column()
    private val botInfo = bold("")
    private val botLogin = button("Подключить") {
        val code = botCode.text.filter(Char::isDigit)
        if (botServer.text.isBlank() || code.length != 8) c.toast("Укажите адрес сервера бота и 8-значный код из бота")
        else { c.linkBot(botServer.text, code); botCode.text = "" }
    }
    private val botSync = button("Получить подписки") { c.syncBot() }
    private val botOff = button("Отключить") { c.unlinkBot() }

    // ---- DNS
    private val dnsPresets = listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google", "9.9.9.9" to "Quad9", "94.140.14.14" to "AdGuard", "system" to "Системный")
    private val dnsRadios = dnsPresets.map { (v, label) -> Radio(label, quiet) { c.setDns(v) } } +
        Radio("Hydra VPN (приватный)", quiet) { c.setDns(HYDRA_DNS) }
    private val dnsOwn = Field(28, quiet)
    private val dnsBad = note("Не похоже на адрес DNS-сервера", Danger)

    // ---- туннель
    private val frag = TlsFragmentMode.entries.map { m ->
        Radio(when (m) { TlsFragmentMode.OFF -> "Выключено"; TlsFragmentMode.RECORD -> "TLS-записи"; TlsFragmentMode.TCP -> "TCP-сегменты" }, quiet) { c.updateRouting { it.copy(tlsFragment = m) } }
    }
    private val mtu = MtuPreset.entries.map { m ->
        Radio(if (m == MtuPreset.AUTO) "Авто (9000)" else "${m.value}", quiet) { c.updateRouting { it.copy(mtu = m) } }
    }
    private val ipv6 = Check("IPv6 через туннель (выключено — IPv6 в TUN блокируется, без утечек)", quiet) { v -> c.updateRouting { it.copy(ipv6 = v) } }

    // ---- обновления
    private val checkUpdates = Check("Проверять обновления при запуске (запрос к GitHub; скачивание — только по кнопке)", quiet) { v -> c.updateSettings { it.copy(checkUpdates = v) } }

    private val root: javax.swing.JScrollPane = page(
        hint,
        section("Режим подключения", proxy, tun, tunNote),
        section("Движки", note("Какое ядро обслуживает протокол. Маршрутизация, DNS и режимы TUN/прокси работают одинаково с каждым."),
            singBox, xray, preferXray, olcRtc, openFlux, enginesNote),
        section("Безопасность", kill, reconnect, autoConnect, login),
        section("Прокси", row(JLabel("Порт:"), port), sysProxy),
        section("Раздача VPN в локальную сеть",
            note("Другие устройства сети (телефон, ТВ, приставка, старый компьютер) смогут ходить через этот VPN, указав прокси с логином и паролем. " +
                "Порт открывается на всех сетевых адаптерах — брандмауэр ОС может спросить разрешение."),
            lan, lanBox),
        section("Аккаунт Hydra VPN (бот «Радар»)", botBox),
        section("DNS", row(*dnsRadios.take(3).toTypedArray()), row(*dnsRadios.drop(3).toTypedArray()),
            JLabel("Свой DNS (IP, https://…/dns-query, tls://…, udp://…):"),
            row(dnsOwn, button("Сохранить") { if (!c.setDns(dnsOwn.text)) dnsBad.isVisible = true else dnsBad.isVisible = false }), dnsBad),
        section("Туннель",
            JLabel("Фрагментация TLS ClientHello (обход DPI; только sing-box, VLESS/VMess/Trojan с TLS):"), row(*frag.toTypedArray()),
            JLabel("MTU адаптера TUN (меньше — если сайты «висят» на загрузке):"), row(*mtu.toTypedArray()), ipv6),
        section("Проверка утечек", note("Подключитесь и откройте проверку в браузере (в режиме прокси — браузером, который использует системный прокси)."),
            row(button("ipleak.net") { openUrl("https://ipleak.net/") }, button("browserleaks.com") { openUrl("https://browserleaks.com/dns") })),
        section("Резервная копия",
            note("Серверы, подписки, настройки и профили в одном файле. В файле пароли серверов — храните его в надёжном месте."),
            row(button("Сохранить в файл…") { chooseFile(save = true)?.let { c.exportBackup(it) } },
                restoreButton())),
        section("О программе",
            JLabel("Hydra ${Platform.version} (Classic) · sing-box 1.12.25 · Xray-core 26.9.30 · ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"),
            note("Данные: ${Platform.dataDir.absolutePath}"),
            note("Classic — упрощённая оболочка для 32-битных систем и Windows 7: те же ядра и настройки, что у обычной Hydra. " +
                "Нет управления роутерами HydraVPN for Router (оно есть в обычной Hydra и на Android). " +
                "AmneziaWG, SSTP и L2TP — только на Android."),
            checkUpdates, row(button("Проверить обновления сейчас") { c.checkForUpdates(manual = true) })),
    )
    val component: Component get() = root

    private fun restoreButton() = button("Восстановить из файла…") {
        val f = chooseFile(save = false) ?: return@button
        val ok = JOptionPane.showConfirmDialog(root, "Текущие серверы, подписки и настройки будут заменены содержимым файла «${f.name}».",
            "Восстановить из резервной копии?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
        if (ok == JOptionPane.OK_OPTION) c.importBackup(f)
    }

    init {
        ButtonGroup().also { it.add(proxy); it.add(tun) }
        ButtonGroup().also { g -> dnsRadios.forEach { g.add(it) } }
        ButtonGroup().also { g -> frag.forEach { g.add(it) } }
        ButtonGroup().also { g -> mtu.forEach { g.add(it) } }
        dnsBad.isVisible = false
        lanBox.add(row(JLabel("Порт:"), lanPort, JLabel("Логин:"), lanUser))
        lanBox.add(row(JLabel("Пароль:"), lanPass, button("Новый пароль") { c.regenerateLanPassword() }))
        lanBox.add(lanInfo)
    }

    private fun chooseFile(save: Boolean): File? {
        val ch = JFileChooser()
        ch.dialogTitle = if (save) "Сохранить резервную копию" else "Открыть резервную копию"
        if (save) ch.selectedFile = File("hydra-backup.json")
        val r = if (save) ch.showSaveDialog(root) else ch.showOpenDialog(root)
        return if (r == JFileChooser.APPROVE_OPTION) ch.selectedFile else null
    }

    private fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    override fun render(ui: UiState) {
        val s = ui.data.settings
        val r = s.routing
        hint.isVisible = ui.active
        proxy.set(s.mode == ConnectionMode.PROXY); tun.set(s.mode == ConnectionMode.TUN)

        val xrayBuilt = c.xrayAvailable
        singBox.set(s.singBoxEnabled)
        xray.set(s.xrayEnabled && xrayBuilt, xrayBuilt)
        xray.text = if (xrayBuilt) "Xray-core — VLESS (XTLS Vision, REALITY, XHTTP), VMess, Trojan, Shadowsocks" else "Xray-core — ядро не найдено в пакете"
        preferXray.set(s.preferXray, xrayBuilt && s.xrayEnabled && s.singBoxEnabled)
        olcRtc.set(s.olcRtcEnabled && c.olcRtcAvailable, c.olcRtcAvailable)
        if (!c.olcRtcAvailable) olcRtc.text = "olcRTC (BETA) — не входит в эту сборку (нужен Go 1.21+, недоступен для Windows 7)"
        openFlux.set(s.openFluxEnabled && c.openFluxAvailable, c.openFluxAvailable)
        if (!c.openFluxAvailable) openFlux.text = "OpenFlux (BETA) — не входит в эту сборку (нужен Go 1.21+, недоступен для Windows 7)"
        enginesNote.text = when {
            !s.singBoxEnabled && !(s.xrayEnabled && xrayBuilt) -> "Все движки выключены — подключиться не к чему."
            !s.singBoxEnabled -> "Hysteria2, TUIC и WireGuard без sing-box недоступны."
            else -> ""
        }
        enginesNote.isVisible = enginesNote.text.isNotEmpty()

        kill.set(s.killSwitch); reconnect.set(s.autoReconnect); autoConnect.set(s.autoConnect)
        login.set(s.launchAtLogin, Autostart.available)
        port.sync(s.proxyPort.toString()); sysProxy.set(s.setSystemProxy)

        val l = s.lanShare
        lan.set(l.enabled)
        lanBox.isVisible = l.enabled
        lanPort.sync(l.port.toString()); lanUser.sync(l.username); lanPass.sync(l.password)
        lanInfo.text = buildList {
            if (s.mode == ConnectionMode.PROXY && l.port == s.proxyPort) add("Порт совпадает с локальным прокси — выберите другой.")
            else if (!l.isUsable) add("Раздача не поднимется, пока не заданы порт, логин и пароль (от ${HotspotSettings.MIN_PASSWORD} символов).")
            else localIps().takeIf { it.isNotEmpty() }?.let { ips ->
                add("На устройстве укажите HTTP- или SOCKS5-прокси: " + ips.joinToString(" или ") { "$it:${l.port}" } + ", логин «${l.username}».")
            }
        }.joinToString(" ")

        renderBot(ui)

        dnsPresets.forEachIndexed { i, (v, _) -> dnsRadios[i].set(r.dns == v) }
        dnsRadios.last().set(r.dns == HYDRA_DNS, c.hydraDnsAvailable)
        dnsRadios.last().isVisible = c.hydraDnsAvailable
        dnsOwn.sync(r.dns.takeIf { it != HYDRA_DNS && dnsPresets.none { p -> p.first == it } } ?: "")

        TlsFragmentMode.entries.forEachIndexed { i, m -> frag[i].set(r.tlsFragment == m) }
        MtuPreset.entries.forEachIndexed { i, m -> mtu[i].set(r.mtu == m) }
        ipv6.set(r.ipv6)
        checkUpdates.set(s.checkUpdates)
    }

    private var botLinked: Any? = Unit

    private fun renderBot(ui: UiState) {
        val link = ui.data.bot
        // Раздел перестраиваем только при входе/выходе: иначе поля теряли бы фокус при каждом обновлении состояния.
        if (botLinked != (link != null) || botBox.componentCount == 0) {
            botLinked = link != null
            botBox.removeAll()
            if (link != null) {
                botBox.add(botInfo)
                botBox.add(row(botSync, botOff))
            } else {
                botBox.add(note("Войдите в аккаунт бота «Радар», чтобы получить выданные вам подписки. В боте откройте «VPN» → «Подключить приложение» и введите код здесь."))
                botBox.add(row(JLabel("Адрес сервера бота:"), botServer))
                botBox.add(row(JLabel("Код из бота (8 цифр):"), botCode, botLogin))
            }
            botBox.revalidate(); botBox.repaint()
        }
        if (link != null) {
            botInfo.text = (if (link.username.isNotBlank()) "Подключено как @${link.username}" else "Подключено к боту") +
                (link.panels?.let { " · панелей: $it" } ?: "") + " · ${link.server}"
            botSync.isEnabled = !ui.botBusy; botOff.isEnabled = !ui.botBusy
        } else {
            botLogin.isEnabled = !ui.botBusy
        }
    }
}
