#if canImport(SwiftUI)
import SwiftUI

public struct AppUpdateButton: View {
    @State private var service = AppUpdateService()
    @State private var presented = false
    @State private var checking = false
    @State private var message = "空闲"
    @State private var release: AppUpdateRelease?
    @Environment(\.openURL) private var openURL

    public init() {}

    public var body: some View {
        Button(checking ? "检查中…" : "检查更新") { presented = true; Task { await check() } }
            .disabled(checking)
            .sheet(isPresented: $presented) {
                VStack(alignment: .leading, spacing: 16) {
                    Text(message).font(.headline)
                    Text("当前版本 \(AppUpdateSource.currentVersion)")
                    if checking { ProgressView() }
                    if let release {
                        Text("最新版本 \(release.version)")
                        if !release.publishedAt.isEmpty { Text("发布于 \(release.publishedAt)") }
                        if let asset = release.asset {
                            if asset.size > 0 { Text(ByteCountFormatter.string(fromByteCount: asset.size, countStyle: .file)) }
                            Button("打开下载页面") { openURL(asset.url) }
                        } else {
                            #if os(iOS)
                            Text("此版本尚未配置 TestFlight 或 App Store 下载地址。")
                            #else
                            Text("发布中没有适配此 Mac 的安装包。")
                            Link("打开发布页面", destination: URL(string: "https://github.com/\(AppUpdateSource.repository)/releases/latest")!)
                            #endif
                        }
                        ScrollView { Text(release.notes).frame(maxWidth: .infinity, alignment: .leading) }
                    }
                    HStack {
                        Button("重新检查") { Task { await check() } }.disabled(checking)
                        Spacer()
                        Button("关闭") { presented = false }
                    }
                }
                .padding(24)
                .frame(minWidth: 300, minHeight: 240)
            }
    }

    @MainActor private func check() async {
        guard !checking else { return }
        checking = true
        release = nil
        message = "检查中"
        defer { checking = false }
        do {
            let latest = try await service.latest()
            if try AppUpdateVersion(latest.version) > AppUpdateVersion(AppUpdateSource.currentVersion) {
                release = latest
                message = "发现新版本"
            } else { message = "已是最新版本" }
        } catch { message = error.localizedDescription }
    }
}
#endif
