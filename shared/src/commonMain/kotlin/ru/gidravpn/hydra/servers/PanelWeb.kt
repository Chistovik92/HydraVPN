package ru.gidravpn.hydra.servers

import org.json.JSONObject

/**
 * Вход на сайт панели за пользователя (0.8.1-rc2, встроенный WebView на Android). Страница панели открывается, и в неё
 * подставляется скрипт: тот же запрос входа, что делает форма панели (`fetch` с того же сайта — cookie сессии остаётся
 * в WebView), затем переход на главную. Пароль не печатается в поле и не идёт через буфер обмена.
 */
object PanelWeb {
    /** Запрос входа: адрес, тело (`application/x-www-form-urlencoded`) и куда перейти после него. */
    data class Login(val url: String, val body: String, val then: String)

    /** Метки в журнале консоли страницы — по ним приложение узнаёт итог входа (без моста JavaScript → Kotlin). */
    const val OK = "hydra-login:ok"
    const val FAIL = "hydra-login:fail"

    /** CSRF 3x-ui v3: где лежит токен на странице и каким заголовком он уходит. */
    const val CSRF_META = "csrf-token"
    const val CSRF_HEADER = "X-CSRF-Token"

    /** Токен CSRF из HTML страницы (для входа не из WebView — тесты, ПК). */
    fun csrfToken(html: String): String? = Regex("""<meta[^>]*name="$CSRF_META"[^>]*content="([^"]*)"""").find(html)?.groupValues?.get(1)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * Как входит каждая панель (проверено вживую в WSL): 3x-ui — `POST {адрес}/login` (`username`, `password`), x-ui —
     * `POST {адрес}/login`, s-ui — `POST {адрес}/api/login` (`user`, `pass`; заголовок Origin `fetch` ставит сам).
     * [base] — адрес панели (внешний или через туннель); null — пароля нет (панель подключена по токену): только открыть.
     */
    fun login(p: PanelInstance, base: String = p.baseUrl): Login? {
        if (p.username.isBlank() || p.password.isBlank()) return null
        val b = base.trimEnd('/') + "/"
        return when (p.kind) {
            PanelKind.SUI -> Login(b + "api/login", "user=${enc(p.username)}&pass=${enc(p.password)}", b)
            PanelKind.XUI3, PanelKind.XUI -> Login(b + "login", "username=${enc(p.username)}&password=${enc(p.password)}", b)
        }
    }

    /**
     * Скрипт для страницы: вход и переход; итог — в `console.log` ([OK] / [FAIL]). 3x-ui v3 без CSRF-токена отвечает на вход
     * 403 (проверено вживую на v3.10.2): токен — в `<meta name="csrf-token">` страницы входа, отправляется заголовком `X-CSRF-Token`.
     */
    fun script(l: Login): String =
        "(function(){var h={'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8','X-Requested-With':'XMLHttpRequest'};" +
            "var m=document.querySelector('meta[name=\"$CSRF_META\"]');if(m&&m.content){h['$CSRF_HEADER']=m.content}" +
            "fetch(${JSONObject.quote(l.url)},{method:'POST',credentials:'include',headers:h," +
            "body:${JSONObject.quote(l.body)}}).then(function(r){return r.json()}).then(function(j){" +
            "if(j&&j.success){console.log('$OK');location.replace(${JSONObject.quote(l.then)})}else{console.log('$FAIL')}" +
            "}).catch(function(){console.log('$FAIL')})})();"

    /** Адрес панели через туннель: та же схема и путь, хост 127.0.0.1 и порт туннеля на устройстве. */
    fun tunnelUrl(baseUrl: String, localPort: Int): String {
        val u = java.net.URL(baseUrl)
        return "${u.protocol}://127.0.0.1:$localPort${u.path}"
    }

    /** Порт панели на сервере (из её адреса). */
    fun remotePort(baseUrl: String): Int = java.net.URL(baseUrl).port.takeIf { it > 0 } ?: if (baseUrl.startsWith("https", true)) 443 else 80
}
