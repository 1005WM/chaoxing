// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Pure sRGB color specification used for cross-platform dynamic tokens and WCAG contrast verification.
public struct CxRGB: Sendable, Equatable {
    public let red: Double
    public let green: Double
    public let blue: Double
    public let alpha: Double

    public init(r: Int, g: Int, b: Int, a: Double = 1.0) {
        self.red = Double(r) / 255.0
        self.green = Double(g) / 255.0
        self.blue = Double(b) / 255.0
        self.alpha = a
    }

    public var relativeLuminance: Double {
        func linearize(_ channel: Double) -> Double {
            channel <= 0.03928 ? (channel / 12.92) : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linearize(red) + 0.7152 * linearize(green) + 0.0722 * linearize(blue)
    }

    public func contrastRatio(with other: CxRGB) -> Double {
        let l1 = max(relativeLuminance, other.relativeLuminance)
        let l2 = min(relativeLuminance, other.relativeLuminance)
        return (l1 + 0.05) / (l2 + 0.05)
    }
}

/// Neutral surfaces and accessible semantic colors for the compact iOS interface.
public enum CxPaletteSpecs {
    public static let canvasLight = CxRGB(r: 245, g: 245, b: 247)
    public static let canvasDark = CxRGB(r: 18, g: 18, b: 20)
    public static let surfaceLight = CxRGB(r: 255, g: 255, b: 255)
    public static let surfaceDark = CxRGB(r: 29, g: 29, b: 31)
    public static let surfaceSecondaryLight = CxRGB(r: 239, g: 240, b: 243)
    public static let surfaceSecondaryDark = CxRGB(r: 42, g: 42, b: 45)
    public static let borderLight = CxRGB(r: 215, g: 216, b: 221)
    public static let borderDark = CxRGB(r: 64, g: 64, b: 68)
    public static let inkLight = CxRGB(r: 24, g: 24, b: 27)
    public static let inkDark = CxRGB(r: 245, g: 245, b: 247)
    public static let inkSecondaryLight = CxRGB(r: 92, g: 92, b: 98)
    public static let inkSecondaryDark = CxRGB(r: 183, g: 183, b: 191)
    public static let inkTertiaryLight = CxRGB(r: 105, g: 105, b: 111)
    public static let inkTertiaryDark = CxRGB(r: 173, g: 173, b: 181)
    public static let accentLight = CxRGB(r: 0, g: 90, b: 190)
    public static let accentDark = CxRGB(r: 112, g: 174, b: 255)
    public static let onAccentLight = CxRGB(r: 255, g: 255, b: 255)
    public static let onAccentDark = CxRGB(r: 18, g: 18, b: 20)
    public static let sageLight = CxRGB(r: 36, g: 110, b: 63)
    public static let sageDark = CxRGB(r: 114, g: 209, b: 144)
    public static let amberLight = CxRGB(r: 136, g: 80, b: 0)
    public static let amberDark = CxRGB(r: 255, g: 195, b: 94)
    public static let cinnabarLight = CxRGB(r: 180, g: 35, b: 35)
    public static let cinnabarDark = CxRGB(r: 255, g: 135, b: 135)
    public static let plumLight = CxRGB(r: 111, g: 65, b: 148)
    public static let plumDark = CxRGB(r: 202, g: 162, b: 240)
}

/// Adaptive SwiftUI colors for Light & Dark modes.
public enum CxPalette {
    public static let canvas = dynamicColor(light: CxPaletteSpecs.canvasLight, dark: CxPaletteSpecs.canvasDark)
    public static let surface = dynamicColor(light: CxPaletteSpecs.surfaceLight, dark: CxPaletteSpecs.surfaceDark)
    public static let surfaceSecondary = dynamicColor(light: CxPaletteSpecs.surfaceSecondaryLight, dark: CxPaletteSpecs.surfaceSecondaryDark)
    public static let border = dynamicColor(light: CxPaletteSpecs.borderLight, dark: CxPaletteSpecs.borderDark)

    public static let ink = dynamicColor(light: CxPaletteSpecs.inkLight, dark: CxPaletteSpecs.inkDark)
    public static let inkSecondary = dynamicColor(light: CxPaletteSpecs.inkSecondaryLight, dark: CxPaletteSpecs.inkSecondaryDark)
    public static let inkTertiary = dynamicColor(light: CxPaletteSpecs.inkTertiaryLight, dark: CxPaletteSpecs.inkTertiaryDark)

    public static let accent = dynamicColor(light: CxPaletteSpecs.accentLight, dark: CxPaletteSpecs.accentDark)
    public static let sage = dynamicColor(light: CxPaletteSpecs.sageLight, dark: CxPaletteSpecs.sageDark)
    public static let amber = dynamicColor(light: CxPaletteSpecs.amberLight, dark: CxPaletteSpecs.amberDark)
    public static let cinnabar = dynamicColor(light: CxPaletteSpecs.cinnabarLight, dark: CxPaletteSpecs.cinnabarDark)
    public static let plum = dynamicColor(light: CxPaletteSpecs.plumLight, dark: CxPaletteSpecs.plumDark)

    public static let onAccent = dynamicColor(light: CxPaletteSpecs.onAccentLight, dark: CxPaletteSpecs.onAccentDark)

    private static func dynamicColor(light: CxRGB, dark: CxRGB) -> Color {
        #if canImport(UIKit)
        return Color(uiColor: UIColor { traits in
            let spec = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: spec.red, green: spec.green, blue: spec.blue, alpha: spec.alpha)
        })
        #elseif canImport(AppKit)
        return Color(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let spec = isDark ? dark : light
            return NSColor(srgbRed: spec.red, green: spec.green, blue: spec.blue, alpha: spec.alpha)
        })
        #else
        return Color(red: light.red, green: light.green, blue: light.blue, opacity: light.alpha)
        #endif
    }
}

/// Semantic fonts scale with Dynamic Type. Existing call sites retain their token names.
public enum CxTypography {
    public static func brandDisplay(_ size: CGFloat = 30) -> Font {
        .system(.largeTitle, design: .default, weight: .bold)
    }

    public static func serifTitle(_ size: CGFloat = 19) -> Font {
        size >= 20 ? .title2.weight(.semibold) : .headline
    }

    public static func serifHeadline(_ size: CGFloat = 16.5) -> Font {
        .headline
    }

    public static func eyebrow() -> Font {
        .caption.weight(.medium)
    }

    public static func mono(_ size: CGFloat = 12, weight: Font.Weight = .medium) -> Font {
        .system(size <= 12 ? .caption : .subheadline, design: .monospaced, weight: weight)
    }
}
