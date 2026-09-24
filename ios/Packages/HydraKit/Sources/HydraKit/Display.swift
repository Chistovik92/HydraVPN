import Foundation

/// «Скрывать ключи» — порт `SecretMask`: ссылка на экране без UUID, паролей, ключей Reality и
/// токенов подписки. QR, копирование и «Поделиться» отдают полную ссылку.
public enum SecretMask {
    public static let dots = "••••"
    static let secretParams: Set<String> = [
        "pbk", "sid", "password", "pass", "psk", "key", "privatekey", "private_key", "publickey",
        "public_key", "presharedkey", "obfs-password", "obfs_password", "auth", "token", "uuid", "id", "spx",
    ]

    public static func mask(_ link: String) -> String {
        guard let sep = link.range(of: "://") else { return link }
        let scheme = String(link[..<sep.upperBound])
        var rest = String(link[sep.upperBound...])
        if !rest.contains("@") && !rest.contains("/") && !rest.contains("?") && rest.count > 24 { return scheme + dots }

        let pathStart = rest.firstIndex(where: { "/?#".contains($0) })
        if let at = rest.firstIndex(of: "@"), pathStart == nil || at < pathStart! {
            rest = dots + rest[at...]
        }
        if scheme.hasPrefix("http") {
            rest = rest.replacingOccurrences(of: #"(/[^/?#]+/)([^/?#]{8,})(?=[?#]|$)"#, with: "$1" + dots, options: .regularExpression)
        }
        guard let re = try? NSRegularExpression(pattern: #"([?&])([^=&#]+)=([^&#]*)"#) else { return scheme + rest }
        var out = "", last = rest.startIndex
        for m in re.matches(in: rest, range: NSRange(rest.startIndex..., in: rest)) {
            guard let whole = Range(m.range, in: rest), let sepR = Range(m.range(at: 1), in: rest),
                  let nameR = Range(m.range(at: 2), in: rest), let valR = Range(m.range(at: 3), in: rest) else { continue }
            out += rest[last..<whole.lowerBound]
            let name = String(rest[nameR])
            if secretParams.contains(name.lowercased()) && !rest[valR].isEmpty {
                out += rest[sepR] + name + "=" + dots
            } else {
                out += rest[whole]
            }
            last = whole.upperBound
        }
        out += rest[last...]
        return scheme + out
    }
}

/// Локация сервера для виджета и Пункта управления — порт `ServerLocation`.
public enum ServerLocation {
    static func isRegional(_ s: Unicode.Scalar) -> Bool { (0x1F1E6...0x1F1FF).contains(s.value) }

    static func leadingFlag(_ s: String) -> String? {
        let t = s.drop(while: \.isWhitespace)
        let scalars = Array(t.unicodeScalars.prefix(2))
        guard scalars.count == 2, scalars.allSatisfy(isRegional) else { return nil }
        var v = String.UnicodeScalarView(); v.append(contentsOf: scalars)
        return String(v)
    }

    public static func flag(_ p: ServerProfile) -> String { leadingFlag(p.name) ?? p.flag }

    public static func isoCode(_ flag: String) -> String? {
        let s = Array(flag.unicodeScalars)
        guard s.count == 2, s.allSatisfy(isRegional) else { return nil }
        return s.map { String(UnicodeScalar(UInt8(97 + $0.value - 0x1F1E6))) }.joined()
    }

    public static func bareName(_ p: ServerProfile) -> String {
        guard let f = leadingFlag(p.name) else { return p.name.trimmingCharacters(in: .whitespaces) }
        let t = p.name.drop(while: \.isWhitespace)
        return String(t.dropFirst(f.count)).trimmingCharacters(in: .whitespaces)
    }

    public static func label(_ p: ServerProfile, countryName: (String) -> String = defaultCountryName) -> String {
        let flag = flag(p)
        let bare = bareName(p)
        let name = bare.isEmpty ? p.address : bare
        guard let code = isoCode(flag) else { return flag == "🌐" ? name : "\(flag) \(name)" }
        let country = countryName(code)
        return name.caseInsensitiveCompare(country) == .orderedSame ? "\(flag) \(country)" : "\(flag) \(country) · \(name)"
    }

    public static func defaultCountryName(_ code: String) -> String {
        Locale.current.localizedString(forRegionCode: code.uppercased()) ?? code.uppercased()
    }
}

public enum Format {
    /// Байты в человекочитаемый вид (как `humanBytes` на Android).
    public static func bytes(_ b: Int64) -> String {
        let ru = Locale.current.identifier.hasPrefix("ru")
        let u = ru ? ["Б", "КБ", "МБ", "ГБ"] : ["B", "KB", "MB", "GB"]
        switch b {
        case 1_000_000_000...: return String(format: "%.2f", Double(b) / 1e9) + " " + u[3]
        case 1_000_000...: return String(format: "%.1f", Double(b) / 1e6) + " " + u[2]
        case 1_000...: return String(format: "%.0f", Double(b) / 1e3) + " " + u[1]
        default: return "\(b) \(u[0])"
        }
    }
}
