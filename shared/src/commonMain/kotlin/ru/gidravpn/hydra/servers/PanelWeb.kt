package ru.gidravpn.hydra.servers

import org.json.JSONObject

/**
 * Вход на сайт панели за пользователя (0.8.1-rc2, встроенный WebView на Android). Страница панели открывается, и в неё
 * подставляется скрипт: тот же запрос входа, что делает форма панели (`fetch` с того же сайта — cookie сессии остаётся
 * в WebView), затем переход на главную. Пароль не печатается в поле и не идёт через буфер обмена.
 */
object PanelWeb {
    /** Запрос входа: адрес, тело (`application/x-www-form-urlencoded`) и куда перейти после него. */
    data class Login(val url: String, val body: String, val then: String, val user: String = "", val pass: String = "")

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
            PanelKind.SUI -> Login(b + "api/login", "user=${enc(p.username)}&pass=${enc(p.password)}", b, p.username, p.password)
            PanelKind.XUI3, PanelKind.XUI -> Login(b + "login", "username=${enc(p.username)}&password=${enc(p.password)}", b, p.username, p.password)
            // PasarGuard: сайт — /dashboard/ (в корне заставка), форма входа React; запасной путь — запрос токена (JWT в localStorage
            // кладёт сама страница — главное форма)
            PanelKind.PASARGUARD -> java.net.URL(b).let { u -> "${u.protocol}://${u.authority}/" }.let { o ->
                Login(o + "api/admin/token", "username=${enc(p.username)}&password=${enc(p.password)}", o + "dashboard/", p.username, p.password) }
            // Remnawave: форма входа React; запасной путь — запрос входа (JWT страница кладёт сама — главное форма)
            PanelKind.REMNAWAVE -> Login(b + "api/auth/login", "username=${enc(p.username)}&password=${enc(p.password)}", b, p.username, p.password)
        }
    }

    /** Итог входа виден и без консоли (ПК, JavaFX): `window.__hydraLogin` = "ok" / "fail". */
    const val RESULT_VAR = "__hydraLogin"

    /**
     * После входа формой панель обычно сама уходит на другую страницу — вместе с ней пропадает и [RESULT_VAR]. Поэтому снаружи
     * успех видно так: страница уже не та, с которой входили, и на ней нет поля пароля (скрипт отдаёт число таких полей).
     */
    const val PASSWORD_FIELDS_JS = "[].slice.call(document.querySelectorAll('input[type=password]')).filter(function(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length)}).length"

    /** Вход прошёл: отметка скрипта или ушли со страницы входа, а поля пароля нет. */
    fun loggedIn(result: String, startUrl: String, location: String, passwordFields: Int): Boolean =
        result == "ok" || (result != "fail" && location.trimEnd('/') != startUrl.trimEnd('/') && passwordFields == 0)

    /**
     * Скрипт для страницы (0.8.2-a2 — запрос владельца «подстановка логин/пароль в форму входа»): ждёт форму входа самой панели
     * (страницы панелей — одностраничные приложения, поля появляются не сразу), **вписывает логин и пароль в её поля** так, чтобы
     * их увидел Vue/React панели (сеттер `value` + события `input`/`change`), и нажимает её кнопку входа. Ушли со страницы
     * входа — успех. Формы нет или вход формой не прошёл (капча, 2FA, другая вёрстка) — запасной путь 0.8.1-rc2: тот же запрос
     * входа, что делает форма (`fetch`; у 3x-ui v3 — с CSRF-токеном из `<meta name="csrf-token">`, без него 403), затем переход.
     * Итог — в `console.log` ([OK] / [FAIL]) и в `window.`[RESULT_VAR].
     */
    fun script(l: Login): String {
        val q = { v: String -> JSONObject.quote(v) }
        return """(function(){
var U=${q(l.user)},P=${q(l.pass)},L=${q(l.url)},B=${q(l.body)},T=${q(l.then)};
function done(r){if(window.$RESULT_VAR)return;window.$RESULT_VAR=r;console.log(r=='ok'?'$OK':'$FAIL');}
function vis(e){return !!(e&&(e.offsetWidth||e.offsetHeight||e.getClientRects().length));}
function all(s){return [].slice.call(document.querySelectorAll(s)).filter(vis);}
function setv(e,v){try{var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');if(d&&d.set)d.set.call(e,v);else e.value=v;}catch(x){e.value=v;}
 ['input','change','blur'].forEach(function(t){try{e.dispatchEvent(new Event(t,{bubbles:true}));}catch(x){}});}
function viaFetch(){var h={'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8','X-Requested-With':'XMLHttpRequest'};
 var m=document.querySelector('meta[name="$CSRF_META"]');if(m&&m.content)h['$CSRF_HEADER']=m.content;
 fetch(L,{method:'POST',credentials:'include',headers:h,body:B}).then(function(r){return r.json();}).then(function(j){
  if(j&&j.success){done('ok');location.replace(T);}else done('fail');}).catch(function(){done('fail');});}
if(!U||!P){done('fail');return;}
var n=0;(function wait(){var pw=all('input[type=password]')[0];
 if(!pw){if(++n<40)return setTimeout(wait,250);return viaFetch();}
 var u=all('input').filter(function(i){var t=(i.getAttribute('type')||'text').toLowerCase();return (t=='text'||t=='email')&&i!==pw;})[0];
 if(u)setv(u,U);setv(pw,P);
 setTimeout(function(){var f=pw.form;
  var b=(f&&f.querySelector('button[type=submit],input[type=submit]'))||all('button').filter(function(x){return /log ?in|sign ?in|вход|войти|увійти|ورود|登录/i.test(x.textContent||'');})[0]||(f&&f.querySelector('button'));
  if(b)b.click();else if(f&&f.requestSubmit)f.requestSubmit();
  var k=0;(function check(){if(all('input[type=password]').length==0){done('ok');return;}if(++k<20)return setTimeout(check,250);viaFetch();})();
 },300);})();
})();"""
    }

    /** Адрес панели через туннель: та же схема и путь, хост 127.0.0.1 и порт туннеля на устройстве. */
    fun tunnelUrl(baseUrl: String, localPort: Int): String {
        val u = java.net.URL(baseUrl)
        return "${u.protocol}://127.0.0.1:$localPort${u.path}"
    }

    /** Порт панели на сервере (из её адреса). */
    fun remotePort(baseUrl: String): Int = java.net.URL(baseUrl).port.takeIf { it > 0 } ?: if (baseUrl.startsWith("https", true)) 443 else 80
}
