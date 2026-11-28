package ru.gidravpn.hydra.servers

/**
 * Закреплённые установщики панелей (0.8.2-rc3): тег и SHA-256 каждого скрипта, который Hydra скачивает на сервер и
 * запускает от root. Раньше ставился «последний выпуск» без проверки содержимого — перезалитый тег или подмена на пути
 * выполнили бы чужой код на сервере пользователя. Теперь ставится проверенный выпуск (пары «тег + хеш» обновляются в Hydra после
 * живого прогона панели на стенде), а скрипт с другим хешем не запускается.
 *
 * Не закрепляются (скачиваются «как есть», у апстрима нет версий): `get.docker.com` и `get.acme.sh`.
 *
 * Хеш — SHA-256 **сырого файла** с `raw.githubusercontent.com` (0.8.4-rc1: пин NX по ошибке был git-blob SHA-1
 * из поля `sha` GitHub API — такие пины никогда не совпадают; страж длины в `ScriptPinsTest`).
 */
object ScriptPins {
    const val XUI3_TAG = "v3.11.0"
    const val SUI_TAG = "v1.6.8"
    const val XUI_TAG = "v1.15.0"
    const val NEXORA_TAG = "v0.2.1"
    /** Репозиторий NX Panel (с 0.1.x репо переехало с `Nexora-VPN/panel`). */
    const val NEXORA_REPO = "nx-panel/panel"

    /** Ключ — `репозиторий@тег/файл`. */
    private val SHA256 = mapOf(
        "MHSanaei/3x-ui@v3.11.0/install.sh" to "9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d",
        "alireza0/s-ui@v1.6.8/install.sh" to "dd006096233be273514f476fcc804b17c158d9133de5192f86a61c80415abcdb",
        "alireza0/x-ui@v1.15.0/install.sh" to "309c5d2c5ea9be3851e389933fb1eff53bf08cc6baab5cf4b280fc87726f3568",
        "nx-panel/panel@v0.2.1/install.sh" to "d83493dc3dc37c57b85528bafbcc7ef0281cb53c4e40e57d0d4b9c80965501c2",
        "PasarGuard/scripts@v0.1.11/pasarguard.sh" to "8125699258ba272b1fce5915c0f18f593fe1734a9961f2fad3579af3a8969743",
        "PasarGuard/scripts@v0.1.11/pg-node.sh" to "abedef14e3d7988ebc126ce58b179c1ab4311fca34da21687eb7e4d1cb2ee1c0",
        "remnawave/backend@3.4.11/docker-compose-prod.yml" to "4f9ca29cbb19c389a1cd6c10e378259dd2ec6bab8e7e06b78851207c2395b8a5",
        "remnawave/backend@3.4.11/.env.sample" to "45be9ce2071db59a50321994607bcb22280425572d0b6c7fb68f8878cbcbb2b7",
    )

    fun sha256(repo: String, ref: String, file: String): String? = SHA256["$repo@$ref/$file"]

    /** Все закреплённые хеши — страж длины/алфавита в ScriptPinsTest (ловит git-blob SHA-1 из GitHub API). */
    val pinnedHashes: Collection<String> get() = SHA256.values

    /**
     * Фрагмент shell: сверить скачанный [path] с закреплённым хешем; не совпал — файл удаляется, шаг завершается ошибкой.
     * Для пары, которой нет в таблице (тег не из закреплённых), — пустая строка (проверки нет; так ставят только тесты).
     */
    fun verify(repo: String, ref: String, file: String, path: String): String {
        val sha = sha256(repo, ref, file) ?: return ""
        return "(echo '$sha  $path' | sha256sum -c - >/dev/null 2>&1 || { echo 'ОШИБКА: SHA-256 $file ($repo $ref) не совпал с проверенным в Hydra — скрипт не запущен'; rm -f $path; false; })"
    }
}
