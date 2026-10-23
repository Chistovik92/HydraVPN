package ru.gidravpn.hydra.servers

/**
 * Закреплённые установщики панелей (0.8.2-rc2, R1 из аудита): тег и SHA-256 каждого скрипта, который Hydra скачивает на сервер и
 * запускает от root. Раньше ставился «последний выпуск» без проверки содержимого — перезалитый тег или подмена на пути
 * выполнили бы чужой код на сервере пользователя. Теперь ставится проверенный выпуск (пары «тег + хеш» обновляются в Hydra после
 * живого прогона панели на стенде), а скрипт с другим хешем не запускается.
 *
 * Не закрепляются (скачиваются «как есть», у апстрима нет версий): `get.docker.com` и `get.acme.sh`.
 */
object ScriptPins {
    const val XUI3_TAG = "v3.10.2"
    const val SUI_TAG = "v1.6.5"
    const val XUI_TAG = "v1.14.0"

    /** Ключ — `репозиторий@тег/файл`. */
    private val SHA256 = mapOf(
        "MHSanaei/3x-ui@v3.10.2/install.sh" to "9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d",
        "alireza0/s-ui@v1.6.5/install.sh" to "eecf94037e2f14fbdfa75c7a3b4597b428f987c0502e437a501538f8693c4965",
        "alireza0/x-ui@v1.14.0/install.sh" to "309c5d2c5ea9be3851e389933fb1eff53bf08cc6baab5cf4b280fc87726f3568",
        "PasarGuard/scripts@v0.1.10/pasarguard.sh" to "bf6cdb904853b59297d7df481926be8268fe81e403c43ff287f2f27301f4ef83",
        "PasarGuard/scripts@v0.1.10/pg-node.sh" to "abedef14e3d7988ebc126ce58b179c1ab4311fca34da21687eb7e4d1cb2ee1c0",
        "remnawave/backend@3.4.7/docker-compose-prod.yml" to "4f9ca29cbb19c389a1cd6c10e378259dd2ec6bab8e7e06b78851207c2395b8a5",
        "remnawave/backend@3.4.7/.env.sample" to "45be9ce2071db59a50321994607bcb22280425572d0b6c7fb68f8878cbcbb2b7",
    )

    fun sha256(repo: String, ref: String, file: String): String? = SHA256["$repo@$ref/$file"]

    /**
     * Фрагмент shell: сверить скачанный [path] с закреплённым хешем; не совпал — файл удаляется, шаг завершается ошибкой.
     * Для пары, которой нет в таблице (тег не из закреплённых), — пустая строка (проверки нет; так ставят только тесты).
     */
    fun verify(repo: String, ref: String, file: String, path: String): String {
        val sha = sha256(repo, ref, file) ?: return ""
        return "(echo '$sha  $path' | sha256sum -c - >/dev/null 2>&1 || { echo 'ОШИБКА: SHA-256 $file ($repo $ref) не совпал с проверенным в Hydra — скрипт не запущен'; rm -f $path; false; })"
    }
}
