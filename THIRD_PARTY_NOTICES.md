# 公开协议参考

应用实现由本项目独立编写，未分发三星的原始应用、商标素材、反编译代码，未引用 WB250F 或 DV150F 项目的源代码片段。UPnP/DLNA 协议字段及互操作行为参考如下：

- [IstvanSafar/SamsungCameraDownloader](https://github.com/IstvanSafar/SamsungCameraDownloader)：MIT。SSDP → ContentDirectory Browse → HTTP 下载的公开实现；README 说明实测相机是 WB350F，不能当作 WB800F 已实测的证明。
- [Vennnnnnn/wb250f-samsung-camera-transfer](https://github.com/Vennnnnnn/wb250f-samsung-camera-transfer)，查看版本 `a829d454b75ffbfa7b6ec5cbfecbb1095b0668b3`：2013 年 WB250F 的公开互操作行为，三星 SEC_DSC_ 发现标识及事件会话；仅采用公开协议事实，未复制或分发其代码。
- [ge0rg/samsung-nx-hacks Remote Viewfinder](https://github.com/ge0rg/samsung-nx-hacks/wiki/Remote-Viewfinder)：NX 系列公开的可选 7788 mode/control 请求；不代表 WB800F 必然提供该端点。
- [Android 17 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)：targetSdk 37 的 ACCESS_LOCAL_NETWORK 运行时权限。
- [Android 17 SDK 设置](https://developer.android.com/about/versions/17/setup-sdk)。
- Samsung SMART CAMERA App 1.4.0_180703：只用于分析互操作协议，核验记录见 `docs/PROTOCOL.md`。1.0.2 的 SP 实现由本工程独立编写，未复制或分发原 APK / 分析生成的源码。

本项目无第三方运行时库。Gradle Wrapper 用于开发构建，来自 Gradle 9.6.0，采用 Apache License 2.0；见 `gradle/WRAPPER-LICENSE.txt`。官方 Android SDK 与 JDK 是构建工具，未打包进入 APK 或源码 ZIP。
