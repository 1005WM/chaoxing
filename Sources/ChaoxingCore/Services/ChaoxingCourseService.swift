// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation
import CoreFoundation

public enum ChaoxingCourseService {
    private static let courseListURL = URL(string: "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1")!
    private static let queryClassNameBase = "https://mooc1-api.chaoxing.com/gas/clazz?fields=name&view=json"
    private static let defaultCourseCover = "https://p.ananas.chaoxing.com/star3/270_160c/669ca80d6a0c5f74835bb936a41aabca.jpg"

    public static func fetchCourses(client: ChaoxingHTTPClient) async throws -> [ChaoxingCourse] {
        var didReauthenticate = false
        var (data, response) = try await client.get(courseListURL)
        if response.statusCode == 401 || response.statusCode == 403 || response.statusCode == 302 {
            didReauthenticate = true
            _ = try await ChaoxingAuthService.reLogin(client: client)
            (data, response) = try await client.get(courseListURL)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取课程列表失败")
        }

        if let parsed = try? parseCourseListJSON(data) {
            return parsed
        }

        if didReauthenticate { return try parseCourseListJSON(data) }
        _ = try await ChaoxingAuthService.reLogin(client: client)
        let (retryData, retryResponse) = try await client.get(courseListURL)
        guard (200..<300).contains(retryResponse.statusCode) else {
            throw ChaoxingError.networkError(statusCode: retryResponse.statusCode, message: "获取课程列表失败")
        }
        return try parseCourseListJSON(retryData)
    }

    public static func parseCourseListJSON(_ data: Data) throws -> [ChaoxingCourse] {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ChaoxingError.parseFailed("课程列表 JSON 解析失败")
        }
        if let result = (json["result"] as? NSNumber)?.intValue, result == 0 {
            throw ChaoxingError.sessionExpired()
        }
        guard let channelList = json["channelList"] as? [[String: Any]] else {
            throw ChaoxingError.sessionExpired("课程列表为空或会话已过期")
        }

        var courses: [ChaoxingCourse] = []
        for item in channelList {
            guard item["cataName"] != nil,
                  let content = item["content"] as? [String: Any],
                  let courseWrapper = content["course"] as? [String: Any],
                  let dataArray = courseWrapper["data"] as? [[String: Any]],
                  let courseObj = dataArray.first else {
                continue
            }
            let courseName = (courseObj["name"] as? String) ?? "未命名课程"
            let teacherName = (courseObj["teacherfactor"] as? String) ?? ""
            let courseId = (courseObj["id"] as? NSNumber)?.int64Value ?? 0
            let classId = (content["id"] as? NSNumber)?.intValue ?? 0
            let imageURL = ((courseObj["imageurl"] as? String).flatMap { $0.isEmpty ? nil : $0 }) ?? defaultCourseCover
            let schools = courseObj["schools"] as? String
            let cpi = positiveIdentifier(content["cpi"]) ?? positiveIdentifier(item["cpi"])

            courses.append(ChaoxingCourse(
                courseName: courseName,
                teacherName: teacherName,
                courseId: courseId,
                classId: classId,
                imageURL: imageURL.replacingOccurrences(of: "http://", with: "https://"),
                schools: schools,
                cpi: cpi
            ))
        }
        return courses
    }

    static func positiveIdentifier(_ value: Any?) -> Int64? {
        let text: String
        if let string = value as? String {
            text = string.trimmingCharacters(in: .whitespacesAndNewlines)
        } else if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() {
            text = number.stringValue
        } else {
            return nil
        }
        guard let id = Int64(text), id > 0 else { return nil }
        return id
    }

    public static func checkClassValid(client: ChaoxingHTTPClient, classId: Int) async throws -> Bool? {
        let (data, response) = try await client.get(courseListURL)
        guard (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if (json["result"] as? NSNumber)?.intValue == 0 {
            return nil
        }
        guard let channelList = json["channelList"] as? [[String: Any]] else {
            return nil
        }
        for item in channelList {
            if let content = item["content"] as? [String: Any],
               (content["id"] as? NSNumber)?.intValue == classId {
                return true
            }
        }
        return false
    }

    public static func getCourseIdFromClassId(client: ChaoxingHTTPClient, classId: Int) async -> Int64? {
        guard let courses = try? await fetchCourses(client: client) else { return nil }
        return courses.first(where: { $0.classId == classId })?.courseId
    }

    public static func queryClassName(client: ChaoxingHTTPClient, classId: Int) async -> String? {
        guard var components = URLComponents(string: queryClassNameBase) else { return nil }
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "id", value: String(classId)))
        components.queryItems = items
        guard let url = components.chaoxingQueryURL,
              let (data, _) = try? await client.get(url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dataArray = json["data"] as? [[String: Any]] else {
            return nil
        }
        return dataArray.first?["name"] as? String
    }
}
