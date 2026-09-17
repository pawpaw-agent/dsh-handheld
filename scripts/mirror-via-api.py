#!/usr/bin/env python3
"""Mirror the local tracked tree to github.com/main via the git-database API.

Used when the git:// https transport is unreliable (TLS resets) and when the
incremental push would be fragile (renames, deletions, empty directories).

Unlike push-via-api.py this does NOT patch the remote tree incrementally: it
rebuilds the ENTIRE tree from `git ls-files`, so the resulting commit is exactly
the local tracked state. Precondition: the remote must not contain tracked files
that the local index lacks (verify with
`comm -13 <(git ls-files|sort) <(gh api .../git/trees/main?recursive=1 ...|sort)`)
— anything remote-only is deleted by a mirror.

Usage: python3 mirror-via-api.py --message MSG
  --expect-base SHA  required: assert the remote branch is still at SHA (default branch)

"""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "pawpaw-agent/dsh-handheld"
API = f"https://api.github.com/repos/{REPO}"
BRANCH = "main"
TOKEN = subprocess.check_output(["gh", "auth", "token"], text=True).strip()


def gh(method, url, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "mirror-via-api",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} {method} {url}: {e.read().decode()}")


def main():
    msg = None
    expect_base = None
    args = sys.argv[1:]
    while args:
        a = args.pop(0)
        if a == "--expect-base":
            expect_base = args.pop(0)
        elif a == "--message":
            msg = args.pop(0)
        else:
            raise SystemExit(f"unexpected argument: {a}")
    if msg is None:
        raise SystemExit("usage: mirror-via-api.py --message MSG")

    # 拒绝镜像「有未提交改动」的工作区。
    #
    # 本脚本的文件**内容取自磁盘**，而文件清单取自 `git ls-files`；两者不一致时会
    # 提交出一个坏版本。实测踩过一次：MainActivity.kt 已改（引用了新的 UiKit），
    # 而 UiKit.kt 还是未跟踪文件（不在 `git ls-files` 里），于是镜像推上去的
    # MainActivity 引用了一个不存在的类，CI 直接编译失败（`Unresolved reference: UiKit`）。
    #
    # 未跟踪文件本身不阻塞（它们本来就不该被镜像），但会列出来：因为
    # 「已跟踪文件引用了未跟踪文件」正是上面那个坑的形态。
    dirty = subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=no"], text=True
    ).strip()
    if dirty:
        raise SystemExit("工作区有未提交的改动，拒绝镜像（先 commit）：\n" + dirty)
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard",
         "scripts", "android", "docs"],
        text=True,
    ).strip()
    if untracked:
        print("注意：以下未跟踪文件不会被镜像（先确认没有已跟踪文件引用它们）：")
        for line in untracked.splitlines():
            print("   ", line)

    # Local index: path -> mode, straight from git (authoritative for exec bits).
    index = subprocess.check_output(
        ["git", "ls-files", "-s"], text=True
    ).splitlines()
    files = []
    for line in index:
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        files.append((path, mode))
    print(f"local tracked files: {len(files)}")

    head = gh("GET", f"{API}/git/ref/heads/{BRANCH}")["object"]["sha"]
    # ── base 断言（2026-09-17 审计 M15）────────────────────────────────────
    # 本脚本按本地索引**重建整棵树**，parent 取「此刻的远端 head」→ 永远 fast-forward，
    # `force: False` 对「远端内容被换掉」毫无约束（远端独有的文件会被直接删掉）。
    # 所以要求调用方显式声明「我以为远端在哪」，并在写之前拦住。
    if expect_base is None:
        raise SystemExit(
            "拒绝镜像：缺少 --expect-base <sha>\n"
            f"  当前 {BRANCH} @ {head}\n"
            "  镜像会按本地索引重建整棵树（远端独有的文件会被删）——确认无误后把上面这个 sha 传进来。")
    if expect_base != head:
        raise SystemExit(
            f"拒绝镜像：--expect-base {expect_base} 与远端 {BRANCH} {head} 不一致\n"
            "  远端已经动过（别人推了 / 你看到的是旧状态）——先看清再重来。")
    print(f"{BRANCH} @ {head}")

    # 1. one blob per file
    blobs = {}
    for path, _mode in files:
        with open(path, "rb") as fh:
            content = base64.b64encode(fh.read()).decode()
        blobs[path] = gh("POST", f"{API}/git/blobs",
                         {"content": content, "encoding": "base64"})["sha"]
    print(f"blobs created: {len(blobs)}")

    # 2. build every tree level bottom-up from the file list alone.
    #    tree[dir] = {child_name: entry}, built only from what we put there, so
    #    no duplicate entries and no stale/empty directories can survive.
    tree = {}
    for path, mode in files:
        d, name = os.path.split(path)
        tree.setdefault(d, {})[name] = {
            "path": name, "mode": mode, "type": "blob", "sha": blobs[path],
        }

    dirs = set(tree)
    for d in list(tree):
        cur = d
        while cur:
            cur = os.path.dirname(cur)
            dirs.add(cur)
    dirs.add("")

    def depth(d):
        return 0 if d == "" else d.count("/") + 1

    built = {}
    for d in sorted(dirs, key=depth, reverse=True):
        entries = list(tree.get(d, {}).values())
        # attach rebuilt child directories whose parent is d
        for sub_dir, sha in built.items():
            if os.path.dirname(sub_dir) == d:
                entries.append({
                    "path": os.path.basename(sub_dir), "mode": "040000",
                    "type": "tree", "sha": sha,
                })
        entries.sort(key=lambda e: e["path"])
        built[d] = gh("POST", f"{API}/git/trees", {"tree": entries})["sha"]
    print(f"trees created: {len(built)}")

    # 3. commit + fast-forward
    commit = gh("POST", f"{API}/git/commits", {
        "message": msg, "tree": built[""], "parents": [head],
    })
    print(f"commit {commit['sha']}")
    gh("PATCH", f"{API}/git/refs/heads/{BRANCH}",
       {"sha": commit["sha"], "force": False})
    print(f"{BRANCH} -> {commit['sha']}")


if __name__ == "__main__":
    main()
