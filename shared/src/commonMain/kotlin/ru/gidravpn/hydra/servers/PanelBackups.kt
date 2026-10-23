package ru.gidravpn.hydra.servers

/**
 * Восстановление базы панели из копий `/root/hydra-backups` (0.8.2-rc2, A3). Копии делает Hydra перед удалением и очисткой сервера
 * ([ServerOps.backupPanelsStep]): `<панель>-<дата>-<время>.db`, где панель — `x-ui` (так называется база и у 3x-ui, и у x-ui), `s-ui`
 * или `pasarguard`. Здесь — разбор списка копий и шаги восстановления; вызовы — в [MyServersManager].
 *
 * Порядок безопасный: копия проверяется (`PRAGMA integrity_check`) до остановки панели, текущая база сохраняется рядом
 * (`<панель>-before-restore.db`), а если панель после подмены не запустилась — прежняя база возвращается на место.
 */
object PanelBackups {
    /** Копия базы на сервере. [stamp] — `ГГГГММДД-ЧЧММСС`, по нему сортируются (новые — первыми). */
    data class Backup(val file: String, val stamp: String) {
        /** «23.10.2026 18:30:05» — для списка. */
        val title: String get() = "${stamp.substring(6, 8)}.${stamp.substring(4, 6)}.${stamp.substring(0, 4)} ${stamp.substring(9, 11)}:${stamp.substring(11, 13)}:${stamp.substring(13, 15)}"
    }

    private val NAME = Regex("^(x-ui|s-ui|pasarguard)-(\\d{8}-\\d{6})\\.db$")

    /** Восстановление из Hydra умеет 3x-ui, x-ui и s-ui (служба systemd); PasarGuard — в Docker, Remnawave — база PostgreSQL: пока нет. */
    fun supports(kind: PanelKind) = kind == PanelKind.XUI3 || kind == PanelKind.XUI || kind == PanelKind.SUI

    /** Имя базы в копиях. */
    private fun key(kind: PanelKind) = if (kind == PanelKind.SUI) "s-ui" else "x-ui"

    fun listCommand() = "ls -1 ${ServerOps.BACKUP_DIR} 2>/dev/null; true"

    /** Копии этой панели из вывода [listCommand]: только имена нужного вида, новые — первыми. */
    fun parse(out: String, kind: PanelKind): List<Backup> = out.lineSequence().map { it.trim() }
        .mapNotNull { f -> NAME.matchEntire(f)?.takeIf { it.groupValues[1] == key(kind) }?.let { Backup(f, it.groupValues[2]) } }
        .sortedByDescending { it.stamp }.toList()

    /** Имя файла из списка — единственное, что подставляется в команды (никаких путей из пользовательского ввода). */
    fun validFile(f: String) = NAME.matches(f)

    private const val ENSURE_SQLITE = "(command -v sqlite3 >/dev/null || (DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 install -y -q sqlite3 || " +
        "dnf install -y sqlite || yum install -y sqlite) >/dev/null 2>&1)"

    fun restoreSteps(kind: PanelKind, file: String): List<Step> {
        require(supports(kind) && validFile(file)) { "Недопустимая копия" }
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
}
