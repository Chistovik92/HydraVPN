// swift-tools-version:5.9
import PackageDescription

// ByeDPI (`ciadpi`, MIT) как статическая библиотека: запускается потоком внутри Network Extension (обход DPI в туннеле)
// и внутри приложения (мастер подбора стратегии). Исходники — v0.17.3 без изменений, кроме переименования main.
let package = Package(
    name: "CiaDPI",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "CiaDPI", targets: ["CiaDPI"])],
    targets: [
        .target(
            name: "CiaDPI",
            path: "Sources/CiaDPI",
            publicHeadersPath: "include",
            cSettings: [
                .define("main", to: "ciadpi_main"),
                .define("_DEFAULT_SOURCE"),
            ]
        ),
    ]
)
