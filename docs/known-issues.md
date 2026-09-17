# 已知待修 / 行为记录

> **历史记录：起笔于 0.1.2（versionCode 29）。** 该版把项目从 `dsh-mobile` 更名为
> `dsh-handheld`，并把此前 1.10.0 / 1.11.0 / 1.11.1 的开发迭代号统一归到 0.1.x 公开版本线。
> 背景：1.10.0 把 SSH 隧道所有权上移到 `DshApp`，1.11.0 删除了后台通知与其前台服务，
> 0.1.2 删除了因此变成死代码的 `DshClient` / `Models` / `Rpc`（850 行）与 okhttp3 依赖。
>
> **版本以 `android/app/build.gradle.kts` 与 Releases 为准，本文不抄第二份**（原先这里写死
> 「当前版本是 0.1.8 / versionCode 35」，到 0.1.12 时已经过期四版）。除 §四的行数与计数已按
> 当时那棵树实测外，下面各节都是**写那一刻**的记录：0.1.5 修隧道重建路径的一组真机缺陷
> （§二、§三）；0.1.6 补机内取证入口（§四）；0.1.8 把前台服务加回来做隧道保活（§三）；
> §七 起是 0.1.10 之后的记录。

---

## 真机验证记录（2026-09-12，SM-G7810 / Android 13）

设备 `192.168.0.186`，release 包 0.1.4，**经 SSH 隧道**（服务端视角为回环）。
逐项截图取证，**无 FATAL、无 ANR**。

### Web 模式

| 环节 | 结果 |
|---|---|
| 连接屏 → 隧道 | `tunnel state: connecting → connected` |
| token 自动获取 | `autoFetchToken: cmd#0 rc=len=43 → SUCCESS` |
| 页面加载 | `onPageFinished: 正式页面加载完成 → ack=true` |
| 数据面 | 工作区、会话列表、Agent 模式列表均从服务端载入 |
| **配置平面** | **设置页可用**（权限/语言/外观/字号/对话显示） |
| **凭据平面** | **模型页可用**（提供方列表、编辑/删除、添加提供方） |
| 移动端适配 | 抽屉式侧栏（非常驻）、底部弹出选择器、无横向溢出 |

中间两项是关键：它们属于 `PRIVILEGED_METHODS`，**LAN 直连必然 403**，只有经 SSH 端口
转发才通（`docs/archive/dsh-protocol.md` §2.5）—— 这是「为什么必须走 SSH 隧道」的实证。
移动端适配的 DOM 层另有独立取证（`docs/mobile-ui-verification.md`）。

### 终端模式

| 环节 | 结果 |
|---|---|
| SSH 连接 + 提示符 | `xsj@rasp:~$`，**彩色**（`TERM=xterm-256color` 生效） |
| 窗口尺寸 | `stty size` → **21 × 40**，与屏幕一致 |
| pty 契约 | `stty -a` 实测 **`iutf8` 已开、`-ixon -ixoff` 已关** |
| **Ctrl+S 不冻结输出** | 按下后输出**继续渲染**（行为验证，非仅看设置） |
| 键排派发 | `—` 输入字面 `-`；CTRL → `c` 得 `^C`；↑ 召回上条命令 |
| 修饰键自动复位 | CTRL 用过即失效（随后 `-` 是字面量而非 Ctrl+-） |
| KEYBOARD 跨行 | uiautomator 实测该键框 `[945,1224]–[1080,1374]`，**纵跨两行**（`rowSpan: 2` 生效） |
| 软键盘 | 进入终端自动弹出 |

**一处观感小瑕疵（非功能问题，未修）**：修饰键用过后，按钮的**高亮背景**偶尔不跟着复位
（曾见 ALT 持续高亮）。已实测确认**功能上没有锁住** —— 之后输入 `xyz` 得到的是字面 `xyz`，
不是修饰序列。属上游 Termux `ExtraKeysView` 的按钮背景未在自动复位后重新同步。

### 仍未覆盖

- **换端口后的页面跟随**：0.1.5 让端口不再漂移（重建前先回收旧 owner 再选端口），
  但「WebView 在 `onLocalBaseChanged` 之后真的重载并恢复视图状态」仍未上机确认
- 默认终端尺寸计算：实测 21×40 是**软键盘弹出后**的结果，键盘收起时未复测
- 应用内 JS 对话框（若有）、长按连发、复制/粘贴
- WebView context 释放在多窗口 / 字号变更下的行为
- **熄屏保活**：0.1.8 起由 `TunnelService`（前台服务）保活，但**真机上还没验过**
  「后台放几分钟后隧道是否仍活着、断线是否自愈」——见 §三

---

## 一、App 重新打开时的判定逻辑（现状）

`MainActivity.onCreate`（实测 `178-337` 行，三分支判定块 `312-336`）三分支，状态源只有两个 SharedPreferences 键：

```
savedUrl   = prefs["url"]           ← 最后一次 connectWeb 写下的 http://127.0.0.1:<port>
currentUrl = retainedWebView?.url   ← 进程内保活 WebView 的当前地址
baseMatch  = currentUrl 以 savedUrl 开头
```

| 条件 | 行为 |
|---|---|
| `currentUrl` 已是 http 页且匹配 `savedUrl` | 连接屏 `GONE`，直接回网页：不重连、不重载、不换 token |
| 有 `savedUrl`、不匹配、且 `ssh_json` 完整 | `autoConnectSsh()`：重建隧道 + 重新捞 token + 带 `?token=` 加载 |
| 其余（无 `url` / 无 ssh 配置） | 停在连接屏 |

- **进程活着**（退后台未被杀）：复用 `DshApp.retainedWebView` 与 `DshApp.sshTunnel`，零成本。
- **进程被杀**（1.11.0 起无前台服务，这是常态）：完整冷启动重连（1-3s 拨号 + 页面重载）。
  端口优先 3080、被占时退到 13080（`SshTunnel.PORT_CANDIDATES`）；只要这次拿到的端口与
  上次相同，origin 就不变，localStorage/IndexedDB 状态不丢。
- **BACK 语义**：网页有历史先退历史；无历史回连接屏 Step 2 且**隧道保持**；连接屏再 BACK
  = `moveTaskToBack`，隧道**仍然保持**。隧道只由「断开连接」或进程结束关闭。

---

## 二、待修：`断开连接` 后重开可能白屏死路

> **状态：已修（0.1.5）。** `MainActivity.onCreate` 现在判的是
> `} else if (currentUrl?.startsWith("http") == true) {`，并在原处留了注释说明
> `about:blank` 为什么不能再命中这一支。下面保留原始分析作为记录。

**症状**：`断开连接` 后若 Activity 被系统销毁而**进程仍存活**（"不保留活动"、后台回收
Activity 而保留进程等），重新打开 App 是一个空白 WebView，既没有连接屏入口，按 BACK 也
只会 `moveTaskToBack`。

**根因**：`断开连接` 删掉 `prefs["url"]` 并把 WebView 载向 `about:blank`。重开时

- `savedUrl == null` → 第二个分支（自动重连）跳过；
- `currentUrl == "about:blank"` **非空** → `else if (!currentUrl.isNullOrBlank())` 命中 →
  `connectView.visibility = GONE`。

**修法**（一行，落在 `MainActivity.kt:331`）：

```kotlin
// MainActivity.onCreate
} else if (!currentUrl.isNullOrBlank()) {          // ← 对 about:blank 也成立
} else if (currentUrl?.startsWith("http") == true) { // ← 收紧为正式页面
```

**影响面**：窄，但一旦命中就是死路（无 UI 出口），修复零风险。等 1.11.0 上机验证后一起改。
（0.1.5 已按此修法落地。）

---

## 三、后台掉线 / 熄屏冻结 / 同端口重建时不自动重载

### 现象

`SshTunnel` 的看门狗线程（`ssh-tunnel-watchdog`）重建 dbclient 时，若候选端口 3080 仍空闲
就会重新绑回 3080 → `localBaseUrl` 未变 → **不触发** `onLocalBaseChanged` → `MainActivity`
不重载页面。回前台看到的是掉线前的旧页面（origin 未变，localStorage 不受影响）。

- 页面自身若重连（前端 SSE/WS 重试）则无感；
- 否则需要用户手动刷新。

### 更根本的一层：熄屏后整个进程被冻结（2026-09-12 实测）

**这个 App 没有前台服务**（1.11.0 起刻意删掉了后台通知与其前台服务），所以熄屏后它就是一个
cached 进程，会被 Android 冻结：

```
mWakefulness=Dozing
/proc/<pid>/cgroup → 7:freezer:/frozen
```

关键的一条：**dbclient 是它的子进程，继承同一个 frozen cgroup，一起被冻在 `connect()` 里。**
解冻瞬间三个残留进程同时报 `Connect failed: Software caused connection abort` —— 那不是
网络问题（同一时刻 `nc` 能拿到 sshd 的 banner），是它们根本没跑起来过。

后果：冻结期间看门狗不运行 → 隧道必死；而当时的 `isAlive()` 只问"进程活着 + 端口能连"
（残留进程正好能满足它），于是**假 connected 会一直持续**（实测 1h33m 零日志），回到前台
看到的是一个永远加载不完的页面。

### 0.1.5 的处理

- `MainActivity.onResume` → `revalidateTunnel()`：用**真流量探针**（往隧道里发一个 HTTP
  请求）校验，不健康就 `ensureTunnel(force = true)` 重建。
  **0.1.9 起同 origin 重建不再重载页面**（见下面「整页重载的流量代价」）；origin 变了才重走
  token 交换 + 重载。
- `SshTunnel.isHealthy()` 同样换成真探针（原来只看端口能连）。
- **前台服务** ✅ **0.1.8 加回来了**（`TunnelService`，`specialUse` 类型）：进程不再进
  cached 队列，既不冻结、oom_adj 也低得多，后台断线因此能自愈。刻意**不申请**
  `POST_NOTIFICATIONS`，所以 Android 13+ 上不显示常驻通知（Android 12 及以下会显示一条
  最低重要性的静默通知）。隧道建立时启动、`closeTunnel()` 时停止。
  注：1.11.0 删掉的那个前台服务宿主是 `AgentMonitorService`（dropbox 里 36 条崩溃全是它），
  与隧道保活不是一回事。

### 「添加附件」点了没反应：WebView 的 onShowFileChooser 从来没实现（2026-09-14）

真机复现：点输入区的 `+`（或回形针）只把输入框聚焦、弹出软键盘，**不弹任何选择器**。

原因不是 dsh：它的两个入口都是 `fileInputRef.current.click()`，点的是页面里那个
`<input type=file multiple accept=…>`。而 Android WebView 只能通过
`WebChromeClient.onShowFileChooser` 把这类请求交给宿主 —— `MainActivity` 的
WebChromeClient 此前**只实现了 `onProgressChanged`**，默认实现返回 false，
于是既不报错也不弹选择器，用户看到的就是「按钮坏了」。

修法：实现 `onShowFileChooser`，把 dsh 写在 input 上的 `accept` 与多选原样透传给
`ACTION_OPEN_DOCUMENT`，选完在 `onActivityResult` 里用
`WebChromeClient.FileChooserParams.parseResult` 回填。两条纪律：**上一次没回话的回调要
先作废、取消也要回 null** —— 否则页面那个 input 会永远卡在等待选择，之后再也弹不出来。

App 自己的「导入私钥」是另一条路径（`pickSshKey()` + `ACTION_OPEN_DOCUMENT` + REQ_PICK_KEY），
两者用不同的 requestCode 分开。

### 手动「断开连接 → 重新连接」也不再重载（2026-09-14）

用户报的「断开重连之后还是会重新加载网页」：`disconnectCurrent()` 原本会
`loadUrl("about:blank")` 把页面丢掉，重连时 `connectViaSsh` 无条件 `connectWeb(base)`
→ 整页重载（实测日志：`disconnectCurrent: … 载入 about:blank` → `connectWeb: url=…` →
`onPageFinished: 正式页面加载完成`）。

改法（与上面 0.1.9「同 origin 重建不重载」同一条思路）：

1. `disconnectCurrent()` **不再清成 about:blank**，只 `stopLoading()` —— 页面留着，连接屏盖在上面；
2. `connectViaSsh()` 里如果隧道仍落在**同一个 origin** 且 WebView 上那页还在同 origin，
   就只切回网页、不重载；同时补齐 `lastUrl` / `prefs[url]` 两处簿记（401 恢复与冷启动自动重连要用）；
3. 顺手推一下页面的重连：`dsh-client-connection` 监听 `online/offline` 并据此调
   `controller.setNetworkAvailable()`；隧道掉线期间 `navigator.onLine` 一直是 true，
   所以只发 `online` 是空操作 —— 必须先 `offline` 再 `online` 造出状态跃迁。

端口漂了（origin 变了）仍然照旧重载：服务端 cookie 名含 authority，必然失效，得重走 token 交换。

### 整页重载的流量代价（0.1.9 实测）

用户反馈「每次都要重新加载网页浪费太多流量」，于是量了一次冷加载（临时 dsh 实例，不动在用的那个）：

| 资源 | 传输 | 缓存指令 |
|---|---|---|
| `/assets/index-*.js` | 214 KB | **无 `Cache-Control` / 无 `ETag` / 无 `Last-Modified`** |
| `/assets/vendor-*.js` | 210 KB | 同上 |
| `/assets/index-*.css` + `vendor-*.css` | 21 KB | 同上 |
| `/plugins/??…`（51 个模块合并包） | **4.33 MB** | ✅ `public, max-age=31536000, immutable` |
| **合计** | **≈4.68 MB** | |

两个事实：

1. 4.33 MB 那个包是**可缓存**的（`immutable`）→ 重载能命中 WebView 缓存；
2. 但 **~446 KB 的 JS/CSS 没有任何验证器** —— 文件名明明带内容哈希（`index-BKQ_L1z6.js`），
   本该永久缓存，却按 HTTP 语义**每次重载都要重新下载**。这是 dsh 静态资源那侧的缺口：
   `/plugins/` 设了头，`/assets/` 没设。

而 0.1.5 曾把它变得更糟：`rebuildTunnel` 里显式 `connectWeb(base)`，于是**每次隧道重建都
整页重载**（端口没变也一样），还顺带清 cookie、重走 token 交换；重载后 SPA 还要把会话历史
重新拉一遍 —— 对长会话那才是真正的大头。

0.1.9 改法：`rebuildTunnel` 比较重建前后的 origin，**相同就不重载**（cookie 仍有效、缓存仍
命中，页面自己的重试会把后续请求接到新隧道上），只在 origin 变化时重载。
代价：同 origin 重建后页面上那条已断的流不会自己恢复，需要手动刷新
（连接屏的「回到网页」就是一次重载）。

**仍未做**：那 446 KB 只能靠 dsh 侧补缓存头，或在 App 里自建 `/assets/*` 磁盘缓存
（`shouldInterceptRequest` 拦下自己发，绕过 WebView 缓存）。属于独立改动。

---

## 四、日志体系：关键路径 + 机内取证入口（0.1.6 补齐）

### 补日志之前的实况（1.11.1 之前）

全项目只有 **28 处** `Log.*`，`Log.d`/`Log.v` 各 0 处，分布在 4 个 TAG
（`DshHandheld` / `DshApp` / `SshTunnel` / `TuiActivity`）。按文件看问题很集中
（行数用 `wc -l`，取自 `17e3e03^`；当时包名还是 `com.dshmobile`）：

| 文件 | 行数 | 补前 Log |
|---|---|---|
| `SshTunnel.kt` | 303 | 10 |
| `TuiActivity.kt` | 409 | 9 |
| `DshApp.kt` | 157 | 5 |
| `MainActivity.kt` | 1375 | **4** |

规律：**有日志的 `SshTunnel` 是唯一被成功定位过的那一层**（双 dbclient 抢 3080 就是靠它
一条 warn 直接定性的）；而 431、白屏、回退后停 Step3 这些只能靠 CDP 手工挖的问题，
全都落在当时零日志的 `MainActivity` 里。

### 现状（0.1.4 实测）

| 文件 | 行数 | `Log.*` |
|---|---|---|
| `SshTunnel.kt` | 303 | 10 |
| `TuiActivity.kt` | 412 | 9 |
| `DshApp.kt` | 157 | 5 |
| `MainActivity.kt` | 1432 | 36 |
| `SecurePrefs.kt` | 157 | 6 |
| `DshTerminalExtraKeys.kt` | 29 | 0 |
| **合计** | **2490** | **66** |

`Log.d` / `Log.v` 仍各 0 处，TAG 仍是那 4 个（`SecurePrefs` 复用 `DshHandheld`）。
旧表写的「`MainActivity` 补后 **29**」在 git 里复现不出来：那次补日志的提交 `17e3e03`
里该文件已经是 **1436 行 / 36 处**——29 在写文档当天就已经过期。

### 1.11.1 补了什么（commit `2609b445`，远端）

**只加日志与注释，行为零变化**：`onCreate` 三分支判定、`onPageFinished` 的 ack 迁移、
`onReceivedError`/`onReceivedHttpError` 的原始 code/desc/reason、`connectWeb` 的
`needsToken`/`tokenLen`/是否清 cookie jar、`connectViaSsh`+`autoConnectSsh` 的配置与
`ensureTunnel` 结果、`handleUnauthorized` 的两段回退、五个生命周期回调与 `onBackPressed`
四个分支、`disconnectCurrent`/`showConnectScreen`/`refreshConnectState` 的按钮可见性。

**安全**：去掉 `autoFetchToken` 的 `token.take(8)` 令牌前缀泄漏；`TuiActivity` 的
`env` 改为只记键名（原 `it.take(8)` 恰好只截到 `DROPBEAR` 这个键名而侥幸没漏密码）。

### 仍未做（当时评估为可延后；已完成的单列标注）

- **应用内日志查看器** ✅ **已完成（0.1.6）**：`DiagLog`（内存环形缓冲 600 条 + 后台线程
  追加到 `filesDir/diag.log`，超 256 KB 时旋转一代）+ 连接屏头部的「诊断」入口。
  页面显示：**上次退出原因**（读系统落盘的 `ApplicationExitInfo`，所以「上次是被低内存杀的
  还是崩的」不用猜）+ 本次运行日志 + 磁盘日志尾部，可一键复制。
  为什么必须自己记而不能去读 logcat —— 见下一节。
- **分级与开关**：仍无 `Log.d/v`，想临时加详细日志 = 改代码 + CI + 安装（十几分钟），
  所以实际上没人会为一次排查去做。
- **`BuildConfig.DEBUG` 守卫**：release 里日志照留。对本项目**有意保留**——用户只装
  debug 包，release 日志反而是资产。
- **清理死代码** ✅ **已完成（0.1.2，commit `b8199cc`）**：`protocol/DshClient.kt`(505) +
  `Models.kt`(237) + `Rpc.kt`(108) 与 `okhttp3` 依赖一并删除，共 850 行。当前 `protocol/`
  下只剩 `SshTunnel.kt`，`grep -r okhttp android/` 零命中。

### 手机上到底「保存」了什么日志（0.1.6 实测）

这是最容易误判的一件事，所以单列。三类完全不同的东西：

| 类型 | 存在哪 | 重启后 | 应用自己能读吗 |
|---|---|---|---|
| **logcat**（`Log.*` 的输出） | 内存环形缓冲（本机实测 `main` 只有 **5 MiB**） | ❌ 丢 | ❌ **读不到** —— `READ_LOGS` 是 `signature\|privileged` |
| **dropbox**（崩溃/ANR/tombstone 事件） | `/data/system/dropbox`，实测 236 条 / 上限 1000 | ✅ 在 | ❌ 要 `DUMP` 权限，只有 adb shell 能看 |
| **ApplicationExitInfo**（进程退出历史） | 由 ActivityManager 落盘（实测 18:58 刚写过一次） | ✅ 在 | ✅ **可以**（API 30+，读自己那个包） |

推论：

- 「应用内看日志」**只能靠自己记**（读不到 logcat）——这就是 `DiagLog` 的做法；
- 「上次为什么没了」不用自己记，系统已经落盘了，`DshApp.reportLastExit()` 直接读；
- **adb 的 logcat 也不能全信**：本机实测三星的 `View.setRequestedFrameRate` 在 WebView
  持续重绘时以 **662 条/10 秒**（约 1.1 MB/分钟）刷屏，5 MiB 撑不到 5 分钟 ——
  我们的行会被冲得一条不剩（0.1.5 那次就是这样，一度误以为「没打日志」）。

**所以用 adb 排查前先压掉那个刷屏**（非持久属性，重启自动失效；撤销把 `W` 换回 `I`）：

```sh
adb shell setprop log.tag.View W     # 实测 662 条/10s → 1 条/10s
```

一条真实收获：`dropbox` 里 **36 条 `data_app_crash` 全部**是旧包名 `com.dshmobile.app`
（v1.5.1 → v1.9.2），栈全是 `AgentMonitorService.startMonitor → DshClient.start →
DshClient.handshakeLoop`，另有一条 `ForegroundServiceDidNotStartInTimeException`；
而改名后的 `com.dshhandheld.app` **一条都没有**。这种跨重启的历史，logcat 里留不住。

### 排查备忘：验证 APK 里的日志字符串

app 自身的类在 **`classes6.dex`**（不是 `classes.dex`，那里是 Kotlin/AndroidX）：

```bash
unzip -p app-debug.apk classes6.dex | grep -a -o "onCreate: savedUrl" | wc -l
# 1 = 含 1.11.1 的日志；v1110 为 0，且 v1110 的 prefix= 命中 1、v1111 命中 0
# （证明 token 前缀泄漏确实被移除）
```

因此该包即使 `versionCode` 与 1.11.0 同为 28，也能靠 `onCreate: savedUrl` 是否存在自我标识。

---

## 五、全 UI 走查（2026-09-13，SM-S9280 / Android 16，release 0.1.9）

方法：`uiautomator dump` 取无障碍树拿到每个元素的精确 `bounds`，**把 bounds 画回截图核对
1:1 对齐**（先验证过坐标空间），再用 `input tap` 驱动；每一步都有截图 + 无障碍树双证据。
不依赖 debuggable。

### 正常

抽屉（按钮开 / 遮罩关 / 收起）、新会话、会话切换、会话搜索（过滤 + 提示 + 清除）、视图选项
（分组 / 排序，实时生效）、设置页（通用设置 / 模型 / 插件，含可展开表单）、`⋯` 菜单、
**文件预览**（会话里的文件链接点开 → 右侧栏文档预览浮层，全屏态带「退出全屏」）、
原生连接屏 + 诊断面板、BACK 从网页回连接屏。

### 修掉的问题

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 点后台任务胶囊，箭头翻转但**菜单在屏幕外** | 上游插件把胶囊 `_root` 降级为 `position: static`，弹层的包含块上溯到整屏高的 frame，`top: calc(100% + 5px)` 落到屏外 | 自研层直接不降级 `_root`，弹层改 `right: 0` 展开 |
| 2 | **导出会话日志提示成功，文件不落地** | `MainActivity` 从未 `setDownloadListener`；Android WebView 对没有 listener 的下载**静默丢弃** | 新增 `enqueueDownload()`：转交 DownloadManager，显式带 Cookie（隧道后面是 cookie 认证），API 29+ 落公共 Downloads |
| 3 | **文件浏览是死按钮**（头部 + 抽屉两处） | 它只给 frame 打 `data-aionui-explorer-open`，靠第三方 dsh-web-ui/aionui 套件的 explorer 列变浮层；该套件**不是 dsh 自带**，本机 grep `data-aionui-explorer-col` 等 4 个标记全部 0 命中 适配层不再提供这个入口（自研层不含 explorer 集成） |
| 4 | **添加工作区**：手机按下无反应，对话框开在电脑上 | web bundle 挂的是 `directory-picker-auto`，boot 采样判定为 native（回环绑定 + 非 SSH 启动 + 有 DISPLAY/WAYLAND + zenity 在 PATH）→ 在**主机桌面**弹 GTK 对话框 适配层在手机上直接隐藏这个入口（做不到就不留）。宿主侧钉 `-browse` 也能让它可用，但那要改服务端 composition，超出本项目边界 |

第 4 条的取证最直接：手机按下后主机上出现了
`zenity --file-selection --directory --title=Select Workspace Directory` 进程；杀掉它，
手机才弹「无法打开文件夹 / directory picker failed: Command failed: zenity …」。

**关于第 4 条的一个来回，记在这里免得重复走**：2026-09-13 当天先在主机侧
`~/.dsh/profiles/web/cordis.patch.yml` 里把交互钉成了 `-browse`（禁用 `directory-picker`、
改挂 `dsh-host-directory-picker-browse` + `dsh-client-ui-directory-picker-browse`），并真机确认
「手机弹出应用内目录对话框」。随后按用户要求**回滚**：不在主机端动 composition。回滚后磁盘
配置与改动前逐字节一致（md5 `0e07e4a4…`）。注意 live patch 的**「加」生效、「撤」不生效** ——
已挂上的两个 Loader 条目要到 `dsh-web` 下次重启才掉（进程内模块表仍列着
`dsh-client-ui-directory-picker-browse`）。


### 顺手清掉的第三个「宿主侧入口」

会话头右上角那枚「在 文件管理器 中打开工作目录」（`dsh-client-ui-open-in-app` 的分屏按钮 +
它的下拉）也是**在电脑上**动作：宿主探测本机装了哪些编辑器/Git GUI/终端/文件管理器
（本机 `GET /open-in-app/apps` 返回 `{"apps":["filemanager"]}`），点击调 `/open-in-app/open`，
效果是电脑桌面上弹出文件管理器窗口 —— 手机上按下去什么都不发生。同一条原则：去掉
（适配层里按类名 `header [class*="_split"]` 隐藏；本 dsh 语料里另两处 `_split` 都不在会话头）。

### 又去掉一个：会话头那枚「⋯」（= 下载 Session 日志）

它由 `dsh-session-log-export` 注册进 `conversation.session.header.utilities` 槽，
内容是「省略号图标 + 只有一个菜单项的菜单」（`menu.download` = 下载 Session 日志）。
这枚按钮存在的唯一目的就是它，所以整枚去掉 —— 手机上不再提供会话日志下载入口。
判据：该插件本版唯一的类名是 `<hash>_moreButton`，全 dsh 安装里只有它一个模块定义这个名字。

（抽屉底部那个「导出会话日志」是旧 vendored 插件加的，已随插件删除；`/export` 斜杠命令仍在，
那属于宿主自己的能力，不在界面入口之列。）

结论：**手机端「添加工作区」与「文件浏览」都按「做不到就不留」处理**，
不再依赖任何主机侧改动；2026-09-13 适配层自研后，这两条是自研层的原生行为，
不再是「打在别人代码上的补丁」。

### 设置页「关不掉」：抽屉的「点一下收起来」把模态一起冻住了（2026-09-14，插件 rev 1.0.12）

现象：设置页打开后，点右上角 ✕ 没有反应；点左边四个分区也不切换；**点页面里任何地方
都像隔着玻璃**。用户的原话就是「设置页面无法关闭」。

定位三步（都在 SM-S9280 / `192.168.0.175:46255`，release 包上做，不依赖 debuggable）：

1. 无障碍树里 ✕ 有正常 bounds（`[1248,420][1361,525]`，clickable=true），坐标没问题；
2. 点 ✕ 不关，点「模型」分区也不切 —— 但**点设置页背后的输入框，软键盘起来了**：
   说明这一笔不是被谁吃掉，而是**穿透**到了背后的页面；
3. 于是查「谁能把整棵子树的命中测试关掉」：自研层里只有一条
   `[data-handheld="frame"][data-sidebar-collapsed] > [class*="_sidebarCol"] { pointer-events: none }`
   —— 抽屉收起时让出指针。而设置对话框**恰好住在侧栏里**（`SettingsRoot` 注册进
   `sidebar.settings` 槽），它虽然 `position: fixed` 盖满整屏，却照样继承这条 `none`。

那么抽屉为什么会在设置页开着的时候收起？它在侧栏里装了一个「捕获阶段的点击」启发式：
抽屉里点到非表单元素（会话行、工作区行那种）＝「选完了」→ 收抽屉。设置对话框的空白处、
某行说明文字、`_options` 容器都不是 button，点一下就命中这条启发式 → 收抽屉 →
`pointer-events: none` 生效 → 对话框当场变成一张点不动的画。

一条链上有两个独立的缺陷，所以两边都堵（只堵一个，另一个还会以别的形式冒出来）：

| 层 | 改法 |
|---|---|
| 触发端（插件 JS） | 抽屉里挂着模态时，那条启发式**整个让位**：`col.querySelector('[role="dialog"][aria-modal="true"]')` 非空就直接 return。另外菜单 / 下拉（`[role=menu]` / `[role=listbox]`）里的点击本来也不该算「选完了」 |
| 兜底（插件 CSS） | 抽屉里只要挂着模态，整列照旧吃指针：`…:has([role="dialog"][aria-modal="true"]) > [class*="_sidebarCol"] { pointer-events: auto }`。`pointer-events` 是继承属性，遮罩与面板自动跟随 |

**判据为什么必须放在祖先范围上**（这是 1.0.11 装到真机上才补的一课）：第一版把判据写成
`target.closest('[role=dialog]')`，只挡住了「对话框**内部**的点击」。而设置对话框的遮罩是
面板的**兄弟**（`_overlay > _mask + _panel`）：点遮罩时 `closest()` 从遮罩往上找不到
`[role=dialog]`，于是「点遮罩 = 关对话框」这一笔照样把抽屉收了。真机上表现为
「对话框关了，但抽屉也没了」—— 不致命，却说明判据是错的：**遮罩点击的本意是关对话框，
不该顺手改变抽屉状态**。真正危险的是另一半：万一某个模态不因遮罩点击而关闭，
这一笔就会把它变成下一张「点不动的画」。改成按祖先范围判定后，模态开着时整条启发式停用。

顺带把「手机上怎么关掉这一屏」补齐，BACK 变成三级阶梯（`MainActivity.handleWebBack()`）：
**模态 → 抽屉 → 原语义**。① 有 `[role=dialog][aria-modal=true]` 时派发一次 `keydown Escape`
（dsh 的模态就是在 document 上监听 Escape 的），240ms 后复查 —— 复查是必须的：有的模态
不监听 Escape，不能把 BACK 变成空操作；② 模态没了但抽屉开着，就点我们自己的遮罩收起它
（开合的真相始终在页面侧那一份状态里）；③ 都没有才走原来的语义，一个字没改。

同一轮还把设置面板的留白从每边 8px 改成 16px：8px 在 384px 视口上只有 30 设备像素
≈ 2.1mm，**「点外面关掉」这条路径实际是点不到的**（复验时我得精确点到 x=4 CSS px）。
16px = 60 设备像素 ≈ 4.2mm，够一根手指；代价只是面板窄了 16px（352 而非 368）。
✕ 与 BACK 仍是主要关闭路径，点外面是"顺手也能关"。

复验（同一台机，release 包，每步 `uiautomator dump` + `logcat -s DshHandheld` 双证据）：

| 动作 | 1.0.11 | 1.0.12 |
|---|---|---|
| 开设置 → 点对话框里一行说明文字（非按钮）→ 再点「模型」分区 | 分区**切换成功** ✓（修复前：抽屉被收、对话框冻住，这一下必然无效） | 同左 ✓ |
| 接着点 ✕ | 对话框关闭 ✓，抽屉保持打开 ✓ | 同左 ✓ |
| 开设置 → 直接点对话框外的遮罩 | 对话框关闭 ✓，**但抽屉也被收了** ✗ ← 这一笔让判据的洞暴露出来 | 对话框关闭 ✓，抽屉保持打开 ✓ |
| 开着设置页按系统 BACK | 对话框关闭 ✓（`logcat`：`BACK: 关掉网页模态（Esc）`），没有跳到连接屏 ✓ | 同左 ✓ |
| 抽屉开着（无模态）按 BACK | 网页历史/连接屏那条老路 ✗ ← 1.0.13 里补成第二级 | 抽屉收起 ✓（`logcat`：`BACK: 收起目录抽屉`），页面不动 ✓ |
| 抽屉里点会话行（`android.view.View`，非按钮） | 仍然照旧收起抽屉并切会话 ✓（启发式本身没坏） | 同左 ✓ |
| 抽屉收起后点输入框 | 软键盘照常弹出 ✓（兜底规则没有把整列变成常驻可点） | 同左 ✓ |
| 设置页四周的遮罩条 | 每边 8px = 30 设备像素，**点不到**（复验时得点到 x=4 CSS px） | 每边 16px = 60 设备像素：点 `x=45 设备像素`（12 CSS，旧面板内、现在是遮罩）→ 关闭 ✓；点面板内部 `x=200` → 不关 ✓ |

FATAL EXCEPTION：0。

**这个 bug 为什么值得单独记一笔**：它不是「某个选择器写错了」，而是两个**各自都合理**的
设计撞在一起 —— 「收起抽屉时让出指针」和「模态住在侧栏里」。任何只测「元素在不在、
坐标对不对」的验证都发现不了它：盒子模型全程正常，坏的是命中测试。所以本仓库的本地渲染
回环（`scripts/css-lab.mjs`）现在也量这一项：`elementFromPoint` 的命中栈 +
`pointer-events` 计算值，见 `docs/mobile-ui-verification.md`。

### 右侧边栏在手机上多一个「收起」（2026-09-14，插件 rev 1.0.14）

用户报告：「右侧边栏有两个收起按钮」。**两个按钮都是宿主的**，不是我们加的
（`dsh-client-ui-sidebar-right` 的 `PanelChrome`）：

```
button[data-sidebar-right-mode]     全屏 / 退出全屏
button[data-sidebar-right-toggle]   收起右侧边栏
```

为什么在手机上等于重复 —— 宿主自己的代码就是判据：

```js
const autoFullscreen = viewportWidth < 768;
const fullscreen = autoFullscreen || surface?.layout.mode === "fullscreen";
// 模式按钮：
onClick: () => { if (fullscreen && autoFullscreen) actions.setExpanded(sessionId, false); actions.setMode(sessionId, next); }
```

本机视口 384px ⇒ 面板只要打开就必然 `fullscreen` ⇒ 那一笔走的就是
`setExpanded(false)` —— **和「收起」同一个动作**。真机复现：在面板里点「退出全屏」，
面板直接关掉，与点「收起」没有可见区别。多出来的只有副作用：它顺手把持久化的
`mode` 写成 `push`，用户回到电脑上打开同一个 dsh 时，面板的初始形态被改掉了。

修法：只在 `fullscreen` 状态下隐藏那个模式按钮（纯 CSS，判据用宿主写的
`data-sidebar-right-panel="fullscreen"` + `button[data-sidebar-right-mode]`，
不依赖 aria-label 的语种）。两个钩子已进契约，本机完整检查（4 个钩子）全在。

复验（`uiautomator`，面板打开时）：

| | 修前 | 修后 |
|---|---|---|
| 面板右上 | `(1173,33)-(1286,146) 退出全屏` + `(1308,33) 收起右侧边栏` | 只剩 `(1308,33) 收起右侧边栏` |

顺带确认了**分栏**不是同类问题：面板里点「分栏」会真的分成两格（384px 下每格 ≈192px），
分完那一格的分栏按钮自己消失（「已达两格上限」），「收起」仍在原位。也就是说这一屏
任何时候都只有一个出口，没有被新 UI 堵住。

### 仍未覆盖

- **终端模式**本轮未复测（切用途要重走一次连接、会动到正在用的隧道）；上一次逐项记录见
  本文开头 2026-09-12 那张表。
- P4 的「套件装上后按钮回来」这一支本机无法验证（没有套件可装）。
- `断开连接` 会走 §二 那条路径，本轮刻意没点。

## 六、逻辑审计（2026-09-14）：不靠谱的地方与处置

方法：三个人分头读 —— 一个审并发/进程生命周期（`SshTunnel` / `TunnelService` / `DshApp`），
一个审适配层与验证脚本，我自己通读 `MainActivity`（1924 行）。**每条结论都回读代码或真机验证过**，
最后单列「查了但排除」，免得下次重复怀疑同一批。

### 已修（本轮）

| # | 问题 | 证据 | 修法 |
|---|---|---|---|
| A1 | `rebuildTunnel` 在 `onUi { }` 里调 `autoFetchToken(t)` → 主线程跑最多 4 条 SSH 命令（`execOnce` 每条默认 8s）= **最长 32s 冻结 / ANR** | `MainActivity.kt:1631`（原）；同函数另三个调用点都在后台线程 | 令牌在 worker 线程取好再回主线程重载。负对照：脚本扫「`onUi` 体内出现阻塞调用」修复前命中 1 处、修复后 0 处 |
| A2 | `ensureTunnel` 把**整段拨号**放在 `synchronized(tunnelLock)` 里，而主线程的「断开连接」→ `closeTunnel()` 要拿同一把锁 → 拨号期间点断开，主线程卡几十秒（ANR） | `DshApp.kt` 原 `222-258` vs `262-269`；拨号最坏 3×15s + 探针 4.5s | 拆两把锁：`tunnelLock` 只做字段级短临界区（主线程可安全拿）、`dialLock` 串行化拨号且长阻塞只在后台；新增 `dialGeneration`（拨号期间断开 → 丢弃这次结果，「断开」必须赢）与 `pendingClose`（回收丢后台，下次拨号前 join，端口稳定仍成立） |
| A3(半) | 探针要跑 1.5–4.5s，迟到的那条回调会把用户刚「断开连接」的隧道又建回来（断开被逆转、`prefs["url"]` 被写回） | `MainActivity.kt:1578-1597` + `beginConnect`(`1795`) 与 `disconnectCurrent`(`1848`) 无互斥、无代数 | 回调先比对「我探的那条隧道还是当前这条吗」，不是就丢弃。A2 的 `dialGeneration` 挡住另一半（拨号结果不发布） |
| B1 | 适配层 17 个 `[class*="_…"]` 判据**没有任何金丝雀**，上游改个 localName 就静默失效 | 同左 | `check-mobile-hooks.mjs` 现在也提取类名后缀，并按来源分两份清单：`classHooks`（核心包，找不到即失败）与 `classHooksPlugin`（可选插件提供，如 `dsh-session-log-export` 的 `_moreButton`，只提示）。负对照：把 `_turnStatus` 改名 → 完整检查报「这条适配规则已经空转」、契约模式报「不一致」 ✓ |

### 待修（按建议顺序）

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| A4 | `persistSshConfig(...)` 写在 `onUi { }` 里（Activity 销毁就永不执行），落盘还是空 catch | `MainActivity.kt:1204-1208`、`1273-1279` | 拨号期间 Activity 被销毁 → 隧道已发布但 `ssh_json` 没落盘 → 下次冷启动走「无 ssh 配置」分支，可能停在指向死端口的旧页面上；`revalidateTunnel` 只查隧道健康、**从不比对 origin**，没有恢复路径 |
| A5 | 前台服务与隧道状态两个方向都不同步：失败路径不 `stop`（假「已连接到 X」常驻）；复用分支不补 `start`（首次在后台拨号被 Android 12+ 拒后**永远不再保活**） | `DshApp.kt:226-229 / 245-249 / 267`、`TunnelService.kt:112-116` | 通知说谎 + 进程优先级与设计意图相反 |
| B2 | `MutationObserver`（`subtree: true`）永不停；标记打上后仍每帧一次全 DOM 查询 | `dsh-handheld-mobile.js` 标记兜底 effect | 流式输出时白烧 CPU |
| B3 | `addDocumentStartJavaScript` 每次 Activity 重建都加一份（WebView 保活 → N 份累积） | `MainActivity.kt:324-337`（返回值丢弃） | N 份脚本白跑；功能上仍只补一条 entry（`defineProperty` 覆盖式重定义），但依赖「最后一次定义生效」这一微妙性质 |
| B4 | 验证工具的「绿」比说的弱：`css-lab.mjs` 只打印不判定（文档却写成「断言」）；`ui-verify` / `device-ui-verify` 不在 CI 跑（标记已过期过一次）；CI 的 `--contract` 只保证「插件与契约一致」，**不保证钩子仍在 dsh 里**；完整检查用子串计数（`data-phase` 会被 `data-phase-count` 满足），且扫到注释也算 | `scripts/*` | 适配层最怕的那类失效恰好不在覆盖范围内 |
| B5 | `device-tunnel-verify.mjs` 的「日志里没有 bind 失败」在没有日志时判 ✓（`log=''` → 0 次），而紧邻那条却严格区分「无法判定 → –」 | `scripts/device-tunnel-verify.mjs:131-134` | 最常见的取证条件下静默变绿 |
| B6 | `mobile-bootstrap.js` 的占位符是**直接拼进双引号 JS 字面量**，两侧只查「有没有残留」 | `assets/plugins/mobile-bootstrap.js:31-43` | 值里出现 `"` 或 `\` → 整段 bootstrap 变 SyntaxError、`__DSH_BOOT__` 不再被钩住、插件静默不加载（`new Function(bootstrap)` 一行就能当哨兵） |

### 查了但排除（不要再怀疑）

- **跨进程幽灵 dbclient 占着 3080**：真机 `am force-stop` 实测 —— 子进程随 App 一起消失
  （`ps -A` 里没有 dbclient），随后冷启动日志仍是 `base=http://127.0.0.1:3080`，**没有漂移**。
- **`close()` 漏杀刚 `pb.start()` 的进程**：`connectOnce` 在 `waitForLocal` 之后有 `started`
  复查 + `reap`（`SshTunnel.kt:320-324`），漏网进程百毫秒级被回收；两条独立推演都到同一结论。
- **看门狗抢注导致端口漂移**：理论窗口存在，但 dbclient **认证完才 bind `-L`**（0.5–3s），
  回收在 ~100ms 内发生 → 实际到不了。
- **`isPortFree` 的试绑竞态**：后果只是「白拆一次隧道」，不单列。
- **Activity 泄漏**：`MutableContextWrapper` 换入换出、观察者 `CopyOnWriteArrayList` + `onDestroy`
  移除，均正确。

## 七、连接屏重设计（0.1.10 / 2026-09-14）：从「三步向导」改到「状态优先」

### 为什么要改（都有实物证据）

起点是 §六 那次审计时的真机截图（`SM-S9280`、release 包、连接中）：

1. **三个同权重的整宽按钮挤在同一列**：`返回修改` / `回到网页` / `断开连接`。主操作要靠
   读文案分辨，而这一屏最该一眼看到的是「连上没有」和「点哪里」。
2. **「回到网页」在连接中是残留状态**（真实缺陷，见下）。它指向的是**上一轮**的页面：
   隧道正在重建，那一刻页面是死的。点下去等于把用户送到一个打不开的页面。
3. **优先级反了**：这一屏每天的实际用法是「看一眼连上没有 / 点一下回网页」，
   而 9 个输入框（地址/端口/账号/登录方式/密码/私钥路径/口令/dsh 端口）每次都摊在最前面，
   还要先过一道「你想做什么？」的模式选择。

### 现在的样子

```
ScrollView（可滚动）
  品牌行（图标 + 名字 + 诊断）
  状态块       ● 已连上电脑 / 未连接 / 连接中… / 连不上你的电脑
               副标题报**电脑**：连着时 xsj@192.168.0.135，没连时「上次连的是 …」
               （原来这里报隧道基址 http://127.0.0.1:3080 —— 127.0.0.1 是手机自己，
                 用户看一眼只会以为连到了本机；隧道地址在诊断页里）
  ①②③ 进度行   仅「连接中」与「刚失败还没重试」时显示
  连接设置卡   折叠：user@host · 密码（非默认端口才多一段）
               展开：全部输入框 + 就地报错
贴底动作区（不随内容滚动）
  状态条       全局提示（就地错误在字段下面）
  看 dsh 网页 / 打开终端      ← 模式从「独立一步」降级成分段控件
  主按钮       连上并打开 dsh 网页 / 打开 dsh 网页 / 打开终端 / 取消连接
  断开连接     文字链（隧道活着才出现）
```

**折叠判据是「配置完不完整」而不是「上次展开过吗」**（`savedConfigUsable()`）：少一个要
持久化的状态，也少一类「展开了却是空的」故障。

**唯一的状态入口是 `syncConnectUi()`**：主按钮文案、按钮可见性、状态块、进度行全部由它按
（相位、失败标志、隧道、页面）四个事实重算，任何分支都不再"记住上一次画了什么"。
相位只有三个：`IDLE`（常态）/ `EDIT`（在改配置）/ `CONNECTING`（在连）。

### 顺手修掉的两个缺陷

| # | 缺陷 | 证据 | 修法 |
|---|---|---|---|
| 1 | **连接中显示「回到网页」**：`connectViaSsh` 一进来就把状态条写成「SSH 隧道建立中…」，但**不刷新按钮可见性**（失败路径反而刷新了，注释还写着为什么必须刷新）→ 按钮停留在上一轮「隧道活着 + 页面在」的可见状态 | 真机截图（连接中，同时可见状态条「SSH 隧道建立中…」与「回到网页」）+ `MainActivity.kt` 两个分支不对称 | 按钮可见性只由 `syncConnectUi()` 从事实推导；「回到网页」这个按钮本身被删掉——主按钮在「隧道活着 + 页面在」时就是它 |
| 2 | **选中态分段控件是白底白字**：`segment()` 给 `RadioButton` 挂 `OnCheckedChangeListener` 改字色，而 `RadioGroup.addView` 会用自己内部的监听器**覆盖**子控件上的监听器（AOSP `RadioGroup.PassThroughHierarchyChangeListener.onChildViewAdded`），挂在子控件上的回调等于没挂 | 按 AOSP 源码判定（待真机复验：切到「私钥」看字色） | 字色改成 `res/color/segment_text.xml` 这个 selector（`state_checked`），不再依赖任何回调 |

### 真机验证（2026-09-14，SM-S9280 / Android 16，release 0.1.10）

逐条做过，结果是**两处改动**：一处是设计确认，一处是真机截图才暴露的错位。

| 项 | 结果 |
|---|---|
| 折叠态（配置齐全） | ✓ 状态块「● 已连上电脑」+ 电脑地址；摘要 `xsj@192.168.0.135 · 密码`；贴底只有「打开 dsh 网页」+ 一条「断开连接」 |
| 展开态 | ✓（截图）—— 同时暴露出**两轮**才修对的错位：「收起」比「连接设置」高 16dp。第一轮只改了左列（让它 48dp + 居中），真机一看**还差 16dp** —— 因为右边那个 TextView 自己高 48dp 且默认把文字画在顶部，光居中容器不够；第二轮给它本身设 `gravity=CENTER_VERTICAL` 才对齐。教训：给控件定高当触摸目标时，「容器居中」与「文字居中」是两件事 |
| 就地报错 | ✓ 地址清空后点主按钮：输入框红框 + 下方红字「请填写电脑地址」+ 自动滚到可见位置；**没有**启动终端。重新输入后红框红字立刻消失（`doAfterTextChanged`） |
| 分段控件选中态 | ✓ 选中段是白底深字（`state_checked` selector 生效）。旧写法（挂在 `RadioButton` 上的监听器）是否真的失效，只按 AOSP 源码判定过，没做旧包对比复现 |
| 连接中（冷启动自动恢复） | ✓ 用主机侧 `iptables` 只丢这台手机的新建 SSH 连接，把拨号挂住 31s：黄点「连接中…」+ ①②③ + 主按钮「取消连接」。**同时只有一个动作** —— 旧版这里会同时出现「回到网页」（缺陷 1） |
| 取消 | ✓ `18:54:08.762 cancelConnect`（点下去同刻处理）→ 状态块「未连接」+ 状态条「已取消连接」+ 主按钮回「连上并打开 dsh 网页」 |
| 迟到的拨号回程 | ✓ 拨号 `18:54:35.496` 才失败，`18:54:35.497` 日志「这次尝试已被取消/取代，丢弃失败回调」；27s 后的截图仍是「已取消连接」，**没有被覆盖**。`ps -A | grep -c dbclient` = **0**（没有孤儿进程） |
| 键盘 | ✓ 聚焦输入框后贴底动作区被顶到键盘上方（截图），输入框不被遮住 |
| 收尾 | ✓ `iptables` 残留规则 0；重新点主按钮 → `base=http://127.0.0.1:3080`、页面回来 |

两条过程记录，免得下次重踩：

- **`adb shell input text` 在中文输入法下会把 IP 打乱**（`192.168.0.135` → `1.92168..0135`）。
  清理办法不是再打一遍，而是**重启 App**：输入框是启动时从 `prefs[ssh_json]` 预填的，
  而校验失败不会落盘，所以重启即回到正确值。
- **首轮 CI 只报一条编译错误**：`buildDiagText` 里还留着 `connectStep`。改名时按
  `ConnectStep`（枚举）搜过，漏了 `connectStep`（字段）—— 大小写不同的标识符要分别搜。

## 八、任务完成通知（0.1.11 / 2026-09-14）：App 自己发，不再靠服务端推送

### 为什么

`DshApp` 的类注释里留着这段历史：**原本的 agent 完成通知已在 1.11.0 移除，改由服务端
经微信推送**。也就是说，想在手机上知道「dsh 干完活了」，得让电脑把消息发到微信、再由微信
叫你 —— 要服务端配合，消息还要绕出 App 再绕回来。现在改回 App 自己发。

### 怎么做的

```
页面（适配插件 1.0.15）
  MutationObserver 盯 dsh 的「深度求索中…」指示器（_turnStatus）
    出现 → postMessage({type:"turn-start"})
    消失 → postMessage({type:"turn-done", title, ms})
        ↓  WebView 消息通道（androidx.webkit 的 addWebMessageListener，
           origin 只放行 127.0.0.1:3080 / 13080；在 DshApp 里注册一次，不随 Activity 重建叠加）
DshApp.onPageMessage
  三道闸门：用户开关 → 是否在前台 → 系统权限（Notifier.allowed）
        ↓
Notifier.turnDone → 渠道 dsh-turn-hi（IMPORTANCE_HIGH，会弹横幅），点开回到 App
```

开关在连接屏「任务完成时提醒我」（默认关闭，打开时才申请 `POST_NOTIFICATIONS`）。
**为什么默认关闭**：这个 App 之前的立场是「除了隧道保活不发任何通知」，通知属于打扰，
让用户自己决定要不要。

三条不显然的地方（都写进代码注释了）：

1. **`onPause` 里的 `webView.pauseTimers()` 与这个功能直接冲突**。它是全局的（"layout,
   parsing, and JavaScript timers"），而「这一轮结束了」这个信号要靠页面重新渲染出来 ——
   定时器一停，渲染与观察者都可能推迟到用户回到 App 才跑，通知永远不会响。
   现在只**在页面空闲时**暂停（`DshApp.pageBusy`，由 `turn-start`/`turn-done` 维护）。
2. **后台不能用 rAF / 定时器**：rAF 在后台根本不跑，`setTimeout` 被节流到分钟级。
   判据改成 `MutationObserver`（微任务，跟着 JS 任务走）+ `isConnected`（O(1)）。
3. **通知权限是全 App 一次性的**：用户为了任务完成提醒批准之后，隧道那条常驻通知
   （`IMPORTANCE_MIN`，静默）也会跟着出现在通知栏里 —— 系统规则，不能只批一半。

### 「它在等你选」也算需要你（同版追加）

第一版只报「这一轮结束了」。但**一轮卡住等你点一下**（批准一次工具调用、回答一个问题）
时那一轮根本没结束 —— 用户却在等一个不会自己来的结果。这一条比「做完了」更该叫人回来，
所以补了第二个判据：

| 场景 | 钩子（宿主自己写的） |
|---|---|
| 工具审批 | `[data-approval-key]`（dsh-client-ui-approval） |
| 提问 / 计划确认 | `[data-question-key]` / `[data-plan-review-key]`（dsh-client-ui-user-questions） |

值就是这一次交互的 key：卡片挂载时提醒一次、重渲染不重复、回答后卡片卸载、下次出现再提醒。
通知走**另一个渠道** `dsh-ask`（`IMPORTANCE_HIGH`，会浮到屏幕上）——「等你选择」比「做完了」
急；两个渠道用户可以分别调重要性。开关面板上的文案也随之改成「dsh 需要你时提醒我」。

### 怎么验证（真机）

1. 连接屏打开「任务完成时提醒我」→ 系统权限对话框 → 允许
2. 回网页，发一条会跑十几秒的消息，**立刻按 HOME**（App 进后台）
3. 等这一轮结束，通知栏应出现「dsh 做完了 / <会话标题>」
4. 反向：留在 App 前台做同样的事 —— **不应该**有通知（`turn-done` 会记进
   `DiagLog`：「App 在前台，不发通知」）
5. 取证不必看屏幕：`adb shell dumpsys notification --noredact | grep -A3 dsh-turn-hi`

### 2026-09-17：漏掉的是「一条 turn-done 都没有」——判据取错了节点（1.0.19）

用户报「完成后没有收到弹窗提醒」。先排除掉最容易误判的三项，全部正常：
`POST_NOTIFICATIONS=granted`、`appops` 默认允许、`dsh-turn` 渠道存在（当时是 importance 3）、
App 内的开关 `notif_turn_done=true`（日志里 `开关=true`）。

真机日志（`adb logcat -s DshApp`，0.1.11 + 插件 1.0.18）：

```
00:22:33  页面报告：一轮生成开始（pageBusy=true）
00:28:41  页面报告：一轮生成结束（367948ms，…，前台=true）→ App 在前台，不发通知
00:29:57  页面报告：一轮生成开始（pageBusy=true）
（此后一整小时没有第二条「页面报告」—— 那一轮早就结束了，用户也回来看过了）
```

所以不是「通知被系统吃了」，是**页面根本没报结束**：适配层的观察者在第一轮之后卡死。
根因是判据取节点的方式：`document.querySelector('[class*="_turnStatus"]')` 只拿**第一个**，
而对话与轨迹两个面板各挂一份同名节点 —— 隐藏的那份 `isConnected` 永远为真，
于是 `running` 停在 true，此后再不会有「从有到无」的跃迁。

改法（1.0.19）：`pick()` / `live()` 都要求 `isConnected && getClientRects().length > 0`，
两个面板谁可见认谁；另外每 60s 发一条 `{"type":"turn-state","nodes":N}` 心跳，App 记进日志
（兜底分支现在会打印消息内容）—— 这类故障的形态就是「一声不响地不再报」，
没有心跳事后连「观察者死了」还是「它看错了节点」都分不出来。

教训一句话：**判「在不在」不能只看 `isConnected`，要看它是否真的被布局出来**
（`getClientRects().length`）—— 同一份 UI 被挂两份是这个前端里的常态。

### 同日追加：通知到了，但只躺在通知栏、不弹横幅

修完上一条，用户的下一条反馈是「有通知但不是弹出横幅通知」。原因在**渠道重要性**：
`dsh-turn` 建的时候是 `IMPORTANCE_DEFAULT`（3）——当初的设计是「响一声就够」，
而**只有 `IMPORTANCE_HIGH`（4）及以上才允许 heads-up（浮到屏幕上）**。

Android 的渠道重要性**创建之后 App 改不动**（只有用户能在设置里改），所以修法是换 id 重建：

| | 旧 | 新 |
|---|---|---|
| id | `dsh-turn` | `dsh-turn-hi` |
| 重要性 | `IMPORTANCE_DEFAULT` | `IMPORTANCE_HIGH` |
| 表现 | 通知栏里躺着 | 浮到屏幕上（横幅） |

`ensureChannels(CHANNEL_TURN)` 里顺带 `deleteNotificationChannel("dsh-turn")` ——
留着只会让设置页多一条永远不会响的「任务完成」。这条只动渠道 id/重要性，
页面侧与消息格式一个字没改。

> 还有一层是**系统设置**，App 管不着：One UI 的「通知弹出风格」要开着（简要/详细），
> 且该渠道没被单独关掉横幅。渠道建出来是 `mImportance=4` 之后，剩下的就归系统与用户。
