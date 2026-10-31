#!/usr/bin/env python3
"""检查 WebInject.kt 里拼接出来的注入 JS 语法是否合法。

为什么需要它：注入脚本是 Kotlin 字符串拼出来的，一旦转义写错（踩过三次：
三层反斜杠、带引号的选择器、注释里带双引号的示例），evaluateJavascript 会
**静默吞掉语法错误**，表现是「注入无任何效果」，排查成本极高。
本脚本把每个 *_JS 常量还原成真实 JS，逐个交给 node --check 判定。

用法: python3 tools/check-inject-js.py
"""
import os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "android/app/src/main/java/com/dshhandheld/app/WebInject.kt")
BS = chr(92)

src = open(SRC, encoding="utf-8").read()
quoted = re.compile(r'"((?:[^"\\]|\\.)*)"')


def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == BS and i + 1 < len(s):
            n = s[i + 1]
            if n == "n":
                out.append(chr(10))
            elif n == "t":
                out.append(chr(9))
            elif n == "r":
                out.append(chr(13))
            else:
                out.append(n)
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_comments(block):
    """先剥掉 Kotlin 注释再抓字符串字面量。

    否则注释里出现的成对双引号（例如举例说明写法）会被当成真实字面量拼进 JS，
    而拼出来的 JS 是一整行，一段行注释就会把它后面的代码全部注释掉（2026-10-31 踩过）。
    """
    block = re.sub(r"/\*.*?\*/", "", block, flags=re.S)
    block = re.sub(r"//[^\n]*", "", block)
    return block


def const_block(name):
    """取出 private const val <name> 到下一个 fun / const / 对象结尾之间的源码。"""
    start = src.find("private const val " + name)
    if start < 0:
        return None
    tail = src[start + 10:]
    cands = [i for i in (tail.find(chr(10) + "    fun "),
                         tail.find(chr(10) + "    private const val "),
                         tail.find(chr(10) + "}")) if i >= 0]
    end = start + 10 + (min(cands) if cands else len(tail))
    return src[start:end]


names = re.findall(r"private const val (\w+_JS)\b", src)
if not names:
    print("FAIL 没找到任何 *_JS 常量")
    sys.exit(1)

result = {}
failed = False
for name in names:
    block = const_block(name)
    if block is None:
        continue
    js = "".join(unescape(x) for x in quoted.findall(strip_comments(block)))
    if not js.strip():
        continue
    path = os.path.join(tempfile.gettempdir(), "dsh-check-%s.js" % name)
    open(path, "w", encoding="utf-8").write(js)
    r = subprocess.run(["node", "--check", path], capture_output=True, text=True)
    print("%s: %d 字符" % (name, len(js)))
    if r.returncode != 0:
        print("FAIL %s 语法错误：" % name)
        print(r.stderr[:900])
        failed = True
    result[name] = (js, block)

if failed:
    sys.exit(1)
print("注入 JS 语法: OK")

js, block = result.get("SIDEBAR_FAB_JS", ("", ""))
ok = True


def check(nm, cond):
    global ok
    if not cond:
        ok = False
    print("  [%s] %s" % ("OK " if cond else "FAIL", nm))


print("=== 设计约束 ===")
check("未使用带引号的 aria-label 选择器（转义坑）", ("aria-label=" + BS + chr(34)) not in block)
check("只改第一列，不写死整条 grid-template", ("gridTemplateColumns" in js) and ("parts[0]" in js))
check("注入结果有回调日志", "evaluateJavascript(SIDEBAR_FAB_JS)" in src)
check("import 了 DiagLog", "import com.dshhandheld.diag.DiagLog" in src)
print("总体:", "通过" if ok else "有 FAIL")
sys.exit(0 if ok else 1)
