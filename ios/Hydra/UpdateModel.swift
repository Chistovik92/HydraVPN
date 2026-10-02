import CryptoKit
import Foundation
import HydraKit
import SwiftUI
import UIKit

/// Обновление из релизов GitHub (0.7.1): проверка (не чаще раза в сутки), прямая загрузка `.ipa` со сверкой
/// SHA-256 и передача файла системному меню «Поделиться» — оттуда его открывает AltStore/Sideloadly/Файлы.
/// iOS не позволяет приложению установить себя само (нужна подпись), поэтому дальше — инструмент установки.
@MainActor
final class UpdateModel: NSObject, ObservableObject {
    @Published private(set) var release: ReleaseInfo?
    @Published private(set) var progress: Double?
    @Published var shareURL: URL?
    @Published var error: String?

    private static let lastCheckKey = "update.lastCheck"
    private(set) var asset: ReleaseAsset?
    private lazy var session = URLSession(configuration: .default, delegate: Redirects(model: self), delegateQueue: nil)

    var current: String { HydraDevice.version }

    func checkIfDue(manual: Bool = false) async {
        let last = UserDefaults.standard.double(forKey: Self.lastCheckKey)
        if !manual && Date().timeIntervalSince1970 - last < 24 * 3600 { return }
        var req = URLRequest(url: UpdateFeed.apiURL, timeoutInterval: 10)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("Hydra-iOS-update-check", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let info = try? UpdateFeed.parse(data) else { return }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastCheckKey)
        if UpdateFeed.isNewer(info.version, than: current) {
            release = info
            asset = UpdateFeed.pickIOS(info)
        } else {
            release = nil
        }
    }

    fileprivate func setProgress(_ p: Double) { progress = p }

    /// Скачивает `.ipa`, сверяет размер и SHA-256 и открывает меню «Поделиться».
    func download() async {
        guard progress == nil, let asset else { return }
        progress = 0
        error = nil
        defer { progress = nil }
        do {
            let (tmp, response) = try await session.download(from: asset.url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            let data = try Data(contentsOf: tmp, options: .mappedIfSafe)
            if asset.size > 0 && Int64(data.count) != asset.size { throw URLError(.cannotDecodeContentData) }
            if let want = asset.sha256 {
                let got = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                if got != want { throw NSError(domain: "Hydra", code: 1, userInfo: [NSLocalizedDescriptionKey: L("upd_ios_bad_hash")]) }
            }
            // Имя файла из релиза — без путей.
            let dest = FileManager.default.temporaryDirectory.appendingPathComponent((asset.name as NSString).lastPathComponent)
            try? FileManager.default.removeItem(at: dest)
            try data.write(to: dest, options: .atomic)
            shareURL = dest
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Прогресс загрузки и ограничение переадресаций: только на хосты GitHub.
private final class Redirects: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private weak var model: UpdateModel?
    init(model: UpdateModel) { self.model = model }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64,
                    totalBytesWritten written: Int64, totalBytesExpectedToWrite total: Int64) {
        guard total > 0 else { return }
        let p = Double(written) / Double(total)
        Task { @MainActor [weak model] in model?.setProgress(p) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url.map(UpdateFeed.trusted) == true ? request : nil)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}

/// Системное меню «Поделиться» для файла.
struct UpdateShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_: UIActivityViewController, context: Context) {}
}

/// Плашка «Доступна новая версия» на главном экране.
struct UpdateBanner: View {
    @ObservedObject var model: UpdateModel
    @EnvironmentObject var app: AppModel
    var theme: HydraTheme { HydraTheme(rawValue: app.state.app.theme) ?? .ambient }

    var body: some View {
        if let r = model.release {
            VStack(alignment: .leading, spacing: 6) {
                if let p = model.progress {
                    Text(L("upd_ios_downloading", Int(p * 100))).font(.subheadline.weight(.semibold)).foregroundStyle(theme.accent)
                    ProgressView(value: p).tint(theme.accent)
                } else if model.asset != nil {
                    Button { Task { await model.download() } } label: {
                        Text(L("upd_ios_banner", r.version)).font(.subheadline.weight(.semibold)).foregroundStyle(theme.accent)
                    }
                    Text(L("upd_ios_hint")).font(.caption).foregroundStyle(Color.hydraMuted)
                } else {
                    Link(L("upd_banner", r.version), destination: r.pageURL).font(.subheadline.weight(.semibold))
                }
                if let e = model.error { Text(e).font(.caption).foregroundStyle(Color.hydraDanger) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(theme.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(theme.accent.opacity(0.5)))
            .sheet(isPresented: Binding(get: { model.shareURL != nil }, set: { if !$0 { model.shareURL = nil } })) {
                if let url = model.shareURL { UpdateShareSheet(url: url) }
            }
        }
    }
}
