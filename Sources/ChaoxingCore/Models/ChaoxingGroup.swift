// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation

public struct ChaoxingEasemobIMConfig: Codable, Sendable, Hashable {
    public let accessToken: String
    public let uuid: String
    public let username: String

    public init(accessToken: String, uuid: String, username: String) {
        self.accessToken = accessToken
        self.uuid = uuid
        self.username = username
    }
}

public struct ChaoxingIMGroup: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let name: String
    public let imageURL: String?

    public init(id: String, name: String, imageURL: String? = nil) {
        self.id = id
        self.name = name
        self.imageURL = imageURL
    }
}

public struct ChaoxingGroupSignActivity: Codable, Sendable, Hashable, Identifiable {
    public var id: Int64 { activeId }
    public let activeId: Int64
    public let title: String
    public let classId: Int
    public let courseId: Int64
    public let courseName: String
    public let startTimeTitle: String
    public let activeTypeName: String
    public let isUnsupportedLegacySign: Bool
    public let messageTimestampMs: Int64

    public var signType: ChaoxingSignType {
        ChaoxingSignType(atypeName: activeTypeName)
    }

    public init(
        activeId: Int64,
        title: String,
        classId: Int,
        courseId: Int64,
        courseName: String,
        startTimeTitle: String,
        activeTypeName: String,
        isUnsupportedLegacySign: Bool,
        messageTimestampMs: Int64
    ) {
        self.activeId = activeId
        self.title = title
        self.classId = classId
        self.courseId = courseId
        self.courseName = courseName
        self.startTimeTitle = startTimeTitle
        self.activeTypeName = activeTypeName
        self.isUnsupportedLegacySign = isUnsupportedLegacySign
        self.messageTimestampMs = messageTimestampMs
    }
}
