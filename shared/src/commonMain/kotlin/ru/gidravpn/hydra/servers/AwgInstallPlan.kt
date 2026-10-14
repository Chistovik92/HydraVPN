package ru.gidravpn.hydra.servers

/** Файл серверной сборки (собирается в нашем CI, SHA-256 публикуется рядом). */
class BundleFile(val name: String, val data: ByteArray, val sha256: String)

/** Серверные бинари под одну архитектуру: `amneziawg-go` (userspace), `awg`, `awg-quick`. */
class AwgBundle(val arch: String, val files: Map<String, BundleFile>) {
    fun file(name: String) = files[name] ?: error("В сборке для $arch нет файла $name")
}

/**
 * План развёртывания своего сервера AmneziaWG (0.8.0): userspace `amneziawg-go`, без модуля ядра и DKMS.
 * Идемпотентен: повторный запуск перезаписывает файлы и перезапускает службу, ключи при этом берутся из [spec].
 * Порт в файрволе открывается только при [openFirewall] (явное согласие пользователя).
 */
object AwgInstallPlan {
    const val BIN_DIR = "/usr/local/bin"
    const val CONF_DIR = "/etc/amnezia/amneziawg"
    const val SERVICE = "hydra-awg"

    private val NAT_UP = "iptables -I FORWARD -i %i -j ACCEPT; iptables -I FORWARD -o %i -j ACCEPT; " +
        "iptables -t nat -A POSTROUTING -s SUBNET.0/24 -o \$(ip -o -4 route show to default | awk '{print \$5; exit}') -j MASQUERADE"
    private val NAT_DOWN = NAT_UP.replace("-I FORWARD", "-D FORWARD").replace("-A POSTROUTING", "-D POSTROUTING")

    fun unit(spec: AwgServerSpec): String = """
        [Unit]
        Description=Hydra AmneziaWG (${spec.iface})
        After=network-online.target
        Wants=network-online.target

        [Service]
        Type=oneshot
        RemainAfterExit=yes
        Environment=WG_QUICK_USERSPACE_IMPLEMENTATION=amneziawg-go
        Environment=PATH=$BIN_DIR:/usr/sbin:/usr/bin:/sbin:/bin
        ExecStart=$BIN_DIR/awg-quick up $CONF_DIR/${spec.iface}.conf
        ExecStop=$BIN_DIR/awg-quick down $CONF_DIR/${spec.iface}.conf

        [Install]
        WantedBy=multi-user.target
    """.trimIndent() + "\n"

    fun conf(spec: AwgServerSpec, clients: List<AwgClient>): String = spec.serverConf(
        clients,
        listOf(
            "PostUp = " + NAT_UP.replace("SUBNET", spec.subnet),
            "PostDown = " + NAT_DOWN.replace("SUBNET", spec.subnet),
        ),
    )

    fun steps(spec: AwgServerSpec, clients: List<AwgClient>, bundle: AwgBundle, openFirewall: Boolean, firewall: String): List<Step> {
        val iface = spec.iface
        val confPath = "$CONF_DIR/$iface.conf"
        return buildList {
            add(Step("stop-old", "Остановить прежнюю службу (если есть)", "systemctl stop $SERVICE 2>/dev/null; true"))
            add(Step("dirs", "Создать каталоги", "mkdir -p $CONF_DIR && chmod 700 $CONF_DIR", rollback = null))
            for ((name, mode) in listOf("amneziawg-go" to 493, "awg" to 493, "awg-quick" to 493)) {
                val f = bundle.file(name)
                add(Step("up-$name", "Загрузить $name", upload = Step.Upload("$BIN_DIR/$name", f.data, mode, f.sha256),
                    rollback = "rm -f $BIN_DIR/$name"))
            }
            add(Step("deps", "Проверить зависимости (iptables, iproute2, bash)",
                "command -v iptables >/dev/null && command -v ip >/dev/null && command -v bash >/dev/null || " +
                    "(apt-get install -y iptables iproute2 bash || dnf install -y iptables iproute bash || apk add iptables iproute2 bash)",
                failHint = "Не удалось поставить iptables/iproute2/bash"))
            add(Step("conf", "Записать $confPath (ключи не показываются)",
                upload = Step.Upload(confPath, conf(spec, clients).toByteArray(), 384),
                rollback = "rm -f $confPath"))
            add(Step("forward", "Включить пересылку пакетов (ip_forward)",
                "printf 'net.ipv4.ip_forward=1\\nnet.ipv6.conf.all.forwarding=1\\n' > /etc/sysctl.d/99-hydra-awg.conf && sysctl -p /etc/sysctl.d/99-hydra-awg.conf",
                rollback = "rm -f /etc/sysctl.d/99-hydra-awg.conf"))
            add(Step("unit", "Создать службу systemd $SERVICE",
                upload = Step.Upload("/etc/systemd/system/$SERVICE.service", unit(spec).toByteArray(), 420),
                rollback = "systemctl disable --now $SERVICE; rm -f /etc/systemd/system/$SERVICE.service; systemctl daemon-reload"))
            if (openFirewall) ServerOps.openPortStep(firewall, "${spec.port}/udp")?.let(::add)
            add(Step("start", "Запустить службу", "systemctl daemon-reload && systemctl enable $SERVICE && systemctl restart $SERVICE",
                rollback = "systemctl disable --now $SERVICE", failHint = "Служба не запустилась (journalctl -u $SERVICE)"))
            add(Step("verify", "Проверить: интерфейс $iface поднят",
                "WG_QUICK_USERSPACE_IMPLEMENTATION=amneziawg-go $BIN_DIR/awg show $iface",
                accept = { it.ok && it.out.contains("listening port: ${spec.port}") },
                failHint = "Интерфейс не слушает порт ${spec.port}"))
            addAll(ServerOps.awgStateSteps(spec, clients))
        }
    }

    /** Полное удаление сервера (кнопка «Удалить сервер»). */
    fun uninstallSteps(spec: AwgServerSpec): List<Step> = listOf(
        Step("stop", "Остановить службу", "systemctl disable --now $SERVICE 2>/dev/null; true"),
        Step("rm", "Удалить файлы",
            "rm -f /etc/systemd/system/$SERVICE.service $CONF_DIR/${spec.iface}.conf /etc/sysctl.d/99-hydra-awg.conf " +
                "$BIN_DIR/amneziawg-go $BIN_DIR/awg $BIN_DIR/awg-quick ${ServerOps.AWG_STATE} && systemctl daemon-reload; " +
                "rmdir $CONF_DIR /etc/amnezia 2>/dev/null; true"),
        Step("fw-close", "Закрыть порт ${spec.port}/udp, если его открывала Hydra", ServerOps.closePortCommand("${spec.port}/udp")),
    )

    /** Добавление/удаление клиента на живом сервере: переписать конфиг и применить без разрыва других клиентов. */
    fun applyClientsSteps(spec: AwgServerSpec, clients: List<AwgClient>): List<Step> = listOf(
        Step("conf", "Обновить конфиг сервера",
            upload = Step.Upload("$CONF_DIR/${spec.iface}.conf", conf(spec, clients).toByteArray(), 384)),
        Step("sync", "Применить без перезапуска",
            "WG_QUICK_USERSPACE_IMPLEMENTATION=amneziawg-go bash -c '$BIN_DIR/awg syncconf ${spec.iface} <($BIN_DIR/awg-quick strip $CONF_DIR/${spec.iface}.conf)'"),
    ) + ServerOps.awgStateSteps(spec, clients)
}
