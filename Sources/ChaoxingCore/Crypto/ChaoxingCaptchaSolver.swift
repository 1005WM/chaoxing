// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import CoreGraphics
import ImageIO

/// 滑块验证码自动定位工具（对应 Issue #204：纯端侧无依赖边缘匹配）
/// 通过提取缺口图的 Alpha 轮廓边缘并与背景图的灰度梯度进行水平互相关匹配，
/// 自动估算滑块所需的 xPosition。
public enum ChaoxingCaptchaSolver {
    public static func estimateXPosition(shadeImageData: Data, cutoutImageData: Data) -> Int? {
        guard let shadeCG = decodeCGImage(from: shadeImageData),
              let cutoutCG = decodeCGImage(from: cutoutImageData) else {
            return nil
        }

        let bgW = shadeCG.width
        let bgH = shadeCG.height
        let cutW = cutoutCG.width
        let cutH = cutoutCG.height

        guard bgW > cutW, bgH >= cutH, cutW > 8, cutH > 8 else {
            return nil
        }

        guard let bgPixels = extractRGBA(from: shadeCG),
              let cutPixels = extractRGBA(from: cutoutCG) else {
            return nil
        }

        // 1. 提取背景图灰度与水平/垂直梯度幅值
        var bgGrad = [Float](repeating: 0, count: bgW * bgH)
        func bgGray(_ x: Int, _ y: Int) -> Float {
            let i = (y * bgW + x) * 4
            return 0.299 * Float(bgPixels[i]) + 0.587 * Float(bgPixels[i + 1]) + 0.114 * Float(bgPixels[i + 2])
        }
        for y in 1..<(bgH - 1) {
            for x in 1..<(bgW - 1) {
                let gx = abs(bgGray(x + 1, y) - bgGray(x - 1, y))
                let gy = abs(bgGray(x, y + 1) - bgGray(x, y - 1))
                bgGrad[y * bgW + x] = gx + gy
            }
        }

        // 2. 提取缺口图的非透明轮廓（Alpha 边界像素）及边界内侧梯度
        struct EdgeSample {
            let dx: Int
            let dy: Int
            let weight: Float
        }
        var samples: [EdgeSample] = []
        samples.reserveCapacity(cutW * 4)

        func cutAlpha(_ x: Int, _ y: Int) -> UInt8 {
            cutPixels[(y * cutW + x) * 4 + 3]
        }

        for y in 1..<(cutH - 1) {
            for x in 1..<(cutW - 1) {
                let a = cutAlpha(x, y)
                if a < 128 { continue }
                let isBoundary = cutAlpha(x - 1, y) < 128
                    || cutAlpha(x + 1, y) < 128
                    || cutAlpha(x, y - 1) < 128
                    || cutAlpha(x, y + 1) < 128
                if isBoundary {
                    samples.append(EdgeSample(dx: x, dy: y, weight: 1.0))
                }
            }
        }

        guard !samples.isEmpty else { return nil }

        // 3. 在背景图上滑动窗口寻找最佳匹配位置
        // 滑块通常位于 x >= 20 之后，跳过最左侧起始区域以降低干扰
        let minX = min(20, max(0, bgW - cutW - 1))
        let maxX = bgW - cutW
        let maxY = bgH - cutH

        // Bound both image memory and matching work. Fall back to manual input when
        // a challenge exceeds the supported search budget instead of freezing the UI.
        let candidates = (maxX - minX + 1) * (maxY + 1)
        guard samples.count <= 2_048, candidates <= 12_000_000 / samples.count else { return nil }
        var bestScoreByX = [Float](repeating: 0, count: maxX + 1)
        var bestScore: Float = -1
        var bestX = minX

        // y 方向通常较小或固定，步长 1 精确扫描
        for y0 in 0...maxY {
            if Task.isCancelled { return nil }
            for x0 in minX...maxX {
                var score: Float = 0
                for s in samples {
                    score += bgGrad[(y0 + s.dy) * bgW + (x0 + s.dx)] * s.weight
                }
                bestScoreByX[x0] = max(bestScoreByX[x0], score)
                if score > bestScore {
                    bestScore = score
                    bestX = x0
                }
            }
        }

        guard bestScore > 0 else { return nil }
        let alternative = (minX...maxX).filter { abs($0 - bestX) > 2 }.map { bestScoreByX[$0] }.max() ?? 0
        guard bestScore > alternative * 1.01 else { return nil }
        return bestX
    }

    private static func decodeCGImage(from data: Data) -> CGImage? {
        guard data.count <= 10_000_000,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              width > 0, height > 0, width <= 1_024, height <= 1_024,
              width <= 524_288 / height else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }

    private static func extractRGBA(from image: CGImage) -> [UInt8]? {
        let width = image.width
        let height = image.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        guard let context = CGContext(
            data: &pixels,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else {
            return nil
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return pixels
    }
}
