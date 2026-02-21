# Android CamSwap (Open Source)
感谢项目 [android_virtual_cam](https://github.com/w2016561536/android_virtual_cam) 为本项目提供的灵感和代码基础。

Android CamSwap 是一个基于 Xposed 框架的虚拟摄像头模块。它能够拦截 Android 系统相机的预览和拍照请求，并将其替换为用户指定的视频和图片。

本项目采用现代化的 Android 开发技术栈（Kotlin, Jetpack Compose）重构，并引入了基于 ContentProvider 的跨进程数据传输机制，完美解决了高版本 Android (11+) 下的文件权限隔离问题。

具体实现方式查看仓库中的 code description.md 文件

## ✨ 主要功能

*   **全 API 支持**：同时支持 Camera1 (Camera) 和 Camera2 (CameraDevice) API。
*   **无缝替换**：
    *   🎥 **视频替换预览**：将相机预览画面替换为指定的 MP4 视频。
    *   📸 **图片替换拍照**：将拍照结果替换为指定的 BMP/JPG 图片。
    *   🎤 **音频替换播放**：播放自定义的mp3音频文件。
*   **跨进程兼容**：使用 ContentProvider 分发数据，目标应用无需申请存储权限即可读取虚拟视频流。
*   **实时控制**：
    *   支持通过通知栏快捷切换下一个视频。
    *   支持通过通知栏快捷切换视频旋转方向。
    *   支持随机播放。
*   **现代化 UI**：基于 Material Design 3 和 Jetpack Compose 构建的管理界面。
*   **自动旋转**：支持根据视频元数据自动处理旋转角度。

## 📱 环境要求

*   **Android 版本**：Android 8.0 (API 26) 及以上。
*   **Root 权限**：必须。
*   **Xposed 框架**：推荐使用 [LSPosed](https://github.com/LSPosed/LSPosed) (Zygisk/Riru 版本均可)。

## 🚀 安装与使用

### 1. 安装模块
1. 下载最新版本的 Release APK。
2. 安装到你的 Android 设备。
3. 在 LSPosed 管理器中**启用**该模块。
4. **作用域勾选**：
    *   **强烈建议**：勾选“系统框架 (System Framework)”以获得最佳兼容性。
    *   或者：勾选你需要进行虚拟摄像头的**目标应用**（如相机、微信、QQ 等）。
5. 重启手机（或重启目标应用）。

### 2. 配置素材
1. 打开 **CamSwap** 应用。
2. 授予必要的文件读写权限。
3. 确认目标应用权限
   *   打开 设置 → 应用 → 找到目标 App（如微信）
   *   进入 权限 或 应用权限
   *   找到 存储 / 文件和媒体，看是否是"允许"状态
   *   如果应用缺少储存权限，你应该打开模块高级设置中“强制私有目录”功能。
3. 在“管理”页面添加你的视频素材：
    *   支持导入 MP4 视频文件。
    *   应用会自动将视频复制到私有目录并进行处理。
4. 在“设置”页面配置默认视频和其他参数。

### 3. 开始使用
打开任意调用相机的应用，你应该能看到预览画面已经被替换为你选择的视频。

## 🛠️ 编译指南

如果你希望自己编译本项目，请按照以下步骤操作：

### 准备环境
*   JDK 17+
*   Android SDK Platform 34 (API 34)

### 克隆仓库
```bash
git clone https://github.com/zensu357/Android-CamSwap-OpenSource.git
cd Android-CamSwap-OpenSource
```

### 配置签名 (可选)
项目默认配置为**不签名**即可编译 Debug 包。如果你需要编译 Release 包，请在项目根目录创建 `local.properties` 文件（如果不存在），并添加你的签名配置：

```properties
sdk.dir=/path/to/your/android/sdk
# 签名配置
storeFile=../release-key.jks
storePassword=your_password
keyAlias=your_alias
keyPassword=your_key_password
```

### 编译 APK
```bash
# 编译 Debug 包
./gradlew assembleDebug

# 编译 Release 包
./gradlew assembleRelease
```

编译产物位于 `app/build/outputs/apk/` 目录下。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库。
2. 创建你的特性分支 (`git checkout -b feature/AmazingFeature`)。
3. 提交你的更改 (`git commit -m 'Add some AmazingFeature'`)。
4. 推送到分支 (`git push origin feature/AmazingFeature`)。
5. 开启一个 Pull Request。

## ⚠️ 免责声明

本项目仅供**安全研究、软件测试和教育目的**使用。
请勿将本项目用于任何非法用途（包括但不限于人脸识别绕过、诈骗等）。
使用者需自行承担因使用本项目而产生的一切法律责任。


## ❤️ 支持

请点Star以示支持，感谢你的支持。
![Star History](https://api.star-history.com/svg?repos=zensu357/Android-CamSwap-OpenSource&type=20260222)


## 📄 许可证

本项目基于 MIT 许可证开源。详情请参阅 [LICENSE](LICENSE) 文件。


