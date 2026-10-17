#!/usr/bin/env python3
"""回归守卫：断言 autoFetchToken 的候选顺序与 401 判定仍在。

为什么需要它：2026-10-18 的 401 死锁根因之一就是**候选顺序错了**（journal 优先于文件），
而那个错误在代码里完全看不出来 —— 它只在「手动重启过 dsh web」的部署上才暴露。
本脚本把那次教训固化成断言：谁把顺序改回去，立刻红。

用法: python3 tools/check-token-candidates.py
"""
import re, sys, os

NL = chr(10)
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SRC = os.path.join(REPO, "android/app/src/main/java/com/dshhandheld/app/MainActivity.kt")
s = open(SRC, encoding="utf-8").read()

i = s.find("private fun autoFetchToken")
j = s.find("val commands = arrayOf(", i)
k = s.find(")" + NL + "        )", j)
if i < 0 or j < 0 or k < 0:
    print("FAIL 找不到 autoFetchToken / commands 数组（源码结构变了？）")
    sys.exit(1)
block = s[j:k]

ok = True
def check(name, cond, extra=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %s%s" % ("OK " if cond else "FAIL", name, ("  | " + extra) if extra else ""))

print("=== autoFetchToken 候选顺序回归检查 ===")
check("含 ~/.dsh/run 的运行日志目录", "~/.dsh/run" in block)
check("按 mtime 取最新", ("find ~/.dsh/run" in block) and ("-printf" in block) and ("sort -rn" in block))
pos_file = block.find("~/.dsh/run")
pos_journal = block.find("journalctl")
check("文件候选排在 journal 之前（事故核心）", pos_file >= 0 and pos_journal >= 0 and pos_file < pos_journal,
      "file@%d journal@%d" % (pos_file, pos_journal))
for p in ["~/.dsh/web.log", "~/.dsh/web_log", "~/.dsh/dsh-web.log"]:
    check("保留老路径 %s" % p, p in block)

print()
print("=== 配套：401 不得被当作认证成功 ===")
check("存在 mainFrameAuthFailed 字段", "private var mainFrameAuthFailed = false" in s)
check("onReceivedHttpError 里置位",
      re.search(r"if [(]code == 401[)] [{][^}]*mainFrameAuthFailed = true", s) is not None)
p1 = s.find("override fun onPageFinished")
seg = s[p1:p1 + 2000]
check("onPageFinished 优先判认证失败", "if (mainFrameAuthFailed)" in seg)
check("认证失败时不隐藏覆盖层", "!navFailed && !mainFrameAuthFailed" in seg)
p2 = s.find("private fun connectWeb")
check("connectWeb 里重置标记", "mainFrameAuthFailed = false" in s[p2:p2 + 1400])

print()
print("总体:", "全部通过" if ok else "有 FAIL")
sys.exit(0 if ok else 1)
