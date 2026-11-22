// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
@preconcurrency import MapKit

public struct IOSMapPlaceResult: Sendable {
    public let name: String
    public let address: String
    public let coordinate: CLLocationCoordinate2D
}

public enum IOSMapSearchHelper {
    public static func searchFirstPlace(query: String) async -> IOSMapPlaceResult? {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !Task.isCancelled else { return nil }
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = trimmed
        guard let response = try? await MKLocalSearch(request: request).start(),
              let item = response.mapItems.first, !Task.isCancelled else {
            return nil
        }
        return IOSMapPlaceResult(
            name: item.name ?? trimmed,
            address: item.placemark.title ?? item.name ?? trimmed,
            coordinate: item.placemark.coordinate
        )
    }
}
