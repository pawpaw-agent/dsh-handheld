# 九、逻辑审计（2026-09-17）：并发、生命周期与「静默失效」

方法：七路并行审计（隧道与进程 / MainActivity 与生命周期 / 终端与一致性测试台 / 凭据·通知·诊断 /
注入插件 / CI 与验证脚本；注入插件那一路最后收工）+ 通读 `MainActivity`、`DshApp`。每条结论都回读代码，少数几条做了实测
（shell 语义复现、`dumpsys`、真机日志）。**下面「高」与「中高」的每一条都由第二个人独立复核过。**

与 §六（2026-09-14 那一轮）的关系：§六 的 A*/B* 编号继续沿用，本轮用 H/M/L 三档新编号；
§六 里仍开着的项在第一节逐条复核。**另注：有一批发现已经被未合并的 PR #1 修掉了**（见第五节），
在动手之前先看那一节，免得重复劳动。

**本轮的计数：高/中高 8 条、中 25 条、低 17 条。**

## 一、§六「待修」的现状（逐条复核）

| # | §六 的问题 | 现状 | 证据 |
|---|---|---|---|
| A4 | `persistSshConfig(...)` 在 `onUi { }` 里，Activity 销毁就永不执行；落盘是空 catch | **仍开着** | `MainActivity.kt:1600`（在 `onUi` 体内）；`onUi = ui.post { if (alive.get()) block() }`；`persistSshConfig` 的 `catch (_: Exception) {}` 是空的 |
| A5 | 前台服务与隧道状态两个方向都不同步 | **仍开着** | `TunnelService.start` 只在 `DshApp.kt:441`（成功分支）、`stop` 只在 `:464`；拨号失败与「复用已有隧道」两条路径都不碰服务 |
| B2 | `MutationObserver`（`subtree: true`）永不停，每帧一次全 DOM 查询 | **仍开着** | 插件「frame marker」effect |
| B3 | `addDocumentStartJavaScript` 每次 Activity 重建追加一份 | **仍开着** | `MainActivity.kt:442`（在 `obtainWebView(this).apply { … }` 里，返回值丢弃） |
| B4 | 验证工具的「绿」比说的弱 | 部分改善 | 新增的 `composer-stats-lab.mjs` 有 A/B 负对照+余量阈值；`--contract` 仍只保证「插件 ⇄ 契约」一致 |
| B5 | `device-tunnel-verify.mjs` 空日志时判 ✓ | **仍开着** | 未改 |
| B6 | `mobile-bootstrap.js` 占位符直接拼进双引号字面量 | **仍开着** | 未改 |

## 二、高 / 中高

| # | 位置 | 机理（一句） | 触发 | 用户可见后果 | 修法 |
|---|---|---|---|---|---|
| **H1** | `MainActivity.kt:2346-2363` | `disconnectCurrent()` 做了 `connectAttempt++` 却**没有 `endConnect()`**；被作废的在飞回程刻意不释放守卫 | 在 `connecting==true` 的窗口里点「断开连接」（回前台探针失败→重建期间、冷启动自动恢复期间都可达） | 守卫永远为 true：此后主按钮只回「正在连接中，请稍候…」，`revalidateTunnel` 也被同一守卫挡住 → **永久连不上**，只能杀进程/重建 Activity | `disconnectCurrent()` 补 `endConnect()`；更好：抽 `invalidateAttempt() = { connectAttempt++; endConnect() }`，让作废与释放永远成对 |
| **H2** | `MainActivity.kt:469/473/482/491/1900/1904/2353/2377` | `connectWeb` 把 token 拼进 URL，而**多处日志直接打印这个 URL**；其中两条的分支条件本身就是「URL 带 `?token=`」 | 服务重启后旧 token 失效（应用自己的 401 恢复路径）或带 token 首跳失败 | 完整 token 明文落进 `filesDir/diag.log`（重启不丢）、上诊断页、被「复制全部」带走 —— 与 `MainActivity.kt:1517` 那句注释「token 本身不记，只记长度」直接矛盾 | 统一 `redactUrl()`（丢 query 或把 `token=…` 打码）替换所有 URL 打印点；`DiagLog.record` 落盘前对 `token=[A-Za-z0-9_-]{20,}` 再兜一层 |
| **H3** | `.github/workflows/ci.yml:222-231` vs `android/app/build.gradle.kts:17-21` | CI 判「有没有正式签名」只看 `SIGNING_KEYSTORE_BASE64` 一个 secret；Gradle 要 4 个都齐。只配一半时 CI 不告警、Gradle 用**公开的 `debug.keystore`** 签，产物名却与正式包相同 | 密码类 secret 漏配/轮换；fork PR | 从 CI 拿到的 `dsh-handheld-release` 是 debug 签名，任何人都能签出可覆盖安装的「升级包」；且与既有 release 签名不同，用户无法覆盖安装 | 判据对齐 `hasReleaseSigning`（四个变量任一为空即让 build 失败，只允许 PR 回退）；回退产物改名；upload 前 `apksigner verify --print-certs` 比对证书 |
| **H4** | `SshTunnel.kt`（无清除入口）、`MainActivity.kt` 失败文案 | dropbear 的 `-y` 只放行**未知**主机；已知主机公钥不匹配时直接 `dropbear_exit`，提示删 `known_hosts` —— 而该文件在应用私有目录，**全仓没有任何清除入口** | 同一 IP 换机器/重装系统/容器重建 | 隧道与终端双双永久连不上，文案还是「连不上你的电脑（检查地址/账号/密码）」，用户只能清应用数据 | 识别 sink 里的 mismatch 原文上屏 + 提供「忘记这台电脑的密钥」入口（**PR #1 已实现 `resetKnownHosts()` + `tunnelFailureHint()`，未合并**） |
| **H5** | `SshTunnel.killCurrent():409-413`、`connectOnce` 失败分支、`DshApp.kt:410-414`、`MainActivity.kt:255/268` | 看门狗重建失败时：`killCurrent()` **刻意不清 `localBaseUrl`**、`proc=null`、`sshTunnel` 也不改；`onStateChange` 的消费者只写日志；UI 的唯一判据是 `sshTunnel != null` | 前台时隧道断且重建失败（对端休眠等），退避最长 45s/轮 | 界面稳定地显示「已连上电脑」，点「打开 dsh 网页」进死页面；唯一自愈是切前后台 | `TunnelObserver` 增加状态回调（失败要通知 UI）；`tunneled` 判据换成「有活着的 owner」而不是「对象非空」 |
| **H6** | `MainActivity.kt:1513`（`loadRetriesLeft = 3`）、`scheduleLoadRetry():2408-2425` | `connectWeb` 每次被调用都把重试预算重置回 3，而重试路径正是通过调用 `connectWeb` 实现的 → 计数器恒为 3，「3 次余量」永不生效；且 `connectWeb` 无条件 `showScreen(WEB)` | 隧道活着但主页面加载失败（停掉 dsh-web、链路失效、5xx） | 页面每 5s 自动重载、永不停止；用户在连接屏按 BACK 后 5s 内被抢回网页屏，只能先「断开连接」 | 预算只在「用户主动发起连接」时重置（或给 `connectWeb` 加 `retry` 参数）；重试路径不切屏 |

| **H7** | `dsh-handheld-mobile.js` 的 `pick()`/`check()`（1.0.19 起） | 完成判据绑在**当前可见视图**上：`pick()` 要求节点被布局出来；而 `_turnStatus` 只由当前显示的会话渲染（本机 dsh 0.1.5-rc.1：chat `client.js:2553` 是 `running && <TurnStatus>`，轨迹视图里 0 处）。切会话/切「轨迹」视图时可见节点消失 → 立刻 post 一条**假 turn-done**（标题还是新会话的），随后真正的结束什么都不发 | 发一条长消息 → 切到别的会话或「轨迹」→ 离开 App | 后台收不到「做完了」通知（与用户报过的症状同族）；同时 `pageBusy` 被错误清零 | 判据按**会话身份**绑定（同一会话根/同一身份下才把「消失」当结束），或换一个不随视图卸载的宿主信号 |
| **H8** | `dsh-handheld-mobile.js`（`if (ms < MIN_TURN_MS) return;`）、`DshApp.kt:133/137/175`、`MainActivity.kt:2108-2110` | `<1.5s` 的轮次只发 `turn-start`、不发 `turn-done`；`running=false` 只是页面局部状态 → App 侧 `pageBusy` 永远停在 true。第七路把它定为**高**（原编号 M16） | 任何 <1.5s 的指示器闪现（代码注释自己说切会话会闪） | `MainActivity.onPause` 永远走「保留定时器」分支：后台省电开关静默失效且**无界**（不会自愈） | 短轮次也补一条结束消息（由 App 决定要不要通知），或把「开始」延迟到确认不是闪现之后再发 |

### H1 的证据（值得单独看）

```kotlin
// cancelConnect（2334-2343）：作废者释放了守卫
connectAttempt++
...
endConnect()                     // ← 有

// disconnectCurrent（2346-2363）：同样作废，但漏了
connectAttempt++                 // ← 只作废
closeCurrentTunnel()
prefs.edit().remove("url").apply()
...
status("已断开")                 // ← 没有 endConnect()

// 被作废的回程（connectViaSsh 1567 / rebuildTunnel 2037 / autoConnectSsh 665,679）
if (connectAttempt != attempt) { DiagLog.i(TAG, "…丢弃这次回调"); return@onUi }   // 也不释放
```

## 三、中

| # | 位置 | 机理 | 后果 | 修法 |
|---|---|---|---|---|
| M1 | `MainActivity.kt:445/506/565` + `DshApp.kt:43/66-80` | WebViewClient / WebChromeClient / DownloadListener 都是捕获 `this@MainActivity` 的匿名对象，却挂在 **Application 保活**的 WebView 上；`onDestroy` 只换 context，不摘 client | 最近一个被销毁的 Activity + 整棵连接屏视图树不可回收；页面回调（`onPageFinished`→`hideErrorPage()` 等）继续打到死实例 | `onDestroy` 换掉这三个 client（`shouldInterceptRequest` 搬到不持 Activity 的 Application 级 client），或 client 内持 `WeakReference` |
| M2 | `MainActivity.kt:615-620` | `onCreate` 分支 1 只看「WebView 上有 http 页面」，**不看隧道还在不在**；而 `断开连接` 现在是「保留页面」 | 断开后 Activity 被销毁再重开 → 直接进无隧道的死页面，连接屏入口消失，`revalidateTunnel` 因 `sshTunnel==null` 直接返回 | 分支 1 补 `&& app.sshTunnel != null` |
| M3 | `MainActivity.kt:461-476`（唯一隐藏点）、`showConnectScreen():2391`、`fallbackBack:2199-2202` | 覆盖层（错误页/401 令牌页）显示与隐藏没有单一真相：唯一自动隐藏是 `onPageFinished` 且**无条件**；BACK 阶梯与 `showConnectScreen()` 完全不知道它存在 | 二选一失效：错误响应若也回调 `onPageFinished` → 401 令牌页刚弹出就被自己隐藏（用户看不到「自动获取令牌并重连」）；若不回调 → 覆盖层永久盖住连接屏，BACK「回连接屏」成了空操作 | 把「这次导航失败」做成显式状态（`onPageStarted` 清、错误回调置），`onPageFinished` 只在未置位时隐藏；`showConnectScreen()` 补 `hideErrorPage()`；BACK 把覆盖层提到与诊断页同级优先关掉 |
| M4 | `MainActivity.kt:1075-1080`（设置卡头部点击） | 头部点击在 EDIT/IDLE 间切换、**不看相位**；切走 CONNECTING 后 `syncConnectUi` 立刻重算成「未连接」，但拨号还在飞 | ①②③ 消失、状态块写「未连接」；「取消连接」入口没了 → 最坏等 45s 才能重试 | 头部点击首句判相位：连接中直接忽略 |
| M5 | `TuiActivity.kt:235-237 / 77-85 / 147-151 / 153` | 全屏 `statusView` 是最后 addView（盖在终端上），唯一隐藏点在 `launchSession`，而它只在 `onCreate` 跑一次 | 任何一次会话结束/失败后提示永驻，**没有任何重连入口**，只能退出重进 | 会话结束时给 `statusView` 挂「重连」动作（清旧 session + 重新 `launchSession`），或至少写明「按返回键退出」 |
| M6 | `Harness.java:94-98/131-161`、`terminal-conformance/README.md` | `implementations()` 只注册 `TermuxOracle` 一个实现，多实现比较循环永不执行，`Screen.diff`/`sameAs` 是死代码；oracle 是钉死的 vendored jar，与 app 实际编译的 `terminal-view`（JitPack 解析）**零版本绑定** | 「CI 全绿」被当成「终端行为已验证」；升级 `terminal-view` 不改语料时门禁照样绿（而这正是需要真机回归的部分） | 加静态断言「app 的 terminal-* 版本 == oracle jar 文件名版本」；README 的能力声明收窄为「对 oracle 的回归」 |
| M7 | `Coverage.java:106-137`、`Harness.java:206-213` | 覆盖率判据部分恒真：`in:wide-chars` = 输入里有任一字节 ≥0x80（52 个用例命中 8 个，其中 5 个一个宽字符都没有）；screen 侧证据只打印不判定 | 删掉真正含宽字符的 3 个用例后门禁仍然全绿 —— 语料空转检查有假象 | `wide-chars` 按 EAW W/F 判（或要求 screen 侧宽格证据）；screen 证据升为第二档判定 |
| M8 | `TuiActivity.kt:187-197 / 297-302` | 生成私钥的 `Thread` 没有 `UncaughtExceptionHandler`：`ProcessBuilder.start()`/`waitFor()` 的异常逃出 `run` 会**杀整个进程**；同一段 `waitFor()` 无超时 | 文件不可执行/SELinux 拒绝时是 FATAL 崩溃（而非「密钥生成失败」提示）；dropbearkey 挂住则永久停在「准备 SSH 密钥…」 | fork 段整体 `runCatching`；`waitFor(timeout)` + 超时 `destroy()` |
| M9 | `AndroidManifest.xml:46-50`、`TuiActivity.kt:72-75/417-421` | TuiActivity 的 `configChanges` 不含 `uiMode/density/fontScale/locale` → 改深色模式/系统字号会重建 Activity；`onCreate` 无条件重开 SSH，`onDestroy` 对旧 session `finishIfRunning()`（上游实现是 `SIGKILL`） | 终端里跑着东西时改字号 → 远端前台进程被杀、回看内容丢失、重新握手 | 补 `uiMode|density|fontScale|smallestScreenSize|locale|layoutDirection` |
| M10 | `SshTunnel.kt:320`（`started` 复查在探针之前）、`close():476-482`（不 join 看门狗）、`DshApp.kt:375-382`（复用快路径不看代数） | §六 A2/A3 的「dialGeneration 已挡住另一半」实际**没有覆盖这两个入口**：探针最长 4.5s 后才发布结果，发布前无事后复查；复用分支直接 `return reusable` 不确认它还是当前隧道 | 窗口亚毫秒级，但命中后果是「断开被逆转」（observer 把 `prefs["url"]` 写回并切回网页屏） | 发布放进 `synchronized(this)` 并复查 `started`；`close()` join 看门狗；复用分支返回前再验 `sshTunnel === reusable` 且代数未变 |
| M11 | `ci.yml:249-261` | release APK 的校验只有「不可调试」一条：不验 native 库是否进包、不验 versionCode、不验签名者。而 dbclient 是以**子进程 exec** 方式运行的，缺 `.so` 不是崩溃而是「隧道永远起不来」 | `jniLibs`/`abiFilters`/`useLegacyPackaging` 被重构、或忘记提 versionCode 时门禁全绿 | 追加三条断言：`unzip -l` 里有 `lib/arm64-v8a/lib{dbclient,dropbearkey}.so`；badging 的 versionCode 与 `build.gradle.kts` 一致；`apksigner verify --print-certs` 比对证书（**PR #1 已加第一条**） |
| M12 | `ci.yml:107-115` | `if ./gradlew :app:dependencies \| grep -q terminal-conformance` 没开 `pipefail`：gradle 失败/配置改名时 stdout 为空 → grep 退 1 → 判「没有依赖」→ 打印 ✓ | 这条不变量可能**永久空转**（配置改名不会让构建失败） | 该步 `set -o pipefail`，或先落盘再判 |
| M13 | `ci.yml:40-53`、`ci.yml:55-66` | `mobile-contract` 的两条 shell 断言都只做否定、且范围小于注释宣称：删掉「从 assets 读 bootstrap」的代码仍 ✓；`assets/plugins` 白名单只看这一个子目录（注释说的却是整个 `assets/` 会进包） | 适配注入静默消失、或 CI-only 文件落在 `assets/` 别处随包分发，两种 CI 都不红 | 补正向断言（`grep plugins/mobile-bootstrap.js` + `addDocumentStartJavaScript`）；白名单覆盖整棵 `assets/` 并断言文件数非零 |
| M14 | `ci.yml:141` | dropbear 缓存键只含脚本与 `localoptions.h`，**不含 NDK 版本**（NDK 来自 runner 镜像且用 `sort \| head -n1` 动态选）；脚本改名后 `hashFiles` 返回空串，键退化成常量 | 产物与「当前工具链」脱钩；脚本改名场景下长期复用旧缓存 | 把 NDK 版本并入键（或加手工 bump 的 `DROPBEAR_TOOLCHAIN_REV`）；命中缓存时也断言两个二进制都在 |
| M15 | `scripts/mirror-via-api.py:69-78/95/143-148`、`scripts/push-via-api.py:69-71/150-158` | 两者都以**当前远端 head** 为 parent → 永远 fast-forward，`force: False` 对「内容被替换」毫无约束；mirror 的文件清单取自本地索引（HEAD 在别的分支/落后时会把 main 整体换掉） | 公开仓库 main 可能被静默删文件/回退 | 断言 `HEAD == main`、`远端 head` 是本地 HEAD 的祖先；mirror 前做远端/本地路径差集检查；push 加 `--expect-base <sha>` |
| M17 | 只有 `MainActivity.kt:1972/1976` 调 `onActivityStarted/Stopped` | `TuiActivity` 不参与前台计数 | 用户在**终端模式**里（App 明明在前台）也会收到完成通知，与「App 在前台时不打扰」矛盾 | `TuiActivity.onStart/onStop` 也调，或统一用 `ActivityLifecycleCallbacks` |
| M18 | `DiagLog.kt:71-78/149-164` | 旋转把上一代日志 `renameTo` 成 `diag.log.1`，而**全项目没有任何地方读 `.1`**；页面标的却是「含上一次运行」 | 崩溃后重启（最需要上一轮的时刻）看不到它；下次再旋转还会把它删掉 | `persistedTail()` 也读 `.1` 并分段标注；判 `renameTo` 返回值 |
| M19 | `Notifier.kt:107-115/194-197` | 第三道闸门只看 App 级 `areNotificationsEnabled()`，不看**渠道级** `IMPORTANCE_NONE`；渠道被单关时 `notify()` 静默 no-op，日志却写「已发」 | 与 §八 追过的「完成了没提醒」同族，但这次日志会把人带偏 | `allowed()` 增加渠道重要性判断；把「渠道被关」单独记一行 |
| M20 | `MainActivity.kt:966` + `SshConfig.kt:62-71` | `toJson` 只写当前登录方式那一半字段，而 `persistSshConfig` 每次成功连接都整串覆盖 | 切一次登录方式（或点一次「打开终端」）就把另一种方式的凭据从磁盘抹掉，切回来要重填 | 两份都写（都已是密文），或 `copy` 时保留另一模式旧值 |
| M21 | `dsh-handheld-mobile.js:670-680` | `getClientRects()` 只证「被布局过」，**不证「看得见」**：`visibility:hidden` / `opacity:0` / 移到屏外 / 被裁剪都不影响它（宿主确实用 `visibility:hidden`，见 conversation `client.js:14652`） | 1.0.19 到底修没修好，取决于那份隐藏副本是**怎么藏的** —— 若是 `visibility:hidden`，判据不成立，仍会挑到隐藏那份 | 先真机复验隐藏方式；判据改用 `checkVisibility({visibilityProperty, opacityProperty})` 或加视口相交判断 |
| M22 | `dsh-handheld-mobile.js:682-711` | 心跳只在 `running` 期间发 —— 而它要诊断的恰恰是「一次都没看见节点」那种情形，那时连一条 `turn-state` 都没有，与「观察者死了」在日志上同形 | 下次再出同类故障时，日志仍然分不出「没看见」和「没在跑」 | 心跳移出 present 分支：每次 check 超过 60s 就发一条（带 present/running/nodes） |
| M23 | `dsh-handheld-mobile.js:483-509 / 544-549` | 抽屉的「点一下收起来」没跟 `MOBILE_QUERY` 一起退场：`ShellOverlay` 无条件挂捕获点击，而会话行是 `role=treeitem`（不在 `INERT_TARGETS`），`closeDrawer` 见 `data-sidebar-collapsed` 缺失就 `toggleSidebar()` —— 宽屏下那个属性本来就不存在 | 平板横屏/折叠屏展开（≥1024 CSS px + `pointer:coarse`）点会话行 → 侧栏被意外收掉 | `onFrameClick` 首行 `if (!matchMedia(MOBILE_QUERY).matches) return;` |
| M24 | `dsh-handheld-mobile.js:670-680 / 713-714` | mutation 回调里 `getClientRects()` 会**强制同步布局**，流式输出时每批 mutation 都强制一整次；另有 `pick()`/心跳的全文档 `querySelectorAll`。代码与 `known-issues.md` §八 仍写「代价只有一次 `isConnected`」，与实现不符 | 流式输出时白烧 CPU（可观测，未量化） | 热路径只用 `isConnected`；只在 mutation 牵扯候选、或 ≥250ms 节流时才做可见性判定 |
| M25 | `check-mobile-hooks.mjs:265 / 288-304 / 335` | 契约只守**属性名**不守**取值**（插件硬编码 `data-phase="hero"/"inert"`、`data-sidebar-right-panel="fullscreen"`；宿主把取值改名就静默空转 —— 本机 `conversationPhase` 已经会返回 `engaging`）；`requiredClasses` 算了却从未使用，「仅插件包」只在日志提示、不计入 failures | dsh 改取值或类名后缀搬家时 CI 仍绿 | 取值进契约（或至少断言关键取值仍存在）；`requiredClasses` 真正参与判定 |

## 四、低

| # | 位置 | 问题 | 修法 |
|---|---|---|---|
| L1 | `SshTunnel.kt:440-474` | `execOnce` 的子进程在异常路径不杀（`p` 在 try 内声明）、也**从不 `spawned.add`** → 中断时留一条 dbclient 活到远端命令结束或 keepalive 超时（~90s） | `p` 提到 try 外，异常分支也 destroy；`spawned.add` 后统一由 `reap` 收 |
| L2 | `SecurePrefs.kt:142-144` + `MainActivity.kt:297-299` | 解密失败被降级成「还没配置过」：用户看到 App「忘了」配置却没有任何解释，密文仍在盘上 | 把「密文但解不开」暴露给上层，连接屏显示一句「保存的连接配置无法解密，请重新填写」 |
| L3 | `DiagLog.kt:85-109` | 写线程因 IO 异常退出后 `writer` 仍非 null → 磁盘日志永久停写，页头却显示「队列满丢弃」 | `pump` 退出时置 `diskFailed`，`record` 停止入队、`stats()` 显示「磁盘写入已中断」 |
| L4 | `TuiActivity.kt:253` | `onTerminalCursorStateChange` 空实现 → 远端隐藏光标期间切后台再回来，之后光标常亮不闪 | `if (state) startCursorBlinker()` |
| L5 | `TuiActivity.kt:98-104/36-40` | 注释承诺「A+/A- 字号可调（`tui_font_size_px`）」，全仓只有读取没有写入者；另一处注释说「本模式使用私钥」，而 `localoptions.h` 明确开了密码认证 | 删掉/标注「预留」；注释改成与构建配置一致 |
| L6 | `AndroidManifest.xml` | `usesCleartextTraffic="true"` 是全应用开关，而明文只应发生在 `127.0.0.1` | 加 `networkSecurityConfig`，只给回环开明文 |
| L7 | `MainActivity.kt:451-459` | `shouldInterceptRequest` 按**子串**匹配任意 URL（任何 origin 上路径含该串都会被喂我们 APK 里的 bundle） | 收紧到隧道那两 个 origin，与 `addWebMessageListener` 白名单一致 |
| L8 | `MainActivity.kt:442` | `addDocumentStartJavaScript(..., setOf("*"))`：脚本在任意 origin 上执行，且每次 Activity 重建追加一份（= B3） | origin 收窄 + 保存返回值在重建时移除旧的 |
| L9 | `scripts/ui-verify.mjs:49` | `PLUGIN_REV` 还停在 `dsh-handheld-mobile-1.0.16`，而 App 已是 `1.0.19`；注释写着「与 MainActivity 的常量保持一致」，但没有任何断言守它 | 从 `MainActivity.kt` 解析 id/rev；或加一条三者一致断言（CI） |
| L10 | `ci.yml` 三处 upload | 未设 `if-no-files-found`（默认 warn）→ artifact 可静默缺失；`dsh-handheld-debug` 被文档当作真机取证的唯一来源 | 三处统一 `if-no-files-found: error`，debug 那份补 `test -f` |
| L11 | `scripts/check-mobile-hooks.mjs:98-119` | 契约提取器的正则只认 `[data-x]` 与 `[data-x=…]`：带运算符（`^= * ~= |=`）或空格的属性选择器、以及只用 `getAttribute/hasAttribute` 的读取都不进契约 | 正则补运算符；补扫 `getAttribute("data-…")` |
| L12 | `terminal-conformance`（`TerminalUnderTest.resize` 零调用） | resize/reflow 在测试台里从不被触发（`syn-resize-baseline.bin` 是程序自己重绘的字节流），`Screen.sameAs`/多实现比较路径是死代码 | 轨迹里插入一次真 resize 并纳入逐片比较 |
| L13 | `docs/known-issues.md:8` | 头部写「当前版本是 0.1.8（versionCode 35）」，实际 0.1.12 / 39 | 改成不带版本号的表述，指向 `build.gradle.kts` 与 Releases |
| L14 | `.gitignore` | 只忽略 `android/app/build/` 与 `android/terminal-conformance/build/`，根项目 `android/build/` 未忽略（当前未跟踪，但 `git add -A` 会扫进去） | 加 `android/build/` |
| L15 | `dsh-handheld-mobile.js:198-200` | `[data-phase] header > :first-child{padding-left:20px}` 没锚定会话头 → 提问卡的头（首子 `headingBlock`）与轨迹视图的头各被加 20px | 收窄为 `header:has([class*="_titleRow"])` |
| L16 | `mobile-bootstrap.js:31-43` | push 非幂等：同一文档第二次写 `__DSH_BOOT__` 会触发宿主 `duplicate graph entry` 抛错 → **整个前端起不来** | 按 id 去重后再 push |
| L17 | `dsh-handheld-mobile.js:507 / 615` | `data-handheld` 有两个写者：ShellOverlay 的 cleanup 会 `removeAttribute`，而标记兜底观察者只订阅 `childList`，看不见属性删除 → 卸载顺序不利时整套移动 CSS 短暂失效 | 观察者加 `attributes: true`（或把标记的写入收成一处） |

## 四·补：第七路对 1.0.19「取可见节点」这个修复的挑战

`known-issues.md` §八 与 `mobile-adaptation.md` 把「完成后没有收到弹窗提醒」的根因写成
「对话与轨迹两个面板各挂一份同名节点，隐藏那份 `isConnected` 恒真」。第七路回读**本机安装的
dsh 0.1.5-rc.1** 产物后指出这个解释站不住：

- 轨迹视图里 **0 个** `_turnStatus`（它那两个 `role=status` 是 `_historyLoading` / `_visuallyHidden`）；
- 本机 chat `client.js:2553` 是 `running && <TurnStatus>` —— 它只在**当前显示的会话**里渲染；
- 宿主自己也用 `visibility: hidden`（conversation `client.js:14652`）。

于是有两条互斥的风险，取决于那份「重复节点」到底是怎么来的、怎么藏的（**需真机复验**）：

1. 若是 `visibility:hidden` 或屏外 —— `getClientRects().length > 0` **判不出来**（M21），1.0.19 等于没修；
2. 若是 `display:none`（真机现象支持这一支：装上 1.0.19 后通知确实发出了）——
   那 `pick()` 在用户**切会话/切视图**时会「看不见 → 立刻报结束」（H7），把「卡死」换成了
   「误报 + 真结束漏报」。

结论：这条不该再靠「同名节点有几个」猜，而要**把判据绑到会话身份**（或换一个不随视图卸载的
宿主信号），先用调试包 + CDP 数一遍 `_turnStatus` 的归属与隐藏方式。修完记得把 §八 与
`mobile-adaptation.md` 里那段根因说明一起订正 —— 现在它是错的。

## 五、与未合并 PR #1 的重叠（先看这里，别重复劳动）

PR #1（`fix/ssh-key-import`，仍开着）已经修掉了本轮的两条高危 + 一条中危：

| 本轮编号 | PR #1 里的对应实现 |
|---|---|
| H4（host key 不匹配 = 永久死路） | `resetKnownHosts()`（连接屏「重置已信任的电脑身份」）+ `tunnelFailureHint()`（把 mismatch 原文翻成人话上屏） |
| 私钥口令死字段（A 的 L 级发现） | `SshKeyImport.ensureUsable()`：导入时就转换 OpenSSH→dropbear，带口令的当场说清楚；`Auth.KeyPair` 去掉 passphrase |
| M11 的第一条（native 库进包） | ci.yml 新增「Verify native binaries are packaged」步骤（`unzip -l` 断言三个 `.so` 都在） |

PR #1 自身还需要 rebase（它与新 main 在 `ci.yml` 头部注释处冲突），并且它把文档章节编成了 §八 ——
现在 §八 已是「任务完成通知」，它应当顺延为 §十（§九 被本文占用）。

## 六、查了但排除（本轮新增，别再怀疑）

- **`syncConnectUi()` 的单一入口性**：逐字段 grep 确认 `connectMainBtn.text` / `disconnectLink.visibility` /
  `heroTitle` / `heroDot` / `heroSub` / `formBody` / `settingsSummary` / `settingsAction` /
  `progressBlock.visibility` 都只有它一处写入；`showScreen` 是 `connectView.visibility` 的唯一写入点。§七 的声明成立。
- **`onUi` 的作废语义**、**`ui.postDelayed` 的可取消性**、**`onPause` 的 `pauseTimers` 配对**：正确。
- **`UiKit`/`Notify`/`TunnelService` 自身**、**加解密实现（IV/GCMParameterSpec/前缀版本化/明文迁移幂等）**、
  **`SshConfig` 的 JSON 兼容**、**密码与口令从不入日志**（走 `DROPBEAR_PASSWORD` env）、
  **通知 id 与渠道迁移**、**vendored 7 类与 `terminal-view` 无同名类冲突**（POM 与 classes.jar 核对过）、
  **键排派发不丢修饰键**、**known_hosts 两边同 HOME**：均正确。
- **§六 已排除的**（跨进程幽灵 dbclient、`close()` 漏杀刚 start 的进程、看门狗抢注导致端口漂移、
  `isPortFree` 试绑竞态、「能用 connect 代替真流量探针」）：本轮未找到反证，继续不要怀疑。
- **对 §六「Activity 泄漏已排除」的订正**：那条只覆盖了 `MutableContextWrapper` 与观察者，
  **没覆盖 WebView 自己的三个 client** —— 见 M1（A 路与本轮我自己都独立确认）。

## 七、建议的修复顺序

1. **H1**（永久死路，一行修）、**H2**（token 进日志，日志侧收口）—— 都不需要动架构；
   **H7/H8**（通知判据）紧随其后 —— 它是这三条里唯一会反复咬人的：先真机复验再改。
2. **H3**（CI 签名判据）+ **M11/M12/M13/L10**（门禁加固）—— 一次改完 ci.yml。
3. **H4/H5**（隧道状态与失败原因上屏）：H4 直接取 PR #1 的 `resetKnownHosts`/`tunnelFailureHint`；
   H5 需要给 `TunnelObserver` 加状态回调。
4. **H6/M2/M3/M4**（连接屏与页面状态机）—— 同一片区域，建议一轮改完 + 真机回归。
5. **M1/M9**（生命周期与重建）、**M17/M16**（前台与通知状态）、**M5/M8**（终端健壮性）。
6. 其余低危按顺手程度并入。