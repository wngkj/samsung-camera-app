# Android 17 系统集成测试

这是单独的测试 APK 和本地模拟相机，不会安装进交付应用，不会验证真实 WB800F 固件。必须使用独立测试手机或模拟器：测试会删除**测试应用创建的 DEMO_*.jpg 模拟照片**来测试取消行为。

需要 Android 17 API 37 模拟器、JDK 17+、API 37 SDK 与 Build Tools 37.0.0；标准模拟器的 Wi-Fi 地址是 10.0.2.x，`10.0.2.2` 对应宿主电脑。

```sh
# 在工程目录，开启模拟器后：
python3 tools/mock-camera.py --delay 0.15
# 另一个终端：
export JAVA_HOME=/path/to/jdk
export ANDROID_SDK_ROOT=/path/to/android-sdk
./tools/build-apk.sh
./tools/build-android-tests.sh
adb install -r build/WB800F-Transfer-1.0.0.apk
adb install -r build/android-tests/tests.apk
adb shell pm clear cn.cameralink.wb800f
adb shell am instrument -w -e cameraUrl http://10.0.2.2:8765/description.xml \
  cn.cameralink.wb800f.tests/cn.cameralink.wb800f.tests.SmokeInstrumentation
```

真实测试手机需要电脑和手机处在同一个 Wi-Fi，并把 `--advertise` 和 `cameraUrl` 改成电脑局域网 IP；此时验证的是手机连接模拟器协议，不是连接相机。

测试检查真实系统中的安装启动、局域网权限弹窗、Wi-Fi 网络请求、ContentDirectory 分页、MediaStore 写入、逐文件 SHA256、拒绝通知权限后的传输、去重、取消回滚、返回桌面后前台服务继续工作。仪器会在独立测试包内部 files 目录生成截图和 result.txt，可由 adb run-as 导出，例如：

```sh
adb exec-out run-as cn.cameralink.wb800f.tests cat files/result.txt
adb exec-out run-as cn.cameralink.wb800f.tests cat files/01-home.png > home.png
```

协议测试用照片由 `tests/MakeFixtures.java` 生成，图片上标记 DEMO，保存在 `tests/fixtures`；不包含用户相机照片。
