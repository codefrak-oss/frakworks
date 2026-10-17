// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "FirstMateKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "FirstMateKit", targets: ["FirstMateKit"])],
    targets: [
        .target(name: "FirstMateKit"),
        .testTarget(name: "FirstMateKitTests", dependencies: ["FirstMateKit"]),
    ]
)
