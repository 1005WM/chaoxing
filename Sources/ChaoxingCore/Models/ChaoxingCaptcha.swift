// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation

public struct ChaoxingCaptchaData: Sendable, Hashable {
    public let captchaId: String
    public let type: String
    public let version: String
    public let token: String
    public let captchaKey: String
    public let iv: String
    public let shadeImageURL: String
    public let cutoutImageURL: String

    public init(
        captchaId: String,
        type: String,
        version: String,
        token: String,
        captchaKey: String,
        iv: String,
        shadeImageURL: String,
        cutoutImageURL: String
    ) {
        self.captchaId = captchaId
        self.type = type
        self.version = version
        self.token = token
        self.captchaKey = captchaKey
        self.iv = iv
        self.shadeImageURL = shadeImageURL
        self.cutoutImageURL = cutoutImageURL
    }
}

public struct ChaoxingQRCodeParseResult: Sendable, Hashable, Codable {
    public let aid: Int64?
    public let code: String?
    public let enc: String

    public init(aid: Int64?, code: String?, enc: String) {
        self.aid = aid
        self.code = code
        self.enc = enc
    }
}
