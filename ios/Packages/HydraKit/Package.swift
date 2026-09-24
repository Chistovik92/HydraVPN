// swift-tools-version:5.9
// HydraKit — переносимая логика iOS-клиента Hydra (Фаза 12): модели, парсеры ссылок и подписок,
// сборка конфига sing-box, резервная копия, маска ключей. Только Foundation — тесты гоняются
// `swift test` и на macOS, и на Linux, без симулятора.
import PackageDescription

let package = Package(
    name: "HydraKit",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "HydraKit", targets: ["HydraKit"]),
    ],
    targets: [
        .target(name: "HydraKit"),
        .testTarget(name: "HydraKitTests", dependencies: ["HydraKit"]),
    ]
)
