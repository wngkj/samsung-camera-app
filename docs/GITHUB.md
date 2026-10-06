# 仓库、历史和后续迭代

- 公开源码：https://github.com/wngkj/samsung-camera-app
- 下载 APK：https://github.com/wngkj/samsung-camera-app/releases
- 维护者私有签名备份：https://github.com/wngkj/app-private-key

公开仓库保留 v1.0.0、v1.0.1、v1.0.2 的应用代码、测试、说明及版本差异。
初始交付的三个版本曾在源码内包含升级私钥和密码；公开前逐版本移除了这些内容，
构建签名改为本地配置或环境变量，所以公开提交 SHA 与原始提交不同。
原始完整 Git 历史及新旧提交映射保存在私有签名仓库，本地原始工程也保留。

所有 Release 的 APK 均为此前交付的原始安装包，没有换签名，也没有重新构建后替换。
公开源码 ZIP 是重新整理后的副本，不含私钥、密码或原始私有 Git bundle。
最新 Release 提供下载的 public-history.bundle 只包含清理后的公开历史。

## 后续开发

在本仓库或其克隆中继续提交应用改动，更新 VERSION_CODE / VERSION_NAME 和 CHANGELOG。
需要发布覆盖升级 APK 时，按 signing/README.md 从私有备份恢复原签名，构建并核验证书指纹。
私钥及实际密码配置不会被 Git 跟踪；不要从旧的原始工程推送历史到公开仓库。

三版协议说明和验证结果保留在 docs/。真实 WB800F 与 Android 17 的端到端传输仍待实机验证。

本次下载文件保存在 `downloads/v<版本>/`，Release 页面提供对应固定提交的下载直链。
