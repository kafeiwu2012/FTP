# 3.0 验证记录

验证环境：JDK 21、Android SDK 35、Gradle 8.9；Android 11 AOSP x86_64 软件模拟器；独立 SMB 客户端 Impacket 与 smbprotocol；实际 Chromium 浏览器。

| 检查 | 结果 |
| --- | --- |
| FTP 回归 | 12 / 12 通过 |
| SMB 与跨协议回归 | 17 / 17 通过 |
| 网页 API 回归 | 9 / 9 通过 |
| 桌面和平板尺寸实际浏览器操作 | 通过 |
| Debug / Release / AndroidTest 构建、Android lint | 通过 |
| 发布签名 v2 / v3、对齐检查 | 通过，沿用旧版签名 |
| Android 真实 SAF 目录读写 | 通过，普通应用 UID 10130 |
| Android 上网页／FTP／SMB 交叉传输 | 通过 |
| Android 上桌面和平板尺寸浏览器全流程 | 通过 |
| iPad 原生“文件”App | 未真机验收，不承诺高位 SMB 端口兼容 |
| iPad Safari、实体 Windows 与手机后台省电行为 | 未在实体设备验收 |

SMB 回归包含带签名的 CREATE → QUERY_INFO → CLOSE 复合请求，后续请求使用全 1 的继承标识；还覆盖空目录 QUERY_DIRECTORY 结束后 CLOSE 成功释放句柄。旧 2.2 不能通过该回归，3.0 通过。

浏览器操作包含匿名只读、账号登录、新建目录、中文上传、明确确认覆盖、覆盖完成后下载逐字节校验、删除文件与空目录。使用 Chromium；平板视口不等同于 Safari 或 iPad 系统仿真。

Android 最终集成输出：

```text
uid=10130
ftp=2121
web=8080
smb=1445
运行中 · 账号可读写 · 匿名只读
445 端口不可用，已改用 1445；iPad 请优先使用网页地址
PASS: actual SAF FTP/SMB Unicode upload, cross-protocol download SHA-256, rename, delete, mkdir/rmdir
PASS: anonymous reads allowed; anonymous writes denied on actual Android service
PASS: actual SAF browser upload, FTP cross-read, range download and anonymous write denial
PASS desktop: guest read-only, account login, mkdir, Unicode upload, overwrite, download byte comparison, delete
PASS tablet: guest read-only, account login, mkdir, Unicode upload, overwrite, download byte comparison, delete
PASS: stopping sharing closes FTP service
ANDROID_SMOKE_PASSED
```

软件模拟器冷启动曾被系统无响应弹窗阻断；选择等待并在测试驱动中处理系统启动弹窗后重跑。浏览器覆盖测试发现上次成功提示造成时序误判，已修正上传状态、阻止忙碌期间文件链接操作，并等待本次 PUT 成功响应后再校验下载。上方结果来自最终通过的运行。
