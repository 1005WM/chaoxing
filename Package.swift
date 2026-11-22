// swift-tools-version: 6.0
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import PackageDescription

let package = Package(
    name: "Caochaoxing",
    platforms: [
        .macOS(.v14),
        .iOS(.v17)
    ],
    products: [
        .library(
            name: "ChaoxingCore",
            targets: ["ChaoxingCore"]
        ),
        .executable(
            name: "CaochaoxingMac",
            targets: ["CaochaoxingMac"]
        )
    ],
    targets: [
        .target(
            name: "ChaoxingCore",
            path: "Sources/ChaoxingCore"
        ),
        .executableTarget(
            name: "CaochaoxingMac",
            dependencies: ["ChaoxingCore"],
            path: "Sources/CaochaoxingMac",
            resources: [.copy("Resources/AppIcon.png")]
        ),
        // Compile the actual iOS state controller for offline host tests.
        // The Xcode App still builds this source directly; no desktop app is added.
        .target(
            name: "IOSAppStateSupport",
            dependencies: ["ChaoxingCore"],
            path: "Sources/CaochaoxingIOS/App",
            exclude: ["CaochaoxingIOSApp.swift"],
            sources: ["IOSAppState.swift", "IOSCourseStudyController.swift"]
        ),
        .testTarget(
            name: "ChaoxingCoreTests",
            dependencies: ["ChaoxingCore", "IOSAppStateSupport"],
            path: "Tests/ChaoxingCoreTests"
        ),
        .testTarget(
            name: "CaochaoxingMacTests",
            dependencies: ["CaochaoxingMac", "ChaoxingCore"],
            path: "Tests/CaochaoxingMacTests"
        )
    ]
)
