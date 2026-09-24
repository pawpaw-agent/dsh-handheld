> ⛔ **本层已于 2026-09-25 按用户要求整体移除。** 本文档保留为**历史记录**：
> 它记录了为什么会有这一层、踩过哪些坑、以及 dsh 0.1.7-rc.2 是如何把它拆掉的
> （见文末「0.1.7-rc.2 会拆掉注入的插件」一节 —— 那是移除的直接原因）。
> 现在 App 只做三件事：全屏 WebView + SSH 隧道 + 原生终端，页面完全交给 dsh 自己的响应式布局。

# 手机 Web UI 适配的验证

这个 App 的价值就是「在手机上用 dsh 网页」，所以**移动端适配是本项目的主体功能**，
隧道只是使能手段。这份文档说明它建立在什么之上、以及怎么验证它没坏。

## 它建立在什么之上

dsh 官方前端是桌面布局，窄屏下侧栏常驻挤占内容。适配靠 **App 侧注入一个客户端插件**
实现（2026-09-13 起这一层是本仓库自研，见 `docs/mobile-adaptation.md`），服务端零改动：

```
MainActivity
  ├─ addDocumentStartJavaScript(assets/plugins/mobile-bootstrap.js)     ← 钩住 __DSH_BOOT__
  └─ shouldInterceptRequest → assets/plugins/dsh-handheld-mobile.js     ← 把插件喂给引导循环
```

插件通过 **dsh 的 DOM 属性与结构**找到界面再改造它。契约（唯一事实来源）在
`scripts/mobile-hooks-contract.json`，扫描范围是**整份 bundle**（规则大量写在 CSS 里）：

| 钩子 | 用途 |
|---|---|
| `data-phase` | 会话阶段判定（`hero` 没有会话头 → 浮动入口在那时出现） |
| `data-sidebar-collapsed` | 抽屉的开合信号（宿主窄屏下的 `!narrowExpanded`） |

**这些钩子没有版本契约**：dsh 独立演进，适配层钉在 `dsh-handheld-mobile-1.0.0`。
dsh 哪天改了属性名或那几层结构，适配就**静默失效** —— 抽屉不弹、布局错位，只能在手机上
发现。这就是下面几层验证要解决的问题。

## 零层：本地渲染回环（改 CSS 时用，秒级）

改一行 CSS 就等一次 CI 构建 + 装机是受不了的，所以在本地把候选样式渲染出来看：

```sh
# fixture 页面（用真实 class 名搭的最小结构）+ 真实的 dsh 组件 CSS
node scripts/css-lab.mjs --page file:///tmp/fixture/fixture.html \
  --css /tmp/fixture/our.css [--open 1] [--hero 1] --out /tmp/fixture/shot.png
```

它同时打一份**计算样式探针**（侧栏 position/left/width、遮罩 opacity、胶囊计数 display、
弹层矩形、横向溢出，以及 `elementFromPoint` 的**命中栈**），所以「抽屉是不是真的滑到了
屏外」「菜单有没有落在视口内」「设置页还点不点得动」是**断言**，不是看图猜。

命中测试那一组（`hit.closeHit / closeIsInPanel / maskHit / behindEatenByOverlay /
panelPE / colPE`）是为 1.0.11 那个「设置页点不动」加的：**几何全对但命中测试全错**
正是它的特征（见 `docs/known-issues.md` §五）。用法：把设置浮层放进 fixture 的**侧栏列
内部**（真实页面就是这样，`SettingsRoot` 注册进 `sidebar.settings` 槽），跑两遍对比 ——

```sh
# 0) 先把 bundle 里那段 CSS 抽出来（与 WebView 里跑的逐字相同）
node scripts/plugin-css.mjs /tmp/plugin-css.css
# 1) 负对照：剥掉模态兜底规则的版本，故障应当复现
node scripts/plugin-css.mjs /tmp/plugin-css-legacy.css --drop-modal-guard
node scripts/css-lab.mjs --page file:///tmp/fixture/fixture-modal.html \
  --css /tmp/plugin-css-legacy.css --out /tmp/shot-legacy.png
#   → closeHit=div               （✕ 不是命中目标 = 点不动）
#     closeIsInPanel=false
#     behindEatenByOverlay=false （点击穿透到背后的页面）
#     panelPE / maskPE / colPE = none
# 2) 当前 CSS：应当可点
node scripts/css-lab.mjs --page file:///tmp/fixture/fixture-modal.html \
  --css /tmp/plugin-css.css --out /tmp/shot-fixed.png
#   → closeHit=button.VOzbGW_close、closeIsInPanel=true
#     maskHit=div.VOzbGW_mask、behindEatenByOverlay=true（浮层吃住了这一笔）
#     panelPE / maskPE / colPE = auto
```

`scripts/css-lab.mjs` 与 `scripts/plugin-css.mjs` 都在仓库里（探针与抽取器随适配层
一起演进）；fixture 页面是本机临时产物（真实的 dsh 组件 CSS 从已安装的 bundle 里抽，
不随仓库走）。**负对照必须有**：一条永远通过的断言等于没有断言。

### 零层之二：统计行的 A/B 断言（`scripts/composer-stats-lab.mjs`）

`css-lab.mjs` 的探针是给抽屉/模态写的（侧栏位置、遮罩命中栈…），不量输入框上方那行统计。
1.0.17 起这一行的窄屏重排有自己的一条断言：fixture 从**安装产物**里抽宿主的三段 CSS ——
这一行自己的 `.bOPqQW_*`、定义变量的 `.wSkVaW_root{...}`、以及**它真正的父容器**
`.uV2eYG_root{...}`（composer dock，左右各 16px）—— 360 / 384 / 412px 各跑两遍：
A 不注入**必须复现截断**（负对照），B 注入后不得截断、两侧不得留白，且**不换行时余量 ≥ 12px**
（真机字体比 fixture 宽一点；0.1.17 第一版就是余量只剩 6px，装机后照旧截断）。

```sh
node scripts/plugin-css.mjs /tmp/plugin.css
node scripts/composer-stats-lab.mjs --css /tmp/plugin.css
```

**父容器那一段是踩过坑才加进去的**：第一版 fixture 只有「视口 384px → 这一行 384px 宽」，
于是小回环全绿而真机仍截断 —— 真机上它住在 composer dock 里，可用宽度少了 32px。
教训是通用的：**fixture 的父链必须照着真实 DOM 搭**，只搭目标元素本身会系统性高估可用宽度。
它自己 `die()` 的地方都很明确（找不到 dsh 产物 / 找不到 chromium / 宿主那几段 CSS 改名了），
不静默跳过 —— 这一行的失败模式本来就是「一声不响地变回截断」。同上：fixture 是近似，
最终判据仍是真机截图。

⚠️ **fixture 是近似**：这个沙箱里 Chromium 发不出任何 HTTP（`Page.navigate` 到 http://
一律超时，`file://` 与 `data:` 可以），所以页面是照着 dsh 真实产物搭的，主题变量不全、
视觉不可信；**几何、层叠与命中测试可信，最终判据永远是下面第三层的真机截图**。

## 第一层：契约金丝雀（可进 CI，秒级）

```sh
node scripts/check-mobile-hooks.mjs --contract
```

不需要浏览器、不需要装 dsh。它断言**插件实际读取的 dsh 钩子**与
`scripts/mobile-hooks-contract.json` 里声明的**完全一致**。

意义：重新 vendoring 插件会改变依赖集合，那必须是**显式动作**（更新契约），
而不是悄悄多依赖几个没人验证过的钩子。

CI 里作为 `mobile-contract` 门禁运行，且是 APK 构建的前置条件。

## 第二层：对真实 dsh 的完整检查（本机跑）

```sh
node scripts/check-mobile-hooks.mjs          # 需要本机装有 dsh
```

逐个断言契约里声明的钩子**确实存在于当前 dsh 的前端产物**里。dsh 改了名字就红。

**每次升级 dsh 之后跑一次。** 若钩子有变，说明适配需要相应处理
（改适配层的选择器 / 改契约后再跑，见 `docs/mobile-adaptation.md`）。

确认新钩子在真实 dsh 上存在后，用 `--update-contract` 刷新契约：

```sh
node scripts/check-mobile-hooks.mjs --update-contract
```

## 第二层半：真机 WebView 验证（推荐先做这个）

```sh
node scripts/device-ui-verify.mjs --url "http://<本机 LAN IP>:38082/?token=<token>" --label adapted
```

它驱动**真机上 App 自己的 WebView**（经 adb 的 `webview_devtools_remote` socket），
所以注入是 App 干的、插件是 App 喂的、视口是真的手机视口 —— 只有"把 WebView 指到某个
地址"这一步是脚本做的。

> 历史记录（vendored 时代，已不适用）：当时 `[data-mobile-nav]` 出现 `frame / drawer-actions /
> explorer / session-log / fab` 五个标记。自研层只有 `data-handheld`，取值是
> `frame / backdrop / fab / toggle`。

前置：
1. 装 **debug** 包（CI 出 `dsh-handheld-debug` artifact）。只有 debuggable 才开
   `setWebContentsDebuggingEnabled` —— release 包拿不到 devtools socket。
2. `adb forward tcp:9222 localabstract:webview_devtools_remote_<app pid>`
3. 一个 WebView 能访问到的地址。`scripts/lan-proxy.mjs` 可以把靶子推到 LAN：
   ```sh
   dsh web --no-open --port 38083
   node scripts/lan-proxy.mjs --target 127.0.0.1:38083 --listen 0.0.0.0:38082
   ```

> ⚠️ **这条路径的覆盖范围有限，别当成端到端验证**：它绕开了 SSH 隧道，而 dsh 有
> browser-trust fence —— 明文 HTTP 下带非回环 `Origin` 的请求按非本机处理，**配置平面**
> （`settings.*` / `credentials.*` / `llm.discoverModels`）**强制仅回环**，所以 LAN 访问
> 下这些接口必 403（见 `docs/archive/dsh-protocol.md` §2.5）。它验证的是**适配的 DOM 层**：
> 插件有没有加载、有没有把桌面布局改成移动布局。**要验配置平面与完整数据流，必须在
> 设备上配好 SSH 连接**（App 的正常使用路径）。
>
> Android WebView 的 devtools 还有个脾气：**只有首条 `Page.navigate` 稳**，
> `Page.enable` / `Runtime.evaluate` 会间歇性挂住。脚本已按此调整顺序并容忍 enable 失败。

## 真机取证的几个坑（都踩过）

- **无障碍树给不出命中测试**：`uiautomator dump` 里的 `clickable=true` + 正常 `bounds`
  只说明布局对，不说明这一笔点得到（1.0.11 的 bug 就是「bounds 全对、点了没反应」）。
  要判断「点了有没有反应」，只能真的 `input tap` 再核对状态变化。
- **穿透要用「背后的元素」来测**：怀疑某层点不动时，去点它背后一个**有明显反应**的元素
  （输入框 → 软键盘弹起、会话行 → 切换），有反应就说明是穿透而不是被吃掉。
  1.0.11 的定位就是靠「点设置页背后的输入框，软键盘起来了」这一条。
- **抽屉开合有个无障碍树信号**：收起时宿主把侧栏渲染成 rail，树里会出现「打开侧边栏」
  这个按钮；展开时它消失。看不到侧栏内容时可以用它判断抽屉状态。
- **`logcat -t N` 会被三星自己的日志挤出窗口**：查本 App 的日志要用
  `adb logcat -d | grep DshHandheld`（`DiagLog.i` 的 tag 就是 `DshHandheld`），
  不要用行数截断 —— 这一条耽误过一次取证。
- **a11y 树偶尔会给出空/残缺的一棵**：dump 后先看可见节点数，太少就再 dump 一次
  （`/tmp/dump.py` 就是这么写的）。

## 隧道不变量：真机秒级检查

```sh
node scripts/device-tunnel-verify.mjs --serial 192.168.0.175:33199
```

上面几层管的是**适配对不对**，这一层管的是**隧道通不通**。隧道失效是**静默**的：
App 说 `connected`，页面却永远加载不完。2026-09-12 在真机上抓到过一整条这样的链
（假 connected / dbclient 进程泄漏 / 端口漂移），脚本把那次事故的不变量变成可重复的断言：

| 断言 | 防的是什么 |
|---|---|
| 隧道能过流量（HTTP 拿到状态行） | 把"本地端口能 connect"当成"隧道可用" —— 残留进程也在监听，connect 会成功而请求永远没回应 |
| 本地端口是 3080、没漂到 13080 | origin 一变，`localStorage` / 会话草稿就按 origin 分家 |
| dbclient 进程只有 1 个 | 重连不回收旧进程 → 幽灵占着端口和 SSH 会话 |
| 日志里没有 bind 失败、末次状态是 `connected` | 假 connected（正是用户看到的那一面） |

退出码 0/1 可直接用在脚本里；判定不了的项目（例如 logcat 缓冲区已滚过）显式标成
`–` 而不是失败 —— **把"没证据"当失败会让检查失去信任**。

## 第三层：真实页面渲染（需要浏览器能联网）

```sh
node scripts/ui-verify.mjs --base http://127.0.0.1:38082 --token <token>
```

用 CDP 驱动 Chromium，**手机视口 + 触屏 UA**，加载真实 dsh 页面，按 App 的方式注入
插件（`Page.addScriptToEvaluateOnNewDocument` ≈ `addDocumentStartJavaScript`，
`Fetch.fulfillRequest` ≈ `shouldInterceptRequest`），然后：

- **A/B 对照**：同一页面跑两遍（不注入 / 注入），差异本身就是适配生效的证据；
- 断言：插件加载成功、打出 `[data-handheld]` 标记、**无横向溢出**、页面已渲染；
- 产出：两张截图 + `report.json`。

零依赖（Node 22 原生 WebSocket + fetch），不需要 `npm install`。

> ⚠️ **本项目的构建沙箱里跑不了**：那里的 Chromium 发不出任何 HTTP 请求
> （`data:` URL 能渲染，`http://` 一律无响应，与网络服务进程/沙箱限制有关）。
> 需要一台浏览器能正常联网的机器 —— 通常就是你跑 dsh 的那台。
>
> 靶子起法（隔离端口，不影响你在用的实例）：
> ```sh
> dsh web --no-open --port 38082     # 从日志里取 token
> ```

## 为什么不用「把页面截图看一眼」

静默失效是这类问题的特征：抽屉不弹、元素重叠、横向溢出，截图能看出**现象**，
但说不出**是哪个钩子断了**。契约检查把「适配还工作吗」变成可断言的清单，
在 dsh 升级的那一刻就报警，而不是等你某天在手机上发现。

---

## 真机验证记录（2026-09-12，SM-G7810 / Android 13 / WebView 153）

设备 `192.168.0.186`，release 包 0.1.4，**经 SSH 隧道**（服务端视角为回环）。
逐项截图取证，全部通过：

| 环节 | 结果 |
|---|---|
| 连接屏 → 隧道 | `tunnel state: connecting → connected`，`已建立 http://127.0.0.1:3080` |
| token 自动获取 | `autoFetchToken: cmd#0 rc=len=43 → SUCCESS` |
| 页面加载 | `onPageFinished: 正式页面加载完成 → ack=true` |
| 数据面 | 工作区 `xsj`、会话列表、Agent 模式列表均从服务端载入 |
| **配置平面** | **设置页可用**（权限/语言/外观/字号/对话显示） |
| **凭据平面** | **模型页可用**（提供方列表、编辑/删除、添加提供方） |
| 移动端适配 | 抽屉式侧栏（非常驻）、底部弹出选择器、无横向溢出 |

其中**配置平面与凭据平面是这次验证的关键**：它们属于 `PRIVILEGED_METHODS`，
**LAN 直连必然 403**（`docs/archive/dsh-protocol.md` §2.5），只有经 SSH 端口转发才通。
这一条正是整个项目"为什么必须走 SSH 隧道"的实证。

### 同批顺带确认

- `autoFetchToken` 在 186 上**成功**（该设备历史上曾恒为 `rc=null`，看来不是必现故障）；
- 本轮无 `FATAL EXCEPTION`、无 ANR。

### 仍未覆盖

- 终端模式（TuiActivity）本轮未走查；
- **换端口后的页面跟随**：`device-tunnel-verify.mjs` 只断言端口没漂移，
  没断言 WebView 在 `onLocalBaseChanged` 之后真的重新加载并恢复视图状态；
- 应用内 JS 对话框（若有）。

### 2026-09-12 之后查到的隧道缺陷（已在 0.1.5 修）

同一批真机上（SM-G7810 / Android 13）后来查出隧道重建路径本身是坏的，与适配无关：

- 熄屏后**整个 App 会被 Android 冻结**（`/proc/<pid>/cgroup` → `freezer:/frozen`），
  而 **dbclient 子进程继承同一个 cgroup，一起被冻在 `connect()` 里** —— 解冻时
  三个残留进程同时报 `Connect failed: Software caused connection abort`。
- 于是看门狗不跑、隧道必死；而 `isAlive()` 只问"进程活着 + 端口能连"，
  残留进程正好能满足它，**假 connected 会一直持续**（实测 1h33m 无日志）。
- 每次重连都漏一个 dbclient（实测两轮各 3 个），它们占着 3080 让新进程 bind 失败
  （`Address already in use` ×2），并把端口从 3080 挤到 13080。

修法与不变量见上文「隧道不变量」一节。
