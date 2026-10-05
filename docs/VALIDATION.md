# 本次交付验证 — 2026-10-05

交付文件是 `WB800F-Transfer-1.0.0.apk`，由工程的 Gradle release 构建生成，使用维护者保存在私有仓库的原升级签名。包名 `cn.cameralink.wb800f`，versionCode 1，versionName 1.0.0，minSdk 29，compileSdk / targetSdk 37。

## 已执行并通过

- 官方 Android 17 API 37 SDK 编译；JDK 21.0.12.1，AGP 9.4.1，Gradle 9.6.0，Build Tools 37.0.0。
- `:app:assembleRelease`：构建成功。
- `:app:lintRelease`：任务成功，0 个错误。报告含 1 条旧版备份配置提示；最终 APK 的 manifest 已同时设置 allowBackup=false、fullBackupContent=false 和 dataExtractionRules。
- 无 Gradle 的官方 SDK 工具构建脚本：构建与签名验证成功。这是另一种等价功能构建路径，不保证与 Gradle 产物逐字节相同。
- `apksigner verify --verbose`：交付 APK 的 APK Signature Scheme v2 验证成功，1 个签名者。
- `aapt2 dump badging / xmltree`：验证版本、入口 Activity、API 37 目标与局域网 / 前台传输权限；无 READ_MEDIA_IMAGES / 全文件管理权限。
- 19 项无第三方依赖的协议及真实本地 HTTP 集成测试全部通过，完整记录见 `protocol-test-results.txt`。
- 独立 Android 集成测试 APK 编译及签名成功，测试源保留于 `tests/android`；编译成功不等于已在 Android 上运行通过。

19 项协议测试包含服务 URLBase 解析、原图优先、缩略图排除、转义 / 未转义 DIDL、DTD 拒绝、SSDP 字段、ObjectID 转义、去重键、文件名、目录分页循环、三星 ObjectID=1 回退、HTTP 200 SOAP 错误回退、下载字节一致性、截断检测、大小不一致、HTML 错误、重定向拒绝、取消和重复分页报错。

## 运行验证的边界

本环境没有真实 WB800F，也没有用户的 Android 17 手机。

曾配置官方 Android 17 API 37 x86_64 模拟器（Google APIs r06，系统镜像 SHA1 `629e507fd5b737c2c836b12b52c81cd0e3b12399` 已核验）。云环境没有 KVM，加速不可用；软件模拟的首次启动耗时很长，并在系统初始化阶段触发 Watchdog，system_server 重启，连权限控制器也报 DeadSystemException。此时交付应用尚未安装或启动，错误来自模拟器系统初始化。

因此，**本次没有完成 Android 系统内的应用启动、权限弹窗、MediaStore、后台传输和实机相机端到端验证**。不能把协议测试或 APK 编译成功描述为“WB800F 与 Android 17 实测可用”。保留了独立 Android 测试 APK 的构建入口和测试代码，方便在有硬件加速的模拟器 / 独立测试手机上继续运行。

## 用户实机确认步骤

1. 先用 WB800F 的 MobileLink 模式连接手机，手机允许局域网权限，相机端确认连接请求。
2. 先传一张照片，检查相册出现、像素和文件大小。若方便，用 SD 卡上的同一照片对比 SHA256。
3. 再测试多选、仅选未传、取消、锁屏 / 返回桌面、Wi-Fi 断开后重新连接。
4. 有问题时导出应用诊断，结合相机固件版本、手机型号及 Android 17 构建号继续迭代。

这些实机确认不需要删除或重新设计工程；`docs/PROTOCOL.md` 记录了协议分层和需要核实的固件差异。
