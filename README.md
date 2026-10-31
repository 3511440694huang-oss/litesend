# 轻传 LiteSend

<p align="center">
  <img src="art/seedream-icon.png" width="128" alt="轻传图标">
</p>

<p align="center">
  局域网内的点对点文件传输 Android 应用<br/>
  不经过服务器 · 不需要账号 · 不需要数据线
</p>

<p align="center">
  <img alt="platform" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-24-blue">
  <img alt="version" src="https://img.shields.io/badge/version-1.3.0-orange">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-green">
</p>

---

## 简介

轻传是一个类似 [LocalSend](https://localsend.org) 的 Android 局域网文件传输工具：两台设备连接同一个
Wi-Fi 后可以自动互相发现，选中文件即可直接传输。所有数据都在局域网内点对点流动，不经过任何中转服务器。

与同类工具相比，它的取舍是「轻」：整个 APK 约 2 MB，除 AndroidX 外没有第三方运行时依赖，
传输引擎是纯 Kotlin 实现，可以脱离 Android 在 JVM 上做端到端单元测试。

## 功能

- **自动发现**：UDP 组播 + 广播双通道（`224.0.0.167:53318` / `255.255.255.255`），也支持手动输入 IP
- **直接传输**：HTTP/1.1 定长流式上传，逐文件一次性 token 鉴权，默认接收前人工确认
- **常用对象**：手动连过的设备可存为常用对象，下次一点即连，无需重复输入地址
- **发送已安装应用**：列出本机用户安装的应用，提取 APK 直接发送；带 split 的应用会打包成 `.apks`
- **图片预览**：缩略图网格 + 全屏查看器，支持双指缩放、双击放大、左右翻页、分享与删除
- **独立保存目录**：接收的文件统一落在 `Download/轻传`，也可切换到与 Download 并排的根目录 `轻传/`
- **后台接收**：前台服务保持接收在线，通知栏显示进度
- **网络诊断**：展示本机地址、网关、发现通道、广播收包情况，并对「连不上」给出判断
- **浅色 / 深色**：跟随系统，也可手动指定

## 下载安装

从 [Releases](../../releases/latest) 下载最新的 `轻传-vX.Y.Z.apk`，在手机上安装即可（需要允许安装未知来源应用）。

> 发布包使用仓库内的 Android 调试证书签名（`keystore/debug.keystore`，密码 `android`），
> 仅用于内部分发与自签名安装，不适合上架应用商店。

## 使用

1. 两台设备连接**同一个 Wi-Fi**，分别打开轻传；
2. 在首页「附近设备」里点一下对方，选择要发送的文件；
3. 接收方确认后开始传输，文件保存到接收目录；
4. 收不到设备时，点「手动添加」输入对方首页显示的 IP（只需 IP，端口会自动尝试）。

| 场景 | 入口 |
|---|---|
| 发送文件 | 首页点设备，或「文件」页右上「发文件」 |
| 发送已安装应用 | 「文件」页右上「发应用」 |
| 查看图片 | 「文件」页点任意图片 |
| 分享 / 删除文件 | 长按缩略图，或点文件行右侧「⋮」 |
| 管理常用对象 | 首页「常用对象 → 管理」 |
| 排查连不上 | 设置 → 其它 → 网络诊断 |

## 文件保存位置

| 模式 | 路径 | 额外权限 |
|---|---|---|
| 默认 | `Download/轻传` | 无（Android 10+ 通过 MediaStore 写入） |
| 根目录 | `/storage/emulated/0/轻传` | 「所有文件访问权限」 |

文件名冲突时自动追加 `(1)` 等序号；非法字符与超长文件名会被清洗。

## 工作原理

- **发现**：每 3 秒广播一次 `announce`（JSON），12 秒无心跳视为离线；
- **传输**：`POST /api/v1/offer` 协商 → 接收方确认 → 逐文件 `PUT /api/v1/upload`（`X-Token` 鉴权、
  `Content-Length` 必须与声明一致）→ `POST /api/v1/complete` 收尾；
- 端口默认 `TCP 53318`，被占用时自动顺延；发现端口固定 `UDP 53318`。

完整的报文格式、状态码与模块接口见 [docs/ARCH.md](docs/ARCH.md)。

## 项目结构

```
app/src/main/java/app/qingchuan/
├── core/                  # 与 Android 解耦的传输引擎与数据层
│   ├── Net.kt             # 极简 HTTP/1.1 服务端
│   ├── TransferServer.kt  # 接收端：会话、鉴权、流式落盘
│   ├── Discovery.kt       # UDP 组播 + 广播发现
│   ├── Sender.kt          # 发送端：offer → upload → complete
│   ├── Engine.kt          # 编排发现、接收、发送与状态流
│   ├── Storage.kt         # MediaStore / 直接文件写入
│   ├── ImageStore.kt      # 图片解码、EXIF、内存与磁盘缓存
│   ├── AppPackages.kt     # 列出已安装应用并提取 APK
│   └── Contacts.kt        # 常用对象持久化
├── service/               # 前台服务（后台接收、通知进度）
└── ui/                    # Jetpack Compose 界面（首页/文件/设置/诊断/查看器）
```

## 从源码构建

环境要求：JDK 17、Android SDK（compileSdk 36）、Gradle 8.11.1（仓库自带 wrapper）。

```bash
# 1. 配置本机 SDK 路径
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2. 编译 / 打包
./gradlew :app:compileDebugKotlin   # 只做编译检查
./gradlew :app:testDebugUnitTest    # 28 个 JVM 单元测试
./gradlew :app:assembleRelease      # 生成已签名 APK
```

产物位于 `app/build/outputs/apk/release/`。

> 在 Termux / aarch64 等非标准环境下，仓库根目录的 `build.sh` 固化了本机的工具链路径
> （JDK、Gradle、aapt2），可作为参考，不使用 wrapper 直接调用。

## 测试

`app/src/test/java/app/qingchuan/EngineTest.kt` 中有 28 个 JVM 单元测试，覆盖：

- JSON 编解码与非法输入；
- HTTP 服务端的 keep-alive、请求头上限、未读 body 处理；
- 接收端：2 MB 二进制逐字节一致性、token 校验、长度不符、半途断开清理、取消传播；
- 发送端到接收端的端到端流程（含中文文件名）；
- 发现通道的 probe 与线程清理。

这些测试不需要 Android 运行时，`./gradlew :app:testDebugUnitTest` 即可运行。

## 兼容性与体积

- 最低 Android 7.0（API 24），targetSdk 34，compileSdk 36；
- 单个通用 APK，不按 ABI 拆分；唯一 native 库是 AndroidX 的 `libandroidx.graphics.path.so`（约 37 KB），
  四个 ABI（`armeabi-v7a` / `arm64-v8a` / `x86` / `x86_64`）都在包内，**32 位设备可直接安装运行**；
- 无 Google Play 服务依赖。

## 常见问题

**两台设备互相看不到 / 手动输入 IP 也超时**

这类情况通常与 App 无关，而是网络隔离。按顺序检查：

1. 路由器是否开启了 **AP 隔离 / 客户端隔离**，或两台设备连到了访客 Wi-Fi；
2. 两台设备是否在同一频段（Wi-Fi 7 的 6GHz 或「IoT 网络」可能与主网络隔离）；
3. 是否开启了 VPN、加速器或代理；
4. 用一台手机开热点、另一台连接后再试 —— 这是最有效的判定方式。

设置 → 网络诊断 会显示本机地址、网关、发现通道与收到的广播数量，可据此判断是「收不到广播」
还是「收到了但连不上」。

更多排查记录见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 已知限制

- 传输为明文 HTTP，仅适用于可信局域网；默认开启「接收前确认」；
- 依赖组播/广播，部分路由器会拦截，此时只能手动输入 IP；
- 不支持断点续传；
- `.apks`（分发包）需要专用安装器（如 SAI），系统无法直接点击安装；
- 后台接收为 `dataSync` 前台服务，Android 15+ 存在每日运行时上限。

## 许可证

[MIT](LICENSE)
