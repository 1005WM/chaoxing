// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation

public enum ChaoxingError: LocalizedError, Sendable {
    case invalidInput(String)
    case networkError(statusCode: Int?, message: String)
    case loginFailed(String)
    case sessionExpired(String = "登录会话已过期，请重新登录")
    case parseFailed(String)
    case unsupportedSignType(String = "暂不支持此签到类型")
    case noPermission(String = "此用户不在班级或无权限访问该活动")
    case signExpired(String = "签到已截止")
    case signAlreadyEnded(String = "迟到或签到已结束")
    case alreadySigned(String = "已经签到过了")
    case deviceDuplicated(String = "同一设备不允许重复签到")
    case wrongPosition(distanceMeters: Double?)
    case qrCodeExpired(String = "二维码已过期，请重新获取")
    case qrCodeParseFailed(String = "二维码解析失败")
    case faceError(String)
    case captchaRequired(enc2: String?)
    case captchaFailed(String = "验证码校验失败，请重试")
    case serverMessage(String)

    public var errorDescription: String? {
        switch self {
        case .invalidInput(let msg): return msg
        case .networkError(let code, let msg):
            if let code { return "网络错误 (\(code)): \(msg)" }
            return "网络错误: \(msg)"
        case .loginFailed(let msg): return msg
        case .sessionExpired(let msg): return msg
        case .parseFailed(let msg): return msg
        case .unsupportedSignType(let msg): return msg
        case .noPermission(let msg): return msg
        case .signExpired(let msg): return msg
        case .signAlreadyEnded(let msg): return msg
        case .alreadySigned(let msg): return msg
        case .deviceDuplicated(let msg): return msg
        case .wrongPosition(let distance):
            if let distance {
                return String(format: "位置不在设置范围内，距离签到点约 %.1f 米", distance)
            }
            return "位置不在设置范围内"
        case .qrCodeExpired(let msg): return msg
        case .qrCodeParseFailed(let msg): return msg
        case .faceError(let msg): return msg
        case .captchaRequired: return "需要完成滑块验证码"
        case .captchaFailed(let msg): return msg
        case .serverMessage(let msg): return msg
        }
    }
}
