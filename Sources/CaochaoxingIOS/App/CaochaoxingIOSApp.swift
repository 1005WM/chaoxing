// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

@main
struct CaochaoxingIOSApp: App {
    @State private var appState = IOSAppState()

    var body: some Scene {
        WindowGroup {
            IOSContentView()
                .environment(appState)
                .tint(CxPalette.accent)
                .onOpenURL { url in
                    Task {
                        await appState.handleIncomingURL(url)
                    }
                }
        }
    }

}
