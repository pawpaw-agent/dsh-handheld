#!/usr/bin/env python3
"""检查 WebInject.kt 里拼接出来的注入 JS 语法是否合法。

为什么需要它：注入脚本是 Kotlin 字符串拼出来的，一旦转义写错（踩过两次：
三层反斜杠、带引号的选择器），evaluateJavascript 会**静默吞掉语法错误**，
表现是「注入无任何效果」，排查成本极高。本脚本把 Kotlin 拼好的 JS 还原出来，
交给 node --check 判定，出错直接红。

用法: python3 tools/check-inject-js.py
"""
import os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "android/app/src/main/java/com/dshhandheld/app/WebInject.kt")
BS = chr(92)

src = open(SRC, encoding="utf-8").read()
start = src.find("private const val SIDEBAR_FAB_JS")
end = src.find("fun applySidebarFab")
if start < 0 or end < 0:
    print("FAIL 找不到 SIDEBAR_FAB_JS 或 applySidebarFab")
    sys.exit(1)
block = src[start:end]

# 先剥掉 Kotlin 注释再抓字符串字面量。
# 否则注释里出现的成对双引号（例如举例说明写法）会被当成真实字面量拼进 JS，
# 而拼出来的 JS 是一整行，一段行注释就会把它后面的代码全部注释掉（2026-10-31 踩过）。
block = re.sub(r"/\*.*?\*/", "", block, flags=re.S)   # 块注释
block = re.sub(r"//[^\n]*", "", block)                  # 行注释

quoted = re.compile(r'"((?:[^"\\]|\\.)*)"')
lits = quoted.findall(block)
if not lits:
    print("FAIL 没有提取到字符串字面量")
    sys.exit(1)

def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == BS and i + 1 < len(s):
            n = s[i + 1]
            if n == "n": out.append(chr(10))
            elif n == "t": out.append(chr(9))
            elif n == "r": out.append(chr(13))
            else: out.append(n)
            i += 2
            continue
        out.append(c); i += 1
    return "".join(out)

js = "".join(unescape(x) for x in lits)
print("注入 JS: %d 字符 / %d 段" % (len(js), len(lits)))

path = os.path.join(tempfile.gettempdir(), "dsh-inject-check.js")
open(path, "w", encoding="utf-8").write(js)
r = subprocess.run(["node", "--check", path], capture_output=True, text=True)
if r.returncode != 0:
    print("FAIL 注入 JS 语法错误：")
    print(r.stderr[:1200])
    sys.exit(1)
print("注入 JS 语法: OK")

ok = True
def check(name, cond):
    global ok
    if not cond:
        ok = False
    print("  [%s] %s" % ("OK " if cond else "FAIL", name))

print("=== 设计约束 ===")
check("未使用带引号的 aria-label 选择器（转义坑）", ("aria-label=" + BS + chr(34)) not in block)
check("只改第一列，不写死整条 grid-template", ("gridTemplateColumns" in js) and ("parts[0]" in js))
check("注入结果有回调日志", "evaluateJavascript(SIDEBAR_FAB_JS)" in src)
check("import 了 DiagLog", "import com.dshhandheld.diag.DiagLog" in src)
print("总体:", "通过" if ok else "有 FAIL")
sys.exit(0 if ok else 1)
