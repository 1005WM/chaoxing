// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation
import CoreImage

public enum ChaoxingQRCodeParser {
    public static func parse(rawValue: String) throws -> ChaoxingQRCodeParseResult {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw ChaoxingError.qrCodeParseFailed("二维码内容为空")
        }

        if trimmed.hasPrefix("SIGNIN:") {
            let content = String(trimmed.dropFirst("SIGNIN:".count)).components(separatedBy: "-").first ?? ""
            guard let query = URLComponents(string: "https://qr.invalid/?" + content)?.queryItems else {
                throw ChaoxingError.qrCodeParseFailed("无效的 SIGNIN 二维码格式")
            }
            return try parseQueryItems(query)
        }
        guard let components = URLComponents(string: trimmed),
              ["http", "https"].contains(components.scheme?.lowercased() ?? ""), components.host != nil else {
            throw ChaoxingError.qrCodeParseFailed("无法解析二维码链接")
        }
        return try parseQueryItems(components.queryItems ?? [])
    }

    private static func parseQueryItems(_ items: [URLQueryItem]) throws -> ChaoxingQRCodeParseResult {
        let encValues = items.filter { $0.name == "enc" }.compactMap(\.value)
        guard let enc = encValues.first, !enc.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              Set(encValues).count == 1 else { throw ChaoxingError.qrCodeParseFailed("二维码缺少或包含冲突的 enc 参数") }
        let targets = items.filter { ["id", "aid", "activePrimaryId"].contains($0.name) }
        let ids = targets.compactMap { $0.value.flatMap(Int64.init) }.filter { $0 > 0 }
        guard targets.isEmpty || !ids.isEmpty, Set(ids).count <= 1 else {
            throw ChaoxingError.qrCodeParseFailed("二维码活动 ID 无效或相互冲突")
        }
        let code = items.first { ["c", "code"].contains($0.name.lowercased()) }?.value
        return ChaoxingQRCodeParseResult(aid: ids.first, code: code?.isEmpty == false ? code : nil, enc: enc)
    }

    public static func decodeQRCodeStrings(from imageData: Data) -> [String] {
        guard let ciImage = CIImage(data: imageData) else { return [] }
        let context = CIContext()
        let options = [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        guard let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: context, options: options) else {
            return []
        }
        let features = detector.features(in: ciImage)
        return features.compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }
}
