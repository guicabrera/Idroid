// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "IdroidApp",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "IdroidApp",
            path: "Sources/IdroidApp"
        )
    ]
)
