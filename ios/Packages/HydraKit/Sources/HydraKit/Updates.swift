import Foundation

/// Обновления из релизов GitHub (0.7.1) — порт `UpdateFeed` с Android и ПК: разбор релиза, выбор файла,
/// сравнение версий. Загрузка и сверка SHA-256 — в приложении (Hydra/UpdateModel.swift).
/// Неподписанный `.ipa` iOS сама не устанавливает: файл отдаётся инструменту установки (AltStore, Sideloadly…).
public struct ReleaseAsset: Equatable, Sendable {
    public var name: String
    public var url: URL
    public var size: Int64
    public var sha256: String?
}

public struct ReleaseInfo: Equatable, Sendable {
    public var version: String
    public var pageURL: URL
    public var assets: [ReleaseAsset]
}

/// Канал обновлений (0.8.0): «Участвовать в бета-тестировании». stable — только выпуски; rc — + релиз-кандидаты; dev — + альфы и беты.
public enum UpdateChannel: String, CaseIterable, Sendable {
    case stable, rc, dev

    public func accepts(_ version: String) -> Bool {
        guard let k = UpdateFeed.preKey(version) else { return true }
        switch self {
        case .stable: return false
        case .rc: return k >= 2000
        case .dev: return true
        }
    }
}

public enum UpdateFeed {
    public static let repo = "Chistovik92/HydraVPN"
    public static let apiURL = URL(string: "https://api.github.com/repos/\(repo)/releases/latest")!
    public static let listURL = URL(string: "https://api.github.com/repos/\(repo)/releases?per_page=40")!

    /// Список релизов (черновики пропускаются).
    public static func parseList(_ data: Data) -> [ReleaseInfo] {
        guard let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            if (o["draft"] as? Bool) == true { return nil }
            guard let d = try? JSONSerialization.data(withJSONObject: o) else { return nil }
            return try? parse(d)
        }
    }

    /// Самый новый релиз, который принимает канал.
    public static func pickNewest(_ list: [ReleaseInfo], channel: UpdateChannel) -> ReleaseInfo? {
        list.filter { channel.accepts($0.version) }.reduce(nil as ReleaseInfo?) { best, r in
            guard let b = best else { return r }
            return isNewer(r.version, than: b.version) ? r : b
        }
    }

    /// Вес пре-релиза: стадия (a=0, b=1, rc=2) * 1000 + номер; nil — обычный выпуск (как `UpdateFeed.preKey` на Kotlin).
    public static func preKey(_ v: String) -> Int? {
        guard let re = try? NSRegularExpression(pattern: "-(alpha|beta|rc|a|b)(\\d*)(?=$|-)", options: [.caseInsensitive]),
              let m = re.firstMatch(in: v, range: NSRange(v.startIndex..., in: v)),
              let sr = Range(m.range(at: 1), in: v), let nr = Range(m.range(at: 2), in: v) else { return nil }
        let stage: Int
        switch v[sr].lowercased() { case "a", "alpha": stage = 0; case "b", "beta": stage = 1; default: stage = 2 }
        return stage * 1000 + (Int(v[nr]) ?? 0)
    }

    /// Файлы берём только с GitHub: хост github.com или *.githubusercontent.com, и только по https.
    public static func trusted(_ url: URL) -> Bool {
        guard url.scheme == "https", let host = url.host?.lowercased() else { return false }
        return host == "github.com" || host.hasSuffix(".github.com") || host.hasSuffix(".githubusercontent.com")
    }

    public static func parse(_ data: Data) throws -> ReleaseInfo {
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any], let tag = o["tag_name"] as? String
        else { throw URLError(.cannotParseResponse) }
        let page = (o["html_url"] as? String).flatMap(URL.init(string:)).flatMap { trusted($0) ? $0 : nil }
            ?? URL(string: "https://github.com/\(repo)/releases/latest")!
        let assets: [ReleaseAsset] = (o["assets"] as? [[String: Any]] ?? []).compactMap { a in
            guard let name = a["name"] as? String, let s = a["browser_download_url"] as? String,
                  let url = URL(string: s), trusted(url) else { return nil }
            let digest = (a["digest"] as? String)?.replacingOccurrences(of: "sha256:", with: "").lowercased()
            let ok = digest.flatMap { $0.count == 64 && $0.allSatisfy(\.isHexDigit) ? $0 : nil }
            return ReleaseAsset(name: name, url: url, size: (a["size"] as? NSNumber)?.int64Value ?? 0, sha256: ok)
        }
        return ReleaseInfo(version: tag.hasPrefix("v") ? String(tag.dropFirst()) : tag, pageURL: page, assets: assets)
    }

    /// `Hydra-ios-X-unsigned.ipa`.
    public static func pickIOS(_ info: ReleaseInfo) -> ReleaseAsset? {
        info.assets.first { $0.name.hasPrefix("Hydra-ios-") && $0.name.hasSuffix(".ipa") }
    }

    /// true, если `remote` новее `current` («0.7.1» > «0.7.0»); суффиксы «-stub»/«-dev» не мешают.
    public static func isNewer(_ remote: String, than current: String) -> Bool {
        func parts(_ v: String) -> [Int] {
            guard let head = v.split(separator: "-").first else { return [] }
            return head.split(separator: ".").map { Int($0) ?? 0 }
        }
        let r = parts(remote), c = parts(current)
        for i in 0..<max(r.count, c.count) {
            let d = (i < r.count ? r[i] : 0) - (i < c.count ? c[i] : 0)
            if d != 0 { return d > 0 }
        }
        // Одинаковые номера: a < b < rcN < выпуск; «-stub»/«-dev» пре-релизами не считаются (0.8.0: раньше 0.8.0 не был новее 0.8.0-rc4).
        switch (preKey(remote), preKey(current)) {
        case (nil, .some): return true
        case let (.some(r), .some(c)): return r > c
        default: return false
        }
    }
}
