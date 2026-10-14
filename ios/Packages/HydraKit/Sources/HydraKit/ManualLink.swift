import Foundation

/// Ручной ввод сервера любого протокола (0.8.0-rc4) — порт `ManualLink` с Android/ПК (shared/…/data/manual/ManualLink.kt).
/// Форма строится по `fields(_:)`, результат — обычная ссылка или `.conf`, которую разбирает тот же `LinkParser`, что и импорт.
/// До rc4 форма iOS спрашивала только имя, адрес и порт — профиль без ключа/пароля не подключался.
public enum ManualLink {
    public enum Kind: Sendable { case text, secret, number, choice, multiline }

    public struct Field: Sendable, Identifiable {
        public let key: String
        public let ru: String
        public let en: String
        public var kind: Kind = .text
        public var required = false
        public var defaultValue = ""
        public var choices: [String] = []
        public var hintRu = ""
        public var hintEn = ""
        /// Поле видно, только если поле `showIf.0` имеет одно из значений `showIf.1`.
        public var showIf: (String, Set<String>)? = nil
        public var id: String { key }

        public func label(en english: Bool) -> String { english ? en : ru }
        public func hint(en english: Bool) -> String { english ? hintEn : hintRu }
        public func visible(_ v: [String: String]) -> Bool {
            guard let (k, set) = showIf else { return true }
            return set.contains(v[k] ?? "")
        }
        public func choiceLabel(_ c: String, en english: Bool) -> String {
            switch c {
            case "": return "—"
            case "no": return english ? "no" : "нет"
            case "yes": return english ? "yes" : "да"
            default: return c
            }
        }
    }

    private static func f(_ key: String, _ ru: String, _ en: String, _ kind: Kind = .text, required: Bool = false, def: String = "",
                          choices: [String] = [], hintRu: String = "", hintEn: String = "", showIf: (String, Set<String>)? = nil) -> Field {
        var x = Field(key: key, ru: ru, en: en)
        x.kind = kind; x.required = required; x.defaultValue = def; x.choices = choices
        x.hintRu = hintRu; x.hintEn = hintEn; x.showIf = showIf
        return x
    }

    private static func address(_ port: Int) -> [Field] {
        [f("address", "Адрес сервера", "Server address", required: true, hintRu: "IP или домен", hintEn: "IP or domain"),
         f("port", "Порт", "Port", .number, required: true, def: String(port))]
    }
    private static let tlsTransports: Set<String> = ["ws", "grpc", "httpupgrade", "xhttp", "h2"]
    private static let transport = f("type", "Транспорт", "Transport", .choice, def: "tcp", choices: ["tcp", "ws", "grpc", "httpupgrade", "xhttp", "h2"])
    private static let path = f("path", "Путь / serviceName", "Path / serviceName", showIf: ("type", tlsTransports))
    private static let host = f("host", "Host (заголовок)", "Host header", showIf: ("type", ["ws", "httpupgrade", "xhttp", "h2"]))
    private static func sni(_ showIf: (String, Set<String>)? = nil) -> Field { f("sni", "SNI (имя сервера TLS)", "SNI (TLS server name)", showIf: showIf) }
    private static func fp(_ showIf: (String, Set<String>)? = nil) -> Field {
        f("fp", "Отпечаток TLS (uTLS)", "TLS fingerprint (uTLS)", .choice, def: "chrome", choices: ["chrome", "firefox", "safari", "ios", "edge", "random"], showIf: showIf)
    }
    private static func insecure(_ showIf: (String, Set<String>)? = nil) -> Field {
        f("insecure", "Не проверять сертификат", "Skip certificate check", .choice, def: "no", choices: ["no", "yes"], showIf: showIf)
    }

    public static let supported = ["vless", "vmess", "trojan", "ss", "hysteria2", "tuic", "wireguard", "awg", "sstp", "l2tp", "pptp", "openflux", "olcrtc", "byedpi"]

    public static func fields(_ id: String) -> [Field] {
        switch id {
        case "vless":
            return address(443) + [
                f("uuid", "UUID", "UUID", required: true),
                f("security", "Шифрование", "Security", .choice, def: "reality", choices: ["reality", "tls", "none"]),
                sni(("security", ["tls", "reality"])),
                f("pbk", "Открытый ключ Reality (pbk)", "Reality public key (pbk)", required: true, showIf: ("security", ["reality"])),
                f("sid", "Short ID Reality (sid)", "Reality short ID (sid)", showIf: ("security", ["reality"])),
                f("flow", "Flow", "Flow", .choice, def: "xtls-rprx-vision", choices: ["xtls-rprx-vision", ""]),
                fp(("security", ["tls", "reality"])), transport, path, host, insecure(("security", ["tls"])),
            ]
        case "vmess":
            var t = transport; t.defaultValue = "ws"
            return address(443) + [
                f("uuid", "UUID", "UUID", required: true), f("aid", "alterId", "alterId", .number, def: "0"),
                f("security", "Шифрование", "Security", .choice, def: "tls", choices: ["tls", "none"]),
                sni(("security", ["tls"])), t, path, host,
            ]
        case "trojan":
            return address(443) + [f("password", "Пароль", "Password", .secret, required: true), sni(), fp(), transport, path, host, insecure()]
        case "ss":
            return address(8388) + [
                f("method", "Шифр", "Cipher", .choice, def: "aes-256-gcm", choices: ["aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305",
                                                                                  "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"]),
                f("password", "Пароль", "Password", .secret, required: true),
            ]
        case "hysteria2":
            return address(443) + [f("password", "Пароль", "Password", .secret, required: true), sni(),
                                   f("obfs-password", "Пароль обфускации salamander (если задан)", "Salamander obfuscation password (if set)", .secret), insecure()]
        case "tuic":
            return address(443) + [f("uuid", "UUID", "UUID", required: true), f("password", "Пароль", "Password", .secret, required: true), sni(),
                                   f("congestion_control", "Управление перегрузкой", "Congestion control", .choice, def: "bbr", choices: ["bbr", "cubic", "new_reno"]), insecure()]
        case "sstp", "l2tp", "pptp":
            var r = address(id == "sstp" ? 443 : id == "l2tp" ? 1701 : 1723) +
                [f("user", "Пользователь", "User", required: true), f("password", "Пароль", "Password", .secret)]
            if id == "l2tp" { r.append(f("tunnel_secret", "Секрет туннеля L2TP (если задан)", "L2TP tunnel secret (if set)", .secret)) }
            return r
        case "wireguard", "awg":
            let byFields: (String, Set<String>) = ("conf", [""])
            var r = [
                f("conf", "Конфиг целиком (.conf) — или заполните поля ниже", "Whole .conf — or fill in the fields below", .multiline),
                f("privatekey", "Закрытый ключ (PrivateKey)", "PrivateKey", .secret, showIf: byFields),
                f("ifaddress", "Адрес в туннеле (Address)", "Tunnel address (Address)", def: "10.8.1.2/32", showIf: byFields),
                f("dns", "DNS", "DNS", def: "1.1.1.1", showIf: byFields),
                f("publickey", "Открытый ключ сервера (PublicKey)", "Server PublicKey", showIf: byFields),
                f("presharedkey", "PresharedKey (если есть)", "PresharedKey (if any)", .secret, showIf: byFields),
                f("endpoint", "Сервер:порт (Endpoint)", "Endpoint host:port", showIf: byFields),
            ]
            if id == "awg" {
                r.append(f("awgparams", "Параметры AmneziaWG (Jc, Jmin, Jmax, S1–S4, H1–H4, I1–I5 — по строке «ключ = значение»)",
                           "AmneziaWG parameters (Jc, Jmin, Jmax, S1–S4, H1–H4, I1–I5 — one «key = value» per line)", .multiline, showIf: byFields))
            }
            return r
        case "openflux":
            return [
                f("transport", "Транспорт", "Transport", .choice, def: "direct", choices: ["direct", "vyandex", "yandex", "mailru", "cupsonline", "oneme"]),
                f("url", "Адрес узла host:порт / ссылка на документ", "Node host:port / document link", required: true,
                  hintRu: "direct — 203.0.113.5:24680; Яндекс — https://docs.yandex.ru/edit/d/…", hintEn: "direct — 203.0.113.5:24680; Yandex — https://docs.yandex.ru/edit/d/…",
                  showIf: ("transport", ["direct", "vyandex", "yandex", "mailru", "cupsonline"])),
                f("maxToken", "Токен MAX", "MAX token", .secret, required: true, showIf: ("transport", ["oneme"])),
                f("maxUid", "ID пользователя MAX", "MAX user ID", required: true, showIf: ("transport", ["oneme"])),
                f("key", "Ключ шифрования (64 hex или пароль)", "Encryption key (64 hex or password)", .secret),
            ]
        case "olcrtc":
            return [
                f("provider", "Сервис комнаты", "Room provider", .choice, def: "telemost", choices: ["telemost", "jazz", "wbstream"]),
                f("transport", "Транспорт", "Transport", .choice, def: "datachannel", choices: ["datachannel", "videochannel"]),
                f("room", "ID комнаты", "Room ID", required: true),
                f("key", "Ключ шифрования (64 hex)", "Encryption key (64 hex)", .secret, required: true),
            ]
        case "byedpi":
            return [f("strategy", "Стратегия (аргументы ciadpi; пусто — по умолчанию)", "Strategy (ciadpi arguments; empty = default)", .multiline,
                      hintRu: "Например: -o1 -At,r,s -d1", hintEn: "E.g. -o1 -At,r,s -d1")]
        default:
            return []
        }
    }

    public static func defaults(_ id: String) -> [String: String] {
        Dictionary(fields(id).map { ($0.key, $0.defaultValue) }, uniquingKeysWith: { a, _ in a })
    }

    private static let wgRequired: Set<String> = ["privatekey", "ifaddress", "publickey", "endpoint"]

    /// Подписи незаполненных обязательных полей; пусто — можно собирать.
    public static func missing(_ id: String, _ v: [String: String], en: Bool = false) -> [String] {
        let wg = id == "wireguard" || id == "awg"
        if wg, !(v["conf"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [] }
        return fields(id).filter { $0.visible(v) && ($0.required || (wg && wgRequired.contains($0.key))) }
            .filter { (v[$0.key] ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
            .map { $0.label(en: en) }
    }

    private static func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
    private static func hostPart(_ h: String) -> String { h.contains(":") && !h.hasPrefix("[") ? "[\(h)]" : h }

    /// Ссылка или `.conf` из полей; nil — не хватает обязательного.
    public static func build(_ id: String, name: String, values: [String: String]) -> String? {
        var v = defaults(id)
        for (k, x) in values { v[k] = x.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard missing(id, v).isEmpty else { return nil }
        let n = name.trimmingCharacters(in: .whitespaces)
        let frag = n.isEmpty ? "" : "#" + enc(n)
        func s(_ k: String) -> String { v[k] ?? "" }
        func q(_ pairs: [(String, String?)]) -> String {
            pairs.compactMap { p in p.1.flatMap { $0.isEmpty ? nil : "\(p.0)=\(enc($0))" } }.joined(separator: "&")
        }
        let addr = "\(hostPart(s("address"))):\(s("port"))"
        let ins: String? = s("insecure") == "yes" ? "1" : nil
        let tr = s("type").isEmpty ? "tcp" : s("type")
        let pth: String? = tr == "tcp" ? nil : s("path")
        let pathKey = tr == "grpc" ? "serviceName" : "path"
        let hdr: String? = (tr == "tcp" || tr == "grpc") ? nil : s("host")
        switch id {
        case "vless":
            let sec = s("security")
            return "vless://\(enc(s("uuid")))@\(addr)?" + q([("type", tr), ("security", sec), ("sni", sec == "none" ? nil : s("sni")),
                ("fp", sec == "none" ? nil : s("fp")), ("pbk", sec == "reality" ? s("pbk") : nil), ("sid", sec == "reality" ? s("sid") : nil),
                ("flow", tr == "tcp" && sec != "none" ? s("flow") : nil), (pathKey, pth), ("host", hdr), ("allowInsecure", sec == "tls" ? ins : nil)]) + frag
        case "vmess":
            let o: [String: Any] = ["v": "2", "ps": n.isEmpty ? "VMess \(s("address"))" : n, "add": s("address"), "port": s("port"), "id": s("uuid"),
                                    "aid": s("aid").isEmpty ? "0" : s("aid"), "net": tr, "type": "none", "host": s("host"), "path": pth ?? "",
                                    "tls": s("security") == "tls" ? "tls" : "", "sni": s("sni")]
            guard let data = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) else { return nil }
            return "vmess://" + data.base64EncodedString()
        case "trojan":
            return "trojan://\(enc(s("password")))@\(addr)?" + q([("security", "tls"), ("type", tr), ("sni", s("sni")), ("fp", s("fp")),
                (pathKey, pth), ("host", hdr), ("allowInsecure", ins)]) + frag
        case "ss":
            let b = Data("\(s("method")):\(s("password"))".utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
            return "ss://\(b)@\(addr)" + frag
        case "hysteria2":
            let obfs = s("obfs-password")
            return "hysteria2://\(enc(s("password")))@\(addr)?" + q([("sni", s("sni")), ("obfs", obfs.isEmpty ? nil : "salamander"),
                ("obfs-password", obfs), ("insecure", ins)]) + frag
        case "tuic":
            return "tuic://\(enc(s("uuid"))):\(enc(s("password")))@\(addr)?" + q([("sni", s("sni")), ("congestion_control", s("congestion_control")),
                ("alpn", "h3"), ("allow_insecure", ins)]) + frag
        case "sstp", "l2tp", "pptp":
            let extra = q([("tunnel_secret", v["tunnel_secret"])])
            return "\(id)://\(enc(s("user"))):\(enc(s("password")))@\(addr)" + (extra.isEmpty ? "" : "?" + extra) + frag
        case "wireguard", "awg":
            if !s("conf").isEmpty { return s("conf") }
            var c = "[Interface]\nPrivateKey = \(s("privatekey"))\nAddress = \(s("ifaddress"))\n"
            if !s("dns").isEmpty { c += "DNS = \(s("dns"))\n" }
            if id == "awg" {
                for line in s("awgparams").split(whereSeparator: \.isNewline) where line.contains("=") {
                    c += line.trimmingCharacters(in: .whitespaces) + "\n"
                }
            }
            c += "\n[Peer]\nPublicKey = \(s("publickey"))\n"
            if !s("presharedkey").isEmpty { c += "PresharedKey = \(s("presharedkey"))\n" }
            c += "AllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = \(s("endpoint"))\nPersistentKeepalive = 25\n"
            return c
        case "openflux":
            let t = s("transport")
            return "openflux://\(t)?" + q([("url", t == "oneme" ? nil : s("url")), ("maxToken", t == "oneme" ? s("maxToken") : nil),
                ("maxUid", t == "oneme" ? s("maxUid") : nil), ("key", s("key"))]) + frag
        case "olcrtc":
            return "olcrtc://\(s("provider"))?\(s("transport"))@\(s("room"))#\(s("key"))" + (n.isEmpty ? "" : "$" + n)
        case "byedpi":
            let strat = s("strategy").replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
            return "byedpi://?s=\(enc(strat))" + (n.isEmpty ? "#" + enc("Обход DPI") : frag)
        default:
            return nil
        }
    }
}
