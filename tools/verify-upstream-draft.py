#!/usr/bin/env python3
"""核实上游 issue 草稿里的每个数字与引用（空白归一化后再比对）。

教训：本脚本第一版对源码做逐字匹配，把「换行缩进差异」误报为 FAIL ——
校验器自己的判定也要经得起检查，所以这里统一先归一化空白。
"""
import re, sys, os

BASE = "/home/xsj/.local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
LAYOUT = os.path.join(BASE, "dsh-client-ui-layout/lib/client.js")
s = open(LAYOUT, encoding="utf-8", errors="replace").read()
flat = re.sub(r"\s+", " ", s)

ok = True
def check(name, cond, extra=""):
    global ok
    if not cond: ok = False
    print("  [%s] %s%s" % ("OK " if cond else "FAIL", name, ("  " + extra) if extra else ""))

def has(fragment):
    """空白归一化后的包含判断。"""
    return re.sub(r"\s+", " ", fragment).strip() in flat

print("=== 1. 引用的事实与源码一致（空白归一化）===")
check("computeColumns 存在", has("function computeColumns(viewport, sidebar, rightbar, collapsedWidth = 56)"))
check("clampWidth 定义", has("function clampWidth(px, min, max) { return Math.min(max, Math.max(min, Math.round(px))); }"))
check("collapsedWidth 默认 56", has("collapsedWidth = 56"))
check("侧栏 clamp [264,420]", has("clampWidth(sidebar, 264, 420)"))
check("RIGHTBAR_MAX_RATIO = .7", has("RIGHTBAR_MAX_RATIO = .7"))
check("SIDEBAR_AUTO_COLLAPSE = 1024", has("SIDEBAR_AUTO_COLLAPSE = 1024"))
check("available 里的 400", has("const available = viewport - s - 400"))
check("available < 300 判据", has("rightbar === 0 || available < 300 ? 0"))
check("narrow 定义", has("const narrow = viewport < SIDEBAR_AUTO_COLLAPSE"))
check("sidebarPreference 三元", has("const sidebarPreference = sidebarCollapsed ? 0 : layoutInfo.sidebar === 0 ? 280 : layoutInfo.sidebar"))
check("内联 gridTemplateColumns", has("gridTemplateColumns: `${cols.sidebar}px minmax("))
check("windows-sidebar-width 条件", has('hasAttribute("data-windows-titlebar")'))
check("narrowExpanded 切换条件", has("if (d.layoutInfo.viewportWidth < 1024) d.layoutInfo.narrowExpanded = !d.layoutInfo.narrowExpanded"))

print()
print("=== 2. 媒体查询（草稿称「无宽度媒体查询」）===")
mq = re.findall(r"@media[^{]{0,50}", s)
width_mq = [m for m in mq if re.search(r"width", m)]
check("布局包无宽度媒体查询", len(width_mq) == 0, "实际: %s" % mq)

print()
print("=== 3. 算术核实 ===")
def clamp(v, lo, hi): return max(lo, min(hi, v))
def compute(viewport, sidebar, rightbar, collapsed=56):
    sb = collapsed if sidebar == 0 else clamp(sidebar, 264, 420)
    available = viewport - sb - 400
    rb = 0 if (rightbar == 0 or available < 300) else min(available, clamp(rightbar, 300, viewport * 0.7))
    return sb, max(0, viewport - sb - rb), rb

cases = [
    (384, 0, 0, (56, 328, 0), "384 收起"),
    (384, 264, 0, (264, 120, 0), "384 展开"),
    (384, 264, 300, (264, 120, 0), "384 展开+右栏"),
    (1440, 264, 0, (264, 1176, 0), "1440 展开"),
]
for vp, sb_in, rb_in, expect, label in cases:
    got = compute(vp, sb_in, rb_in)
    check("%s => 侧栏%d/中心%d/右栏%d" % (label, expect[0], expect[1], expect[2]), got == expect, "实算 %s" % str(got))

check("384 展开中心占比 31%", round(100*120/384) == 31, "%d%%" % round(100*120/384))
check("1440 展开中心占比 82%", round(100*1176/1440) == 82, "%d%%" % round(100*1176/1440))
check("264 在桌面占 18%", round(100*264/1440) == 18, "%d%%" % round(100*264/1440))
check("264 在手机占 69%", round(100*264/384) == 69, "%d%%" % round(100*264/384))

print()
print("=== 4. 建议里的算式 ===")
ms = min(264, round(384 * 0.62))
check("minSidebar(384, 0.62) = 238", ms == 238, "实算 %d" % ms)
check("内容区 = 146", 384 - ms == 146, "实算 %d" % (384 - ms))
check("侧栏占比降到 62%", round(100*ms/384) == 62, "实算 %d%%" % round(100*ms/384))
check("改善幅度 69% -> 62%（至少方向对）", ms < 264)

print()
print("总体:", "全部通过 ✓" if ok else "有 FAIL ✗")
sys.exit(0 if ok else 1)
