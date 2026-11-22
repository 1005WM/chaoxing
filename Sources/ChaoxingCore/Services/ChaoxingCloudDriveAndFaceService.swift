// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public enum ChaoxingCloudDriveService {
    private static let tokenURL = URL(string: "https://pan-yz.chaoxing.com/api/token/uservalid")!
    private static let uploadBase = "https://pan-yz.chaoxing.com/upload?_from=mobilelearn&_token="

    public static func getCloudToken(client: ChaoxingHTTPClient) async throws -> String {
        if let cached = client.storageCloudToken {
            return cached
        }
        let (data, response) = try await client.get(tokenURL)
        guard (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = json["_token"] as? String, !token.isEmpty else {
            throw ChaoxingError.parseFailed("获取超星云盘 Token 失败")
        }
        client.storageCloudToken = token
        return token
    }

    public static func uploadImage(client: ChaoxingHTTPClient, imageData: Data, filename: String = "\(UUID().uuidString).jpg") async throws -> String {
        guard !filename.isEmpty,
              filename.rangeOfCharacter(from: .controlCharacters) == nil,
              !filename.contains("\""), !filename.contains("\\") else {
            throw ChaoxingError.parseFailed("上传文件名包含非法字符")
        }
        do {
            return try await performUpload(client: client, imageData: imageData, filename: filename)
        } catch ChaoxingError.networkError(let status, _) where status == 401 || status == 403 {
            // Only an explicit authorization rejection is safe to retry. A timeout or
            // malformed success response may follow an upload already stored by the server.
            try Task.checkCancellation()
            client.storageCloudToken = nil
            return try await performUpload(client: client, imageData: imageData, filename: filename)
        }
    }

    private static func performUpload(client: ChaoxingHTTPClient, imageData: Data, filename: String) async throws -> String {
        let token = try await getCloudToken(client: client)
        try Task.checkCancellation()
        guard let url = URL(string: uploadBase + ChaoxingHTTPClient.formPercentEncode(token)) else {
            throw ChaoxingError.parseFailed("云盘上传 URL 无效")
        }
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()

        func appendString(_ str: String) {
            body.append(Data(str.utf8))
        }

        appendString("--\(boundary)\r\n")
        appendString("Content-Disposition: form-data; name=\"puid\"\r\n\r\n")
        appendString("\(client.session.puid)\r\n")

        appendString("--\(boundary)\r\n")
        appendString("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n")
        appendString("Content-Type: image/jpeg\r\n\r\n")
        body.append(imageData)
        appendString("\r\n")
        appendString("--\(boundary)--\r\n")

        let (data, response) = try await client.postRaw(
            url,
            body: body,
            contentType: "multipart/form-data; boundary=\(boundary)"
        )
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "上传照片到超星云盘失败")
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let objectId = json["objectId"] as? String, !objectId.isEmpty else {
            throw ChaoxingError.parseFailed("上传照片到超星云盘失败")
        }
        return objectId
    }
}

public enum ChaoxingFaceService {
    private static let profileFaceURL = URL(string: "https://mobilelearn.chaoxing.com/v2/apis/sign/collectionfilephotoEnc?DB_STRATEGY=DEFAULT")!
    private static let checkFaceBase = "https://mobilelearn.chaoxing.com/pptSign/check-face-result?DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId"
    public static let sharedImageURLFormat = "https://p.cldisk.com/star4/%@/origin.jpg"

    public static func fetchUserProfileFaceObjectId(client: ChaoxingHTTPClient) async throws -> String {
        let (data, response) = try await client.get(profileFaceURL)
        guard (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dataObj = json["data"] as? [String: Any],
              let oldObjectId = dataObj["oldObjectId"] as? String, !oldObjectId.isEmpty else {
            throw ChaoxingError.faceError("获取人脸底库照片失败，可能当前账号未设置过人脸识别照片")
        }
        return oldObjectId
    }

    public static func fetchUserProfileFaceImageURL(client: ChaoxingHTTPClient) async throws -> URL {
        let objectId = try await fetchUserProfileFaceObjectId(client: client)
        guard let url = URL(string: String(format: sharedImageURLFormat, objectId)) else {
            throw ChaoxingError.faceError("人脸图片地址无效")
        }
        return url
    }

    public static func checkFaceResultAndGetEnc(
        client: ChaoxingHTTPClient,
        objectId: String,
        activeId: Int64
    ) async throws -> String {
        let cxtime = String(Int64(Date().timeIntervalSince1970 * 1000))
        let faceResultDict: [String: Any] = [
            "currentFaceId": objectId,
            "LiveDetectionStatus": 1,
            "collectStatus": 1,
            "cxtime": cxtime
        ]
        let faceResultData = try JSONSerialization.data(withJSONObject: faceResultDict)
        let faceResultStr = String(data: faceResultData, encoding: .utf8) ?? "{}"

        guard var components = URLComponents(string: checkFaceBase) else {
            throw ChaoxingError.faceError("人脸校验 URL 构建失败")
        }
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "activeId", value: String(activeId)))
        items.append(URLQueryItem(name: "faceResult", value: faceResultStr))
        components.queryItems = items

        guard let url = components.url else {
            throw ChaoxingError.faceError("人脸校验 URL 无效")
        }
        let (data, response) = try await client.get(url)
        guard (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let enc = json["enc"] as? String, !enc.isEmpty else {
            throw ChaoxingError.faceError("获取人脸校验凭证 faceEnc 失败")
        }
        return enc
    }
}
