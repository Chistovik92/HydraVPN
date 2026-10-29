package ru.gidravpn.hydra.servers

/**
 * Восстановление базы панели из копий `/root/hydra-backups` (0.8.2-rc2, A3). Копии делает Hydra перед удалением и очисткой сервера
 * ([ServerOps.backupPanelsStep]): `<панель>-<дата>-<время>.db`, где панель — `x-ui` (так называется база и у 3x-ui, и у x-ui), `s-ui`
 * или `pasarguard`; Remnawave — `<панель>-<дата>-<время>.sql`. Здесь — разбор списка копий и шаги восстановления; вызовы — в [MyServersManager].
 *
 * Порядок безопасный: SQLite-копия проверяется (`PRAGMA integrity_check`) до остановки панели, SQL-копия Remnawave —
 * заголовком `pg_dump`. Текущая база сохраняется рядом (`<панель>-before-restore.db/.sql`), а если панель после подмены
 * не запустилась — прежняя база возвращается на место.
 */
object PanelBackups {
    /** Копия базы на сервере. [stamp] — `ГГГГММДД-ЧЧММСС`, по нему сортируются (новые — первыми). */
    data class Backup(val file: String, val stamp: String) {
        /** «23.10.2026 18:30:05» — для списка. */
        val title: String get() = "${stamp.substring(6, 8)}.${stamp.substring(4, 6)}.${stamp.substring(0, 4)} ${stamp.substring(9, 11)}:${stamp.substring(11, 13)}:${stamp.substring(13, 15)}"
    }

    private val NAME = Regex("^(x-ui|s-ui|pasarguard|remnawave)-(\\d{8}-\\d{6})\\.(db|sql)$")

    /** Восстановление из Hydra умеет SQLite-панели, PasarGuard (SQLite на общем томе Docker) и Remnawave (PostgreSQL). */
    fun supports(kind: PanelKind) = kind == PanelKind.XUI3 || kind == PanelKind.XUI || kind == PanelKind.SUI ||
        kind == PanelKind.PASARGUARD || kind == PanelKind.REMNAWAVE

    /** Имя базы в копиях. */
    fun key(kind: PanelKind) = when (kind) {
        PanelKind.SUI -> "s-ui"
        PanelKind.PASARGUARD -> "pasarguard"
        PanelKind.REMNAWAVE -> "remnawave"
        PanelKind.NEXORA -> "nexora"
        else -> "x-ui"
    }

    fun listCommand() = "ls -1 ${ServerOps.BACKUP_DIR} 2>/dev/null; true"

    /** Копии этой панели из вывода [listCommand]: только имена нужного вида, новые — первыми. */
    fun parse(out: String, kind: PanelKind): List<Backup> = out.lineSequence().map { it.trim() }
        .mapNotNull { f -> NAME.matchEntire(f)?.takeIf { it.groupValues[1] == key(kind) && it.groupValues[3] == if (kind == PanelKind.REMNAWAVE) "sql" else "db" }?.let { Backup(f, it.groupValues[2]) } }
        .sortedByDescending { it.stamp }.toList()

    /** Имя файла из списка — единственное, что подставляется в команды (никаких путей из пользовательского ввода). */
    fun validFile(f: String) = NAME.matches(f)

    private const val ENSURE_SQLITE = "(command -v sqlite3 >/dev/null || (DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 install -y -q sqlite3 || " +
        "dnf install -y sqlite || yum install -y sqlite) >/dev/null 2>&1)"

    fun restoreSteps(kind: PanelKind, file: String): List<Step> {
        require(validFile(file)) { "Недопустимая копия" }
        val match = NAME.matchEntire(file)!!
        require(match.groupValues[1] == key(kind) && match.groupValues[3] == if (kind == PanelKind.REMNAWAVE) "sql" else "db") { "Копия другой панели" }
        return when (kind) {
            PanelKind.PASARGUARD -> pasarguardSteps(file)
            PanelKind.REMNAWAVE -> remnawaveSteps(file)
            PanelKind.NEXORA -> error("Восстановление Nexora выполняется из копии каталога /var/opt/nexora")
            else -> sqliteSteps(kind, file)
        }
    }

    private fun sqliteSteps(kind: PanelKind, file: String): List<Step> {
        val db = OtherPanels.dbPath(kind); val svc = OtherPanels.serviceOf(kind)
        val src = "${ServerOps.BACKUP_DIR}/$file"; val safe = "${ServerOps.BACKUP_DIR}/${key(kind)}-before-restore.db"
        return listOf(
            Step("restore-check", "Проверить копию $file", "$ENSURE_SQLITE && test -s $src && [ \"\$(sqlite3 $src 'PRAGMA integrity_check')\" = ok ]",
                failHint = "Копия повреждена или пуста — восстановление не начато, панель не тронута"),
            Step("restore-safe", "Сохранить текущую базу на случай отката", "mkdir -p ${ServerOps.BACKUP_DIR} && cp -a $db $safe && test -s $safe",
                failHint = "Не удалось сохранить текущую базу (нет места?) — восстановление не начато"),
            Step("restore-stop", "Остановить панель", "systemctl stop $svc"),
            Step("restore-copy", "Подменить базу копией", "cp -a $src $db && rm -f $db-wal $db-shm && chmod 600 $db",
                failHint = "Не удалось подменить базу"),
            // не запустилась — прежняя база возвращается тут же, в этом шаге (откат Installer отдельно не вызывается)
            Step("restore-start", "Запустить панель", "systemctl start $svc; sleep 3; if systemctl is-active --quiet $svc; then echo restore-ok; else " +
                "systemctl stop $svc; test -s $safe && cp -a $safe $db && rm -f $db-wal $db-shm; systemctl start $svc; echo restore-rolled-back; fi",
                accept = { r -> r.out.lineSequence().any { it.trim() == "restore-ok" } },
                failHint = "Панель не запустилась с этой копией — прежняя база возвращена"),
        )
    }

    /** PasarGuard: база лежит на общем томе, поэтому меняется на хосте; контейнер перезапускается после подмены. */
    private fun pasarguardSteps(file: String): List<Step> {
        val db = ServerOps.PANEL_DBS.getValue("pasarguard")
        val src = "${ServerOps.BACKUP_DIR}/$file"; val safe = "${ServerOps.BACKUP_DIR}/pasarguard-before-restore.db"
        val up = "cd ${PasarGuardPanel.DIR} && docker compose -p pasarguard up -d"
        return listOf(
            Step("restore-check", "Проверить копию $file", "$ENSURE_SQLITE && test -s $src && [ \"\$(sqlite3 $src 'PRAGMA integrity_check')\" = ok ]",
                failHint = "Копия повреждена или пуста — восстановление не начато, панель не тронута"),
            Step("restore-safe", "Сохранить текущую базу на случай отката", "$up && test -s $db && cp -a $db $safe && test -s $safe",
                failHint = "Не удалось сохранить текущую базу — восстановление не начато"),
            Step("restore-stop", "Остановить контейнер панели", "cd ${PasarGuardPanel.DIR} && docker compose -p pasarguard stop pasarguard"),
            Step("restore-copy", "Подменить базу копией", "cp -a $src $db && rm -f $db-wal $db-shm && chmod 600 $db",
                failHint = "Не удалось подменить базу"),
            Step("restore-start", "Запустить панель", "$up; sleep 5; if curl -s -o /dev/null --max-time 10 http://127.0.0.1:${PasarGuardPanel.PORT}/api/admin/token -X POST; then echo restore-ok; else " +
                "cd ${PasarGuardPanel.DIR}; docker compose -p pasarguard stop pasarguard; test -s $safe && cp -a $safe $db && rm -f $db-wal $db-shm; $up; echo restore-rolled-back; fi",
                accept = { r -> r.out.lineSequence().any { it.trim() == "restore-ok" } },
                failHint = "Панель не запустилась с этой копией — прежняя база возвращена"),
        )
    }

    /** Remnawave: восстановление всего кластера PostgreSQL в пустой каталог, затем атомарная замена тома базы. */
    private fun remnawaveSteps(file: String): List<Step> {
        val src = "${ServerOps.BACKUP_DIR}/$file"; val safe = "${ServerOps.BACKUP_DIR}/remnawave-before-restore.sql"
        val tmp = "${ServerOps.BACKUP_DIR}/remnawave-restore-new.sql"
        val old = "${ServerOps.BACKUP_DIR}/remnawave-before-restore.volume"
        val data = "/var/lib/docker/volumes/remnawave-db-data/_data"
        val stop = "cd ${RemnawavePanel.DIR} && docker compose stop remnawave remnawave-db"
        val swap = "rm -rf $old && mv $data $old && mkdir -m 700 $data && docker run --rm -v remnawave-db-data:/var/lib/postgresql postgres:18.4 chown -R 999:999 /var/lib/postgresql"
        return listOf(
            Step("restore-check", "Проверить копию $file", "test -s $src && head -n 5 $src | grep -q 'PostgreSQL database dump' && grep -q 'PostgreSQL database dump complete' $src",
                failHint = "Копия повреждена, пуста или не является полным pg_dump — восстановление не начато"),
            Step("restore-safe", "Сохранить текущую базу на случай отката",
                "docker start remnawave-db >/dev/null && sleep 3 && docker exec remnawave-db pg_dump -U postgres postgres > $safe && test -s $safe && grep -q 'PostgreSQL database dump complete' $safe && chmod 600 $safe",
                failHint = "Не удалось сохранить текущую базу — восстановление не начато"),
            Step("restore-stop", "Остановить панель и базу", stop),
            Step("restore-copy", "Восстановить копию в новый каталог PostgreSQL",
                "cp -a $src $tmp && chmod 600 $tmp && $swap && docker start remnawave-db && for i in \$(seq 1 30); do docker exec remnawave-db pg_isready -U postgres >/dev/null 2>&1 && break; sleep 2; done && " +
                    "docker exec -i remnawave-db psql -v ON_ERROR_STOP=1 -U postgres postgres < $tmp && docker exec remnawave-db psql -At -U postgres postgres -c 'SELECT 1' | grep -qx 1 && " +
                    "cd ${RemnawavePanel.DIR} && docker compose stop remnawave-db && rm -f $tmp",
                rollback = "cd ${RemnawavePanel.DIR}; docker compose stop remnawave remnawave-db >/dev/null 2>&1; rm -rf $data; test ! -e $data && mv $old $data; rm -f $tmp; true",
                failHint = "PostgreSQL не принял копию — прежний каталог базы будет возвращён"),
            Step("restore-start", "Запустить панель", "cd ${RemnawavePanel.DIR} && docker compose up -d; sleep 8; if curl -s -o /dev/null --max-time 10 ${RemnawaveApi.HEADERS} http://127.0.0.1:${RemnawavePanel.API_PORT}/api/auth/status; then " +
                "rm -rf $old; echo restore-ok; else docker compose stop remnawave remnawave-db; rm -rf $data; mv $old $data; docker compose up -d; echo restore-rolled-back; fi",
                rollback = "cd ${RemnawavePanel.DIR}; docker compose stop remnawave remnawave-db >/dev/null 2>&1; rm -rf $data; test ! -e $data && mv $old $data; docker compose up -d >/dev/null 2>&1; true",
                accept = { r -> r.out.lineSequence().any { it.trim() == "restore-ok" } },
                failHint = "Панель не запустилась с этой копией — прежняя база возвращена"),
        )
    }
}
