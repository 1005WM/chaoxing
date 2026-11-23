// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// From the repository root: swift docs/artwork/render-icon.swift
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true)
let artwork = root.appendingPathComponent("docs/artwork/Avatar.png")
guard let source = CGImageSourceCreateWithURL(artwork as CFURL, nil),
      let original = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fatalError("Cannot read docs/artwork/Avatar.png")
}
let edge = min(original.width, original.height)
let crop = CGRect(x: (original.width - edge) / 2, y: (original.height - edge) / 2, width: edge, height: edge)
let portrait = original.cropping(to: crop)!
let space = CGColorSpace(name: CGColorSpace.sRGB)!
func canvas(_ width: Int, _ height: Int, opaque: Bool = false) -> CGContext {
    let alpha: CGImageAlphaInfo = opaque ? .noneSkipLast : .premultipliedLast
    let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: 0, space: space, bitmapInfo: alpha.rawValue)!
    context.interpolationQuality = .high
    return context
}
func save(_ context: CGContext, to path: String) throws {
    let file = root.appendingPathComponent(path)
    guard let bitmap = context.makeImage(),
          let output = CGImageDestinationCreateWithURL(file as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw CocoaError(.fileWriteUnknown)
    }
    CGImageDestinationAddImage(output, bitmap, nil)
    guard CGImageDestinationFinalize(output) else { throw CocoaError(.fileWriteUnknown) }
}
func paint(_ context: CGContext, image: CGImage = portrait, rect: CGRect, radius: CGFloat = 0) {
    context.saveGState()
    if radius > 0 {
        context.addPath(CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil))
        context.clip()
    }
    context.draw(image, in: rect)
    context.restoreGState()
}
let ios = canvas(1024, 1024, opaque: true)
paint(ios, rect: CGRect(x: 0, y: 0, width: 1024, height: 1024))
try save(ios, to: "Sources/CaochaoxingIOS/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
let mac = canvas(1024, 1024)
paint(mac, rect: CGRect(x: 88, y: 88, width: 848, height: 848), radius: 189)
try save(mac, to: "Sources/CaochaoxingMac/Resources/AppIcon.png")
let android = canvas(432, 432, opaque: true)
paint(android, rect: CGRect(x: 0, y: 0, width: 432, height: 432))
try save(android, to: "android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png")
let preview = canvas(384, 192, opaque: true)
preview.setFillColor(CGColor(red: 239.0 / 255, green: 237.0 / 255, blue: 231.0 / 255, alpha: 1))
preview.fill(CGRect(x: 0, y: 0, width: 384, height: 192))
let visibleEdge = CGFloat(edge) * 2 / 3
let visible = portrait.cropping(to: CGRect(x: (CGFloat(edge) - visibleEdge) / 2,
    y: (CGFloat(edge) - visibleEdge) / 2, width: visibleEdge, height: visibleEdge))!
paint(preview, image: visible, rect: CGRect(x: 16, y: 16, width: 160, height: 160), radius: 80)
paint(preview, image: visible, rect: CGRect(x: 208, y: 16, width: 160, height: 160), radius: 35)
try save(preview, to: "docs/artwork/AndroidAppIcon-preview.png")
