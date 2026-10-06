# 协议和迭代说明

## 相机流程与不确定性

WB800F 是目标相机。本环境未接入实际相机。用户日志已经证明应用启动、局域网授权和 Wi-Fi 绑定，并显示 192.168.104.11 / 网关 192.168.104.1；没有证明照片端到端传输成功。1.0.2 根据旧三星客户端的协议分析补齐相机选片发送；其余 DLNA 路径依据同代 WB 公开实现。详见下面的核对记录。

### 相机选片发送（SP，1.0.2 新增）

原客户端对 DHCP 相机地址的第三段分类：101 为手机选片，102 为取景，103 为自动发送，104 为相机选片，107 使用模式协商。这说明 104 网络不能只按 DLNA 去请求 7676。应用根据当前 Wi-Fi 网关的 192.168.104.* 选择 SP；提供完整描述 URL 时保留用户显式指定的 DLNA 连接。

1. 手机在当前 Wi-Fi IPv4 上监听 TCP 18100，接收端只接受当前相机 IP。
2. 向相机 8100–8103 依次发送 `HEAD /sp/control`。字段包含 `SEC_SP_<手机端标识>`、`Data-Server`（手机 IPv4:18100）、`Data-Port`（18100）、`NTS: alive`、`Access-Method: manual`、`HOST-PNumber: none`。不是向 8100 发送 `/mode/control`。
3. 相机成功响应后，用户在相机选片并共享 / 发送。手机接收带文件路径、Content-Length 和 Expect 的请求，先创建 pending 相册条目，再答复 100 Continue，然后写入固定长度的原 JPEG 字节。
4. 仅接收 JPG / JPEG，检查 JPEG 开头，单张上限 256 MiB；截断、存储失败或取消均不发布 pending 文件。成功关闭流并提交相册后，发送 200 和 Sub-ErrorCode: 0。
5. 完整文件 SHA256 作为 SP 导入索引；若对应的已保存 URI 仍存在，清理本次 pending 重复副本并成功应答。该内容索引与 DLNA 的目录键分别维护，跨模式首次导入可能重复。
6. 接收会话保持前台服务，用户取消或相机结束命令会关闭监听和当前接收，通知相机 NTS byebye（尽力发送）；未完成条目删除，完成条目保留。

手机 MAC 不是相机 MAC。默认继续使用应用生成的持久化客户端标识；「手动连接」可选填系统当前 Wi-Fi 详情里的手机 MAC。应用不通过受限 API 偷读真实 MAC，不增加定位或电话权限。

### 旧三星客户端核对记录

分析对象为 Samsung SMART CAMERA App 1.4.0_180703，包名 com.samsungimaging.connectionmanager。文件 SHA256 `ee64e1a658f93970207cd88f8cf2b2ebeae2d847fcc7e937d7757a41b3e78aaf`，签名证书 SHA256 `84ec03b097fe6f5e88ad35d93e92be5ab4ca76f710375dc69990b4960547b4a8`；下载后分别核验了文件哈希和 APK v1 签名，与历史镜像元数据一致。

协议事实通过分析客户端模式分类、SelectivePush 控制端点、接收头与应答格式确认。采用这些协议事实独立实现，不复制原客户端的方法或类，不将原 APK 或分析生成的源码放入交付工程。历史版本元数据：[APKMirror 1.4.0_180703](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-smart-camera-app/samsung-smart-camera-app-1-4-0_180703-release/samsung-smart-camera-app-1-4-0_180703-android-apk-download/)。客户端分析不是 WB800F 实机抓包，仍需新版日志核实实际握手和发送。

### 手机选片浏览（DLNA）

1. 用户自行在系统 Wi-Fi 设置连接相机 AP。应用不扫描 SSID，不读取真实 Wi-Fi MAC，不需要定位权限。
2. `ConnectivityManager.requestNetwork` 选择 Wi-Fi，而不要求 `INTERNET` / `VALIDATED` capability。所有 HTTP/TCP/UDP 使用选定 `Network`，因此系统把默认互联网切到蜂窝时，相机请求依旧走 Wi-Fi。
3. SSDP 每 2 秒发送 `M-SEARCH` 到 `239.255.255.250:1900`，`ST: ssdp:all` / `upnp:rootdevice` / `MediaServer:1`，包含 `USER-AGENT: SEC_DSC_<app client>`、`ACCESS-METHOD: manual`。同时尝试向当前 Wi-Fi 网关单播搜索。临时端口接收搜索回复，另一个加入该 Wi-Fi 多播组的 UDP 1900 套接字接收 `NOTIFY` 公告，并从固定端口补发多播搜索。监听失败时仍保留临时端口路径。实际 MAC 在新安卓不可可靠读取，应用生成且持久化一个本地管理格式的 **应用客户端标识**，并不假装它是真实硬件地址。
4. 优先使用 SSDP `LOCATION`；没有响应时，仅针对当前 Wi-Fi 网关和相同 /24 的常见三星相机地址尝试设备描述路径 `/smp_6_`、`/smp_2_`、`/description.xml`。在约 45 秒的服务等待窗口内，持续收包并更新地址，同时公平轮询设备描述，单个 URL 重试间隔至少 7 秒。收到描述后或取消 / 失败时关闭发现套接字。不是扫描整个网络，不会假定所有相机使用同一个地址。
5. 描述请求失败时对该主机尝试 7788 `/mode/control`。这是可选的 NX 固件兼容尝试；WB800F 可能不提供该端点，失败会记录后继续，不强制依赖它。若固件显示连接请求则由用户确认；直接连接时不要求有此弹窗。
6. 从设备 XML 获取 ContentDirectory 的真实 `serviceType`、`controlURL`、`eventSubURL`，遵守 `URLBase` 和相对 URL，不硬编码控制路径。
7. 有 eventSubURL 时创建本地回调，`SUBSCRIBE`，确认相机的 `NOTIFY`，每 40 秒续期申请的 300 秒会话。订阅失败不阻止读取，可在日志中识别会话问题。自定义 HTTP 方法用绑定 Wi-Fi 的原始 TCP 请求，避免 Android HttpURLConnection 不接受 SUBSCRIBE。
8. 从 ObjectID=0 递归 `BrowseDirectChildren`，每页请求 100 条，依据 NumberReturned / TotalMatches 分页；根目录无法读取或为空时兼容三星 ObjectID=1 图像目录。目录循环去重；重复分页明确报错，避免无提示丢照片。
9. DIDL 选择 `image/*` 条目中优先 `JPEG_LRG` 的资源，其余按分辨率和大小选择；`JPEG_TN` / `JPEG_SM` 及明显缩略图不作为可导入原图。原图字节流直接写入相册，保存前核验目录大小与 HTTP Content-Length。

## 保存和权限

- 最低 Android 10，使用 MediaStore scoped storage，不申请整个相册读取或所有文件管理权限。
- Android 17 以上请求 `ACCESS_LOCAL_NETWORK`；通知权限用于用户控制前台任务。
- 文件插入 `IS_PENDING=1`，完整写入且关闭流后更新为 0；失败 / 取消删除当前 pending URI。
- 去重键是相机 UDN、ObjectID、资源路径、标题、日期及大小的 SHA256；使用本应用成功保存的 URI 判断是否仍存在，不以“文件同名”判断。
- 写入时计算 SHA256 放入本应用导入索引；目录未提供原始哈希，因此该哈希不能证明与相机源文件一致。完整性判定是传输大小校验。
- 进程意外死亡时，下一次启动清理超过一小时的本应用 pending 文件；清理不删已完成照片。应用保存目录为 `Pictures/WB800F/`，MediaStore 默认查询在无读取权限时仅返回本应用可访问的条目。
- HTTP 不跟随重定向，实际应用传输层只允许私有 / link-local 相机地址。允许明文 HTTP 是这类旧相机的协议要求。

## 推荐实机联调记录

记录相机固件版本、手机型号和 Android 17 具体构建号。先传 1 张原图，用 SD 卡读卡器取得同一文件比较字节大小和 SHA256；再测多选、未传筛选、锁屏、返回桌面、取消、断开 Wi-Fi、重新连接。不要只凭缩略图可见认定原图传输成功。

若连接卡住，导出诊断，关注：

- Wi-Fi IPv4 / gateway 是否是相机网络；Android 局域网权限是否已允许。
- SSDP 是否收到 LOCATION；是否触发相机确认；描述路径返回的 HTTP 状态。
- XML 是否提供 ContentDirectory 服务；是否需要不同客户端 User-Agent 或额外协议握手。
- Browse 是否返回 701 / 其他 UPnP 错误；实际根 ObjectID、容器和分页总数。
- 原图资源的 URL、JPEG_LRG / 分辨率 / size；是否需要 Samsung 扩展动作。
- GENA 订阅和续期是否被固件接受；相机自动退出模式前是否发送 byebye。

仅在日志或抓包证实需要时增加具体固件的扩展请求。不要盲目发送可能更改相机状态的未知 SOAP 动作。

## 代码职责

`CameraProtocol`、`CameraClient`、`SsdpDiscovery` 不依赖 Android，可用本地 HTTP / UDP 夹具验证。`WifiConnection` 负责 Android 网络和 Samsung 会话。`PhotoStore` 独立处理 MediaStore 提交。`TransferService` 保留可取消任务与进度。`MainActivity` 只负责界面和用户权限，`LogProvider` 只暴露一份用户主动导出的只读日志。
