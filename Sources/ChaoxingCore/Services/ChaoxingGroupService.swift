// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public enum ChaoxingGroupService {
    private static let easemobTokenURL = URL(string: "https://a1-vip6.easemob.com/cx-dev/cxstudy/token")!
    private static let easemobGroupsFormat = "https://a1-vip6.easemob.com/cx-dev/cxstudy/users/%@/joined_chatgroups?detail=true&version=v3&pagenum=1&pagesize=200"
    private static let messageRoamingFormat = "https://a1-vip6.easecdn.com/cx-dev/cxstudy/users/%@/messageroaming"
    private static let easemobUserAgent = "Easemob-SDK(Android) 4.9.0.1"

    public static func getEasemobConfig(client: ChaoxingHTTPClient) async throws -> ChaoxingEasemobIMConfig {
        let imPassword = ChaoxingCrypto.decryptIMPasswordDES(client.session.imEncryptedPassword)
        let payload: [String: Any] = [
            "grant_type": "password",
            "password": imPassword,
            "username": client.session.uid
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await client.postRaw(
            easemobTokenURL,
            body: body,
            contentType: "application/x-www-form-urlencoded",
            headers: ["User-Agent": easemobUserAgent]
        )
        guard (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = json["access_token"] as? String,
              let userObj = json["user"] as? [String: Any],
              let uuid = userObj["uuid"] as? String,
              let username = userObj["username"] as? String else {
            throw ChaoxingError.parseFailed("获取环信 IM 配置失败")
        }
        return ChaoxingEasemobIMConfig(accessToken: token, uuid: uuid, username: username)
    }

    public static func fetchJoinedGroups(
        client: ChaoxingHTTPClient,
        imConfig: ChaoxingEasemobIMConfig
    ) async throws -> [ChaoxingIMGroup] {
        guard let username = imConfig.username.addingPercentEncoding(withAllowedCharacters: .alphanumerics), !username.isEmpty,
              let base = URLComponents(string: String(format: easemobGroupsFormat, username)) else {
            throw ChaoxingError.parseFailed("群聊列表 URL 无效")
        }
        var groups: [ChaoxingIMGroup] = []
        var seen = Set<String>()
        // The endpoint explicitly exposes pagenum/pagesize. Stop on a short page;
        // reject repeated full pages instead of silently returning an incomplete list.
        for page in 1...100 {
            try Task.checkCancellation()
            var components = base
            components.queryItems = (base.queryItems ?? []).filter { $0.name != "pagenum" }
                + [URLQueryItem(name: "pagenum", value: String(page))]
            guard let url = components.chaoxingQueryURL else { throw ChaoxingError.parseFailed("群聊列表 URL 无效") }
            let (data, response) = try await client.get(url, headers: [
                "Authorization": "Bearer \(imConfig.accessToken)",
                "User-Agent": easemobUserAgent
            ])
            guard (200..<300).contains(response.statusCode) else {
                if response.statusCode == 401 || response.statusCode == 403 { throw ChaoxingError.sessionExpired("群聊会话已过期，请重新加载群聊") }
                throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取群聊列表失败")
            }
            guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let items = root["data"] as? [[String: Any]] else { throw ChaoxingError.parseFailed("获取群聊列表失败") }
            let previousCount = groups.count
            for item in items {
                if let group = parseJoinedGroup(item), seen.insert(group.id).inserted { groups.append(group) }
            }
            if items.count < 200 { return groups }
            guard groups.count > previousCount else { throw ChaoxingError.parseFailed("群聊分页未返回新数据，请稍后重试") }
        }
        throw ChaoxingError.parseFailed("群聊分页超过处理上限，未能获取完整列表")
    }

    private static func parseJoinedGroup(_ obj: [String: Any]) -> ChaoxingIMGroup? {
        guard let id = obj["id"] as? String, !id.isEmpty else { return nil }
        let rawName = obj["name"] as? String
        var courseName: String?
        var imageURL: String?
        if let descStr = obj["description"] as? String,
           let descData = descStr.data(using: .utf8),
           let descJSON = try? JSONSerialization.jsonObject(with: descData) as? [String: Any],
           let courseInfo = descJSON["courseInfo"] as? [String: Any] {
            courseName = courseInfo["coursename"] as? String
            imageURL = (courseInfo["imageUrl"] as? String)?.replacingOccurrences(of: "http://", with: "https://")
        }
        return ChaoxingIMGroup(id: id, name: (rawName?.isEmpty == false ? rawName : nil) ?? courseName ?? "未命名群聊", imageURL: imageURL)
    }

    public static func fetchGroupSignActivities(
        client: ChaoxingHTTPClient,
        group: ChaoxingIMGroup,
        imConfig: ChaoxingEasemobIMConfig
    ) async throws -> [ChaoxingGroupSignActivity] {
        guard let username = imConfig.username.addingPercentEncoding(withAllowedCharacters: .alphanumerics), !username.isEmpty else {
            throw ChaoxingError.invalidInput("群聊用户名无效")
        }
        let urlStr = String(format: messageRoamingFormat, username)
        guard let url = URL(string: urlStr) else {
            throw ChaoxingError.parseFailed("群聊消息 URL 无效")
        }
        let payload: [String: Any] = [
            "end": "-1",
            "queue": "\(group.id)@conference.easemob.com",
            "start": "-1"
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await client.postRaw(
            url,
            body: body,
            contentType: "text/plain;charset=UTF-8",
            headers: [
                "Authorization": "Bearer \(imConfig.accessToken)",
                "User-Agent": easemobUserAgent
            ]
        )
        guard (200..<300).contains(response.statusCode) else {
            if response.statusCode == 401 || response.statusCode == 403 { throw ChaoxingError.sessionExpired("群聊会话已过期，请重新加载群聊") }
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取群聊消息失败")
        }
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dataObj = root["data"] as? [String: Any],
              let msgs = dataObj["msgs"] as? [[String: Any]] else {
            throw ChaoxingError.parseFailed("群聊消息响应格式错误")
        }

        var activities: [ChaoxingGroupSignActivity] = []
        var seenActiveIds = Set<Int64>()

        for msgObj in msgs {
            guard let msgStr = msgObj["msg"] as? String,
                  let msgData = Data(base64Encoded: msgStr, options: .ignoreUnknownCharacters),
                  let decoded = EasemobProtobufDecoder.decodeMetaMessage(from: msgData),
                  let attachmentStr = decoded.attachmentJSON,
                  let activity = parseGroupSignActivity(
                      attachmentJSON: attachmentStr,
                      timestampMs: decoded.timestampMs,
                      fallbackGroupName: group.name
                  ),
                  !seenActiveIds.contains(activity.activeId) else {
                continue
            }
            seenActiveIds.insert(activity.activeId)
            activities.append(activity)
        }

        return activities.sorted { $0.messageTimestampMs > $1.messageTimestampMs }
    }

    /// 解析环信群聊签到附件 JSON（兼容新版 att_chat_course 与旧版/带位置变体，对应 Issue #64 / #237）
    public static func parseGroupSignActivity(
        attachmentJSON: String,
        timestampMs: Int64,
        fallbackGroupName: String
    ) -> ChaoxingGroupSignActivity? {
        guard let attData = attachmentJSON.data(using: .utf8),
              let attJSON = try? JSONSerialization.jsonObject(with: attData) as? [String: Any] else {
            return nil
        }
        let attachmentType = (attJSON["attachmentType"] as? NSNumber)?.intValue
        guard attachmentType == 15 || attJSON["att_chat_course"] != nil else {
            return nil
        }

        let signInfo = (attJSON["att_chat_course"] as? [String: Any])
            ?? (attJSON["att_chat_sign"] as? [String: Any])
            ?? attJSON

        let atype = (signInfo["atype"] as? NSNumber)?.intValue ?? 0
        let activeTypeName = (signInfo["atypeName"] as? String) ?? ""
        let rawUrl = (signInfo["url"] as? String) ?? (signInfo["pcUrl"] as? String) ?? ""
        let isSignKeyword = activeTypeName.contains("签到") || activeTypeName.contains("打卡")
            || rawUrl.contains("activePrimaryId") || rawUrl.contains("stuSign") || rawUrl.contains("preSign")
        guard atype == 2 || atype == 74 || isSignKeyword else {
            return nil
        }

        let activeId = extractInt64(signInfo["aid"])
            ?? extractInt64(signInfo["activeId"])
            ?? extractActiveIdFromURL(rawUrl)
            ?? 0
        guard activeId != 0 else { return nil }

        let courseInfo = signInfo["courseInfo"] as? [String: Any]
        let classId = extractInt(courseInfo?["classid"])
            ?? extractInt(signInfo["classId"])
            ?? extractQueryInt(rawUrl, keys: ["classId", "classid"])
            ?? 0
        let courseId = extractInt64(courseInfo?["courseid"])
            ?? extractInt64(signInfo["courseId"])
            ?? extractQueryInt64(rawUrl, keys: ["courseId", "courseid"])
            ?? 0
        let courseName = (courseInfo?["coursename"] as? String) ?? fallbackGroupName
        let title = (signInfo["title"] as? String) ?? (activeTypeName.isEmpty ? "群聊签到" : activeTypeName)
        let startTimeTitle = (signInfo["subTitle"] as? String) ?? ""

        return ChaoxingGroupSignActivity(
            activeId: activeId,
            title: title,
            classId: classId,
            courseId: courseId,
            courseName: courseName,
            startTimeTitle: startTimeTitle,
            activeTypeName: activeTypeName,
            isUnsupportedLegacySign: false,
            messageTimestampMs: timestampMs
        )
    }

    private static func extractInt64(_ raw: Any?) -> Int64? {
        if let num = raw as? NSNumber { return num.int64Value }
        if let str = raw as? String { return Int64(str) }
        return nil
    }

    private static func extractInt(_ raw: Any?) -> Int? {
        if let num = raw as? NSNumber { return num.intValue }
        if let str = raw as? String { return Int(str) }
        return nil
    }

    private static func extractActiveIdFromURL(_ urlString: String) -> Int64? {
        extractQueryInt64(urlString, keys: ["activePrimaryId", "activeId", "aid", "id"])
    }

    private static func extractQueryInt64(_ urlString: String, keys: [String]) -> Int64? {
        guard let components = URLComponents(string: urlString), let items = components.queryItems else {
            return nil
        }
        for key in keys {
            if let value = items.first(where: { $0.name.caseInsensitiveCompare(key) == .orderedSame })?.value,
               let num = Int64(value), num != 0 {
                return num
            }
        }
        return nil
    }

    private static func extractQueryInt(_ urlString: String, keys: [String]) -> Int? {
        extractQueryInt64(urlString, keys: keys).map(Int.init)
    }
}
