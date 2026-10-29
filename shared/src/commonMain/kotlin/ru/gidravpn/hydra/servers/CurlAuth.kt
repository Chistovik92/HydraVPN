package ru.gidravpn.hydra.servers

/**
 * Токен API панели — не в командной строке `curl` (0.8.2-rc2, R3 из аудита): аргументы любого процесса видны всем пользователям
 * сервера через `ps`. Токен кладётся в файл конфигурации `curl -K` (0600, во время вызова) и удаляется сразу после. Ключ `-K` есть во
 * всех curl (в отличие от `-H @файл`, который появился в 7.55 и недоступен на CentOS 7).
 */
object CurlAuth {
    /** Содержимое файла `-K`: одна строка `header = "Authorization: Bearer …"` с экранированием `\` и `"`. */
    fun config(token: String): String = "header = \"Authorization: Bearer ${token.replace("\\", "\\\\").replace("\"", "\\\"")}\"\n"

    /** Выполняет [work] с файлом `-K` ([work] получает его путь); файл создаётся одним `upload` (0600) и потом удаляется. */
    fun <T> withConfig(ssh: SshSession, token: String, work: (path: String) -> T): T {
        val path = "/tmp/.hydra-curl-${XuiPanel.random(8).lowercase()}"
        ssh.upload(config(token).toByteArray(), path, 384)
        try { return work(path) } finally { runCatching { ssh.exec("rm -f $path") } }
    }
}
