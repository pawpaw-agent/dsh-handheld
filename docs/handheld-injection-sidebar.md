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
   - 悬浮按钮固定 `left:10px; bottom:150px`（放在输入区上方，避免压住输入区的 + 号）；
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

- 悬浮按钮与消息底部操作条（点赞/分享那一行）在部分滚动位置会重叠；
  可考虑改成「随页面滚动收起」或挪到左下角更靠下（但会压输入区）。
- 抽屉宽度固定 280px（≈73% 屏宽），未做手势拖动调宽。
- 遮罩在深色主题下视觉区分度不高。
