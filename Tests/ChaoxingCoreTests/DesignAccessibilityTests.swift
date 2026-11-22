// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Testing
@testable import ChaoxingCore

@Suite("Interface readability")
struct DesignAccessibilityTests {
    @Test("Small primary button and selected account text remain readable in both appearances")
    func primaryContrast() {
        #expect(CxPaletteSpecs.onAccentLight.contrastRatio(with: CxPaletteSpecs.accentLight) >= 4.5)
        #expect(CxPaletteSpecs.onAccentDark.contrastRatio(with: CxPaletteSpecs.accentDark) >= 4.5)
    }

    @Test("All text levels remain readable on the actual page, card and input surfaces")
    func textContrast() {
        for text in [CxPaletteSpecs.inkLight, CxPaletteSpecs.inkSecondaryLight, CxPaletteSpecs.inkTertiaryLight] {
            for surface in [CxPaletteSpecs.canvasLight, CxPaletteSpecs.surfaceLight, CxPaletteSpecs.surfaceSecondaryLight] {
                #expect(text.contrastRatio(with: surface) >= 4.5)
            }
        }
        for text in [CxPaletteSpecs.inkDark, CxPaletteSpecs.inkSecondaryDark, CxPaletteSpecs.inkTertiaryDark] {
            for surface in [CxPaletteSpecs.canvasDark, CxPaletteSpecs.surfaceDark, CxPaletteSpecs.surfaceSecondaryDark] {
                #expect(text.contrastRatio(with: surface) >= 4.5)
            }
        }
    }

    @Test("Small semantic labels remain readable on tinted badges")
    func badgeContrast() {
        func composite(_ foreground: CxRGB, over background: CxRGB) -> CxRGB {
            CxRGB(r: Int((foreground.red * 0.13 + background.red * 0.87) * 255),
                  g: Int((foreground.green * 0.13 + background.green * 0.87) * 255),
                  b: Int((foreground.blue * 0.13 + background.blue * 0.87) * 255))
        }
        for text in [CxPaletteSpecs.accentLight, CxPaletteSpecs.sageLight, CxPaletteSpecs.amberLight, CxPaletteSpecs.cinnabarLight, CxPaletteSpecs.plumLight] {
            #expect(text.contrastRatio(with: composite(text, over: CxPaletteSpecs.surfaceLight)) >= 4.5)
        }
        for text in [CxPaletteSpecs.accentDark, CxPaletteSpecs.sageDark, CxPaletteSpecs.amberDark, CxPaletteSpecs.cinnabarDark, CxPaletteSpecs.plumDark] {
            #expect(text.contrastRatio(with: composite(text, over: CxPaletteSpecs.surfaceDark)) >= 4.5)
        }
    }
}
