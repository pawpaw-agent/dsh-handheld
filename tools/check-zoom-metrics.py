#!/usr/bin/env python3
"""check-zoom-metrics.py — 缩放结论的机器门禁（题目验收 A1/A3/A4/A5）

它读 web-zoom-probe / zoom-harness 产出的 JSON，检查两件事：

  1. **测量本身可信**（仪器自证）：
     - 模拟的视口是否真的生效（innerWidth == 条件里的 width）
     - 页面是否标准模式（CSS1Compat，quirks 下 viewport meta 会被忽略）
  2. **结论没有被悄悄推翻或悄悄回退**：
     - 缩小（<100）是否仍被钳制在 1.0
     - 放大（>100）是否真的生效
     - 加宽视口时布局是否重排（无横向溢出、被测元素变宽）
     - App 里有没有把默认缩放重新设成 <100（那是已知无效的假象）

用法：
  python3 tools/check-zoom-metrics.py                 # 用 docs/measurements 里的存档
  python3 tools/check-zoom-metrics.py --dir /tmp      # 用刚跑出来的

退出码 0 = 全部符合；1 = 有断言不成立（输出会说明是哪条、为什么重要）。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DEFAULT_DIR = os.path.join(REPO, "docs", "measurements")

args = sys.argv[1:]
DIR = DEFAULT_DIR
if "--dir" in args:
    DIR = args[args.index("--dir") + 1]

ok = True


def check(name, cond, detail=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %s%s" % ("OK " if cond else "FAIL", name, ("  | " + detail) if detail else ""))


def load(name):
    p = os.path.join(DIR, name)
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def integrity(d, label):
    """仪器自证：模拟生效 + 标准模式。这两条不成立，后面的数都不算数。"""
    info = d["page"]["info"]
    w = d["conditions"]["width"]
    check("%s: 模拟视口已生效（innerWidth≈%d）" % (label, w),
          abs(info.get("innerWidth", -1) - w) <= 2, "实测 %s" % info.get("innerWidth"))
    check("%s: 标准模式（CSS1Compat）" % label,
          info.get("compatMode") == "CSS1Compat", str(info.get("compatMode")))


d384 = load("zoom-384.json") or load("zoom-384-in.json")
d512 = load("zoom-512.json")

print("=== 1. 仪器可信（A1）===")
if d384 is None:
    print("  [SKIP] 缺少 384 宽的测量存档，先跑 tools/zoom-harness.mjs")
    ok = False
else:
    integrity(d384, "384")
    if d512 is not None:
        integrity(d512, "512")

print()
print("=== 2. 缩放语义（A3 / 已知事实：缩小无效、放大有效）===")
if d384 is not None:
    steps = {s["requested"]: s for s in d384["probe"]["steps"]}
    s075 = steps.get(0.75)
    if s075 is None:
        print("  [SKIP] 存档里没有 0.75 档位")
    else:
        # 这条不是「要求它被钳制」，而是「一旦不再被钳制，就必须重新评估」——
        # 因为我们基于「0.75 无效」做了决策（默认值保持 100）。
        check("缩小 0.75 仍被钳制在 1.0（若此处 FAIL：钳制行为变了，需重新评估决策）",
              s075["clamped"] and abs(s075["actualScale"] - 1.0) < 1e-6,
              "实际 %s" % s075["actualScale"])
    s125 = steps.get(1.25)
    if s125 is not None:
        check("放大 1.25 真正生效", (not s125["clamped"]) and abs(s125["actualScale"] - 1.25) < 0.03,
              "实际 %s" % s125["actualScale"])

print()
print("=== 3. 布局宽度是真正的杠杆（A4 / H3）===")
if d384 is not None and d512 is not None:
    g384 = d384["probe"]["baseline"]
    g512 = d512["probe"]["baseline"]
    check("384 宽无横向溢出", g384["scrollWidth"] == g384["clientWidth"],
          "%s vs %s" % (g384["scrollWidth"], g384["clientWidth"]))
    check("512 宽无横向溢出", g512["scrollWidth"] == g512["clientWidth"],
          "%s vs %s" % (g512["scrollWidth"], g512["clientWidth"]))
    check("加宽视口后被测元素变宽（321 -> 449 量级）",
          g512["rectCss"]["w"] > g384["rectCss"]["w"] + 50,
          "%.0f -> %.0f CSS px" % (g384["rectCss"]["w"], g512["rectCss"]["w"]))
    check("字号不随视口宽度变化（宿主不缩放字体）",
          abs(g512["fontSizePx"] - g384["fontSizePx"]) < 0.5,
          "%s vs %s" % (g384["fontSizePx"], g512["fontSizePx"]))

print()
print("=== 4. App 侧：缩小必须走视图级缩放，不能走 WebView 的 zoom API（A5）===")
kt = os.path.join(REPO, "android/app/src/main/java/com/dshhandheld/app/MainActivity.kt")
if os.path.exists(kt):
    src = open(kt, encoding="utf-8").read()
    import re
    # 结论来自本文件第 2 节：page scale 的缩小被钳制在 1.0。
    # 所以只要还有人用 setInitialScale/zoomBy 去做「小于 100%」，就一定不生效 —— 拦住它。
    code = re.sub(r"/\*.*?\*/", "", src, flags=re.S)   # 去掉块注释，避免误伤说明文字
    code = re.sub(r"//[^\n]*", "", code)               # 去掉行注释
    check("不再用 setInitialScale()（对自带 viewport 的页面无效）",
          "setInitialScale(" not in code)
    check("不再用 zoomBy() 做缩小（page scale 会被钳制）", "zoomBy(" not in code)
    check("用视图级缩放（scaleX/scaleY）", ("scaleX = scale" in code) and ("scaleY = scale" in code))
    # 默认路径必须是「最简单」的那条：只缩字号。视图级缩放属于可选项，
    # 否则每加一个显示偏好都要动布局，风险与收益不成比例。
    check("默认走 setTextZoom（一行方案）", "textZoom = pct" in code)
    check("布局缩放由开关控制（默认关）",
          ("PREF_WEB_ZOOM_LAYOUT" in code) and ("layoutZoomEnabled()" in code))
    m = re.search(r"DEFAULT_WEB_ZOOM_PCT\s*=\s*(\d+)", code)
    if m:
        val = int(m.group(1))
        check("默认缩放在 50..100 之间（视图级缩放的有效区间）", 50 <= val <= 100, "当前 %d" % val)
    else:
        check("找到 DEFAULT_WEB_ZOOM_PCT", False)

print()
print("总体:", "全部通过" if ok else "有 FAIL")
sys.exit(0 if ok else 1)

