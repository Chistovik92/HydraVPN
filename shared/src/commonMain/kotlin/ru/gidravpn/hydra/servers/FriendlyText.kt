package ru.gidravpn.hydra.servers

/** Понятное объяснение: что случилось ([title]) и что делать ([advice]); [raw] — исходный технический текст (под спойлером и для обращения в поддержку). */
data class Friendly(val title: String, val advice: String, val raw: String)

/** Строка проверки сервера для человека: зелёная галочка или красный крест с подсказкой. */
data class CheckItem(val ok: Boolean, val title: String, val help: String)

/**
 * Простые тексты для экранов «Мои серверы» (0.8.0-rc4; из 0.8.1-a): без жаргона — ни «SSH», ни «systemd», ни «NAT» в первой строке.
 * Технические подробности остаются в журнале и под кнопкой «Подробности». Язык: русский по умолчанию, английский — `en = true`
 * (Android выбирает по языку приложения; остальные языки показывают английский).
 */
object FriendlyText {

    private fun t(en: Boolean, ru: String, enText: String) = if (en) enText else ru

    /** Превращает ошибку подключения/установки в объяснение и совет. */
    fun explain(raw: String, en: Boolean = false): Friendly {
        val r = raw.lowercase()
        fun f(ru: String, ruAdvice: String, enTitle: String, enAdvice: String) = Friendly(t(en, ru, enTitle), t(en, ruAdvice, enAdvice), raw)
        return when {
            "отпечаток сервера изменился" in r || "hostkeymismatch" in r || "host key" in r && "chang" in r ->
                f("Сервер выглядит иначе, чем в прошлый раз",
                    "Так бывает, если сервер переустановили. Если вы его не переустанавливали — не продолжайте: возможно, подключение перехватывают. Если переустанавливали — удалите запись о сервере из списка и добавьте его заново.",
                    "The server looks different from last time",
                    "This happens when a server is reinstalled. If you did not reinstall it, stop: the connection may be intercepted. If you did, remove the server from the list and add it again.")
            "auth fail" in r || "auth cancel" in r || "authentication" in r || "permission denied" in r || "userauth" in r ->
                f("Сервер не принял логин или пароль",
                    "Проверьте пароль из письма хостинга: лучше не вводить его вручную, а скопировать. Имя пользователя обычно root. Если пароль меняли в панели хостинга — введите новый.",
                    "The server did not accept the login or password",
                    "Check the password from the hosting email: better to copy it than to type it. The user is usually root. If you changed the password in the hosting panel, enter the new one.")
            "timed out" in r || "timeout" in r || "не ответил за" in r ->
                f("Сервер не ответил вовремя",
                    "Проверьте, что сервер включён и адрес введён верно. Иногда доступ закрывает файрвол хостинга — откройте в панели хостинга порт 22 (SSH) или обратитесь в поддержку.",
                    "The server did not answer in time",
                    "Check that the server is on and the address is correct. Sometimes the hosting firewall blocks access: open port 22 (SSH) in the hosting panel or contact their support.")
            "connection refused" in r || "econnrefused" in r ->
                f("Сервер отказал в подключении",
                    "На сервере не включён удалённый вход или указан неверный порт. Обычно это порт 22. Проверьте адрес и порт в письме хостинга.",
                    "The server refused the connection",
                    "Remote login is off on the server or the port is wrong. It is usually port 22. Check the address and port in the hosting email.")
            "unknownhost" in r || "unknown host" in r || "nodename nor servname" in r || "unable to resolve" in r ->
                f("Не удалось найти такой адрес",
                    "Проверьте, что адрес введён без опечаток и пробелов. Он выглядит как четыре числа через точки, например 203.0.113.5.",
                    "Could not find this address",
                    "Check the address for typos and spaces. It looks like four numbers separated by dots, for example 203.0.113.5.")
            "enetunreach" in r || "network is unreachable" in r || "no route to host" in r || "нет доступа в интернет" in r ->
                f("Нет подключения к интернету",
                    "Проверьте интернет на этом устройстве. Если включён другой VPN или прокси — отключите его и повторите.",
                    "No internet connection",
                    "Check the internet on this device. If another VPN or proxy is on, turn it off and try again.")
            "algorithm negotiation" in r || "kex" in r && "fail" in r ->
                f("Не получилось договориться о шифровании",
                    "Сервер слишком старый или настроен очень строго. Нажмите «Скопировать подробности» и отправьте нам — подскажем, что сделать.",
                    "Could not agree on encryption",
                    "The server is too old or very strict. Tap “Copy details” and send it to us — we will tell you what to do.")
            "контрольная сумма" in r || "checksum" in r ->
                f("Загруженный файл оказался повреждён",
                    "Для вашей безопасности установка остановлена. Повторите попытку; если ошибка повторяется — проблема в сети или у хостинга.",
                    "The downloaded file is damaged",
                    "For your safety the installation was stopped. Try again; if it repeats, the problem is the network or the hosting.")
            "не удалось скачать" in r || "curl" in r && "fail" in r || "could not resolve host" in r ->
                f("Сервер не смог скачать нужные файлы",
                    "Сервер не выходит в интернет или GitHub для него недоступен. Проверьте это в панели хостинга или напишите в их поддержку.",
                    "The server could not download the files",
                    "The server has no internet or cannot reach GitHub. Check it in the hosting panel or ask their support.")
            "порт" in r && ("занят" in r || "busy" in r) ->
                f("Этот порт уже занят",
                    "Откройте «Дополнительно» и выберите другой порт, например 443 или любой от 20000 до 60000.",
                    "This port is already in use",
                    "Open “Advanced” and choose another port, for example 443 or any from 20000 to 60000.")
            else -> f("Что-то пошло не так",
                "Нажмите «Скопировать подробности» и отправьте нам — разберёмся. Установка остановлена, ничего лишнего на сервере не осталось.",
                "Something went wrong",
                "Tap “Copy details” and send it to us. The installation was stopped, nothing extra is left on the server.")
        }
    }

    /** Результат проверки сервера — списком «зелёных галочек» с понятными подсказками. */
    fun checklist(pre: Preflight, en: Boolean = false): List<CheckItem> = listOf(
        CheckItem(pre.arch in setOf("amd64", "arm64", "armv7"),
            t(en, "Подходящая система: ${pre.osName.ifBlank { "Linux" }} (${pre.arch})", "Suitable system: ${pre.osName.ifBlank { "Linux" }} (${pre.arch})"),
            t(en, "Нужен Linux на обычном процессоре. Лучше всего подходят Debian и Ubuntu.", "Linux on a regular processor is needed. Debian and Ubuntu work best.")),
        CheckItem(pre.root, t(en, "Есть права администратора", "Administrator rights are available"),
            t(en, "Войдите под пользователем root — его пароль указан в письме хостинга.", "Sign in as user root — its password is in the hosting email.")),
        CheckItem(pre.systemd, t(en, "Система современная (поддерживает фоновые службы)", "The system is modern (supports background services)"),
            t(en, "Выберите при аренде более новую систему: Debian 10+ или Ubuntu 20.04+.", "When renting, choose a newer system: Debian 10+ or Ubuntu 20.04+.")),
        CheckItem(pre.portFree, t(en, "Нужный порт свободен", "The needed port is free"),
            t(en, "Порт уже занят другой программой. Откройте «Дополнительно» и выберите другой.", "The port is used by another program. Open “Advanced” and pick another.")),
        CheckItem(pre.internet, t(en, "Сервер выходит в интернет", "The server can reach the internet"),
            t(en, "Сервер не может скачать файлы. Обратитесь в поддержку хостинга.", "The server cannot download files. Contact your hosting support.")),
    )

    /** Что реально произойдёт на сервере — по одной простой фразе на шаг (команды видны под спойлером «Подробности»). */
    fun planSentences(kind: ServerKind, openFirewall: Boolean, en: Boolean = false): List<String> = when (kind) {
        ServerKind.AMNEZIAWG -> buildList {
            add(t(en, "Загрузим на сервер программу VPN (проверим, что файлы не повреждены)", "Download the VPN program to the server (we verify the files are intact)"))
            add(t(en, "Создадим ключи и настройки — они хранятся у вас на устройстве и копией на самом сервере (только для администратора)", "Create keys and settings — they are kept on your device and as a copy on the server itself (admin only)"))
            add(t(en, "Включим пересылку трафика, чтобы сервер мог выпускать устройства в интернет", "Turn on traffic forwarding so the server can let devices out to the internet"))
            add(t(en, "Настроим автозапуск: VPN будет работать после перезагрузки сервера", "Set up autostart: the VPN keeps working after a server reboot"))
            if (openFirewall) add(t(en, "Откроем нужный порт в защите сервера", "Open the needed port in the server firewall"))
            add(t(en, "Проверим, что VPN запустился, и добавим готовое подключение в «Серверы»", "Check that the VPN started and add the ready connection to “Servers”"))
        }
        ServerKind.OPENFLUX -> listOf(
            t(en, "Скачаем официальный установщик узла OpenFlux и проверим его подлинность", "Download the official OpenFlux node installer and verify it"),
            t(en, "Установим узел и настроим автозапуск", "Install the node and set up autostart"),
            t(en, "Выберем свободный порт", "Pick a free port"),
            t(en, "Проверим, что узел запустился, и покажем готовую ссылку для подключения", "Check the node started and show the ready connection link"),
        )
    }

    /** Рекомендация по умолчанию: что поставить, если человек не знает. */
    fun recommendation(kind: ServerKind, en: Boolean = false): String = when (kind) {
        ServerKind.AMNEZIAWG -> t(en, "Рекомендуем: самый простой путь к своему VPN. Подойдёт большинству.", "Recommended: the simplest way to your own VPN. Fits most people.")
        ServerKind.OPENFLUX -> t(en, "Для опытных: обходит блокировки через сервисы вроде Яндекс Документов.", "For experienced users: bypasses blocking through services like Yandex Docs.")
    }
}
