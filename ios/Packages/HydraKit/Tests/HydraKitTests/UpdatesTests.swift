import XCTest
@testable import HydraKit

final class UpdatesTests: XCTestCase {
    private let sha = String(repeating: "c", count: 64)

    func testParsesReleaseAndPicksIpa() throws {
        let json = """
        {"tag_name":"v0.7.1","html_url":"https://github.com/Chistovik92/HydraVPN/releases/tag/v0.7.1","assets":[
          {"name":"Hydra-ios-0.7.1-unsigned.ipa","size":1234,"digest":"sha256:\(sha)","browser_download_url":"https://github.com/x/Hydra-ios-0.7.1-unsigned.ipa"},
          {"name":"Hydra-full-0.7.1.apk","size":1,"browser_download_url":"https://github.com/x/a.apk"},
          {"name":"evil.ipa","size":1,"browser_download_url":"https://evil.example/evil.ipa"}]}
        """
        let info = try UpdateFeed.parse(Data(json.utf8))
        XCTAssertEqual(info.version, "0.7.1")
        XCTAssertEqual(info.assets.count, 2)   // чужой хост отброшен
        let ipa = try XCTUnwrap(UpdateFeed.pickIOS(info))
        XCTAssertEqual(ipa.name, "Hydra-ios-0.7.1-unsigned.ipa")
        XCTAssertEqual(ipa.sha256, sha)
        XCTAssertEqual(ipa.size, 1234)
    }

    func testVersionsAndTrust() {
        XCTAssertTrue(UpdateFeed.isNewer("0.7.1", than: "0.7.0"))
        XCTAssertTrue(UpdateFeed.isNewer("0.7.0.1", than: "0.7.0"))
        XCTAssertFalse(UpdateFeed.isNewer("0.7.0", than: "0.7.0-stub"))
        XCTAssertFalse(UpdateFeed.isNewer("0.6.27", than: "0.7.0"))
        XCTAssertTrue(UpdateFeed.trusted(URL(string: "https://release-assets.githubusercontent.com/x")!))
        XCTAssertFalse(UpdateFeed.trusted(URL(string: "http://github.com/x")!))
        XCTAssertFalse(UpdateFeed.trusted(URL(string: "https://github.com.evil.example/x")!))
    }
}

/// Каналы обновлений 0.8.0 и порядок пре-релизов.
final class UpdateChannelTests: XCTestCase {
    func testPrereleaseOrderAndChannels() {
        XCTAssertTrue(UpdateFeed.isNewer("0.8.0", than: "0.8.0-rc4"))
        XCTAssertTrue(UpdateFeed.isNewer("0.8.0-rc2", than: "0.8.0-rc1"))
        XCTAssertTrue(UpdateFeed.isNewer("0.8.1-b", than: "0.8.1-a"))
        XCTAssertFalse(UpdateFeed.isNewer("0.8.0-rc1", than: "0.8.0"))
        XCTAssertFalse(UpdateFeed.isNewer("0.7.0", than: "0.7.0-stub"))
        XCTAssertTrue(UpdateChannel.rc.accepts("0.8.1-rc1"))
        XCTAssertFalse(UpdateChannel.rc.accepts("0.8.1-b"))
        XCTAssertTrue(UpdateChannel.dev.accepts("0.8.1-a"))
        func rel(_ t: String, _ draft: Bool = false) -> [String: Any] {
            ["tag_name": "v\(t)", "draft": draft, "html_url": "https://github.com/Chistovik92/HydraVPN/releases/tag/v\(t)", "assets": [Any]()]
        }
        let data = try! JSONSerialization.data(withJSONObject: [rel("0.8.2-a"), rel("0.8.1-rc1", true), rel("0.8.0"), rel("0.8.0-rc4")])
        let list = UpdateFeed.parseList(data)
        XCTAssertEqual(list.count, 3)
        XCTAssertEqual(UpdateFeed.pickNewest(list, channel: .rc)?.version, "0.8.0")
        XCTAssertEqual(UpdateFeed.pickNewest(list, channel: .dev)?.version, "0.8.2-a")
    }
}
