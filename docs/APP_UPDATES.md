# 应用更新

更新仓库为 `1005WM/chaoxing`。Android 首页课程页右上角有「检查更新」入口。
点击后显示检查状态、版本号、发布时间、更新说明、安装包大小和下载进度。
检查结果缓存 5 分钟。下载可取消，失败后可重新下载。

## 更新源配置

- Android 在 `android/core/src/main/kotlin/com/caochaoxing/core/service/AppUpdateService.kt` 的 `UpdateSource` 中配置仓库和 `MIRROR_LATEST_URL`。
- Apple 在 `Sources/ChaoxingCore/Services/AppUpdateService.swift` 的 `AppUpdateSource` 中配置镜像和商店链接。
- 镜像默认留空。填写后优先请求镜像，2 秒超时后回退 GitHub。更新请求匿名发送，使用独立客户端。
- 默认检查正式版本。需要预发布版时修改 `INCLUDE_PRERELEASE` 或 `includePrerelease`。

镜像可以直接托管 Release 附带的 `latest.json`。配置文件示例使用虚构地址。

```json
{
  "version": "1.6.0",
  "notes": "更新说明",
  "publishedAt": "2026-11-23T00:00:00Z",
  "assets": [
    {
      "platform": "android",
      "arch": "universal",
      "url": "https://example.com/Caochaoxing-v1.6.0-android-universal-debug.apk",
      "size": 12345678,
      "sha256": "填写安装包的64位十六进制SHA256"
    }
  ]
}
```

## Android 安装

下载目录为应用私有缓存 `updates`。下载结束后检查 APK 包名、签名、版本和构建号。
元数据提供 SHA256 时也会校验。镜像下载失败后尝试 GitHub 的同版本安装包。
每次重试重新下载文件。

`AndroidManifest.xml` 声明 `REQUEST_INSTALL_PACKAGES` 和 FileProvider。
`res/xml/update_file_paths.xml` 将授权范围限定在更新缓存目录。
用户点击「安装」后，应用按需打开未知来源安装授权页，再交给系统安装器。
私有目录下载无需申请共享存储权限。

本次提供 Debug 签名 APK，应用标识为 `com.caochaoxing.android.debug`。
后续更新需使用同一签名、相同应用标识和更高的 `versionCode`。

## Apple 平台

iOS 首页有更新按钮。发现新版本时打开已配置的 TestFlight 或 App Store 链接。
当前仓库尚无实际商店链接，配置为空时界面会说明。
macOS 首页提供匹配架构的发布资产下载链接，下载交给系统浏览器。
本次发布仅提供 Android 安装包。Apple 源码的本地编译需要 macOS 和 Xcode。

## 发布步骤

同时递增 Android 的 `versionName`、`versionCode`，以及 Xcode 版本和共享更新源的当前版本。
创建对应版本的 GitHub Release，上传 APK、`latest.json` 和 `SHA256SUMS.txt`。
镜像使用相同版本、安装包大小和校验摘要。
