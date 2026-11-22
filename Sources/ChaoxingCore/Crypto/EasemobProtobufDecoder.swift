// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public enum EasemobProtobufDecoder {
    public struct DecodedMessage: Sendable {
        public let timestampMs: Int64
        public let attachmentJSON: String?
    }

    public static func decodeMetaMessage(from data: Data) -> DecodedMessage? {
        do {
            var reader = WireReader(data: data)
            var timestampMs: Int64 = 0
            var bodyData: Data?
            while let (fieldNumber, wireType) = try reader.readTag() {
                switch (fieldNumber, wireType) {
                case (4, 0): timestampMs = Int64(bitPattern: try reader.readVarint())
                case (6, 2): bodyData = try reader.readLengthDelimited()
                default: try reader.skipField(wireType: wireType)
                }
            }
            guard let bodyData else { return nil }
            return DecodedMessage(timestampMs: timestampMs, attachmentJSON: try decodeMessageBodyAttachment(from: bodyData))
        } catch {
            return nil
        }
    }

    private static func decodeMessageBodyAttachment(from data: Data) throws -> String? {
        var reader = WireReader(data: data)
        var attachment: String?
        while let (fieldNumber, wireType) = try reader.readTag() {
            if fieldNumber == 5, wireType == 2 {
                let (key, value) = try decodeKeyValue(from: reader.readLengthDelimited())
                if key == "attachment" { attachment = value }
            } else {
                try reader.skipField(wireType: wireType)
            }
        }
        return attachment
    }

    private static func decodeKeyValue(from data: Data) throws -> (String?, String?) {
        var reader = WireReader(data: data)
        var key: String?
        var stringValue: String?
        while let (fieldNumber, wireType) = try reader.readTag() {
            switch (fieldNumber, wireType) {
            case (1, 2): key = String(data: try reader.readLengthDelimited(), encoding: .utf8)
            case (6, 2): stringValue = String(data: try reader.readLengthDelimited(), encoding: .utf8)
            default: try reader.skipField(wireType: wireType)
            }
        }
        return (key, stringValue)
    }

    private enum WireError: Error { case malformed }

    private struct WireReader {
        private let bytes: [UInt8]
        private var offset = 0

        init(data: Data) { bytes = [UInt8](data) }

        mutating func readTag() throws -> (Int, Int)? {
            guard offset < bytes.count else { return nil }
            let raw = try readVarint()
            let field = raw >> 3
            let wire = Int(raw & 7)
            guard field > 0, field <= 536_870_911, [0, 1, 2, 5].contains(wire) else {
                throw WireError.malformed
            }
            return (Int(field), wire)
        }

        mutating func readVarint() throws -> UInt64 {
            var result: UInt64 = 0
            for index in 0..<10 {
                guard offset < bytes.count else { throw WireError.malformed }
                let byte = bytes[offset]
                offset += 1
                // A UInt64 has only one remaining bit in the tenth byte.
                guard index < 9 || byte <= 1 else { throw WireError.malformed }
                result |= UInt64(byte & 0x7f) << (index * 7)
                if byte & 0x80 == 0 { return result }
            }
            throw WireError.malformed
        }

        mutating func readLengthDelimited() throws -> Data {
            let length = try readVarint()
            // Compare before converting or adding: untrusted lengths may exceed Int.max.
            guard length <= UInt64(bytes.count - offset) else { throw WireError.malformed }
            let end = offset + Int(length)
            defer { offset = end }
            return Data(bytes[offset..<end])
        }

        mutating func skipField(wireType: Int) throws {
            switch wireType {
            case 0: _ = try readVarint()
            case 2: _ = try readLengthDelimited()
            case 1, 5:
                let count = wireType == 1 ? 8 : 4
                guard count <= bytes.count - offset else { throw WireError.malformed }
                offset += count
            default: throw WireError.malformed
            }
        }
    }
}
