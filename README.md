# 局域网 FTP / LAN FTP

Android 8.0（API 26）及以上的本地只读 FTP 服务器。通过 Android 系统文件夹选择器授权，局域网内的 Windows、iPad 等终端可以浏览、下载手机文件。无需账号注册或云端服务。

[下载 Android APK](https://github.com/kafeiwu2012/FTP/raw/refs/heads/main/downloads/LanFTP-1.0.apk) · [SHA-256 校验](downloads/SHA256SUMS)

## 安装和使用

1. 将 `LanFTP-1.0.apk` 复制到安卓手机，允许对应安装来源“安装未知应用”，完成安装。
2. 打开“局域网 FTP”，点击“选择 / 更换共享文件夹”，在系统选择器中选择要共享的本地文件夹并授权。
3. 保留随机生成的密码或设置至少 8 位密码，用户名默认 `ftp`，端口默认 `2121`。
4. 点击“启动 FTP 服务器”，允许通知以便随时停止。保持手机连接 Wi-Fi，或让其他设备连接手机热点。
5. 在其他设备输入手机界面显示的 `ftp://手机IP:2121`，使用当前界面显示的用户名和密码登录。
6. 用完点击“停止共享”，也可以从通知中停止。

密码不写入偏好设置；服务运行期间在内存中保留。服务退出后重新打开界面会生成新密码。文件夹的只读授权、用户名、端口会保存。

### Windows

在**文件资源管理器**（不是网页浏览器）地址栏输入 FTP 地址。如果系统集成功能不可用，使用 FileZilla Client：协议 FTP，主机填写手机 IP，端口 2121，加密选择“只使用普通 FTP”，填写用户名与密码。建议被动模式。

### iPad

使用支持 FTP 的客户端，如 FE File Explorer，在新增连接中选择 FTP，填写手机 IP、端口、用户名、密码。iPad 系统“文件”App 的“连接服务器”提供 SMB，不支持 FTP；Safari 也不作为 FTP 客户端使用。

## 权限、限制和安全

- 仅授权目录及其子目录可见。采用 Storage Access Framework，不申请所有文件管理权限。
- Android 11+ 通常禁止授权内部存储根目录、Downloads 根目录、Android/data、Android/obb 等位置。可以选择允许的子目录，例如 DCIM/Camera、Pictures 中的子目录，或新建“共享”目录；具体可选位置由系统文件选择器决定。应用无法访问其他应用私有数据。
- 只读：支持浏览、下载、中文 UTF-8 文件名、SIZE、MDTM、REST 断点续传、MLSD、PASV/EPSV 被动模式和 PORT 主动模式；拒绝上传、改名、删除。
- 默认控制端口 2121，数据连接使用动态端口。局域网设备隔离、访客 Wi-Fi、防火墙可能阻止连接。先确认同网段并关闭路由器客户端隔离；不要向公网映射端口。
- FTP **没有加密**，密码和文件以明文传输，仅用于可信局域网；不是 SFTP 或 FTPS。不同网络接口存在时界面可能显示多个私网地址，使用与客户端同网段的那个。
- 前台通知、CPU/Wi-Fi 锁用于提升传输稳定性。厂商省电策略仍可能中断服务；可在系统电池设置中允许应用后台运行。Android 15 对 dataSync 前台服务有累计后台时长限制，达到系统上限会停止，需要重新手动启动。
- 同时最多 8 个控制连接；空闲控制连接约 120 秒断开。单个断开的数据连接可由客户端用 REST 续传。
- 当前不支持多共享目录、上传、TLS、自动开机启动或公网分享。

## 构建

使用 JDK 17 或 21、Android SDK Platform 35 / Build Tools 35.0.0。Gradle Wrapper 固定 8.9，Android Gradle Plugin 固定 8.7.3，无第三方应用运行依赖。

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon --max-workers=2 :app:assembleDebug
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`，自动签名，可直接安装。生产分发请使用自己的安全保管的签名密钥签署 release APK：

```sh
./gradlew --no-daemon --max-workers=2 :app:assembleRelease
# 使用 Android SDK apksigner 对 app-release-unsigned.apk 签名
```

仓库不包含签名私钥。此次交付的 APK 使用此云环境单独生成的本地分发密钥签名；后续更新必须保留并使用同一密钥，或卸载旧版后安装。

如环境使用 HTTPS 代理，应配置 Java/Gradle 的代理及受信任的 CA，不能关闭 TLS 或校验。云端可用的构建入口见 `scripts/cloud-build.sh`，默认本机开发直接使用 Gradle Wrapper。

## 测试

```sh
python3 tests/test_ftp.py
./gradlew --no-daemon --max-workers=2 :app:lintDebug
```

Python 测试先用 `javac` 编译**应用实际使用的 FTP 引擎**，再以 `ftplib` 执行真实 TCP 请求：登录失败、中文路径、二进制完整性、续传、空文件、主动/被动/EPSV 模式、元数据、并发、越界与写入拒绝。测试 VFS 为临时本地目录，不代表 Android 文档提供器的真机测试。

真机验收：选择含中文名称和大文件的本地目录；分别在 Windows / iPad 下载并核对哈希；熄屏后继续下载；停止服务后确认不能连接；撤销目录权限后确认不会越权访问。云环境未连接安卓手机时，这些项目不能视为已通过。
