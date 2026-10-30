import re, json, subprocess, sys
P = "/home/xsj/Documents/deepseek-harness/default-workspace/dsh-handheld/android/app/src/main/java/com/dshhandheld/app/WebInject.kt"
src = open(P, encoding="utf-8").read()

start = src.find("private const val SIDEBAR_FAB_JS")
if start < 0:
    print("找不到 SIDEBAR_FAB_JS"); sys.exit(1)
end = src.find("fun applySidebarFab")
block = src[start:end]

# 取出该块里所有 Kotlin 字符串字面量（"..."，含转义）
lits = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
print("字符串字面量数量:", len(lits))

def unescape(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == chr(92) and i + 1 < len(s):
            n = s[i+1]
            if n == "n": out.append(chr(10))
            elif n == "t": out.append(chr(9))
            elif n == "r": out.append(chr(13))
            elif n == chr(34): out.append(chr(34))
            elif n == chr(39): out.append(chr(39))
            elif n == chr(92): out.append(chr(92))
            elif n == "u" and i + 5 < len(s):
                out.append(chr(int(s[i+2:i+6], 16))); i += 6; continue
            else: out.append(n)
            i += 2
        else:
            out.append(c); i += 1
    return "".join(out)

js = "".join(unescape(l) for l in lits)
open("/tmp/injected.js", "w", encoding="utf-8").write(js)
print("还原出的 JS 长度:", len(js))
print("前 120 字:", js[:120].replace(chr(10), " "))

# 语法检查
r = subprocess.run(["node", "--check", "/tmp/injected.js"], capture_output=True, text=True)
print("node --check 退出码:", r.returncode)
if r.returncode != 0:
    print(r.stderr[:800])
