// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public enum ChaoxingAuthService {
    private static let loginURL = URL(string: "https://passport2.chaoxing.com/fanyalogin")!
    private static let userInfoURL = URL(string: "https://sso.chaoxing.com/apis/login/userLogin4Uname.do")!

    public static func login(
        phoneNumber: String,
        password: String,
        isEncryptedPassword: Bool = false,
        userAgent: String = ChaoxingClientPreset.default.userAgent,
        existingDeviceCode: String? = nil,
        isPrimary: Bool = false
    ) async throws -> ChaoxingAccountSession {
        let trimmedPhone = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedPhone.isEmpty else {
            throw ChaoxingError.invalidInput("手机号/账号不能为空")
        }
        if !isEncryptedPassword {
            guard !password.isEmpty else {
                throw ChaoxingError.invalidInput("密码不能为空")
            }
            guard (8...16).contains(password.count) else {
                throw ChaoxingError.invalidInput("密码位数应在 8-16 位之间")
            }
        }

        let deviceCode = existingDeviceCode?.isEmpty == false
            ? existingDeviceCode!
            : ChaoxingCrypto.randomizedDeviceCode()

        let tempSession = ChaoxingAccountSession(
            phoneNumber: trimmedPhone,
            encryptedPassword: isEncryptedPassword ? password : try ChaoxingCrypto.encryptLoginField(password),
            deviceCode: deviceCode,
            isPrimary: isPrimary
        )
        let client = ChaoxingHTTPClient(session: tempSession, userAgent: userAgent)
        try await performLogin(client: client, phoneNumber: trimmedPhone, encryptedPassword: tempSession.encryptedPassword)

        let profile = try await fetchUserProfile(client: client)
        return try client.applyAuthenticatedProfile(profile, matching: tempSession)
    }

    public static func reLogin(client: ChaoxingHTTPClient) async throws -> ChaoxingAccountSession {
        try await client.reauthenticate { try await performReLogin(client: client) }
    }

    private static func performReLogin(client: ChaoxingHTTPClient) async throws -> ChaoxingAccountSession {
        let context = client.authenticationContext()
        let session = context.session
        guard !session.phoneNumber.isEmpty, !session.encryptedPassword.isEmpty else {
            throw ChaoxingError.sessionExpired()
        }
        try await performLogin(
            client: client,
            phoneNumber: session.phoneNumber,
            encryptedPassword: session.encryptedPassword
        )
        let profile = try await fetchUserProfile(client: client)
        return try client.applyAuthenticatedProfile(profile, matching: session, generation: context.generation)
    }

    private static func performLogin(
        client: ChaoxingHTTPClient,
        phoneNumber: String,
        encryptedPassword: String
    ) async throws {
        let encryptedPhone = try ChaoxingCrypto.encryptLoginField(phoneNumber)
        let encodedBody = [
            "fid=-1",
            "uname=\(ChaoxingCrypto.urlFormEncodeBase64(encryptedPhone))",
            "password=\(ChaoxingCrypto.urlFormEncodeBase64(encryptedPassword))",
            "refer=https%3A%2F%2Fi.chaoxing.com",
            "t=true",
            "forbidotherlogin=0",
            "validate=",
            "doubleFactorLogin=0",
            "independentId=0",
            "independentNameId=0"
        ].joined(separator: "&")

        let (data, response) = try await client.postFormEncoded(loginURL, encodedBody: encodedBody)
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "登录请求失败")
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ChaoxingError.parseFailed("登录响应格式错误")
        }
        let status = (json["status"] as? Bool) ?? false
        if !status {
            let msg2 = (json["msg2"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            throw ChaoxingError.loginFailed((msg2?.isEmpty == false ? msg2 : nil) ?? "登录失败，请检查账号或密码")
        }
    }

    private static func positiveIdentifier(_ value: Any?) -> Int? {
        if let text = value as? String, let integer = Int(text), integer > 0 { return integer }
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              let integer = Int(number.stringValue), integer > 0 else { return nil }
        return integer
    }

    public static func fetchUserProfile(client: ChaoxingHTTPClient) async throws -> ChaoxingUserProfile {
        let (data, response) = try await client.get(userInfoURL)
        guard (200..<300).contains(response.statusCode) else {
            if response.statusCode == 403 || response.statusCode == 302 {
                throw ChaoxingError.sessionExpired()
            }
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取用户信息失败")
        }
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let msg = root["msg"] as? [String: Any] else {
            throw ChaoxingError.sessionExpired("获取用户信息失败，会话可能已过期")
        }

        guard let uid = positiveIdentifier(msg["uid"]), let puid = positiveIdentifier(msg["puid"]) else {
            throw ChaoxingError.sessionExpired("用户信息缺少有效账号标识，请重新登录")
        }
        let fid = positiveIdentifier(msg["fid"]) ?? 0
        let name = (msg["name"] as? String) ?? ""
        let uname = (msg["uname"] as? String) ?? ""
        let pic = ((msg["pic"] as? String) ?? "").replacingOccurrences(of: "http://", with: "https://")
        let clientId = (msg["clientId"] as? String).flatMap { $0.isEmpty ? nil : $0 }

        var imPassword = ""
        if let accountInfo = msg["accountInfo"] as? [String: Any],
           let imAccount = accountInfo["imAccount"] as? [String: Any] {
            imPassword = (imAccount["password"] as? String) ?? ""
        }

        var schoolsMap: [(Int, String)] = []
        let defaultSchoolName = (msg["schoolname"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "未知学校"
        schoolsMap.append((fid, defaultSchoolName))

        if let configs = msg["unitConfigInfos"] as? [[String: Any]] {
            for config in configs {
                guard let configFid = (config["fid"] as? NSNumber)?.intValue else { continue }
                let schoolName = (config["schoolname"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "未知学校"
                if let existingIndex = schoolsMap.firstIndex(where: { $0.0 == configFid }) {
                    if schoolsMap[existingIndex].1 == "未知学校", schoolName != "未知学校" {
                        schoolsMap[existingIndex].1 = schoolName
                    }
                } else {
                    schoolsMap.append((configFid, schoolName))
                }
            }
        }

        let schools = schoolsMap.map { ChaoxingSchool(fid: $0.0, name: $0.1) }
        return ChaoxingUserProfile(
            uid: uid,
            puid: puid,
            fid: fid,
            name: name,
            uname: uname,
            avatarURL: pic,
            phoneNumber: client.session.phoneNumber,
            imEncryptedPassword: imPassword,
            clientId: clientId,
            schools: schools
        )
    }
}
