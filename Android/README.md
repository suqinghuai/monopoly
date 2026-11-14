# 安卓版（打包工程）

手机自己当服务器：装到手机上点一下，就开出一个局域网房间，同一 WiFi 下的设备用浏览器就能进同一局。

- 包名 `com.monopoly.lan`，版本 1.1.0（versionCode 2），minSdk 24 / targetSdk 30 / compileSdk 34
- 界面全部由代码构建（无 XML 布局），不依赖 androidx，只用系统 API
- 图标沿用 Windows 版 `assets\app_icon.png`，已生成 5 档 `mipmap-*\ic_launcher.png`

## 只有一种模式：局域网房间

| 做什么 | 别人怎么进 |
| --- | --- |
| 起内置 HTTP 服务（默认 9000 端口，占用就退 9001–9004），并在局域网广播房间（UDP 9009），同一 WiFi 的手机点「扫描局域网」就能看到并加入 | 同一 WiFi，浏览器开 `http://手机IP:端口/` |

点「应用内打开」直接用内置 WebView 进这一局，不用跳到外部浏览器。

> 远程 / 公网 / 隧道这条路已经从安卓端整体移除：实测在移动流量下运营商拦入站 IPv6，
> 免费反向隧道又拿不到分配到的地址（详见 git 历史与当时的实测记录）。想远程玩，用电脑版
> `dist\大富翁.exe` 选「2 互联网模式」，它自带 serveo 固定地址（必要时退 Cloudflare）。

> 本目录只负责构建：页面不做第二份，`build.gradle.kts` 的 `syncWeb` 任务每次构建前
> 把根目录 `www\index.html` 同步进 `app\src\main\assets\www\`；改界面只改根目录那一份。
> 该页面在手机内置服务上会自动退回 SSE（Android 的 `HttpServer` 只实现了 `/api/events`）。

### 服务端实现要点

- `HttpServer` 直接监听 `0.0.0.0`，只服务同一网段。
- `NetInfo.ipAddress()` 只认 Wi-Fi / 以太网 IPv4，跳过 `rmnet` / `ccmni` 这类流量网卡和 P2P 接口。

## 目录

```
Android\
  build.gradle.kts        AGP 8.5.2 + Kotlin 1.9.24（apply false）
  settings.gradle.kts     pluginManagement + include(":app")
  gradle.properties       android.overridePathCheck=true（项目路径含中文要跳过检查）
  local.properties        sdk.dir 指向本机 android-toolchain\sdk
  build.ps1               一键打包：设 JDK/SDK、跑 Gradle、复制 APK 到 release\
  app\
    build.gradle.kts      namespace/applicationId com.monopoly.lan，versionName 1.1.0
                          注册 syncWeb 任务：构建前把 ..\www\index.html 拷进 assets\www
    src\main\AndroidManifest.xml
    src\main\res\values\strings.xml
    src\main\res\mipmap-*\ic_launcher.png
    src\main\assets\www\index.html       （构建时自动同步，改页面请改根目录 www\index.html）
    src\main\java\com\monopoly\lan\      12 个 .kt，见下表
  salvage\                最初那套代码的备份副本（JVM 冒烟工程里的同款），只作参考
  release\大富翁v1.1.0.apk  打包产物
```

## 源码分工

| 文件 | 说明 |
| --- | --- |
| `Game.kt` | 全部对局逻辑（买地 / 升级 / 租金 / 交换 / 机会卡 / 存档），与桌面版同一套规则；含 `chat()` |
| `Rules.kt` | 棋盘 40 格、色组、机会卡、角色定义 |
| `Bots.kt` | 电脑玩家的决策 |
| `Json.kt` | 轻量 JSON 工具 |
| `HttpServer.kt` | 自写 HTTP/1.1 服务（路由 + SSE），`Response` 提供 json/error/html/text/beginSse |
| `Store.kt` | `SQLiteOpenHelper` 存档，表结构对齐桌面版 `monopoly.db`；另有 `ai_fail_log` |
| `AiBrain.kt` | OpenAI 兼容 `/chat/completions` 调用（`HttpURLConnection`），配置存 SharedPreferences |
| `ServerHub.kt` | 把 Game 挂上全部接口：`/`（assets 页面）、`/api/{lobby,rules,seats,saves,state,events,ai_config,watch,join,claim,load,action,chat,leave}`；端口 9000 起，占用则退 9001–9004，再不行交系统分配 |
| `NetInfo.kt` | 取本机 Wi-Fi IPv4；UDP 广播房间信息到 `255.255.255.255:9009`（每 2.5 s），`scan()` 收 4 s |
| `ServerService.kt` | 前台服务，只跑局域网房间，常驻通知带地址与「停止服务」 |
| `MainActivity.kt` | 主界面：一个「创建局域网房间」按钮、状态卡、地址栏（应用内打开 / 浏览器打开 / 复制 / 停止）、加入别人的房间（手输地址 + 扫描局域网） |
| `WebClientActivity.kt` | 全屏 WebView，启用 JS / domStorage / wideViewPort，返回键先 `goBack()` |

## 打包

```powershell
powershell -ExecutionPolicy Bypass -File Android\build.ps1            # debug（默认签名，可直接装）
powershell -ExecutionPolicy Bypass -File Android\build.ps1 -Release   # release 未签名
```

脚本做的事：自动在 `C:\Users\43061\android-toolchain\jdk` 下找带 `bin\java.exe` 的子目录当 `JAVA_HOME`
（注意 JDK 真身在 `jdk\jdk-17.0.21+9`，指到 `jdk` 本身会报 invalid directory），设 `ANDROID_HOME`，
跑 `gradle assembleDebug --offline --no-daemon`，再从 `app\build.gradle.kts` 读出 versionName
复制成 `release\大富翁v<版本>.apk`。

手动构建时同理，必须显式设：

```powershell
$env:JAVA_HOME="C:\Users\43061\android-toolchain\jdk\jdk-17.0.21+9"
$env:ANDROID_HOME="C:\Users\43061\android-toolchain\sdk"
& "C:\Users\43061\android-toolchain\gradle\gradle-8.7\bin\gradle.bat" -p Android assembleDebug --offline --no-daemon
```

## 说明 / 已知限制

- 用的是 `--offline`，因为本机 Gradle 缓存里只有 AGP 8.5.2 + Kotlin 1.9.24，androidx 一个都没有；
  因此工程里不能引入任何 androidx 依赖，界面全部手写。
- 项目路径含中文，靠 `gradle.properties` 里的 `android.overridePathCheck=true` 放行（无 NDK，安全）。
- 服务是长连接 SSE（`/api/events`），手机锁屏 / 省电策略可能掐断，所以服务跑在前台 Service 里，通知栏常驻。
- 聊天走 `POST /api/chat`，消息进对局记录，页面上就是日志框底部那条输入栏（与桌面版共用同一份页面）。
- 装到手机上后，系统可能提示「电池优化」；想稳定挂着，建议在系统设置里给本应用关掉省电限制。
- 真机上的 AI 座位（`AiBrain`）没有实测过；接口不通时会自动退回人机，并把失败写进 `ai_fail_log`。

### 局域网里连不上时

- 先确认**真的在同一个 WiFi**，且手机连的不是「访客网络」——很多路由器开了 AP 隔离，同一 WiFi 也不互通。
- 界面上的「同网段地址」为空，说明没取到 Wi-Fi IPv4：检查 WiFi 是否连着、是否被 VPN 接管。
- 别人扫不到房间，但地址能打开：UDP 广播常被路由器拦，让他直接手输上面那个地址就行。
- 手机热点也能开局：用一台手机开热点，另一台连上后手输开热点那台的地址。
