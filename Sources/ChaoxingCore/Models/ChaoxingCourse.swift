// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation

public enum ChaoxingSignType: String, Codable, Sendable, Hashable {
    case photoOrClick = "0"
    case qrCode = "2"
    case gesture = "3"
    case location = "4"
    case password = "5"
    case unknown = "-1"

    public init(otherId: String?) {
        self = ChaoxingSignType(rawValue: otherId ?? "") ?? .unknown
    }

    public init(atypeName: String) {
        switch atypeName {
        case "拍照签到", "普通签到", "签到":
            self = .photoOrClick
        case "二维码签到":
            self = .qrCode
        case "手势签到":
            self = .gesture
        case "位置签到":
            self = .location
        case "密码签到", "签到码签到":
            self = .password
        default:
            self = .unknown
        }
    }

    public var displayName: String {
        switch self {
        case .photoOrClick: return "拍照 / 点击签到"
        case .qrCode: return "二维码签到"
        case .gesture: return "手势签到"
        case .location: return "位置签到"
        case .password: return "签到码签到"
        case .unknown: return "其他签到"
        }
    }

    public var systemImage: String {
        switch self {
        case .photoOrClick: return "camera.viewfinder"
        case .qrCode: return "qrcode.viewfinder"
        case .gesture: return "hand.draw"
        case .location: return "location.fill"
        case .password: return "number.square"
        case .unknown: return "checkmark.circle"
        }
    }
}

public enum ChaoxingSignActivityStatus: String, Sendable, Codable {
    case readyToSign
    case alreadySigned
    case expired
}

public struct ChaoxingCourse: Codable, Sendable, Hashable, Identifiable {
    public var id: String { "\(courseId)_\(classId)" }
    public let courseName: String
    public let teacherName: String
    public let courseId: Int64
    public let classId: Int
    public let imageURL: String
    public let schools: String?
    public let cpi: Int64?

    public init(
        courseName: String,
        teacherName: String,
        courseId: Int64,
        classId: Int,
        imageURL: String,
        schools: String? = nil,
        cpi: Int64? = nil
    ) {
        self.courseName = courseName
        self.teacherName = teacherName
        self.courseId = courseId
        self.classId = classId
        self.imageURL = imageURL
        self.schools = schools
        self.cpi = cpi
    }
}

public struct ChaoxingSignActivity: Codable, Sendable, Hashable, Identifiable {
    public let id: Int64
    public let startTimeMs: Int64?
    public let endTimeMs: Int64?
    public let userStatus: Int
    public let otherId: String
    public let isLook: Bool
    public let type: Int
    public let activeType: Int
    public let nameOne: String
    public let status: Int
    public let nameFour: String
    public let courseId: Int64
    public let classId: Int
    public let courseName: String
    public let extContent: String

    public var signType: ChaoxingSignType {
        ChaoxingSignType(otherId: otherId)
    }

    public var isAvailable: Bool {
        status == 1
    }

    public var isAlreadySignedByStatus: Bool {
        [1, 2, 3, 9].contains(userStatus)
    }

    public var isLate: Bool {
        guard let endTimeMs, endTimeMs > 0 else { return false }
        return Int64(Date().timeIntervalSince1970 * 1000) > endTimeMs
    }

    public init(
        id: Int64,
        startTimeMs: Int64?,
        endTimeMs: Int64?,
        userStatus: Int,
        otherId: String,
        isLook: Bool,
        type: Int,
        activeType: Int,
        nameOne: String,
        status: Int,
        nameFour: String,
        courseId: Int64,
        classId: Int,
        courseName: String,
        extContent: String
    ) {
        self.id = id
        self.startTimeMs = startTimeMs
        self.endTimeMs = endTimeMs
        self.userStatus = userStatus
        self.otherId = otherId
        self.isLook = isLook
        self.type = type
        self.activeType = activeType
        self.nameOne = nameOne
        self.status = status
        self.nameFour = nameFour
        self.courseId = courseId
        self.classId = classId
        self.courseName = courseName
        self.extContent = extContent
    }
}

public struct ChaoxingSignOutInfo: Sendable, Hashable {
    public let signInId: Int64?
    public let signOutId: Int64?
    public let signOutPublishTimeMs: Int64?
    public let classId: Int
    public let courseId: Int64

    public init(
        signInId: Int64?,
        signOutId: Int64?,
        signOutPublishTimeMs: Int64?,
        classId: Int,
        courseId: Int64
    ) {
        self.signInId = signInId
        self.signOutId = signOutId
        self.signOutPublishTimeMs = signOutPublishTimeMs
        self.classId = classId
        self.courseId = courseId
    }
}

public struct ChaoxingSignDetailMetadata: Sendable, Hashable {
    public let activeId: Int64
    public let signType: ChaoxingSignType
    public let title: String
    public let isCaptchaRequired: Bool
    public let isFaceRequired: Bool
    public let isPositionRequired: Bool
    public let isPhotoRequired: Bool
    public let isRefreshQRCode: Bool
    public let numberCount: Int?
    public let targetLocation: ChaoxingLocationTarget?
    public let signOutInfo: ChaoxingSignOutInfo

    public init(
        activeId: Int64,
        signType: ChaoxingSignType,
        title: String,
        isCaptchaRequired: Bool,
        isFaceRequired: Bool,
        isPositionRequired: Bool,
        isPhotoRequired: Bool,
        isRefreshQRCode: Bool,
        numberCount: Int?,
        targetLocation: ChaoxingLocationTarget?,
        signOutInfo: ChaoxingSignOutInfo
    ) {
        self.activeId = activeId
        self.signType = signType
        self.title = title
        self.isCaptchaRequired = isCaptchaRequired
        self.isFaceRequired = isFaceRequired
        self.isPositionRequired = isPositionRequired
        self.isPhotoRequired = isPhotoRequired
        self.isRefreshQRCode = isRefreshQRCode
        self.numberCount = numberCount
        self.targetLocation = targetLocation
        self.signOutInfo = signOutInfo
    }
}
