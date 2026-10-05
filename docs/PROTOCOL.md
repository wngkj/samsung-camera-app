# 协议和迭代说明

## 相机流程与不确定性

WB800F 是目标相机。本次环境未接入实际相机；以下是依据同代 Samsung WB 相机的公开协议实现得到的互操作策略，不是 WB800F 抓包结果。后续应使用应用诊断日志核实实际 LOCATION、设备描述、Browse 服务及资源。

1. 用户自行在系统 Wi-Fi 设置连接相机 AP。应用不扫描 SSID，不读取真实 Wi-Fi MAC，不需要定位权限。
2. `ConnectivityManager.requestNetwork` 选择 Wi-Fi，而不要求 `INTERNET` / `VALIDATED` capability。所有 HTTP/TCP/UDP 使用选定 `Network`，因此系统把默认互联网切到蜂窝时，相机请求依旧走 Wi-Fi。
3. SSDP 发送 `M-SEARCH` 到 `239.255.255.250:1900`，`ST: ssdp:all` / `upnp:rootdevice`，包含 `USER-AGENT: SEC_DSC_<app client>`、`ACCESS-METHOD: manual`。实际 MAC 在新安卓不可可靠读取，应用生成且持久化一个本地管理格式的 **应用客户端标识**，并不假装它是真实硬件地址。
4. 优先使用 SSDP `LOCATION`；没有响应时，仅针对当前 Wi-Fi 网关和相同 /24 的常见三星相机地址尝试设备描述路径 `/smp_6_`、`/smp_2_`、`/description.xml`。不是扫描整个网络，不会假定所有相机使用同一个地址。
5. 描述请求失败时对该主机尝试 7788 `/mode/control`。这是可选的 NX 固件兼容尝试；WB800F 可能不提供该端点，失败会记录后继续，不强制依赖它。相机屏幕的连接确认必须由用户完成。
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

`CameraProtocol`、`CameraClient` 不依赖 Android，易于用本地 HTTP 夹具验证。`WifiConnection` 负责 Android 网络和 Samsung 会话。`PhotoStore` 独立处理 MediaStore 提交。`TransferService` 保留可取消任务与进度。`MainActivity` 只负责界面和用户权限，`LogProvider` 只暴露一份用户主动导出的只读日志。
