# 验证记录 · 轻传 LiteSend v1.3.0

> 只记录**真实跑过**的结果；没跑的一律写「未验证」，不写推断。

## 1. 单元测试（JVM，真 socket，无 Android 运行时）

```
bash build.sh :app:testDebugUnitTest
→ app.qingchuan.EngineTest  tests=28  failures=0  errors=0
```

覆盖范围（测试文件 `app/src/test/java/app/qingchuan/EngineTest.kt`，918 行）：

| 模块 | 关键用例 |
|---|---|
| Json | 往返、引号/反斜杠/换行/中文/`\u4e2d`/4GB Long/嵌套、6 类非法输入 |
| Net | query + 大小写不敏感 header、keep-alive 连续两请求、handler 不读 body 也先 drain、16KB 头 → 431 |
| TransferServer | info/ping、404/405、2MB 随机字节**逐字节比对落盘**、64KB 粒度进度单调、complete、拒绝、三种 400 limit、坏 JSON、bad_token 403、长度不符 409、半途断开 500 且删半截文件、cancel 回执 + 旧 token 失效、**offer 等待确认期间 ping 仍 <1s**、忙端口 start 返回 null |
| Sender | 对真实 server 端到端（含「a测试.jpg」2MB + 「说明 文件.txt」）、字节比对、DECLINED / CANCELLED（对端收到 cancel）/ 对端不可达 FAILED |
| Discovery | probe 添加设备（latency ≥ 0）、关闭端口返回 null、start/stop 后 `qc-*` 线程清零 |

另外两条集成正确性由单测钉死：
- `StoredFile.id` 跨会话唯一（`${sid}_${fid}`），否则历史记录会互相覆盖；
- Discovery 每条 announce 都回调，翻页/刷新不会误删在线设备。

## 2. APK 静态校验（对交付的 `APK/轻传-v1.3.0.apk` 实测）

| 项 | 结果 |
|---|---|
| 大小 | 2,128,898 字节（2.1 MB） |
| SHA-256 | `3c1717a7d8c2b732ed04b7554d9c05edadf9a4d5a1942e5a7d4e0ea29d1ddc54` |
| 包名 / 版本 | `app.qingchuan` / versionCode 4 · versionName 1.3.0 |
| minSdk / targetSdk / compileSdk | 24 / 34 / 36 |
| 入口 | `app.qingchuan.MainActivity`，label「轻传」 |
| dex | 1 个 classes.dex（未触发 multidex） |
| 签名 | APK Signature Scheme **v2** 通过；证书 CN=Android Debug（侧载用，非商店签名） |
| R8 类名保留 | `mapping.txt` 证实 `QcApp` / `MainActivity` / `TransferService` 未被改名（`aapt_rules.txt` 生成 keep 规则） |
| 图标 | `mipmap/ic_launcher`、`ic_launcher_round`、`ic_launcher_bg` 各 5 个密度 + `anydpi-v26` 自适应 XML 均在包内；抽出的 `ic_launcher.png` 逐像素看过，是最终图形 |
| 权限 | QUERY_ALL_PACKAGES（列出已安装应用）、INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE / WAKE_LOCK / POST_NOTIFICATIONS / FOREGROUND_SERVICE(+DATA_SYNC) / WRITE_EXTERNAL_STORAGE(maxSdk 28) / READ_EXTERNAL_STORAGE(maxSdk 32) / MANAGE_EXTERNAL_STORAGE（可选） |

### 32 位结论（用户硬性要求）

`aapt2 dump badging` 输出：

```
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

包内 native 库只有 4 个 ABI 的 `libandroidx.graphics.path.so`（37 KB，Compose 的间接依赖），
**armeabi-v7a 在包内**，其余全部为 dex。因此：

- 32 位机型安装时取 `armeabi-v7a`，可正常运行；
- 未做 ABI split，单个通用 APK，不依赖任何自写 .so。

## 3. 没有验证到的部分（重要）

本机**没有任何可用的设备控制通道**：无线调试未开启、Shizuku 未就绪、无障碍未开启，
`pm install` / 截屏 / 点击都不可用。因此：

- ❌ 未做真机安装与冒烟测试（启动、崩溃、通知、权限弹窗都没有实机跑过）；
- ❌ 未做双机互传实测（协议只在 JVM 端到端里验证过）；
- ❌ UI 未截图验证，仅通过 Kotlin/Compose 编译期类型检查 + 代码审阅。

建议安装后按此清单自测（也是最可能暴露问题的顺序）：

1. 冷启动是否正常进入首页，状态是否变「在线」且显示本机地址；
2. 两台设备同一 Wi-Fi 下能否互相发现；发现不到时用「手动添加 IP」；
3. 传一张图片：接收方弹窗 → 接收 → 进度 → 文件出现在 `Download/轻传`；
4. 「文件」页缩略图是否正常，点开全屏：双指缩放、双击、左右翻页、分享、删除；
5. 设置 → 保存位置 → 切到根目录并授权，确认落盘到 `/storage/emulated/0/轻传`；
6. 设置 → 接收 → 关闭「后台接收」，再退回桌面，确认服务停止且不再占通知栏。

## 4. 图标说明（seedream 未成功）

- v1.1 之前的 8 次 `generate_image` 全部 `fetch failed`（网络侧 TLS 被重置）；v1.2 时网络恢复，**第 9 次调用一次成功**，
  出图 1 张（`art/seedream-icon.png`，1536×1536），按要求没有重试第二张；
- 现场诊断：系统 DNS 对 `api.apimart.ai` 返回被污染的地址；用 DoH（doh.pub）拿到真实
  Cloudflare IP 后，TCP 可连（0.19s）但 **TLS 握手被立即重置**；同网络下 `cloudflare.com`、
  `baidu.com`、`dl.google.com`、`repo1.maven.org` 全部正常 → 是该域名 SNI 被阻断，不是本机网络故障；
- v1.2 的图标来自这张 seedream 图：`tools/PrepareArt.java` 缩到 88% 留出遮罩安全区做图标底图，
  并裁成圆形吉祥物；`tools/IconMaker.java` 出 5 个密度 + 自适应图标背景层。
  已从 APK 里抽出 144px 的 `ic_launcher.png` 逐像素确认是新的猫娘。

网络恢复后换成 seedream 出图只需两步（**只花一次出图钱**）：

```bash
# 1) 把生成的 1024 方图放到 art/icon_src.png，重出全套 mipmap
JAVA=/data/user/0/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk
$JAVA/bin/java -Djava.awt.headless=true -cp tools/build IconMaker \
  --src art/icon_src.png --res app/src/main/res --preview tools/icon_preview.png
# 2) 重新打包
bash build.sh :app:assembleRelease
```

## 5. v1.2 变更（本次）

**界面再简化**：白底 + 分组列表 + 单一强调色，去掉渐变、阴影、装饰卡片与贴士卡；
首页只剩「我是谁 / 附近设备 / 最近文件」三块；底栏去掉 indicator 底色，只留颜色区分。

**网络可用性**（针对「能连到我、我连不到它」的现场问题）：
- 发现通道增加**定向广播 + 255.255.255.255 广播**兜底（部分路由器过滤组播但放行广播）；
- 手动连接只填 IP 即可，自动在 53318–53322 找端口，失败按「超时 / 拒绝 / 不可达」分类提示；
- 新增**网络诊断**页：服务状态、本机地址、网关、发现通道、收到广播条数与时间、设备数、结论与五步排查清单。

**图标**：换成 seedream 生成的一张猫娘图（第 9 次调用成功，未重复生成）。

单测仍为 28/28 通过；引擎改动集中在 Discovery（广播 + 统计 + 失败分类）与 Engine（手动连接），协议未变。


## 6. v1.3 变更（本次）

- **常用对象**：`ContactStore`（filesDir/contacts.json，原子写、去重、最多 50 条）+ `ContactsScreen`；
  手动连接成功后自动保存，首页「常用对象」一点即连。
- **发送已安装应用**：`AppPackages` 用 PackageManager 列出用户应用，普通应用发 base.apk，
  带 split 的打包成 `.apks` 再发；`AppPickerScreen` 支持搜索与多选。新增 `QUERY_ALL_PACKAGES` 权限。
- **修复**：① 应用内吉祥物一直是旧的手绘图（`drawable-nodpi/mascot.png` 没跟着换），现已换成 seedream 的圆形头像；
  ② 自适应图标改成「纯色背景 + 75% 前景」，不再被系统 1.5 倍裁切；③ 网络诊断页浮层缺不透明底色导致两页重叠，已修。
- 新增代码由两个子 Agent 并行完成（引擎侧 `AppPackages`/`Contacts` 与两个界面文件），主控负责接线与构建。
- 单测仍 28/28 通过（协议未变）。
