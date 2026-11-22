// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation

public struct ChaoxingLocationTarget: Codable, Sendable, Hashable {
    public let latitude: Double?
    public let longitude: Double?
    public let rangeMeters: Int?

    public init(latitude: Double?, longitude: Double?, rangeMeters: Int?) {
        self.latitude = latitude
        self.longitude = longitude
        self.rangeMeters = rangeMeters
    }
}

public struct ChaoxingLocationSignInfo: Codable, Sendable, Hashable {
    public var latitude: Double
    public var longitude: Double
    public var address: String
    public var enableRandomOffset: Bool
    public private(set) var randomizedLatitude: Double
    public private(set) var randomizedLongitude: Double

    public init(
        latitude: Double,
        longitude: Double,
        address: String,
        enableRandomOffset: Bool = true
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.address = address
        self.enableRandomOffset = enableRandomOffset
        if enableRandomOffset {
            let latOffset = Double.random(in: -0.000045...0.000045)
            let lonOffset = Double.random(in: -0.000045...0.000045)
            self.randomizedLatitude = latitude + latOffset
            self.randomizedLongitude = longitude + lonOffset
        } else {
            self.randomizedLatitude = latitude
            self.randomizedLongitude = longitude
        }
    }

    public mutating func disableRandomOffset() {
        self.enableRandomOffset = false
        self.randomizedLatitude = latitude
        self.randomizedLongitude = longitude
    }
}

public struct ChaoxingLocationPreset: Codable, Sendable, Hashable, Identifiable {
    public let id: UUID
    public var name: String
    public var address: String
    public var latitude: Double
    public var longitude: Double

    public init(
        id: UUID = UUID(),
        name: String,
        address: String,
        latitude: Double,
        longitude: Double
    ) {
        self.id = id
        self.name = name
        self.address = address
        self.latitude = latitude
        self.longitude = longitude
    }

    public func toSignInfo(enableRandomOffset: Bool = true) -> ChaoxingLocationSignInfo {
        ChaoxingLocationSignInfo(
            latitude: latitude,
            longitude: longitude,
            address: address,
            enableRandomOffset: enableRandomOffset
        )
    }
}
