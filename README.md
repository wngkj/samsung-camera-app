# WB800F 照片传输

独立开发的中文 Android 应用，用于三星 WB800F 在 MobileLink 模式下通过相机 Wi-Fi 向手机传输照片。最低 Android 10，compileSdk / targetSdk 为 Android 17 API 37。无需账号，无广告，照片不上传到服务器。

交付 APK 使用 Gradle release 构建，是已签名的独立安装包；相机协议依据公开的三星 Wi-Fi 相机实现开发。**WB800F 真实相机与用户 Android 17 手机的端到端传输仍需实机验证，不能把模拟协议测试等同于实机兼容认证。** 具体执行过的验证见 `docs/VALIDATION.md`。

## 安装和使用

安装包及历次版本见 [GitHub Releases](https://github.com/wngkj/samsung-camera-app/releases)。

1. 在安卓手机打开交付的 `WB800F-Transfer-1.0.2.apk`。已安装 1.0.0 / 1.0.1 时直接覆盖安装，保留导入记录。按系统提示允许当前文件管理器或浏览器安装应用。
2. 相机拨盘切到 **Wi-Fi → MobileLink**。如果出现选择方式，可选 **从相机选择文件**（当前实机日志的 192.168.104.* 网络对应此方式），也支持 **从智能手机选择文件**。
3. 打开应用的 **Wi-Fi 设置**，连接相机屏幕显示的 `AP_SSC_WB800F…` 网络。提示“没有互联网”时，选择保持连接。不要连接手机自己创建的热点。
4. 回到应用点 **连接 / 刷新**。安卓 17 的 **附近设备 / 本地网络** 权限提示请选择允许。相机若出现请求，在**相机屏幕**确认允许；已记住设备或直接连接时无需确认。
5. 若应用显示 **请在相机上发送照片**，在相机屏幕选择 JPEG 照片，再点 **共享 / 发送**，手机自动接收。若显示照片列表，在手机选片并点 **传输**。两种方式均保存到系统相册 `Pictures/WB800F`。
6. 通知权限用于显示传输进度和取消按钮，拒绝通知权限不阻止照片传输。

传输时相机保持开启，手机必须留在相机 Wi-Fi。应用使用前台传输服务，手机返回桌面或锁屏后可继续传输；某些厂商的电池策略可能仍会影响后台运行。已完成照片保留，取消或失败的当前照片清理，再次传输会跳过本应用之前成功保存且仍存在的文件。未实现跨断点续传：失败的单张照片从头重传。相机选片模式按完整文件 SHA256 去重，重复发送仍需接收全部字节，但不会重复保存。该索引与手机选片索引分别维护，跨模式首次导入可能产生一份重复照片。

## 连接失败的处理

- 相机必须处于 **MobileLink**，支持从相机或手机选片；Remote Viewfinder、自动备份、电子邮件模式不属于本应用的传输流程。
- 确认手机实际连接到相机 Wi-Fi，并选择保持无互联网连接；必要时暂时关闭 VPN、移动数据自动切换。
- 1.0.2 根据当前网络自动区分接收方式：192.168.104.* 走相机选片发送；手机选片模式继续使用持续发现与目录浏览。不要求相机出现“允许”弹窗。
- 不要将手机自身 IPv4（例如 192.168.104.11）填成相机地址；本次日志网关 / 相机地址为 192.168.104.1。
- 「手动连接」可选填手机当前相机 Wi-Fi 详情中的 MAC，用于旧固件识别。留空继续使用持久化的应用客户端标识；不要填写相机 MAC。
- 可用 **手动连接** 输入相机 IPv4 地址，例如 `192.168.107.1`。也支持 `IP:端口` 或完整 UPnP 设备描述 URL。示例 IP 是三星部分机型的常见地址，**不是已经确认的 WB800F 固定地址**；以手机 Wi-Fi 详情中的网关地址或相机实际地址为准。
- 使用 **连接诊断 → 导出日志 / 复制**。请等到显示“连接未完成”再导出完整日志。日志包含手机型号、系统版本、本地网络权限、网络接口、SSDP 搜索及回复 / 公告、局域网地址、相机返回的服务 URL、照片文件名和状态；应用只在你手动分享时导出日志。
- 如果能连接但照片列表为空，确认相机内有照片、相机端已允许访问；相机仅提供缩略图而未提供大图的条目不会作为“原图”下载。

## 支持的功能

相机选片发送接收（SP 控制握手、手机接收、完整写入后成功应答、相册事务与内容去重）；自动 SSDP 发现（含三星 `SEC_DSC_` 标识、搜索回复和 UDP 1900 公告监听）；相机确认期间持续发现 / 服务重试；解析设备真实服务地址；可选旧固件 `mode/control` 配对；GENA 事件订阅及续期；递归及分页照片列表；相机提供的缩略图预览；多选批量传输；本应用导入记录去重；取消操作；完整性大小检查；按相机提供的照片日期写入相册；诊断日志分享。

下载优先选择 `JPEG_LRG` 资源，否则选择相机提供的非缩略图高分辨率资源；不改动下载的照片字节、EXIF 或画面。本版本聚焦**照片接收**，不包含遥控拍照、AutoShare 自动发送、视频传输和删除相机内容。

## 源码和再次构建

源码、Gradle Wrapper、协议测试和公开签名证书均保留在本目录；私钥和密码保存在维护者的私有签名仓库，不包含在公开源码 ZIP 中。Android 应用没有第三方运行时依赖，体积较小是正常的。

### Android Studio / Gradle（推荐开发）

使用支持 AGP 9.4 的 Android Studio（Quail 4 或更新版），JDK 17 或 21，Android 17 SDK Platform（API 37）及 Build Tools 37.0.0。项目固定使用 AGP 9.4.1 和 Gradle 9.6.0。

1. 解压源码 ZIP，Android Studio 打开工程根目录。
2. 用 SDK Manager 安装 API 37 SDK Platform 和 Build Tools 37.0.0。
3. 维护者先按 `signing/README.md` 恢复私钥和本地配置，再同步 Gradle 并执行：

```sh
./gradlew :app:assembleRelease
```

Windows 用 `gradlew.bat :app:assembleRelease`。产物在 `app/build/outputs/apk/release/app-release.apk`。首次构建需联网下载构建工具。未配置签名时输出未签名 APK；其他开发者可用 `:app:assembleDebug` 构建调试安装包。

### 无 Gradle 构建（亦已验证）

在 Linux / macOS 的 Bash 环境使用官方 Android SDK 自带 `aapt2`、`d8`、`zipalign`、`apksigner`，以及 JDK 17+、zip：

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_SDK_ROOT=/path/to/android-sdk
# 先按 signing/README.md 配置私钥路径和密码环境变量。
./tools/build-apk.sh
```

输出 `build/WB800F-Transfer-<版本>.apk`。脚本兼容 SDK 的 `android-37.0` / `android-37` 平台目录。非常规路径可用 `ANDROID_PLATFORM` 和 `ANDROID_BUILD_TOOLS` 指向实际平台及 Build Tools 目录。

### 协议测试（不需要手机或 Android SDK）

```sh
export JAVA_HOME=/path/to/jdk
./tools/test.sh
```

测试使用本地 HTTP、TCP 和 UDP 套接字模拟相机，验证目录分页、协议 XML、文件完整性、发现回复、延迟相机公告和取消操作。测试不需要访问互联网。

### 后续迭代与覆盖安装

- 修改 `version.properties` 的 `VERSION_CODE`（每次递增）及 `VERSION_NAME`，修改源码后重新构建。
- **保留 `signing/wb800f-release.p12`，保持包名 `cn.cameralink.wb800f`**，新 APK 才能直接覆盖升级，保留导入记录。
- 原签名和实际密码由维护者私下保存；本仓库只提供公钥证书及本地配置模板，恢复方法见 `signing/README.md`。
- Git 仓库保留清理后的三个版本标签；原始完整历史保存在私有备份，说明见 `docs/GITHUB.md`。
- 包内 `docs/PROTOCOL.md` 说明了已知协议、固件差异、类的职责和下一步如何结合实机日志迭代。

## 目录

```text
app/src/main/java/cn/cameralink/wb800f/
  MainActivity.java       中文界面、权限、照片选择、诊断
  TransferService.java    会话管理、前台传输、取消、进度
  WifiConnection.java     Wi-Fi 网络绑定、SSDP、GENA、可选配对
  SsdpDiscovery.java      持续发现、搜索回复与相机公告接收
  SamsungPushClient.java  相机选片 SP 会话握手
  SamsungPushReceiver.java 原照片接收、取消和成功应答
  PushHttp.java           有界 HTTP 帧解析
  CameraClient.java       可测试 HTTP 客户端及分页目录浏览
  CameraProtocol.java     UPnP / SOAP / DIDL 解析和资源选择
  PhotoStore.java         相册事务保存、去重索引
  LogProvider.java        只读诊断分享
signing/                   公钥证书、签名配置模板和恢复说明
version.properties         统一版本号
tools/                     本项目构建和验证入口
tests/                     无第三方依赖的协议及 HTTP 集成测试
docs/                      协议、验证和实机联调说明
```

许可：本项目 MIT，见 `LICENSE`。公开协议资料和参考项目见 `THIRD_PARTY_NOTICES.md`。这不是三星官方应用，也没有包含三星原版 APK 或反编译代码。
