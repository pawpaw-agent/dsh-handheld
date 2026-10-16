# 上游 Issue 草稿：窄屏（手机）下展开侧栏会把内容区挤到 31%

> 状态：**草稿**，等设备回来后补一张真机截图与实测宽度即可提交。
> 来源：本仓库 `docs/known-issues.md` 第二十一节第四轮补充。

## 环境

| 项 | 值 |
|---|---|
| dsh | 0.2.0-rc.2 |
| 客户端 | 本仓库 App（Android WebView，全屏加载 dsh 官方前端） |
| 设备 | Samsung SM-S9280（Galaxy S24 Ultra）/ Android 16 / API 36 |
| 屏幕 | 1440×3120 物理像素 |
| **CSS 视口** | **384×832**，dpr 3.75 |
| 指针 | `(pointer: coarse)` = true，`(hover: none)` = true |

## 现象

窄屏上**侧栏展开后仍按桌面宽度计算**，把内容区挤到无法使用：

| 场景（视口 384px） | 侧栏 | 内容区 | 内容区占比 |
|---|---|---|---|
| 侧栏收起（图标轨道） | 56px | 328px | 85% |
| **侧栏展开** | **264px** | **120px** | **31%** |
| 侧栏展开 + 右栏 | 264px | 120px | 31%（右栏实际打不开） |

对照桌面 1440px：侧栏展开后内容区仍有 1176px（82%）。

也就是说：**同样的 264px，在桌面上占 18%，在手机上占 69%。**

## 定位（读 `dsh-client-ui-layout/lib/client.js`）

列宽算法：

```js
function computeColumns(viewport, sidebar, rightbar, collapsedWidth = 56) {
  const s = sidebar === 0 ? collapsedWidth : clampWidth(sidebar, 264, 420);
  const available = viewport - s - 400;
  const r = rightbar === 0 || available < 300
    ? 0
    : Math.min(available, clampWidth(rightbar, 300, viewport * RIGHTBAR_MAX_RATIO));
  return { sidebar: s, center: Math.max(0, viewport - s - r), rightbar: r };
}
```

相关常量：`collapsedWidth = 56`、侧栏 clamp **[264, 420]**、`RIGHTBAR_MAX_RATIO = 0.7`、
`SIDEBAR_AUTO_COLLAPSE = 1024`。

窄屏分支已经存在：

```js
const narrow = viewport < SIDEBAR_AUTO_COLLAPSE;            // 384 < 1024 => true
const sidebarCollapsed = narrow ? !layoutInfo.narrowExpanded : layoutInfo.sidebar === 0;
const sidebarPreference = sidebarCollapsed ? 0 : layoutInfo.sidebar === 0 ? 280 : layoutInfo.sidebar;
```

所以**折叠逻辑是对的**（窄屏默认不挤占），问题只在**展开时的宽度下限 264px**：
它在窄视口上必然吃掉大部分宽度，而 `clampWidth` 的下限是硬编码的，没有按视口缩放。

另外 `available = viewport - s - 400` 里的 `400` 也是固定值，在 384px 视口上
`available` 恒为负，导致右栏永远打不开 —— 这个行为本身合理，但它说明这些常量
都没有为窄屏重新标定过。

**宿主布局包内没有任何宽度相关的媒体查询**（只有 `prefers-reduced-motion` /
`prefers-reduced-transparency`），所以这不是配置问题。

## 建议

最小改动（不改结构，只让窄屏不挤占）：

```js
// narrow 时给侧栏一个与视口相关的下限/上限，而不是硬编码 264
const minSidebar = narrow ? Math.min(264, Math.round(viewport * 0.62)) : 264;
const s = sidebar === 0 ? collapsedWidth : clampWidth(sidebar, minSidebar, 420);
```

按这个改法，384px 视口下侧栏 ≈ 238px、内容区 ≈ 146px —— 仍然不宽，但侧栏不再占 69%，
且**保留了「展开就是展开」的语义**，不做成抽屉也能用。

若更倾向抽屉语义（展开时覆盖内容而非挤压），那是更大的改动，但可能更符合手机上
对侧栏的预期，值得一并讨论。

## 为什么我们没有用样式覆盖绕开

我们验证过：列宽是**内联样式**落上去的

```js
gridTemplateColumns: `${cols.sidebar}px minmax(...) minmax(...)`
```

而内联样式在层叠中优先于样式表规则（含 `!important`），所以**纯 CSS 无法覆盖侧栏宽度**。
我们也核过 `--dsh-windows-sidebar-width`：它只在
`document.documentElement.hasAttribute("data-windows-titlebar")` 时设置，
Android 上不存在该属性 —— 那条通道同样不可用。

所以这个问题**只能从计算列宽的那段 JS 解决**，也就是需要上游改动。

## 补充：窄屏上还有一批触控目标小于推荐值

同一环境下实测：视口内 30/30 个可点元素的最小边 < 44px（最小 22px，如统计胶囊；
顶栏按钮 28×28、页签 25×25、输入区按钮 28×28/34×34）。我们用样式表补丁把大部分抬到
40px 做了验证，**证明纯 CSS 这一层是有效的**；但胶囊那类受 `overflow:hidden` 细带
限制、页签宽度受 `display:grid` 列固有宽度限制，**需要宿主结构调整**，故一并提出。

（如果需要，我们可以把这份触控目标的完整测量表附上。）
