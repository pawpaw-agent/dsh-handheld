#!/usr/bin/env python3
"""回归守卫：断言 autoFetchToken 的「收集 + 验证」结构与 401 判定仍在。

为什么需要它：2026-10-18 与 2026-10-23 两次 401 死锁，根因是**同一类错误的两个方向** ——
先是「journal 优先于文件」，后是「文件优先于 journal」。结论：**任何固定优先级都会在
某一种部署上拿到过期 token**（token 落哪儿取决于 dsh web 是手动启还是 systemd 启），
正确解法是「收集全部 + 逐个向服务端验证」。

本脚本把那条结论固化成断言：谁把「验证」删掉、或把某个来源设成唯一来源，立刻红。

用法: python3 tools/check-token-candidates.py
"""
import re, sys, os

NL = chr(10)
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SRC = os.path.join(REPO, "android/app/src/main/java/com/dshhandheld/app/MainActivity.kt")
s = open(SRC, encoding="utf-8").read()

i = s.find("private fun autoFetchToken")
if i < 0:
    print("FAIL 找不到 autoFetchToken")
    sys.exit(1)
j = s.find(NL + "    }", i)
if j < 0:
    print("FAIL 找不到函数结尾")
    sys.exit(1)
fn = s[i:j]

ok = True
def check(name, cond, extra=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %s%s" % ("OK " if cond else "FAIL", name, ("  | " + extra) if extra else ""))

print("=== 收集：必须覆盖两种启动方式的落点 ===")
check("手动脚本部署：~/.dsh/run/web-*.log", "~/.dsh/run/web-*.log" in fn)
check("systemd 部署：journalctl 按 user unit 过滤", "_SYSTEMD_USER_UNIT=dsh-web.service" in fn)
check("systemd 部署：journalctl --user -u", "journalctl --user -u dsh-web.service" in fn)
check("老路径仍保留", "~/.dsh/web.log" in fn)
check("按 mtime 取新（ls -t）", "ls -t" in fn)

print()
print("=== 验证：核心防线 ===")
check("对候选做 curl 验证", "curl -s -o /dev/null -w" in fn)
check("接受状态码 303/200", re.search(r'code == "303" \|\| code == "200"', fn) is not None)
check("验证命令带远端端口", "remotePort" in fn)
check("有候选上限常量", "MAX_TOKEN_CANDIDATES" in s)
check("全部验证失败时有回退", "fallback" in fn)

print()
print("=== 不得退回「单一来源 + 只看长度」的旧做法 ===")
check("旧的多命令顺序结构已移除", "commands.withIndex()" not in fn)
check("日志不打印 token 明文", "0:8" not in fn and "substring" not in fn)

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
