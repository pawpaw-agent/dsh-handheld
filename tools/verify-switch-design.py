#!/usr/bin/env python3
"""核实多服务器切换设计文档里的每处代码引用与事实断言。"""
import re, os, sys

REPO = "/home/xsj/Documents/deepseek-harness/default-workspace/dsh-handheld"
def read(p):
    return open(os.path.join(REPO, p), encoding="utf-8", errors="replace").read()

flat = lambda s: re.sub(r"\s+", " ", s)
ok = True
def check(name, cond, extra=""):
    global ok
    if not cond: ok = False
    print("  [%s] %s%s" % ("OK " if cond else "FAIL", name, ("  | " + extra) if extra else ""))

sshcfg = read("android/app/src/main/java/com/dshhandheld/app/SshConfig.kt")
dshapp = read("android/app/src/main/java/com/dshhandheld/app/DshApp.kt")
main = read("android/app/src/main/java/com/dshhandheld/app/MainActivity.kt")
tunnel = read("android/app/src/main/protocol/SshTunnel.kt") if os.path.exists(os.path.join(REPO, "android/app/src/main/protocol/SshTunnel.kt")) else read("android/app/src/main/java/com/dshhandheld/protocol/SshTunnel.kt")

f_sshcfg, f_dshapp, f_main, f_tunnel = map(flat, (sshcfg, dshapp, main, tunnel))

print("=== 1. 数据层断言 ===")
check("存储键 ssh_json", "PREF_KEY = \"ssh_json\"" in f_sshcfg)
check("存储键 ssh_hosts_json", "LIST_KEY = \"ssh_hosts_json\"" in f_sshcfg)
check("存储键 ssh_active_host", "ACTIVE_KEY = \"ssh_active_host\"" in f_sshcfg)
check("SshConfig 有 id/name/host/port/user/remoteHost/remotePort/authType/password/keyPath/keyPass",
      all(k in f_sshcfg for k in ["val id:", "val name:", "val host:", "val port:", "val user:",
                                  "val remoteHost:", "val remotePort:", "val authType:",
                                  "val password:", "val keyPath:", "val keyPass:"]))
check("fingerprint 排除 id/name", "刻意不含 `id`/`name`" in f_sshcfg or "不含 `id`/`name`" in f_sshcfg)

print()
print("=== 2. 隧道单例与拨号串行化 ===")
check("sshTunnel 单例声明", "var sshTunnel: SshTunnel? = null" in f_dshapp)
check("private set（外部只读）", "var sshTunnel: SshTunnel? = null private set" in f_dshapp)
check("dialLock 存在", "private val dialLock = Any()" in f_dshapp)
check("注释称拨号最坏 3×15s", "最坏 3×15s" in f_dshapp)
check("注释称拨号 ~1-3s", "拨号 ~1-3s" in f_dshapp)
check("ensureTunnel 指纹不匹配时先置空再拨",
      "sshTunnel = null" in f_dshapp and "obsolete?.close()" in f_dshapp)
check("CLOSE_JOIN_MS = 5000", "CLOSE_JOIN_MS = 5_000L" in f_dshapp)

print()
print("=== 3. 端口与 origin 断言 ===")
check("PORT_CANDIDATES 存在", "PORT_CANDIDATES" in f_tunnel)
check("3080 优先 / 13080 兜底", "3080" in f_tunnel and "13080" in f_tunnel)
check("注释记过 origin 漂移", "origin" in f_tunnel and ("漂" in f_tunnel or "cookie" in f_tunnel))

print()
print("=== 4. 切换链路断言 ===")
check("applyHostToForm 存在", "applyHostToForm" in f_main)
check("SshHosts.setActive 调用", "SshHosts.setActive(prefs, picked.id)" in f_main)
check("切换时 disconnectCurrent", "disconnectCurrent()" in f_main)
check("waitTunnelReleasedThen(2000)", "waitTunnelReleasedThen(2000)" in f_main)
check("下拉第 0 项是「＋ 新建主机…」", "＋ 新建主机…" in f_main)
check("单选钮标签「＋ 新建主机」", "＋ 新建主机" in f_main)

print()
print("=== 5. 现有机制断言（用来避免「重复发明」）===")
check("ConnectDecisions.showProgress 存在（A3 应复用它）",
      "fun showProgress" in flat(read("android/app/src/main/java/com/dshhandheld/app/ConnectDecisions.kt")))
check("hostSwitchAllowed 守卫存在", "fun hostSwitchAllowed" in flat(read("android/app/src/main/java/com/dshhandheld/app/ConnectDecisions.kt")))
check("SshHosts.activeId 已持久化（A4 基础）", "fun activeId(" in f_sshcfg)

print()
print("=== 6. 我的新增点是否真的「新增」（不该已存在）===")
check("尚无 ssh_host_health_json（A2 要新增）", "ssh_host_health_json" not in f_sshcfg)
check("尚无 lastOkAt / 健康记录字段", "lastOkAt" not in f_sshcfg and "lastError" not in f_sshcfg)

print()
print("=== 7. 设计稿自身的结构完整性 ===")
doc = open(os.path.join(REPO, "docs/multi-server-switch-design.md"), encoding="utf-8").read()
for sec in ["## 一、现状", "## 二、问题拆解", "## 三、方案 A", "## 四、方案 B", "## 五、落地前必须先量", "## 六、建议的落地顺序", "## 七、明确不做的"]:
    check("含章节 %s" % sec, sec in doc)
check("声明了未测量的边界", "不是我在真机上秒表量出来的" in doc)
check("未声称改过代码（本稿仅设计）", "设计稿" in doc)

print()
print("总体:", "全部通过 ✓" if ok else "有 FAIL ✗")
sys.exit(0 if ok else 1)
