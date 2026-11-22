// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
//
// From the repository root: swift docs/artwork/render-icon.swift
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true)
let artwork = root.appendingPathComponent("docs/artwork/Avatar.png")
guard let source = CGImageSourceCreateWithURL(artwork as CFURL, nil),
      let portrait = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fatalError("Cannot read docs/artwork/Avatar.png")
}
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let paper = CGColor(gray: 1, alpha: 1)
let ink = CGColor(gray: 0.09, alpha: 1)
let black = CGColor(gray: 0, alpha: 1)

// A themed launcher tints alpha. Derive it from the ink so the white paper
// and the space around the illustration take on the launcher's background.
func makeInkMask(_ image: CGImage) -> CGImage {
    let gray = CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8,
                         bytesPerRow: 0, space: CGColorSpaceCreateDeviceGray(),
                         bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    let bounds = CGRect(x: 0, y: 0, width: image.width, height: image.height)
    gray.setFillColor(gray: 1, alpha: 1)
    gray.fill(bounds)
    gray.draw(image, in: bounds)
    let paperAndInk = gray.makeImage()!
    // A Core Graphics image mask paints through its black samples.
    return CGImage(maskWidth: paperAndInk.width, height: paperAndInk.height,
                   bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: paperAndInk.bytesPerRow,
                   provider: paperAndInk.dataProvider!, decode: nil, shouldInterpolate: true)!
}
let inkMask = makeInkMask(portrait)

func drawInk(_ ctx: CGContext, in rect: CGRect, color: CGColor) {
    ctx.saveGState()
    ctx.clip(to: rect, mask: inkMask)
    ctx.setFillColor(color)
    ctx.fill(rect)
    ctx.restoreGState()
}

func canvas(_ width: Int, _ height: Int, opaque: Bool = false) -> CGContext {
    let alpha: CGImageAlphaInfo = opaque ? .noneSkipLast : .premultipliedLast
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                        bytesPerRow: 0, space: space, bitmapInfo: alpha.rawValue)!
    ctx.interpolationQuality = .high
    return ctx
}

func save(_ ctx: CGContext, to path: String) throws {
    let file = root.appendingPathComponent(path)
    try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
    guard let bitmap = ctx.makeImage(),
          let output = CGImageDestinationCreateWithURL(file as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw CocoaError(.fileWriteUnknown)
    }
    CGImageDestinationAddImage(output, bitmap, nil)
    guard CGImageDestinationFinalize(output) else { throw CocoaError(.fileWriteUnknown) }
}

func paintTile(_ ctx: CGContext, rect: CGRect, radius: CGFloat,
               background: CGColor = paper, foreground: CGColor? = nil) {
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil))
    ctx.clip()
    ctx.setFillColor(background)
    ctx.fill(rect)
    if let foreground {
        drawInk(ctx, in: rect, color: foreground)
    } else {
        ctx.draw(portrait, in: rect)
    }
    ctx.restoreGState()
}

let ios = canvas(1024, 1024, opaque: true)
paintTile(ios, rect: CGRect(x: 0, y: 0, width: 1024, height: 1024), radius: 0)
try save(ios, to: "Sources/CaochaoxingIOS/Assets.xcassets/AppIcon.appiconset/AppIcon.png")

let mac = canvas(1024, 1024)
paintTile(mac, rect: CGRect(x: 88, y: 88, width: 848, height: 848), radius: 189)
try save(mac, to: "Sources/CaochaoxingMac/Resources/AppIcon.png")

// The master already includes its margins. Map it to the launcher's visible 72 dp.
let adaptiveScale = 72.0 / 108.0
let android = canvas(432, 432)
let inset = 432 * (1 - adaptiveScale) / 2
drawInk(android, in: CGRect(x: 0, y: 0, width: 432, height: 432).insetBy(dx: inset, dy: inset), color: black)
try save(android, to: "android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png")

let preview = canvas(576, 192, opaque: true)
preview.setFillColor(CGColor(gray: 0.87, alpha: 1))
preview.fill(CGRect(x: 0, y: 0, width: 576, height: 192))
// A launcher shows the central 72 dp, then applies its mask and theme.
for tile in 0..<3 {
    let rect = CGRect(x: 16 + tile * 192, y: 16, width: 160, height: 160)
    paintTile(preview, rect: rect, radius: tile == 1 ? 35 : 80,
              background: tile == 2 ? ink : paper, foreground: tile == 2 ? paper : black)
}
try save(preview, to: "docs/artwork/AndroidAppIcon-preview.png")
