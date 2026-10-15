package ru.gidravpn.hydra.servers

/** Язык простых текстов «Моих серверов» (0.8.1-rc2: кроме русского и английского — украинский, персидский, китайский). */
enum class UiLang {
    RU, EN, UK, FA, ZH;

    companion object {
        /** По языку системы/приложения (`ru`, `uk`, `fa`, `zh`, `zh-CN`…); остальные — английский. */
        fun of(tag: String?): UiLang = when (tag.orEmpty().lowercase().substringBefore('-').substringBefore('_')) {
            "ru" -> RU; "uk" -> UK; "fa" -> FA; "zh" -> ZH; else -> EN
        }
    }
}

/**
 * Переводы простых текстов на uk/fa/zh (0.8.1-rc2). Ключ — английский текст из [FriendlyText]; `%s` — подстановки.
 * Нет перевода — показывается английский (тест `FriendlyTextTest` следит, чтобы переводы были у всех фраз).
 */
object FriendlyI18n {
    fun tr(lang: UiLang, en: String): String? = when (lang) {
        UiLang.UK -> T[en]?.get(0)
        UiLang.FA -> T[en]?.get(1)
        UiLang.ZH -> T[en]?.get(2)
        else -> null
    }

    private val T: Map<String, Array<String>> = mapOf(
        // ---------------------------------------------------------------- ошибки
        "The server looks different from last time" to arrayOf(
            "Сервер виглядає інакше, ніж минулого разу",
            "سرور با دفعهٔ قبل فرق دارد",
            "服务器与上次看起来不一样"),
        "This happens when a server is reinstalled. If you did not reinstall it, stop: the connection may be intercepted. If you did, remove the server from the list and add it again." to arrayOf(
            "Так буває, якщо сервер перевстановили. Якщо ви його не перевстановлювали — не продовжуйте: можливо, з'єднання перехоплюють. Якщо перевстановлювали — видаліть сервер зі списку й додайте його знову.",
            "این اتفاق وقتی می‌افتد که سرور دوباره نصب شده باشد. اگر آن را دوباره نصب نکرده‌اید، ادامه ندهید: ممکن است اتصال شنود شود. اگر نصب کرده‌اید، سرور را از فهرست حذف کنید و دوباره اضافه کنید.",
            "服务器重装后会出现这种情况。如果您没有重装过，请停止：连接可能被拦截。如果重装过，请从列表中删除该服务器并重新添加。"),
        "The server did not accept the login or password" to arrayOf(
            "Сервер не прийняв логін або пароль",
            "سرور نام کاربری یا رمز عبور را نپذیرفت",
            "服务器不接受用户名或密码"),
        "Check the password from the hosting email: better to copy it than to type it. The user is usually root. If you changed the password in the hosting panel, enter the new one." to arrayOf(
            "Перевірте пароль із листа хостингу: краще скопіювати його, а не вводити вручну. Ім'я користувача зазвичай root. Якщо пароль змінювали в панелі хостингу — введіть новий.",
            "رمز عبور را از ایمیل هاستینگ بررسی کنید: بهتر است آن را کپی کنید تا اینکه تایپ کنید. نام کاربری معمولاً root است. اگر رمز را در پنل هاستینگ تغییر داده‌اید، رمز جدید را وارد کنید.",
            "请核对主机商邮件中的密码：最好复制而不是手动输入。用户名通常是 root。如果您在主机面板中改过密码，请输入新密码。"),
        "The server did not answer in time" to arrayOf(
            "Сервер не відповів вчасно",
            "سرور به‌موقع پاسخ نداد",
            "服务器没有及时响应"),
        "Check that the server is on and the address is correct. Sometimes the hosting firewall blocks access: open port 22 (SSH) in the hosting panel or contact their support." to arrayOf(
            "Перевірте, що сервер увімкнений і адресу введено правильно. Іноді доступ закриває файрвол хостингу — відкрийте в панелі хостингу порт 22 (SSH) або зверніться до підтримки.",
            "بررسی کنید که سرور روشن است و نشانی درست وارد شده. گاهی فایروال هاستینگ دسترسی را می‌بندد: پورت 22 (SSH) را در پنل هاستینگ باز کنید یا با پشتیبانی تماس بگیرید.",
            "请确认服务器已开机且地址正确。有时主机商的防火墙会阻止访问：请在主机面板中开放 22 端口（SSH）或联系其客服。"),
        "The server refused the connection" to arrayOf(
            "Сервер відмовив у з'єднанні",
            "سرور اتصال را رد کرد",
            "服务器拒绝了连接"),
        "Remote login is off on the server or the port is wrong. It is usually port 22. Check the address and port in the hosting email." to arrayOf(
            "На сервері не ввімкнено віддалений вхід або вказано неправильний порт. Зазвичай це порт 22. Перевірте адресу й порт у листі хостингу.",
            "ورود از راه دور روی سرور خاموش است یا پورت اشتباه است. معمولاً پورت 22 است. نشانی و پورت را در ایمیل هاستینگ بررسی کنید.",
            "服务器未开启远程登录，或端口不对。通常是 22 端口。请核对主机商邮件中的地址和端口。"),
        "Could not find this address" to arrayOf(
            "Не вдалося знайти таку адресу",
            "این نشانی پیدا نشد",
            "找不到这个地址"),
        "Check the address for typos and spaces. It looks like four numbers separated by dots, for example 203.0.113.5." to arrayOf(
            "Перевірте, що адресу введено без помилок і пробілів. Вона має вигляд чотирьох чисел через крапки, наприклад 203.0.113.5.",
            "نشانی را از نظر غلط تایپی و فاصله بررسی کنید. شبیه چهار عدد است که با نقطه جدا شده‌اند، مثلاً 203.0.113.5.",
            "请检查地址是否有错字或空格。它看起来像用点分隔的四个数字，例如 203.0.113.5。"),
        "No internet connection" to arrayOf(
            "Немає підключення до інтернету",
            "اتصال اینترنت وجود ندارد",
            "没有网络连接"),
        "Check the internet on this device. If another VPN or proxy is on, turn it off and try again." to arrayOf(
            "Перевірте інтернет на цьому пристрої. Якщо ввімкнено інший VPN або проксі — вимкніть його й повторіть.",
            "اینترنت این دستگاه را بررسی کنید. اگر VPN یا پروکسی دیگری روشن است، آن را خاموش کنید و دوباره امتحان کنید.",
            "请检查本设备的网络。如果开着其他 VPN 或代理，请关闭后重试。"),
        "Could not agree on encryption" to arrayOf(
            "Не вдалося домовитися про шифрування",
            "توافق روی رمزنگاری ممکن نشد",
            "无法协商加密方式"),
        "The server is too old or very strict. Tap “Copy details” and send it to us — we will tell you what to do." to arrayOf(
            "Сервер надто старий або налаштований дуже суворо. Натисніть «Скопіювати подробиці» й надішліть нам — підкажемо, що робити.",
            "سرور خیلی قدیمی است یا خیلی سخت‌گیرانه تنظیم شده. «کپی جزئیات» را بزنید و برای ما بفرستید — می‌گوییم چه کنید.",
            "服务器太旧或设置过于严格。请点击“复制详情”并发送给我们——我们会告诉您怎么做。"),
        "The downloaded file is damaged" to arrayOf(
            "Завантажений файл пошкоджено",
            "فایل دانلودشده خراب است",
            "下载的文件已损坏"),
        "For your safety the installation was stopped. Try again; if it repeats, the problem is the network or the hosting." to arrayOf(
            "Задля вашої безпеки встановлення зупинено. Спробуйте ще раз; якщо помилка повторюється — проблема в мережі або в хостингу.",
            "برای امنیت شما نصب متوقف شد. دوباره امتحان کنید؛ اگر تکرار شد، مشکل از شبکه یا هاستینگ است.",
            "为了您的安全，安装已停止。请重试；如果反复出现，问题出在网络或主机商。"),
        "The server could not download the files" to arrayOf(
            "Сервер не зміг завантажити потрібні файли",
            "سرور نتوانست فایل‌ها را دانلود کند",
            "服务器无法下载所需文件"),
        "The server has no internet or cannot reach GitHub. Check it in the hosting panel or ask their support." to arrayOf(
            "Сервер не виходить в інтернет або GitHub для нього недоступний. Перевірте це в панелі хостингу або напишіть до їхньої підтримки.",
            "سرور به اینترنت دسترسی ندارد یا به GitHub نمی‌رسد. این را در پنل هاستینگ بررسی کنید یا از پشتیبانی آن‌ها بپرسید.",
            "服务器无法上网或无法访问 GitHub。请在主机面板中检查，或咨询其客服。"),
        "This port is already in use" to arrayOf(
            "Цей порт уже зайнятий",
            "این پورت از قبل در حال استفاده است",
            "该端口已被占用"),
        "Open “Advanced” and choose another port, for example 443 or any from 20000 to 60000." to arrayOf(
            "Відкрийте «Додатково» й виберіть інший порт, наприклад 443 або будь-який від 20000 до 60000.",
            "«پیشرفته» را باز کنید و پورت دیگری انتخاب کنید، مثلاً 443 یا هر عددی از 20000 تا 60000.",
            "打开“高级”并选择其他端口，例如 443，或 20000 到 60000 之间的任意端口。"),
        "Could not reach the panel" to arrayOf(
            "Не вдалося зв'язатися з панеллю",
            "اتصال به پنل ممکن نشد",
            "无法连接到面板"),
        "The panel port is probably closed by the hosting firewall. Open it in the hosting panel or manage the panel through its website." to arrayOf(
            "Найімовірніше, порт панелі закритий файрволом хостингу. Відкрийте цей порт у панелі хостингу або керуйте панеллю через її сайт.",
            "احتمالاً پورت پنل را فایروال هاستینگ بسته است. آن را در پنل هاستینگ باز کنید یا پنل را از طریق وب‌سایتش مدیریت کنید.",
            "面板端口可能被主机商的防火墙关闭了。请在主机面板中开放它，或通过面板网站进行管理。"),
        "3x-ui is already on this server" to arrayOf(
            "На сервері вже є 3x-ui",
            "3x-ui از قبل روی این سرور نصب است",
            "该服务器上已经安装了 3x-ui"),
        "No need to install it again. Open its website and create a user there." to arrayOf(
            "Повторно встановлювати її не потрібно. Відкрийте її сайт і створіть користувача там.",
            "نیازی به نصب دوباره نیست. وب‌سایت آن را باز کنید و کاربر را همان‌جا بسازید.",
            "无需再次安装。打开它的网站并在那里创建用户。"),
        "Could not get a certificate for the panel" to arrayOf(
            "Не вдалося отримати сертифікат для панелі",
            "گرفتن گواهی برای پنل ممکن نشد",
            "无法为面板获取证书"),
        "The certificate needs port 80 open from outside. Open it in the hosting panel (or turn off its firewall) and tap “Enable HTTPS” again. The panel keeps working without encryption for now." to arrayOf(
            "Для сертифіката потрібен відкритий ззовні порт 80. Відкрийте його в панелі хостингу (або вимкніть там файрвол) і натисніть «Увімкнути HTTPS» ще раз. Поки що панель працює без шифрування.",
            "گواهی به پورت 80 باز از بیرون نیاز دارد. آن را در پنل هاستینگ باز کنید (یا فایروال آن را خاموش کنید) و دوباره «فعال‌سازی HTTPS» را بزنید. فعلاً پنل بدون رمزنگاری کار می‌کند.",
            "证书需要从外部可访问的 80 端口。请在主机面板中开放它（或关闭其防火墙），然后再次点击“启用 HTTPS”。面板暂时继续以不加密方式工作。"),
        "Could not open the secure tunnel to the panel" to arrayOf(
            "Не вдалося відкрити захищений тунель до панелі",
            "باز کردن تونل امن به پنل ممکن نشد",
            "无法打开到面板的安全隧道"),
        "The panel is closed from outside, so Hydra opens it through the server. Check that the server is on and the sign-in data is correct, then try again." to arrayOf(
            "Панель закрита ззовні, тому Hydra відкриває її через сервер. Перевірте, що сервер увімкнений і дані входу правильні, і спробуйте ще раз.",
            "پنل از بیرون بسته است، پس Hydra آن را از طریق سرور باز می‌کند. بررسی کنید سرور روشن است و اطلاعات ورود درست است، سپس دوباره امتحان کنید.",
            "面板已对外关闭，因此 Hydra 通过服务器打开它。请确认服务器已开机且登录信息正确，然后重试。"),
        "Something went wrong" to arrayOf(
            "Щось пішло не так",
            "مشکلی پیش آمد",
            "出了点问题"),
        "Tap “Copy details” and send it to us. The installation was stopped, nothing extra is left on the server." to arrayOf(
            "Натисніть «Скопіювати подробиці» й надішліть нам — розберемося. Встановлення зупинено, нічого зайвого на сервері не лишилося.",
            "«کپی جزئیات» را بزنید و برای ما بفرستید. نصب متوقف شد و چیز اضافه‌ای روی سرور نمانده است.",
            "请点击“复制详情”并发送给我们。安装已停止，服务器上没有留下多余的东西。"),

        // ---------------------------------------------------------------- проверка сервера
        "Suitable system: %s (%s)" to arrayOf(
            "Відповідна система: %s (%s)",
            "سیستم مناسب: %s (%s)",
            "系统合适：%s（%s）"),
        "Linux on a regular processor is needed. Debian and Ubuntu work best." to arrayOf(
            "Потрібен Linux на звичайному процесорі. Найкраще підходять Debian і Ubuntu.",
            "به لینوکس روی پردازندهٔ معمولی نیاز است. Debian و Ubuntu بهترین گزینه‌اند.",
            "需要运行在普通处理器上的 Linux。Debian 和 Ubuntu 最合适。"),
        "Administrator rights are available" to arrayOf(
            "Є права адміністратора",
            "دسترسی مدیر وجود دارد",
            "具有管理员权限"),
        "Sign in as user root — its password is in the hosting email." to arrayOf(
            "Увійдіть як користувач root — його пароль є в листі хостингу.",
            "با کاربر root وارد شوید — رمز آن در ایمیل هاستینگ است.",
            "请以 root 用户登录——其密码在主机商邮件中。"),
        "The system is modern (supports background services)" to arrayOf(
            "Система сучасна (підтримує фонові служби)",
            "سیستم به‌روز است (از سرویس‌های پس‌زمینه پشتیبانی می‌کند)",
            "系统足够新（支持后台服务）"),
        "When renting, choose a newer system: Debian 10+ or Ubuntu 20.04+." to arrayOf(
            "Під час оренди виберіть новішу систему: Debian 10+ або Ubuntu 20.04+.",
            "هنگام اجاره، سیستم جدیدتری انتخاب کنید: Debian 10+ یا Ubuntu 20.04+.",
            "租用时请选择较新的系统：Debian 10+ 或 Ubuntu 20.04+。"),
        "The needed port is free" to arrayOf(
            "Потрібний порт вільний",
            "پورت لازم آزاد است",
            "所需端口空闲"),
        "The port is used by another program. Open “Advanced” and pick another." to arrayOf(
            "Порт уже зайнятий іншою програмою. Відкрийте «Додатково» й виберіть інший.",
            "پورت را برنامهٔ دیگری استفاده می‌کند. «پیشرفته» را باز کنید و پورت دیگری انتخاب کنید.",
            "该端口被其他程序占用。打开“高级”并选择其他端口。"),
        "The server can reach the internet" to arrayOf(
            "Сервер виходить в інтернет",
            "سرور به اینترنت دسترسی دارد",
            "服务器可以访问互联网"),
        "The server cannot download files. Contact your hosting support." to arrayOf(
            "Сервер не може завантажувати файли. Зверніться до підтримки хостингу.",
            "سرور نمی‌تواند فایل دانلود کند. با پشتیبانی هاستینگ تماس بگیرید.",
            "服务器无法下载文件。请联系主机商客服。"),

        // ---------------------------------------------------------------- план установки
        "Download the VPN program to the server (we verify the files are intact)" to arrayOf(
            "Завантажимо на сервер програму VPN (перевіримо, що файли не пошкоджені)",
            "برنامهٔ VPN را روی سرور دانلود می‌کنیم (سالم بودن فایل‌ها را بررسی می‌کنیم)",
            "将 VPN 程序下载到服务器（会校验文件是否完整）"),
        "Create keys and settings — they are kept on your device and as a copy on the server itself (admin only)" to arrayOf(
            "Створимо ключі й налаштування — вони зберігаються на вашому пристрої та копією на самому сервері (лише для адміністратора)",
            "کلیدها و تنظیمات را می‌سازیم — روی دستگاه شما و یک نسخه روی خود سرور (فقط برای مدیر) نگه داشته می‌شوند",
            "生成密钥和设置——保存在您的设备上，并在服务器上保留一份副本（仅管理员可见）"),
        "Turn on traffic forwarding so the server can let devices out to the internet" to arrayOf(
            "Увімкнемо пересилання трафіку, щоб сервер міг випускати пристрої в інтернет",
            "ارسال ترافیک را روشن می‌کنیم تا سرور بتواند دستگاه‌ها را به اینترنت برساند",
            "开启流量转发，让服务器能把设备连到互联网"),
        "Set up autostart: the VPN keeps working after a server reboot" to arrayOf(
            "Налаштуємо автозапуск: VPN працюватиме після перезавантаження сервера",
            "اجرای خودکار را تنظیم می‌کنیم: VPN بعد از راه‌اندازی دوبارهٔ سرور هم کار می‌کند",
            "设置自动启动：服务器重启后 VPN 继续工作"),
        "Open the needed port in the server firewall" to arrayOf(
            "Відкриємо потрібний порт у захисті сервера",
            "پورت لازم را در فایروال سرور باز می‌کنیم",
            "在服务器防火墙中开放所需端口"),
        "Check that the VPN started and add the ready connection to “Servers”" to arrayOf(
            "Перевіримо, що VPN запустився, і додамо готове підключення в «Сервери»",
            "بررسی می‌کنیم VPN اجرا شده و اتصال آماده را به «سرورها» اضافه می‌کنیم",
            "检查 VPN 是否已启动，并将现成的连接添加到“服务器”"),
        "Download the official OpenFlux node installer and verify it" to arrayOf(
            "Завантажимо офіційний інсталятор вузла OpenFlux і перевіримо його справжність",
            "نصب‌کنندهٔ رسمی گره OpenFlux را دانلود و اصالت آن را بررسی می‌کنیم",
            "下载官方 OpenFlux 节点安装程序并校验"),
        "Install the node and set up autostart" to arrayOf(
            "Встановимо вузол і налаштуємо автозапуск",
            "گره را نصب و اجرای خودکار را تنظیم می‌کنیم",
            "安装节点并设置自动启动"),
        "Pick a free port" to arrayOf(
            "Виберемо вільний порт",
            "یک پورت آزاد انتخاب می‌کنیم",
            "选择一个空闲端口"),
        "Check the node started and show the ready connection link" to arrayOf(
            "Перевіримо, що вузол запустився, і покажемо готове посилання для підключення",
            "بررسی می‌کنیم گره اجرا شده و پیوند اتصال آماده را نشان می‌دهیم",
            "检查节点是否已启动，并显示现成的连接链接"),
        "Download the official 3x-ui panel of the stated version" to arrayOf(
            "Завантажимо офіційну панель 3x-ui зазначеної версії",
            "پنل رسمی 3x-ui با نسخهٔ ذکرشده را دانلود می‌کنیم",
            "下载指定版本的官方 3x-ui 面板"),
        "Install it and generate a strong login and password" to arrayOf(
            "Встановимо її та придумаємо надійний логін і пароль",
            "آن را نصب می‌کنیم و نام کاربری و رمز عبور قوی می‌سازیم",
            "安装它并生成强用户名和密码"),
        "Create the first user and a ready connection (VLESS + Reality)" to arrayOf(
            "Створимо першого користувача й готове підключення (VLESS + Reality)",
            "اولین کاربر و یک اتصال آماده (VLESS + Reality) می‌سازیم",
            "创建第一个用户和现成的连接（VLESS + Reality）"),
        "Add this connection to “Servers” — you only need to tap “Connect”" to arrayOf(
            "Додамо це підключення в «Сервери» — залишиться натиснути «Підключити»",
            "این اتصال را به «سرورها» اضافه می‌کنیم — فقط کافی است «اتصال» را بزنید",
            "将此连接添加到“服务器”——您只需点击“连接”"),
        "Download the official %s panel of the stated version" to arrayOf(
            "Завантажимо офіційну панель %s зазначеної версії",
            "پنل رسمی %s با نسخهٔ ذکرشده را دانلود می‌کنیم",
            "下载指定版本的官方 %s 面板"),
        "Install it and set a strong login, password and a random panel address" to arrayOf(
            "Встановимо її та задамо надійний логін, пароль і випадкову адресу панелі",
            "آن را نصب می‌کنیم و نام کاربری، رمز عبور قوی و نشانی تصادفی پنل را تنظیم می‌کنیم",
            "安装它并设置强用户名、密码和随机的面板地址"),
        "Check that the panel opens" to arrayOf(
            "Перевіримо, що панель відкривається",
            "بررسی می‌کنیم پنل باز می‌شود",
            "检查面板能否打开"),
        "You create users and connections on the panel website — the “Open panel” button" to arrayOf(
            "Користувачів і підключення ви створите на сайті панелі — кнопка «Відкрити панель»",
            "کاربران و اتصال‌ها را در وب‌سایت پنل می‌سازید — دکمهٔ «باز کردن پنل»",
            "用户和连接请在面板网站上创建——点击“打开面板”按钮"),
        "Get a free certificate so the panel opens over HTTPS" to arrayOf(
            "Отримаємо безкоштовний сертифікат, щоб панель відкривалася по HTTPS",
            "یک گواهی رایگان می‌گیریم تا پنل با HTTPS باز شود",
            "获取免费证书，让面板通过 HTTPS 打开"),

        // ---------------------------------------------------------------- рекомендации
        "Recommended: the simplest way to your own VPN. Fits most people." to arrayOf(
            "Рекомендуємо: найпростіший шлях до свого VPN. Підійде більшості.",
            "پیشنهاد ما: ساده‌ترین راه برای VPN شخصی. برای بیشتر افراد مناسب است.",
            "推荐：拥有自己 VPN 的最简单方式，适合大多数人。"),
        "For experienced users: bypasses blocking through services like Yandex Docs." to arrayOf(
            "Для досвідчених: обходить блокування через сервіси на кшталт Яндекс Документів.",
            "برای کاربران باتجربه: از طریق سرویس‌هایی مثل Yandex Docs از مسدودسازی عبور می‌کند.",
            "适合有经验的用户：通过 Yandex 文档等服务绕过封锁。"),
        "For those who want to manage users on a panel website." to arrayOf(
            "Для тих, хто хоче сам керувати користувачами на сайті-панелі.",
            "برای کسانی که می‌خواهند کاربران را در وب‌سایت پنل مدیریت کنند.",
            "适合想在面板网站上自行管理用户的人。"),
        "If you are used to this panel: everything is managed on its website." to arrayOf(
            "Якщо ви звикли до цієї панелі: усе керування — на її сайті.",
            "اگر به این پنل عادت دارید: همهٔ مدیریت در وب‌سایت آن است.",
            "如果您习惯使用这个面板：所有管理都在其网站上进行。"),
    )

    /** Все ключи — для проверки полноты в тестах. */
    val keys: Set<String> get() = T.keys
}
