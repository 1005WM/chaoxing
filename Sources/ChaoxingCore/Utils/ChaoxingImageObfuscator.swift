// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// 图片防重微扰工具（对应 Issue #209：规避教师端重复图片检测）
/// 通过微调尺寸/裁剪边缘、轻微像素色彩扰动与抹除 EXIF 元数据重编码 JPEG，
/// 使同一张照片每次生成的二进制数据与哈希值均不相同。
public enum ChaoxingImageObfuscator {
    public static func obfuscateJPEG(_ inputData: Data, quality: Double = 0.86) -> Data {
        guard let source = CGImageSourceCreateWithData(inputData as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              width > 0, height > 0 else {
            return appendRandomJPEGComment(to: inputData)
        }
        // Apply EXIF rotation/mirroring before metadata is discarded. Decode a bounded
        // thumbnail so a high-resolution photo cannot require a full-size RGBA copy.
        let decodeOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: min(4096, max(width, height))
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, decodeOptions as CFDictionary) else {
            return appendRandomJPEGComment(to: inputData)
        }

        let origWidth = cgImage.width
        let origHeight = cgImage.height
        guard origWidth > 16, origHeight > 16 else {
            return appendRandomJPEGComment(to: inputData)
        }

        // 随机裁剪 1~3 像素边缘并微调输出尺寸（±2px）
        let cropLeft = Int.random(in: 0...2)
        let cropTop = Int.random(in: 0...2)
        let cropRight = Int.random(in: 0...2)
        let cropBottom = Int.random(in: 0...2)
        let cropRect = CGRect(
            x: cropLeft,
            y: cropTop,
            width: max(8, origWidth - cropLeft - cropRight),
            height: max(8, origHeight - cropTop - cropBottom)
        )

        let cropped = cgImage.cropping(to: cropRect) ?? cgImage
        let outWidth = max(8, cropped.width + Int.random(in: -2...2))
        let outHeight = max(8, cropped.height + Int.random(in: -2...2))

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        guard let context = CGContext(
            data: nil,
            width: outWidth,
            height: outHeight,
            bitsPerComponent: 8,
            bytesPerRow: outWidth * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else {
            return appendRandomJPEGComment(to: inputData)
        }

        context.interpolationQuality = .high
        context.draw(cropped, in: CGRect(x: 0, y: 0, width: outWidth, height: outHeight))

        // 在像素缓冲中叠加极微弱的不可见噪点与随机微调
        if let buffer = context.data {
            let ptr = buffer.assumingMemoryBound(to: UInt8.self)
            let totalBytes = outWidth * outHeight * 4
            let rBias = Int.random(in: -2...2)
            let gBias = Int.random(in: -2...2)
            let bBias = Int.random(in: -2...2)

            // 均匀抽样微调部分像素，保持视觉几乎无感但彻底改变哈希
            let strideStep = max(4, (totalBytes / 4096) * 4)
            var idx = 0
            while idx + 2 < totalBytes {
                ptr[idx] = UInt8(clamping: Int(ptr[idx]) + rBias + Int.random(in: -1...1))
                ptr[idx + 1] = UInt8(clamping: Int(ptr[idx + 1]) + gBias + Int.random(in: -1...1))
                ptr[idx + 2] = UInt8(clamping: Int(ptr[idx + 2]) + bBias + Int.random(in: -1...1))
                idx += strideStep
            }
        }

        guard let outputImage = context.makeImage() else {
            return appendRandomJPEGComment(to: inputData)
        }

        let mutableData = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            mutableData,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else {
            return appendRandomJPEGComment(to: inputData)
        }

        // 每次微调压缩质量，并抹除原始 EXIF
        let randomizedQuality = min(0.95, max(0.72, quality + Double.random(in: -0.04...0.04)))
        let options: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: randomizedQuality
        ]
        CGImageDestinationAddImage(destination, outputImage, options as CFDictionary)
        guard CGImageDestinationFinalize(destination) else {
            return appendRandomJPEGComment(to: inputData)
        }

        return mutableData as Data
    }

    private static func appendRandomJPEGComment(to data: Data) -> Data {
        var result = data
        let salt = UUID().uuidString.data(using: .utf8) ?? Data([0x00])
        result.append(salt)
        return result
    }
}
