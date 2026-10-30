#!/usr/bin/env python3
"""检查 Kotlin 源文件的块注释是否配对。

为什么需要它（2026-10-30 真实事故）：注释里写了 `5s/15s/**45s**`，其中的 `/**` 在
Kotlin 里会**开启嵌套块注释**（Kotlin 的块注释可嵌套），于是后面的整个文件都被吞进
注释里，编译器只报「Missing }」和结尾的「Unclosed comment」——完全指不到真正的位置，
排查成本极高。这个脚本直接报出「哪个文件的注释从第几行开始没闭合」。

用法: python3 tools/check-kotlin-comments.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "android", "app", "src", "main", "java")

def check(path):
    src = open(path, encoding="utf-8").read()
    stack = []      # 每个未闭合 /* 的行号
    i, line = 0, 1
    while i < len(src) - 1:
        two = src[i:i+2]
        if two == "/*":
            if "*/" not in src[i:]:
                return f"块注释未闭合（起始于第 {line} 行）"
            depth = 1
            j = i + 2
            while j < len(src) - 1 and depth > 0:
                if src[j] == chr(10):
                    line += 1
                if src[j:j+2] == "/*":
                    stack.append(line); depth += 1; j += 2; continue
                if src[j:j+2] == "*/":
                    depth -= 1
                    if stack: stack.pop()
                    j += 2; continue
                j += 1
            i = j; continue
        if src[i] == chr(10):
            line += 1
        i += 1
    if stack:
        return f"嵌套块注释未闭合（起始于第 {stack[-1]} 行）"
    return None

bad = []
checked = 0
for dirpath, _, files in os.walk(ROOT):
    for f in sorted(files):
        if not f.endswith(".kt"):
            continue
        p = os.path.join(dirpath, f)
        checked += 1
        err = check(p)
        if err:
            bad.append((os.path.relpath(p, ROOT), err))

print(f"检查了 {checked} 个 Kotlin 文件")
if bad:
    for p, e in bad:
        print(f"FAIL {p}: {e}")
    sys.exit(1)
print("块注释: 全部配对")
