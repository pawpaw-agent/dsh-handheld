#!/usr/bin/env python3
"""生成移动端触控补丁，并对着**未打补丁的** dist CSS 核验选择器归属。

用法:
  python3 tools/gen-mobile-touch-patch.py            # 只生成到 docs/
  python3 tools/gen-mobile-touch-patch.py --apply    # 追加进 dsh dist CSS（自动备份 .orig）
  python3 tools/gen-mobile-touch-patch.py --revert   # 从 .orig 还原
"""
import os, sys, shutil

BASE = "/home/xsj/.local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
DIST_CSS = os.path.join(BASE, "dsh-web-frontend/dist/assets/index-BPHePDI_.css")
DIST_ORIG = DIST_CSS + ".orig"
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(REPO, "docs/mobile-touch-patch.css")
MARK = "/* dsh-handheld mobile touch patch"

GROUPS = [
    ("紧凑按钮（顶栏「更多操作」「打开右侧边栏」）：高 28 -> 40",
     [("_sm_1rv3m_28", False)], "hw"),
    ("侧栏图标（开合 / 新建 / 插件）：36 -> 40（行距 48，留 8px）",
     [("hHd-Xa_iconButton", True), ("hHd-Xa_toggle", True), ("hHd-Xa_newSession", True), ("hHd-Xa_panelRow", True)], "wh"),
    ("面板图标（右栏收起 / 分栏 / 目标操作）：28 -> 40",
     [("nLMEza_iconBtn", True), ("_iconButton_6nhg2_511", False), ("P3OORG_iconButton", True)], "wh"),
    ("页签（对话 / 轨迹）：高 25 -> 40（宽 25 是 grid 固有，改不了）",
     [("wSkVaW_tab", True), ("wSkVaW_tabActive", True)], "h"),
    ("输入区附件按钮：28 -> 40", [("uV2eYG_add", True)], "wh"),
    ("输入区发送按钮：34 -> 40", [("uV2eYG_primary", True)], "wh"),
    ("输入区模式 / 模型触发器：高 28 -> 40",
     [("iWlSmW_trigger", True), ("_7KE1Ra_trigger", True)], "h"),
    ("消息区可折叠行（思考 / 代码 / 命令预览）：高 24 -> 40",
     [("_row_jhda5_16", False)], "h_expandable"),
]

def render():
    L = []
    L.append(MARK + " (prototype, 2026-10-16)")
    L.append(" *")
    L.append(" * 目的：粗指针（手机）上把过小的可点元素抬到 40px（贴近 44px 推荐值）。")
    L.append(" * 范围：仅 @media (pointer: coarse) 生效，桌面精确指针不受影响。")
    L.append(" * 基线：真机 SM-S9280 / Android 16 / CSS 视口 384x832 / dpr 3.75 / dsh 0.2.0-rc.2。")
    L.append(" * 第一轮真机验证：补丁前视口内 30/30 个可点元素 < 44px；补丁后目标元素均达 40px；")
    L.append(" *                 14/14 选择器在页面上命中；无横向溢出；补丁由样式表提供。")
    L.append(" * 选择器来源：真机 DOM 实测。部分类（hHd-Xa_* / nLMEza_* / wSkVaW_* / uV2eYG_* 等）")
    L.append(" *             来自 /plugins/ 注入的内联样式，不在 dist CSS 里 —— 靠 !important")
    L.append(" *             赢过层叠顺序，因此追加到 dist CSS 末尾同样生效。")
    L.append(" * 警告：类名含构建哈希，dsh 升级后会变；升级后需重新实测并重生成。")
    L.append(" */")
    L.append("")
    L.append("@media (pointer: coarse) {")
    for title, names, mode in GROUPS:
        sel = ", ".join("." + n for n, _ in names)
        L.append("  /* %s */" % title)
        if mode == "wh":
            L.append("  %s { min-width: 40px !important; min-height: 40px !important; }" % sel)
        elif mode == "hw":
            L.append("  %s { height: 40px !important; min-width: 40px !important; }" % sel)
        elif mode == "h_expandable":
            L.append("  /* 仅可展开的行抬高：不可展开的抬了会白占竖向空间 */")
            L.append("  %s[data-expandable] { height: 40px !important; }" % sel)
        else:
            L.append("  %s { height: 40px !important; }" % sel)
        L.append("")
    L.append("  /* 未处理：统计胶囊（22px 高）。宿主把它放在 height:16px + overflow:hidden 的")
    L.append("   * 固定细带里，抬高会被裁掉并挤掉正文 —— 见 known-issues 第十五节限制 1。 */")
    L.append("}")
    return "\n".join(L) + "\n"

rendered = render()
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8").write(rendered)
print("补丁已生成: %s (%d 字节)" % (OUT, len(rendered.encode())))

# 核验基准：优先用未打补丁的 .orig，避免「对着自己打过的补丁验自己」的假阳性
ref = DIST_ORIG if os.path.exists(DIST_ORIG) else DIST_CSS
is_orig = os.path.exists(DIST_ORIG)
print()
print("=== 选择器归属核验（基准: %s）===" % ("原始 .orig" if is_orig else "当前 dist CSS —— 未打补丁前才准确"))
ref_s = open(ref, encoding="utf-8").read()
for title, names, mode in GROUPS:
    for n, optional in names:
        present = ("." + n) in ref_s
        where = "dist 产物" if present else "插件内联样式（靠 !important）"
        flag = "" if (present or optional) else "  <- 缺失! 需真机复核"
        print("  %-30s %s%s" % (n, where, flag))

if "--revert" in sys.argv:
    if os.path.exists(DIST_ORIG):
        shutil.copy2(DIST_ORIG, DIST_CSS)
        print("已从 .orig 还原 dist CSS")
    else:
        print("找不到 .orig，无法还原")
    sys.exit(0)

if "--apply" in sys.argv:
    s = open(DIST_CSS, encoding="utf-8").read()
    if MARK in s:
        s = s[:s.index(MARK)].rstrip() + "\n"
        print("已剥离旧补丁")
    if not os.path.exists(DIST_ORIG):
        shutil.copy2(DIST_CSS, DIST_ORIG)
        print("原始备份: %s" % DIST_ORIG)
    open(DIST_CSS, "w", encoding="utf-8").write(s.rstrip() + "\n\n" + rendered)
    print("已写入 dist CSS: %.1f KB" % (os.path.getsize(DIST_CSS)/1024))
