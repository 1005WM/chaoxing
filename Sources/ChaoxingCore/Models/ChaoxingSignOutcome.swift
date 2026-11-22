// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

public enum ChaoxingSignDisposition: Equatable, Sendable {
    case accepted
    case alreadySigned
    case failed

    public init(error: Error) {
        if case ChaoxingError.alreadySigned = error {
            self = .alreadySigned
        } else {
            self = .failed
        }
    }
}

public struct ChaoxingSignOutcome: Sendable {
    public let disposition: ChaoxingSignDisposition
    public let message: String
    public var success: Bool { disposition == .accepted }

    public init(disposition: ChaoxingSignDisposition, message: String) {
        self.disposition = disposition
        self.message = message
    }

    public init(success: Bool, message: String) {
        self.init(disposition: success ? .accepted : .failed, message: message)
    }
}

public struct ChaoxingSignCounts: Equatable, Sendable {
    public private(set) var accepted = 0
    public private(set) var alreadySigned = 0
    public private(set) var failed = 0
    public var total: Int { accepted + alreadySigned + failed }
    public var summary: String { "本次获接受 \(accepted) · 此前已签 \(alreadySigned) · 失败或未确认 \(failed)" }

    public init(dispositions: [ChaoxingSignDisposition]) {
        for disposition in dispositions {
            switch disposition {
            case .accepted: accepted += 1
            case .alreadySigned: alreadySigned += 1
            case .failed: failed += 1
            }
        }
    }

    init?(accepted: Int, alreadySigned: Int, total: Int) {
        guard accepted >= 0, total >= accepted, alreadySigned >= 0, alreadySigned <= total - accepted else { return nil }
        self.accepted = accepted
        self.alreadySigned = alreadySigned
        self.failed = total - accepted - alreadySigned
    }
}
