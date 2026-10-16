# 3.1 验证记录

3.1 基于 3.0 移除 SMB，只保留网页和 FTP 文件共享。

| 检查 | 结果 |
| --- | --- |
| FTP 回归 | 12 / 12 通过 |
| 网页、跨协议文件读写和权限回归 | 10 / 10 通过 |
| 桌面与平板尺寸 Chromium 完整操作 | 通过：匿名只读、账号登录、新建目录、中文上传、确认覆盖、下载逐字节校验、删除 |
| Debug / Release / AndroidTest 构建与 Android lint | 通过 |
| 发布签名 v2 / v3、对齐、升级签名一致性 | 通过，与 3.0 相同 |
| APK 内容检查 | 非调试包，versionCode 6 / versionName 3.1；DEX 中无 SmbServer、Ntlm、RpcPipe、SMB URL 或设置字段 |

本次没有重新运行 Android 模拟器或实体设备验收。保留并更新了网页／FTP 的 Android SAF 集成测试驱动，AndroidTest APK 编译通过；3.0 的运行验证记录属于旧版，不作为 3.1 的运行结果。

APK 及源码压缩包的 SHA-256 见 downloads/SHA256SUMS。源码不包含发布私钥。
