# 局域网文件共享 3.0

Android 8.0 及以上的本地文件服务器：共享手机已授权的文件夹，让同一局域网内的 Windows、iPad 等设备浏览、下载，并按权限上传、覆盖、删除。支持账号和匿名访问，无需 Root，不上传云端。

**[下载 Android APK 3.0](https://github.com/kafeiwu2012/FTP/raw/refs/heads/main/downloads/LanFTP-3.0.apk)** · [源码压缩包](downloads/LanFTP-3.0-source.zip) · [SHA-256 校验](downloads/SHA256SUMS)

## 安装与使用

1. 覆盖安装 APK，与 1.x / 2.x 保持同一应用 ID 和发布签名。
2. 选择手机上的共享文件夹，在系统选择器中允许访问。旧版本若只有读取授权，开启写入前需要重新选择并授权。
3. 设置账号密码，按需打开权限开关，点击“启动文件共享”。
4. 手机与另一台设备连接同一 Wi-Fi，或让另一台设备连接手机热点。在 **iPad Safari 或 Windows 浏览器输入手机显示的 `http://` 网页地址**，例如 `http://192.168.1.20:8080`。
5. 未开启匿名访问时，浏览器要求输入手机界面的账号密码。匿名只读时，可点击网页“账号登录”使用账号的写入权限。
6. 浏览文件夹，点击文件下载；写入权限开启后，网页显示上传、新建文件夹、删除按钮。覆盖前必须确认。iPad 可从 Safari 下载列表将文件保存到“文件”App。
7. 用完点击“停止共享”，或从前台通知中停止。权限修改需要停止后重新启动。

手机主界面的“在本机预览共享文件”可先确认目录内容；“复制连接地址”提供全部网络接口的网页、FTP 和 SMB 地址。优先显示 Wi-Fi／热点地址；若该地址不可达，可从复制菜单选择同一局域网的另一个地址。地址随当前网络变化，以手机实际显示为准。

## 权限

网页、FTP、SMB 使用同一份授权目录和权限，不会因为更换协议而扩大写入权限。

| 开关 | 效果 |
| --- | --- |
| 允许账号用户上传、覆盖、删除 | 账号用户可写；关闭时账号只读 |
| 允许匿名 / 来宾访问 | 无需密码即可读取；默认关闭 |
| 同时允许匿名用户上传、删除 | 前两个开关均开启时才可开启；匿名用户可覆盖、删除 |

写入直接修改手机文件，删除不进入回收站；仅允许删除空目录。取消或中断上传可能留下不完整文件，覆盖中断可能影响原文件。只向可信局域网共享，并对重要文件保留备份。

密码不写入偏好设置；服务退出后重新打开界面会生成新密码。浏览器使用标准 HTTP Basic 身份验证，不在页面脚本中保存密码；浏览器可能缓存登录凭据，可关闭对应隐私浏览窗口结束缓存会话。错误账号不会自动降级为匿名。

## FTP 与 SMB

- FTP：`ftp://手机IP:2121`，可自定义端口。推荐 FileZilla 或其他 FTP 客户端，支持主动/被动模式、断点下载、上传、追加、重命名、空目录操作。
- SMB：共享名固定为 `share`。服务先尝试 TCP 445；被 Android 权限或占用阻止时，回退到 1445（与 FTP 冲突时用 1446）。实际端口会显示在界面，可手动配置高位端口。
- Windows 文件资源管理器通常使用 `\\手机IP\share` 和 445；使用高位 SMB 端口需要相应客户端支持。
- **不能保证免 Root 手机上的高位 SMB 端口兼容 iPad 原生“文件”App。iPad 建议直接使用 Safari 网页入口。** 如需尝试原生连接，在“文件 → 浏览 → 连接服务器”中填写实际 SMB 地址。
- SMB 使用 NTLMv2、SMB 2.0.2 / 2.1 和 SMB2 消息签名；不支持 SMB1 文件操作或 SMB3 内容加密。要求消息签名的客户端可能不允许来宾会话，可改用注册账号。

3.0 修复复合 SMB 请求的共享标识继承：CREATE 后的相关 QUERY_INFO / QUERY_DIRECTORY 可使用继承的 TreeId / SessionId / FileId，并正确返回相关操作标志；查询失败不会阻止 CLOSE 释放句柄。新增签名复合请求回归，覆盖挂载时的文件系统属性查询和空目录枚举。该修复已在独立协议客户端验证，尚无 iPad 原生客户端真机通过的证据。

## 文件与系统边界

- 使用 Android SAF 系统文件夹授权，所有协议限制在该目录内；不申请“所有文件访问权限”或 Root。受 Android 限制，不能访问其他应用私有数据；部分系统禁止选择存储根目录、Download 根目录或 Android/data，可选择具体子文件夹。
- 下载按流读取，网页上传按流写入，不将整个大文件加载到内存或上传到外部服务。网页支持 HTTP Range 断点下载和 Content-Length / chunked 上传。
- 网页不依赖 CDN、外部脚本或云端。文件名按文本显示；修改请求检查来源和自定义请求头，不开放跨域访问。
- HTTP、FTP 和 SMB2 不提供文件内容加密，只用于可信局域网，不映射公网端口。
- 不支持完整 Windows ACL、锁、远程命令执行、跨目录重命名、符号链接共享或任意文档提供器能力。时间戳、剩余空间及写入能力依赖 Android 提供器，失败时返回错误。
- 网页最多 12 个并发连接，FTP / SMB 各最多 8 个连接；每个 SMB 会话最多 256 个打开句柄，闲置连接约 120 秒断开。网页默认 8080，端口不可用时尝试到 8090，最终端口显示在主界面。
- 前台通知和 CPU / Wi-Fi 锁帮助保持传输，厂商省电策略仍可能干预。Android 15 的累计后台 dataSync 时限约 6 小时，达到后停止，需手动重启。

“连接诊断”显示启动目录项数、网页请求状态、SMB TCP 连接／帧／命令／目录请求计数，以及有界的最近事件。不记录文件名、密码或账号。网页与 SMB 的计数用于区分网络、认证和目录读取故障。

## 构建

JDK 17 / 21、Android SDK Platform 35 / Build Tools 35.0.0，Gradle Wrapper 8.9、AGP 8.7.3。应用运行时无第三方依赖。

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:assembleRelease :app:lintDebug
```

云环境使用 `./scripts/cloud-build.sh` 激活已有工具及受信代理 CA。调试包：`app/build/outputs/apk/debug/app-debug.apk`。Release 构建生成未签名包；可使用自己保管的签名密钥签署，云环境使用 `./scripts/sign-local.sh 3.0`。签名私钥位于仓库外，不包含在源码、APK 或下载目录中。后续更新需使用同一密钥。下载及 TLS 校验保持开启。

## 验证

[3.0 验证记录与实际 Android 输出](docs/validation-3.0.md)。

```sh
python3 tests/test_ftp.py
python3 -m venv /tmp/lanftp-tests
/tmp/lanftp-tests/bin/pip install -r tests/requirements.txt
/tmp/lanftp-tests/bin/python tests/test_sharing.py
python3 tests/test_web.py
./gradlew --no-daemon --max-workers=2 :app:lintDebug
```

12 项 FTP、17 项跨协议与 SMB、9 项网页测试，使用生产 Java 引擎与有边界检查的临时本地目录。覆盖中文读写、签名认证与篡改拒绝、复合请求继承、共享枚举、文件标识、目录格式、上传覆盖、分块上传、Range 下载、重命名删除、匿名关闭／只读／可写、错误密码不降级、越界与符号链接拦截、跨站修改拦截、重复请求头与歧义传输长度拒绝。

实际浏览器操作测试需要 Node、Playwright 与 Chromium：

```sh
python3 tests/run_browser_smoke.py
```

在桌面和平板尺寸下实际操作匿名只读、账号登录、新建目录、中文上传、确认覆盖、下载逐字节校验、删除；同时检查页面没有 JavaScript 错误。使用 Chromium，不代表 Safari 引擎或 iPad 真机已通过。

### Android 11 AOSP 模拟器集成验证

测试 APK 使用普通应用 UID，打开真实系统目录选择器并授予 SAF 读写权限；不伪造授权，不调用 Root。仅限可丢弃的 AOSP 模拟器。

```sh
./scripts/cloud-build.sh :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell mkdir -p /sdcard/Documents/LanFTPTest
adb -s emulator-5554 push tests/fixtures/android-sample.txt /sdcard/Documents/LanFTPTest/sample.txt
adb -s emulator-5554 shell run-as com.lanftp rm -f files/smoke-ready files/smoke-stop
adb -s emulator-5554 shell am instrument -w -r com.lanftp.test/com.lanftp.SmokeInstrumentation
```

最后一条命令运行期间，在另一个终端执行：

```sh
BROWSER_SMOKE=1 ADB=/path/to/adb ANDROID_TEST_SERIAL=emulator-5554 python tests/android_host_smoke.py
```

主机通过 adb TCP 转发访问实际 Android 前台服务；文件操作仍由普通应用 UID 和真实 SAF 提供器执行。检查网页／FTP／SMB 的中文读写、跨协议逐字节校验、目录操作、匿名写入拒绝、网页 Range 下载、实际浏览器全流程和停止服务。结束后关闭测试共享、移除自己创建的转发。无 KVM 时，模拟器冷启动与系统选择器操作较慢。
