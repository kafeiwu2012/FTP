# 局域网文件共享 2.0 / LAN FTP + SMB2

Android 8.0（API 26）及以上的本地文件服务器。手机通过系统文件夹选择器授权一个共享目录，局域网内的设备可使用 FTP 或 SMB2 浏览、下载；可选开启上传、覆盖、删除和匿名访问。应用不申请 Root，不使用云端文件服务。

**[下载 Android APK 2.0](https://github.com/kafeiwu2012/FTP/raw/refs/heads/main/downloads/LanFTP-2.0.apk)** · [校验文件](downloads/SHA256SUMS)

## 安装、授权与权限开关

1. 安装 APK；与 1.0 使用相同应用 ID 和签名，可直接覆盖升级。
2. 打开“局域网文件共享”，选择本地共享文件夹并授权。**从 1.0 升级的旧目录只有读取权限，开启写入前需重新选择并授予写权限。**
3. 设置账号密码。密码至少 8 位；用户名不能是保留的 `guest` / `anonymous`。账号密码同时用于 FTP 和 SMB。
4. 按需设置以下三个开关，默认都关闭：

| 开关 | 效果 |
| --- | --- |
| 允许账号用户上传、覆盖、删除 | 账号用户可修改共享目录，关闭时只读 |
| 允许匿名 / 来宾访问 | 允许无密码访问；默认匿名用户只读 |
| 同时允许匿名用户上传、删除 | 仅前两个开关均开启时有效；匿名用户也能覆盖或删除文件 |

5. 点击“启动文件共享”。连接同一 Wi-Fi，或让其他终端连接手机热点。界面显示 FTP、SMB 地址和 SMB 启动状态；复制按钮可选择协议。
6. 用完从界面或通知点击“停止共享”。修改权限必须先停止服务再启动，因此不会在连接过程中扩大用户权限。

写入直接修改手机文件，删除不进入回收站；目录删除仅允许空目录。匿名写入意味着局域网内任何可连接的设备都可改动共享文件，请只对可公开且有备份的目录开启。

密码仅在服务和界面内存中保留，不写入偏好设置；服务退出后重新打开界面生成新密码。目录授权、用户名、端口和权限选项会保存。

## iPad“文件”App：SMB

文件 → 浏览 → 右上角菜单 → 连接服务器，输入应用显示的 SMB 地址，如 `smb://192.168.1.20/share`，选择“注册用户”并填写账号密码；共享名固定为 `share`。开启匿名访问后可尝试选择“客人”。

**免 Root 的端口限制：** 服务先尝试标准 TCP 445。若手机内核权限、SELinux 或端口占用使 445 不可用，会自动尝试 1445（FTP 已占用 1445 时使用 1446），界面明确显示实际端口。自定义端口也可在界面设置。

高位端口不是 iPad 原生兼容性的保证。iPad“文件”App 对高位端口及来宾会话的支持需按实际系统测试；若不接受 `smb://IP:1445/share`，不能靠改变地址格式或关闭密码解决。可使用支持自定义 SMB 端口 / FTP 的客户端，或在同一局域网另一台有权限的设备上，将它的 TCP 445 转发到手机的 SMB 端口，再让 iPad 连接该设备的 `smb://设备IP/share`。手机本身无需 Root。不要将此转发暴露到公网。

SMB 使用 NTLMv2，可验证并生成 SMB2 消息签名，支持 SMB 2.0.2 / 2.1、共享枚举及基本文件读写。**不支持 SMB1 文件访问或 SMB3 内容加密。** 要求消息签名的客户端可能禁止匿名会话；此时使用注册账号。没有修改或降低客户端系统安全策略。

## Windows 与其他客户端

- SMB 标准端口：文件资源管理器输入 `\\手机IP\share`。Windows 资源管理器通常不接受自定义 SMB 端口，遇到高位端口时使用兼容客户端或同网段 445 转发设备。
- FTP：文件资源管理器地址栏输入 `ftp://手机IP:2121`；浏览下载可用系统集成，上传、删除推荐使用 FileZilla。
- FileZilla：协议 FTP，主机填写手机 IP，端口默认 2121，加密选择“普通 FTP”；使用注册账号，或开启匿名后选择匿名登录。建议被动模式。
- iPad FTP：使用 FE File Explorer 等支持 FTP 的客户端，系统“文件”App 不支持 FTP。

FTP 支持 UTF-8 中文文件名、PASV/EPSV、PORT、SIZE、MDTM、MLSD、下载断点续传、上传/覆盖（STOR）、追加（APPE）、删除（DELE）、新建/删除空目录（MKD/RMD）、同目录重命名（RNFR/RNTO）。FTP 上传续传 REST+STOR 暂不支持。

## 范围和限制

- 仅访问授权目录及其子目录，不申请“所有文件访问权限”。系统可能禁止授权内部存储根目录、Downloads 根目录、Android/data、Android/obb、其他应用私有数据等；以系统选择器为准。
- Android 文档提供器必须授予写权限并支持修改操作。SMB 随机写入需要本地提供器支持可定位的文件描述符，推荐手机本地存储。云端文档、虚拟文件或只读 SD 卡可能不支持。
- 当前不支持跨目录移动、非空目录递归删除、文件 ACL、备用数据流、持久句柄、文件变更通知、字节范围锁、保证任意设置文件时间戳等完整 Windows 文件服务器功能。内容修改时间由 Android 提供器更新；SMB 设置修改时间会尝试通过已授权文件描述符完成，提供器拒绝时返回失败；不伪造不支持操作的成功结果。
- FTP 明文传输；SMB2 签名用于完整性验证，不加密文件内容。只在可信局域网使用，不映射公网端口。
- 访客 Wi-Fi、客户端隔离、防火墙、省电或厂商后台限制可能阻止连接。前台通知及 CPU/Wi-Fi 锁用于保持传输；Android 15 累计后台 dataSync 时限到达后会停止，需要手动重启。
- FTP、SMB 各最多 8 个连接；每个 SMB 会话最多 256 个打开句柄。闲置连接约 120 秒断开。
- Android 模拟器检查不等同于安卓手机、Windows 资源管理器或 iPad“文件”App 真机验证。特别是 445 端口与 iPad 客户端行为必须在实际设备上验收。

## 构建

JDK 17 或 21、Android SDK Platform 35 / Build Tools 35.0.0。Gradle Wrapper 8.9、Android Gradle Plugin 8.7.3。应用运行时没有第三方依赖。

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:lintDebug
```

调试 APK 自动签名：`app/build/outputs/apk/debug/app-debug.apk`。Release：`:app:assembleRelease`，生成未签名包后使用自己的安全保管的签名密钥签署。云环境可使用 `./scripts/cloud-build.sh` 激活保留的工具及受信代理 CA，使用 `./scripts/sign-local.sh 2.0` 签名。

签名私钥保存在云环境仓库外，未进入源码或下载目录。后续更新需使用同一密钥。Gradle 下载保留官方校验值，TLS 校验保持开启。

## 验证

```sh
python3 tests/test_ftp.py
python3 -m venv /tmp/lanftp-tests
/tmp/lanftp-tests/bin/pip install -r tests/requirements.txt
/tmp/lanftp-tests/bin/python tests/test_sharing.py
./gradlew --no-daemon --max-workers=2 :app:lintDebug
```

12 项 FTP 回归测试与 14 项跨协议功能测试使用生产 Java 引擎，测试后端为带边界检查的临时本地目录。共享测试使用 smbprotocol 和 Impacket 两套独立 SMB 实现，覆盖中文读写、签名认证及篡改拒绝、FTP/SMB 交叉传输校验、随机写入/截断、重命名、删除、空目录约束、匿名关闭/只读/可写、错误密码不降级匿名、共享枚举、越界/符号链接拦截和未登录请求。

真机验收：授权本地目录，分别测试账号只读、账号可写、匿名只读、匿名可写；核对 Windows/iPad 与手机文件内容及大小；确认删除只影响共享目录；检查实际 SMB 端口；熄屏后传输并验证停止服务后不可连接。文档提供器的能力与 iPad 原生客户端兼容性不能由主机协议测试替代。

### Android 11 AOSP 模拟器烟雾检查

本次已实际验证：应用以普通 UID 运行，系统选择器授予真实 SAF 读写权限；445 被拒绝后回退到 1445；FTP/SMB 中文上传、跨协议下载逐字节校验、重命名、删除、目录操作、匿名只读限制和停止服务均通过。该结果不代表 iPad 自带“文件”App 已真机通过。

`SmokeInstrumentation` 只存在于测试 APK，使用标准初始目录提示打开一次性测试目录，再通过系统确认按钮授予权限，不伪造权限，不调用 Root。仅用于可丢弃的 AOSP 模拟器。复现步骤（需要 Android SDK adb 和已启动的 emulator-5554）：

```sh
./scripts/cloud-build.sh :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell mkdir -p /sdcard/Documents/LanFTPTest
adb -s emulator-5554 push tests/fixtures/android-sample.txt /sdcard/Documents/LanFTPTest/sample.txt
adb -s emulator-5554 shell run-as com.lanftp rm -f files/smoke-ready files/smoke-stop
adb -s emulator-5554 shell am instrument -w -r com.lanftp.test/com.lanftp.SmokeInstrumentation
```

最后一条命令保持运行期间，在另一个终端执行（Python 环境需安装 `tests/requirements.txt`）：

```sh
ADB=/path/to/android-sdk/platform-tools/adb ANDROID_TEST_SERIAL=emulator-5554 python tests/android_host_smoke.py
```

主机检查通过 adb 转发真实 TCP 数据，并逐个转发 FTP 的动态被动端口；实际文件操作仍由 Android 普通应用 UID 和授权文档提供器执行。结束后关闭本次共享并移除自己创建的转发。SDK 无 KVM 时启动和系统 UI 操作较慢。
