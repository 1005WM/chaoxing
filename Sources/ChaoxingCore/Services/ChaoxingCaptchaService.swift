// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public enum ChaoxingCaptchaService {
    private static let captchaId = "Qt9FIw9o4pwRjOyqM6yizZBh682qN2TU"
    private static let captchaConfBase = "https://captcha.chaoxing.com/captcha/get/conf?callback=cx_captcha_function"
    private static let captchaImageBase = "https://captcha.chaoxing.com/captcha/get/verification/image"
    private static let captchaResultBase = "https://captcha.chaoxing.com/captcha/check/verification/result?callback=cx_captcha_function"

    public static func fetchCaptcha(
        client: ChaoxingHTTPClient,
        refererURL: String
    ) async throws -> (captcha: ChaoxingCaptchaData, shadeImageData: Data, cutoutImageData: Data) {
        let t = try await getCaptchaConf(client: client)
        let type = "slide"
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let captchaKey = ChaoxingCrypto.md5Hex("\(t)\(UUID().uuidString)")
        let iv = ChaoxingCrypto.md5Hex("\(captchaId)\(type)\(nowMs)\(UUID().uuidString)")
        let (expiresAt, overflow) = t.addingReportingOverflow(300_000)
        guard t > 0, !overflow else { throw ChaoxingError.captchaFailed("验证码配置时间无效") }
        let token = ChaoxingCrypto.md5Hex("\(t)\(captchaId)\(type)\(captchaKey)") + ":\(expiresAt)"

        var components = URLComponents(string: captchaImageBase)!
        components.queryItems = [
            URLQueryItem(name: "callback", value: "cx_captcha_function"),
            URLQueryItem(name: "captchaId", value: captchaId),
            URLQueryItem(name: "type", value: type),
            URLQueryItem(name: "version", value: "1.1.20"),
            URLQueryItem(name: "captchaKey", value: captchaKey),
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "referer", value: refererURL),
            URLQueryItem(name: "iv", value: iv),
            URLQueryItem(name: "_", value: String(nowMs))
        ]
        guard let url = components.chaoxingQueryURL else {
            throw ChaoxingError.captchaFailed("验证码图片 URL 无效")
        }
        let (data, response) = try await client.get(url)
        guard (200..<300).contains(response.statusCode),
              let json = parseJSONP(data),
              let respToken = json["token"] as? String,
              let imageVo = json["imageVerificationVo"] as? [String: Any],
              let rawShadeURL = imageVo["shadeImage"] as? String,
              let rawCutoutURL = imageVo["cutoutImage"] as? String else {
            throw ChaoxingError.captchaFailed("获取滑块验证码失败")
        }
        let shadeURLStr = rawShadeURL.replacingOccurrences(of: "http://", with: "https://")
        let cutoutURLStr = rawCutoutURL.replacingOccurrences(of: "http://", with: "https://")
        guard let shadeURL = URL(string: shadeURLStr),
              let cutoutURL = URL(string: cutoutURLStr) else {
            throw ChaoxingError.captchaFailed("验证码图片地址无效")
        }

        async let shadeTask = client.get(shadeURL)
        async let cutoutTask = client.get(cutoutURL)
        let ((shadeData, shadeResponse), (cutoutData, cutoutResponse)) = try await (shadeTask, cutoutTask)
        guard (200..<300).contains(shadeResponse.statusCode), (200..<300).contains(cutoutResponse.statusCode),
              !shadeData.isEmpty, !cutoutData.isEmpty else {
            throw ChaoxingError.captchaFailed("下载验证码图片失败")
        }

        let captcha = ChaoxingCaptchaData(
            captchaId: captchaId,
            type: type,
            version: "1.1.20",
            token: respToken,
            captchaKey: captchaKey,
            iv: iv,
            shadeImageURL: shadeURLStr,
            cutoutImageURL: cutoutURLStr
        )
        return (captcha, shadeData, cutoutData)
    }

    public static func verifyCaptcha(
        client: ChaoxingHTTPClient,
        captcha: ChaoxingCaptchaData,
        xPosition: Int,
        refererURL: String
    ) async throws -> String? {
        var components = URLComponents(string: captchaResultBase)!
        var items = components.queryItems ?? []
        items.append(contentsOf: [
            URLQueryItem(name: "captchaId", value: captcha.captchaId),
            URLQueryItem(name: "type", value: captcha.type),
            URLQueryItem(name: "token", value: captcha.token),
            URLQueryItem(name: "textClickArr", value: "[{\"x\":\(xPosition)}]"),
            URLQueryItem(name: "coordinate", value: "[]"),
            URLQueryItem(name: "runEnv", value: "10"),
            URLQueryItem(name: "version", value: captcha.version),
            URLQueryItem(name: "t", value: "a"),
            URLQueryItem(name: "iv", value: captcha.iv),
            URLQueryItem(name: "_", value: String(Int64(Date().timeIntervalSince1970 * 1000)))
        ])
        components.queryItems = items

        guard let url = components.chaoxingQueryURL else {
            throw ChaoxingError.captchaFailed("验证码校验 URL 无效")
        }
        let (data, response) = try await client.get(url, headers: ["Referer": refererURL])
        guard (200..<300).contains(response.statusCode),
              let json = parseJSONP(data) else {
            throw ChaoxingError.captchaFailed("验证码响应解析失败")
        }
        if (json["error"] as? NSNumber)?.intValue == 1 {
            let msg = (json["msg"] as? String) ?? "未知错误"
            throw ChaoxingError.captchaFailed("\(msg)，验证码校验失败")
        }
        guard (json["result"] as? Bool) == true else {
            return nil
        }
        if let extraStr = json["extraData"] as? String {
            let normalized = extraStr.replacingOccurrences(of: "\\\"", with: "\"")
            if let extraData = normalized.data(using: .utf8),
               let extraJSON = try? JSONSerialization.jsonObject(with: extraData) as? [String: Any],
               let validate = extraJSON["validate"] as? String {
                return validate
            }
        }
        return nil
    }

    private static func getCaptchaConf(client: ChaoxingHTTPClient) async throws -> Int64 {
        var components = URLComponents(string: captchaConfBase)!
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "captchaId", value: captchaId))
        items.append(URLQueryItem(name: "_", value: String(Int64(Date().timeIntervalSince1970 * 1000))))
        components.queryItems = items

        let (data, response) = try await client.get(components.chaoxingQueryURL!)
        guard (200..<300).contains(response.statusCode),
              let json = parseJSONP(data),
              let t = (json["t"] as? NSNumber)?.int64Value else {
            throw ChaoxingError.captchaFailed("获取验证码配置失败")
        }
        return t
    }

    private static func parseJSONP(_ data: Data) -> [String: Any]? {
        guard var text = String(data: data, encoding: .utf8) else { return nil }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasSuffix(";") { text = String(text.dropLast()).trimmingCharacters(in: .whitespacesAndNewlines) }
        if text.hasPrefix("cx_captcha_function(") && text.hasSuffix(")") {
            text = String(text.dropFirst("cx_captcha_function(".count).dropLast())
        }
        guard let jsonData = text.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any]
    }
}
