# 升级签名配置

公开仓库仅保存公钥证书 `wb800f-signing-certificate.pem` 和配置模板，不含私钥或实际密码。

- 包名：`cn.cameralink.wb800f`
- 密钥别名：`wb800f`
- 原 APK 证书 SHA256：`15056dd5ef0e40b5ef5039ac8eb413da408c7197d15899bf8f45a50730f4ed9b`

## 维护者恢复同一签名

原签名保存在维护者的私有 `app-private-key` 仓库 `apps/wb800f-transfer/` 目录。
将其中的 `wb800f-release.p12` 和 `keystore.properties` 复制到本目录，再执行：

```sh
./gradlew :app:assembleRelease
```

`signing/keystore.properties.example` 说明配置格式。私钥及实际配置已在 `.gitignore` 中排除。
`storeFile` 相对工程根目录解析，也支持绝对路径。

也可用环境变量 `WB800F_KEYSTORE_FILE`、`WB800F_STORE_PASSWORD`、`WB800F_KEY_ALIAS` 和
`WB800F_KEY_PASSWORD` 配置。环境变量优先；别名默认 `wb800f`，私钥密码默认与密钥库密码一致。
请通过密码管理器或不回显的终端输入提供密码，避免将真实密码写进公开脚本。

无 Gradle 构建脚本只使用环境变量：

```sh
export WB800F_KEYSTORE_FILE=/private/path/wb800f-release.p12
read -r -s -p 'Keystore password: ' WB800F_STORE_PASSWORD
export WB800F_STORE_PASSWORD
# 如私钥密码不同，另行设置并导出 WB800F_KEY_PASSWORD。
./tools/build-apk.sh
```

## 其他开发者构建

未配置私钥时 `./gradlew :app:assembleRelease` 输出未签名 APK，不能直接安装；
`./gradlew :app:assembleDebug` 使用本机调试签名。使用自己的签名时无法覆盖维护者发布的 APK。
直接安装请下载 GitHub Releases 中的原签名 APK。

后续覆盖升级必须保持原包名、原签名，并递增 `version.properties` 中的 `VERSION_CODE`。
公钥证书可以用于核验，不能用来签发 APK，也不能代替私钥备份。
