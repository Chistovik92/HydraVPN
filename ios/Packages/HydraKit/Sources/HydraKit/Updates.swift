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

public enum UpdateFeed {
    public static let repo = "Chistovik92/HydraVPN"
    public static let apiURL = URL(string: "https://api.github.com/repos/\(repo)/releases/latest")!

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
        return false
    }
}
