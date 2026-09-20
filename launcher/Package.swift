// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BeatorajaLauncher",
    platforms: [.macOS("26.0")],
    targets: [
        .executableTarget(
            name: "BeatorajaLauncher",
            path: "Sources/BeatorajaLauncher"
        )
    ]
)
