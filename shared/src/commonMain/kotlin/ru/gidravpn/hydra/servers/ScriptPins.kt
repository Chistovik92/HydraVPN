package ru.gidravpn.hydra.servers

/**
 * Закреплённые установщики панелей (0.8.2-rc3): тег и SHA-256 каждого скрипта, который Hydra скачивает на сервер и
 * запускает от root. Раньше ставился «последний выпуск» без проверки содержимого — перезалитый тег или подмена на пути
 * выполнили бы чужой код на сервере пользователя. Теперь ставится проверенный выпуск (пары «тег + хеш» обновляются в Hydra после
 * живого прогона панели на стенде), а скрипт с другим хешем не запускается.
 *
 * Не закрепляются (скачиваются «как есть», у апстрима нет версий): `get.docker.com` и `get.acme.sh`.
 */
object ScriptPins {
    const val XUI3_TAG = "v3.11.0"
    const val SUI_TAG = "v1.6.8"
    const val XUI_TAG = "v1.15.0"
    const val NEXORA_TAG = "v0.1.2"
    /** Репозиторий NX Panel (с 0.1.x репо переехало с `Nexora-VPN/panel`). */
    const val NEXORA_REPO = "nx-panel/panel"

    /** Ключ — `репозиторий@тег/файл`. */
    private val SHA256 = mapOf(
        "MHSanaei/3x-ui@v3.11.0/install.sh" to "e81707f6cced0c4144bcf420b1704ec7cba71650ab66ff3ec5c0ddedf418c5db",
        "alireza0/s-ui@v1.6.8/install.sh" to "dd006096233be273514f476fcc804b17c158d9133de5192f86a61c80415abcdb",
        "alireza0/x-ui@v1.15.0/install.sh" to "c22b96dbc0fcd016d6f9ab2065aff4c04d2ecb75844984410a190049344253dc",
        "nx-panel/panel@v0.1.2/install.sh" to "3219a450b02abcbd75cfba8626898e787999acff",
        "PasarGuard/scripts@v0.1.11/pasarguard.sh" to "8125699258ba272b1fce5915c0f18f593fe1734a9961f2fad3579af3a8969743",
        "PasarGuard/scripts@v0.1.11/pg-node.sh" to "abedef14e3d7988ebc126ce58b179c1ab4311fca34da21687eb7e4d1cb2ee1c0",
        "remnawave/backend@3.4.11/docker-compose-prod.yml" to "4f9ca29cbb19c389a1cd6c10e378259dd2ec6bab8e7e06b78851207c2395b8a5",
        "remnawave/backend@3.4.11/.env.sample" to "45be9ce2071db59a50321994607bcb22280425572d0b6c7fb68f8878cbcbb2b7",
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
