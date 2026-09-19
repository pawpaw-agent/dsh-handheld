**手机端「把留白还给内容」的一版，外加一条协议上的坑。** 全部改动都在真机
（SM-S9280 / Android 16，1440×3120 @ dpr 3.75 → 视口 384×832 CSS px）上**逐项量过**，
不留「我觉得好了」。

## 📐 顶部与两侧：那几百像素原来是白留的

用户连报两条：「提升两侧和顶部空间利用」「顶部还是大片留白」。真机逐层量下来，
留白一共有**三个来源**，各修一处：

### ① 两侧 32px + 顶部 76px（宿主自己的内边距）

从**安装产物**里核对出的规则（不是猜）：

```
.EvIC1a_scroll { padding: 16px calc(var(--dsh-composer-side-clearance) + 16px) }   /* 左右各 32px */
.wSkVaW_header { min-height: 76px; padding: 10px 28px 0 20px }
```

384px 的屏幕上，正文两侧被吃掉 64px（全宽的 17%）。改成正文左右 **12px**、上边距 8px
→ **多出 40px 内容宽度**。

顶部那条更绕：把 header 的 `min-height` 从 76 压到 52 **没用** —— 真机截图里页签一动不动。
那 76px 是内容算出来的：`padding-top 10 + 标题行 30 + 页签 margin-top 10 + 页签本身 25`。
所以只能动内容本身：`padding-top 10→4`、标题行 `min-height 30→24`、页签 `margin-top 10→4`
（页签自己的 `padding-bottom: 9` **不动** —— 那是手指的目标区）→ 约 **−18px**。
两条合起来，顶部一共省下约 **26px**，两侧多 40px。

### ② 挖孔的 34px：页面 viewport 自己内缩了

`DisplayCutout{insets=Rect(0,128,0,0)}` → 屏幕上缘 **128 设备 px = 34 CSS px**，
和截图里标题上方那块空白**完全相等**。根因在首页 HTML：

```html
<meta name="viewport" content="width=device-width, initial-scale=1" />   <!-- 没有 viewport-fit=cover -->
```

于是 Chromium 按挖孔把 viewport 整体内缩。改法：`mobile-bootstrap.js`（**document-start**
注入，唯一能赶在宿主那条 meta 之前动手的地方）补上 `viewport-fit=cover`、删掉多余的第二条 meta，
并给 `<html>` 打 `data-dsh-cover="1"`；适配层据此把会话头 `padding-top` 设成 14px
（挖孔本体约 11px，14px 既避开摄像头又比「34px viewport 内缩 + 4px」省约 20px）。

真机实测 `innerH`：**798 → 832**（= 3120 ÷ 3.75，整屏 ✓）。

### ③ 补了 cover 之后，宿主外壳反而把 35px 又加回来了

这一条是**我自己引入的回归**，也是用户说的「顶部还是大片留白」的真身：`viewport-fit=cover`
生效后，宿主 `dsh-client-ui-layout` 的外壳开始**用 JS 内联**写 `padding-top: env(safe-area-inset-top)`，
本机实测 **35px** —— 而挖孔本体只有约 11px。**净收益为 0**。

定位方式不是猜：插件里加了 `layout-diag`，一次性把**祖先链**（每层的 `top/h/paddingTop/class`）
和 `env(safe-area-inset-top)` 探针打回来，一次装包就指到了具体元素。真机前后对照：

```
                        改前                        改后
pI_x6G_frame   top=0  h=832  padTop=35px     padTop=12px     ← 压掉 23px
wSkVaW_root    top=35 h=797                  top=12 h=820    ← 会话区 +23px
header         top=35 h=71  padTop=14px      top=12 h=59  padTop=2px
titleRow       top=49                        top=14          ← 上移 35px
```

（内联样式用 `!important` 压得住 ✓；12px 仍在挖孔下缘 11px 之下 ✓。契约补了 `_frame` 钩子。）

### ④ 右侧恒定多出的 10px：宿主的滚动条槽

用户第二次报「两侧还是不对称」。逐块量下来，**每一块内容的右侧都比左侧多约 10px**：

| 元素 | 左 | 右 | Δ |
|---|---|---|---|
| 输入卡（边框） | 27.5 | 37.3 | +9.9 |
| 待办卡 | 44.3 | 54.1 | +9.9 |
| 统计行 | 41.9 | 51.5 | +9.6 |
| 头部两个按钮 | 18.1 | 18.7 | +0.5 ✓ |

差值**恒定**就说明不是内边距写错，而是右侧被固定吃掉一块。从安装产物里核对到宿主原话：

```css
.wSkVaW_scrollBody { scrollbar-gutter: stable; margin-right: 2px; overflow-y: auto }
--dsh-scrollbar-width: 8px
```

8px 的滚动条槽 + 2px 外边距 = **10px** ✓ 与实测分毫不差（左侧 = 我们 12px，右侧 = 12+8+2 = 22，
两侧再各叠宿主 16px 的 composer clearance → 28 / 38）。

改法：把槽宽算进右内边距 —— 滚动条本体保留（它是位置指示器），内容同时多回 10px：

```css
/* ≤560px */
[data-handheld="frame"] [data-phase] [class*="_scrollBody"] { margin-right: 0 !important; padding-right: 4px !important }
/* 右 = 8(槽) + 4 = 12 = 左 */
```

两级验证：

- **引擎级**（新增的本地小回环，真 Chromium，**故意不带** `--hide-scrollbars`，384px）：
  只上宿主规则 → 左 0 / 右 10（Δ 10.00，复现真机那 10px，这是负对照）；
  再叠适配层现有那条（左右各 12px）→ 左 12 / 右 22（对上真机的 22 vs 12）；
  加上新修法 → **左 12.00 / 右 12.00，Δ 0** ✓；
- **真机**（0.1.14 + 插件 rev 1.0.34，`side-diag` 实测）：`scrollBody: padL=12px padR=4px marR=0px
  gutter=stable scrollbarW=8`、**统计行 left=28 right=28** ✓；截图量：输入卡 **27.5 / 27.5（Δ 0.0，
  改前 +9.9）**、统计行 41.9 / 41.3。统计行可用宽度 318 → **328px**。

### 头部左右不对称

用户问「和左边不对称？」。量化办法是**量截图**（标题行最左/最右深色像素距屏幕边缘 ÷ 3.75）：
左 14.1px / 右 18.9px，差 4.8px，而且**改之前就在**。两个按钮的盒子分别在 8px 与 12px：
左端是插件自己的「打开目录」（`left: 8px`），右端是宿主右侧栏按钮
（`padding-right: 28` 叠加 `.wSkVaW_headerCorner{margin-right:-16px}` = 12px）。

改：左端 `left: 8 → 12px`，标题让位随之 `padding-left: 20 → 24px` → 实测 **18.1 vs 19.2px（差 1.1px）** ✓。

顺带修掉上一版为此踩的坑：我曾把 header 的 `padding-right` 从宿主 28px 压到 12px，
结果那个 `-16px` 的负 margin 让右端按钮落到了 **−4px**（几乎贴边，用户报「右侧边栏按钮太靠右了」）。
现在 `padding-right` **不覆盖**，两侧的空间优化只作用在正文与统计行上。

### 头部左右按钮还要在同一条**水平线**上

用户接着问「两边上下不对称？」。一量就看出是我自己前面那次「顶部省 18px」留下的：

```
改前   左（目录按钮）图标中心 y = 38.0      右（宿主角落按钮）图标中心 y = 27.9     差 10.1px
改后   左 27.9                              右 27.9                              差 0.0 ✓
```

原因是目录按钮 `position: absolute`，`top` 写死 **12px**（相对 header 顶边），而它该对齐的
是**标题行**上沿 —— 也就是 header 的 `padding-top`。我把 `padding-top` 从 10 压到 4（有 cover
时 2），死值 12px 却留在原处，于是按钮整整矮了 10px。

改法：把「标题行上沿」做成**一个 CSS 变量**，在 header 上设置、按钮作为其后代继承 ——
两处永远同一个值（`≤560px` 为 4px、有 cover 时 2px、兜底 12px = 宿主默认布局下的原值）：

```css
header:has([class*="_titleRow"]) { --dsh-handheld-head-top: 4px; padding-top: var(--dsh-handheld-head-top) }
[data-handheld="toggle"]         { top: var(--dsh-handheld-head-top, 12px) }
```

`layout-diag` 顺带把两个按钮的盒子与图标中心都报出来（`toggle` / `toggleIcon` / `cornerIcon`，
实测两个图标 `mid` 都是 **28**），下次问「对没对齐」直接看数，不必再量截图。

### 工作区（文件夹）行上的「⋯」与「+」：宿主只在 hover 时显示，手机上等于没有

用户：「工作区文件夹上的 … 和 + 按钮是隐藏的，需要改成不隐藏」。宿主
（`client/ui-workspace` 的 `rows/Rows.module.css`）原话：

```css
.rowActions { display: none }
.projectRow:hover .rowActions, .sessionRow:hover .rowActions, .projectRow.menuOpen .rowActions { display: inline-flex }
```

手机上**根本没有 hover** → 行上那两颗按钮永远不出现。而它们做的是实打实的事：
**⋯ = 重命名 / 删除工作区，+ = 在这个工作区里开新会话** —— 没有它们，手机端没法管理工作区。

改法（≤560px）：`[class*="_projectRow"] [class*="_rowActions"]` 与 `[class*="_sessionRow"] [class*="_rowActions"]`
都恒为 `inline-flex`（用户接着补一句：「会话的 … 按钮也不要隐藏」）。同一族的 hover-only 还有
**工作区的展开/折叠箭头** `.projectRow .chevron{display:none}`（hover 时才用箭头换掉文件夹图标）——
也一并恒显示，文件夹图标保留（全屏抽屉里有位置，两个都给）。时间戳那颗 `_time` 不动 ——
它与按钮是并排的 flex 项，不会叠在一起；标题是 `flex:1 + ellipsis`，宽度不够先省略标题。
真机实测：会话行、工作区行都露出各自的 ⋯，点开菜单可用（工作区：重命名/删除工作区；
会话：重命名/分叉会话/归档会话）✓。标题宽度的代价在本地小回环里量过（宿主真实 CSS + 真实类名，
384px）：宿主下 `display:none`（手机上永远看不见）→ 覆盖后 `display:flex`，标题 261 → 245px。

### 左侧边栏展开即全屏 + 里面再没有「藏起来的按钮」

用户：「左侧边栏展开变成全屏 把……里面的按钮都展开」。两件事：

1. **展开即全屏**：抽屉宽度从 `340px / max 86vw` 改成 `100vw`（`max-width: none`），
   收起态改成 `left: calc(-1 * (100vw + 12px))`；
2. **把空间真的用起来**（用户紧接着报：「没有利用好空间」）—— 全屏只是第一步，里面还有两处浪费：
   - **宿主侧栏自己那层根节点**（`SidebarRoot`）挂着内联 `style={width}`（来自 layout 的
     `cols.sidebar`，本机实测 **~265px**）——它不跟着我们那条 100vw 的列走，右边于是空出
     **~119px**。第一版只用 CSS（`[class*="_sidebarCol"] > * { width: 100% !important }`）
     去压它，**没生效**：根节点被槽运行时包了一层，那层才是直接子元素。改成插件按几何找
     ——列的第一/第二层子元素里凡是带**像素内联宽度**的就是它，改写 `width:100%`（React 按
     自己的 prop 做 diff，值没变就不会把 DOM 改回去；再挂一个 MutationObserver 观察
     `style` 属性兜底），CSS 那条留着当第一道；
   - **尺寸还是桌面档**：会话行 32px、工作区行 34px、标题 14px、行内图标 16px、新会话按钮 36px ——
     在一整屏白底上又小又空，触摸目标也只有 32dp（Material 建议 ≥48dp）。按手指档抬一档：
     行 **44px**、标题 **15px**、时间 13px、行内图标 **20px**、新会话 **44px**、面板行（设置）46px。
     选择器**限定在行内**：宿主三个模块都有 `_iconButton`（侧栏品牌那颗 28px、工作区行里 16px、
     浏览器工具条里又是另一个），不限定会把品牌那颗挤小。

3. **里面再没有藏起来的按钮** —— 逐个交代：
   - 工作区行：`⋯`（重命名/删除）、`+`（开新会话）、**展开/折叠箭头**（原本只有 hover 才出现）→ 全部恒显示；
   - 会话行：`⋯`（重命名/分叉/归档）→ 恒显示；
   - 工作区标题行那颗 `+`（添加工作区）—— **这颗是我们自己藏的**（`aria-label="添加工作区"`），
     现在放回来。依据：它开的目录选择器由宿主的 `directory-picker-auto` 决定，那条规则要求
     「本地回环绑定 + 非 SSH 启动 + 有可服务的显示会话（linux 要 DISPLAY/WAYLAND_DISPLAY +
     zenity/kdialog）」；这台宿主已经确认没有桌面（present 文件时宿主自己回「此主机没有可用的桌面」），
     所以它必然落到 `browse`（网页版目录浏览器）→ 手机上点得动 ✓。
   - 时间戳、文件夹图标、搜索框、视图选项等本来就可见的，一个没动。

## 📊 底部统计行：一行到底、不再被省略号吃掉

用户明确要「一行」。上一版把 `flex-wrap: wrap` 当兜底 —— 真机上直接变成两行，因为小回环算出的
22px 余量抵不住真机字体（Roboto / Noto Sans CJK）的宽度差。现在的形态：

- `flex-wrap: nowrap`；字号 11.5 → **10px**、内边距 12 → **5px**、分隔符 2 → **1px**、gap 4 → **3px**；
- 每个胶囊 `white-space: nowrap` + `min-width: 0` + `text-overflow: ellipsis`
  ——万一还是装不下，**宁可尾部省略也不多占一行**；
- ≤380px 再降一档（9.5px / 4px）。

小回环（`scripts/composer-stats-lab.mjs`）的 fixture 换成**真机当前的数字**
（`101 轮 314 步 · 179 tok/s` / `77.4M tok · 缓存命中 98%`），阈值 40 → **45px**，
并把「小回环余量 ≈ 真机余量 + 32px」这条偏差记在脚本里 —— 之前拿「估」当「测」，翻过一次车。

真机一次性几何诊断（`stats-diag`）现在两条胶囊都是 `scrollW == clientW`（138/138、142/142）✓，
即**没有被裁掉任何像素**。

## ⏱ 事件流不再可能拖住审批（waterfall 要回话）

App 为了「后台也能收到任务完成」而订阅了 `/api/remote.mux` 的 `$events` —— 于是它成了 Host
事件流的**第二个客户端**。转发清单里有两类 **waterfall** 事件：`approval/request`、
`user-questions/request`，而 `forwardWaterfall` 会**等客户端回 result 才继续**。

我的客户端此前**只读不回**：这几轮没触发审批，所以没观察到卡顿 —— 但这属于「没测到的运气」。
现在收到 waterfall 就明确回一个 `{kind:"next"}`（=「我不处理，交给下一个」，正是页面客户端
没命中监听器时的语义 `REMOTE_EVENT_NEXT`）：

```
POST /api/$events/result
{"type":"client-request","rpcId":…,"method":"$events/result",
 "payload":{"args":{"clientId":…,"eventId":…,"outcome":{"kind":"next"}}}}
```

用最小的 HTTP/1.1 POST 实现（不引依赖），回话失败最坏也只是让 Host 继续等，与不回一样。

### 顺带说清一条报错

`历史加载失败：api gateway: Remote stream WebSocket closed` 是**页面侧**的报错：隧道当时是断的
（App 侧 `ECONNREFUSED (127.0.0.1:3080)`），页面自己的 mux 连接同样会断，在飞的流一起失败 ——
与新增的事件流订阅无关（心跳按 socket 记，互不影响），隧道恢复后即好。

## 🩺 诊断：判据从「我估」变成「真机实测」

这一版新增四组**一次性**诊断（只在页面 load 时打，常驻零成本），全部由 App 认领后写进
`adb logcat -s DshApp`：

- `viewport-diag`：`innerW/innerH/screenH/dpr/meta` —— 确认 viewport 到底有没有被内缩；
- `stats-diag`：统计行与每个胶囊的 `scrollWidth/clientWidth` + 计算字号 —— 被吃掉多少，这里就有多少；
- `layout-diag`：祖先链的 `top/h/paddingTop/class` + `env(safe-area-inset-top)` 探针
  —— 留白属于谁，一次装包定位到元素；
- `side-diag`：左右内缩到底谁贡献的 —— 滚动体的 `padding/margin/gutter`，以及
  `offsetWidth − clientWidth`（**真实滚动条宽度**，实测 8px）。

## 🔩 工程侧

- CI 的 `mobile-contract` 增加 `node --check`：注入 bundle 的 CSS 写在**模板字面量**里，
  注释里混一个反引号就会把模板提前结束、语法直接坏，而「读文本」的契约检查看不出来
  （这一坑踩了四次）；
- 契约补钩子：`_scroll`、`_tabs`、`_frame`、`_scrollBody`、`data-dsh-cover`
  （`verifiedAgainst.dsh = 0.1.5-rc.1`，即隧道那头实际装的那份产物）；
- 盒模型这类问题一律先复现再改：留白/对称各有一次是**小回环先复现、真机再确认**
  （统计行的小回环、这次新加的滚动条槽小回环），负对照不复现就不认结论；
- 右侧栏按钮的坑写进注释：`headerCorner` 带 `margin-right: -16px`，别再去动 header 的右内边距。

---

**升级提示**：签名与 0.1.13 相同（`a9401663…`），可直接覆盖安装；versionCode 41。
