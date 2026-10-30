# 侧栏 → 悬浮按钮（App 侧注入）

> 2026-10-30 用户要求：「侧边栏改成悬浮按钮显示，希望在 app 端注入」。
> 这**推翻了 2026-10-15 的零注入决定**，因此本文件记录：为什么改、怎么实现、踩过什么坑、怎么退回。

## 1. 方向变更

| 日期 | 决定 | 原因 |
|---|---|---|
| 2026-10-15 | 零注入，移除全部注入层 | 注入层反复被宿主升级打断，维护成本高 |
| **2026-10-30** | **重新引入，但只此一项** | 用户明确要求把侧栏改成悬浮按钮 |

实现上刻意保持克制：**不改页面内容、不改宿主代码、不动其它界面**，只调整侧栏的呈现方式。
出问题可以关开关（`inject_sidebar_fab=false`）立刻回到原版。

## 2. 宿主结构（真机实测）

```
.pI_x6G_frame            display:grid
  inline: grid-template-columns: 56px minmax(0px,1fr) minmax(0px,0px)
  ├─ .pI_x6G_sidebarCol   图标栏（收起 56px / 展开 280px）
  ├─ .pI_x6G_centerCol    内容
  ├─ .pI_x6G_rightbarCol  右侧面板
  ├─ .pI_x6G_bottomRow
  ├─ .pI_x6G_overlayLayer
  └─ .pI_x6G_handle
```

侧栏内部：`[class*=hHd-Xa_root]`，收起时带 `collapsed` 类；
开关按钮的 `aria-label` 在「打开侧边栏」/「收起侧边栏」之间切换。

## 3. 实现

1. **CSS**：把侧栏那一列压成 0（内容吃满宽度）；侧栏改 `position:fixed` + 默认
   `translateX(-102%)`，展开时滑入（280px）。
2. **JS**：
   - 只把 `grid-template-columns` 的**第一列**替换成 `0px`，其余列原样保留
     （写死整条会把宿主右侧面板一起压没）；
   - 开合状态**跟随宿主**：读 rail 的 `collapsed` 类 → 给 `<html>` 加/去 `dshhh-open`；
   - 切换 = 找到宿主自己的开关按钮并 `.click()`，不直接改宿主状态；
   - 悬浮按钮固定在**最左上角** `left:6px; top:6px`（40px）；实测直接放会盖住宿主左上角的
  「智能体团队」入口（x=31,w=28），因此同时给 `header` 加 `padding-left:56px` 让位 ——
  按钮与宿主入口都完整可见（改后「智能体团队」x=66、对话标签 x=64）；
- **消息区左右留白**：宿主默认 `padding: 32px`（消息列 313/384，浪费约 18.5%）；
  注入把它改成 **12px** → 列宽 313 → **360**。
  用 JS 而非 CSS：目标元素是「消息列」的**父元素**（跨层级），CSS 只能靠 `:has()`
  （WebView 版本不保证），所以走 JS 定位 + 行内样式 + 每 500ms 自愈；
- **左右对称要单独处理**：该滚动容器自带 **7px 竖向滚动条**，容器只有 377 而非 384，
  于是「左 12 / 右 19」看着右宽。做法两层互为兜底：① 隐藏滚动条；
  ② 按实测差值补偿 `padR = max(4, 12 - (innerWidth - 容器宽))`。
  **实测发现 ① 不改变布局宽度（容器仍 377），真正管用的是 ②** —— 补偿后左右各 12px；
- **选择器不带 CSS 哈希前缀**：宿主每次升级都会重算哈希（历史上 `_row_jhda5_16` → `_row_k7ery_26`），
  统一用 `[class*=frame]` / `[class*=sidebarCol]` 这类只匹配名字部分的写法；
   - 每 500ms 自愈一次（SPA 重渲染后重新绑定观察器与状态）。

## 4. 踩过的坑（都让注入**静默失效**）

| # | 坑 | 现象 | 修法 |
|---|---|---|---|
| 1 | 侧栏 `position:fixed` 脱离网格流 | 其余列整体前移，内容列被挤成 **0 宽、白屏** | 显式给其余子元素指定 `grid-column` |
| 2 | 把整条 grid 写死成 `0 1fr 0` | 宿主右侧面板被压成 0 | 只替换第一列，保留其余列 |
| 3 | Kotlin/JS 双层转义 | 生成出 `"[aria-label=\" + X + "\"]"`，整段变成一个字符串 → 语法错误 | 不用带引号的选择器，改为遍历 `[aria-label]` 比较文本 |
| 4 | 延迟构建分支漏绑观察器 | 元素建出来了但样式/状态不生效 | `start()` 统一承担 build + 绑定 |
| 5 | `evaluateJavascript(..., null)` | **语法/运行时错误被完全吞掉**，只看到「没效果」 | 传回调，把结果写进 DiagLog |
| 6 | `DiagLog` 未 import | debug/release 编译失败 | `import com.dshhandheld.diag.DiagLog` |

第 5 条是这次排查成本最高的：日志里只有「已注入」，看不出失败。现在 DiagLog 会有
`inject: 侧栏悬浮按钮 → "ok"` 这样的结果回执。

## 5. 防回归

`tools/check-inject-js.py`：把 Kotlin 里拼接的注入 JS **还原出来**交给 `node --check`，
并额外断言 4 条设计约束（不用带引号的选择器 / 只改第一列 / 有回调日志 / 有 DiagLog import）。
失败退出非 0，可直接接进 CI。

## 6. 真机验证

```
inject: 侧栏悬浮按钮已注入
inject: 侧栏悬浮按钮 → "ok"
元素: fab/style/scrim 均存在，flag=v5
网格: grid-template-columns: 0px minmax(0px, 1fr) minmax(0px, 0px) !important
点 FAB: rail 的 collapsed 消失、newSessionW 36→252（宿主确实展开）、html.dshhh-open ✓
```

## 7. 退回方式

```bash
# 关掉注入，回到原版页面（改完重启 App 生效）
adb shell run-as com.dshhandheld.app \
  sed -i 's/inject_sidebar_fab" value="true"/inject_sidebar_fab" value="false"/' \
  shared_prefs/dsh-handheld.xml
```

## 8. 已知不足 / 待改进

- ~~悬浮按钮与消息底部操作条重叠~~ → 已改为左上角（2026-10-30）。
- 左上角按钮占用了头部起始位置，宿主头部内容整体右移 36px；头部较窄的设备上，
  「5 个后台任务」这类长标签可能更容易被压缩。
- 抽屉宽度固定 280px（≈73% 屏宽），未做手势拖动调宽。
- 遮罩在深色主题下视觉区分度不高。
