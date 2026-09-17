#!/usr/bin/env python3
"""Push a small set of file updates to github.com via the git-database API.

Used when the git:// https transport is unreliable (TLS resets).
Creates one commit on top of current main.
Usage: python3 push-via-api.py [--delete PATH]... [--message MSG] FILE1 [FILE2 ...]
  --expect-base SHA  required: assert the remote branch is still at SHA (default branch)

"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

REPO = "pawpaw-agent/dsh-handheld"
API = f"https://api.github.com/repos/{REPO}"
TOKEN = subprocess.check_output(["gh", "auth", "token"], text=True).strip()


def gh(method, url, payload=None):
    if payload is not None:
        data = json.dumps(payload).encode()
    else:
        data = None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "push-via-api",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        raise SystemExit(f"HTTP {e.code} {method} {url}: {body}")


def tree_of(commit_sha):
    """Return {path: (mode, type, sha)} for all entries under the commit tree."""
    t = gh("GET", f"{API}/git/trees/{commit_sha}?recursive=1")
    out = {}
    for e in t.get("tree", []):
        out[e["path"]] = (e["mode"], e["type"], e["sha"])
    return out


def main():
    args = sys.argv[1:]
    if not args:
        raise SystemExit("usage: push-via-api.py [--delete PATH]... [--message MSG] FILE...")
    deletes = []
    msg = None
    files = []
    expect_base = None
    while args:
        a = args.pop(0)
        if a == "--delete":
            deletes.append(args.pop(0))
        elif a == "--expect-base":
            expect_base = args.pop(0)
        elif a == "--message":
            msg = args.pop(0)
        else:
            files.append(a)
    if not files and not deletes:
        raise SystemExit("nothing to push")

    ref = gh("GET", f"{API}/git/ref/heads/main")
    head_sha = ref["object"]["sha"]
    print(f"main @ {head_sha}")
    # ── base 断言（2026-09-17 审计 M15）────────────────────────────────────
    # 这两个脚本都以**当前远端 head** 为 parent 建提交，于是永远 fast-forward，
    # `force: False` 对「内容被换掉」毫无约束；mirror 更是按本地索引重建整棵树。
    # 所以要求调用方显式给出「我以为远端在哪」：
    #   --expect-base <sha>    与远端 head 不一致就直接拒绝（别人推过 / 我看错了分支）
    # 取不到或不匹配绝不继续 —— 这条检查的意义就是**在写之前**拦住。
    if expect_base is None:
        raise SystemExit(
            "拒绝推送：缺少 --expect-base <sha>\n"
            f"  当前 main @ {ref['object']['sha']}\n"
            "  这样能保证你看到的远端状态与你要提交的 parent 一致（审计 M15）；\n"
            "  确认无误后把上面那个 sha 传进来：--expect-base <sha>")
    if expect_base != ref["object"]["sha"]:
        raise SystemExit(
            f"拒绝推送：--expect-base {expect_base} 与远端 main {ref['object']['sha']} 不一致\n"
            "  说明远端已经动过（别人推了 / 你看到的是旧状态）—— 先 fetch 看清再重来。")


    all_entries = tree_of(head_sha)
    if msg is None:
        msg = "chore: push changed files via the git-database API\n\nCo-Authored-By: push-via-api (git transport unreliable on this host)"

    # verify deletes exist in the target tree
    for f in deletes:
        if f not in all_entries:
            raise SystemExit(f"delete target not on remote: {f}")

    # 1. create blobs for the changed files
    new_sha = {}
    for f in files:
        with open(f, "rb") as fh:
            content = base64.b64encode(fh.read()).decode()
        b = gh("POST", f"{API}/git/blobs", {"content": content, "encoding": "base64"})
        new_sha[f] = b["sha"]
        print(f"blob {f} -> {b['sha'][:8]}")

    # 2. rebuild trees bottom-up along the changed paths
    by_dir = {}
    for f in files:
        d, name = os.path.split(f)
        by_dir.setdefault(d, []).append((name, new_sha[f]))
    for f in deletes:
        d, name = os.path.split(f)
        by_dir.setdefault(d, [])
        if (name, None) not in [(n, s) for n, s in by_dir[d]]:
            by_dir[d].append((name, None))

    # directories to rebuild: dirs of changed files plus all ancestors, deepest first
    dirs = set()
    for d in by_dir:
        cur = d
        while cur:
            dirs.add(cur)
            cur = os.path.dirname(cur)
    dirs.add("")
    # depth = number of path segments ("/" -> 0 segments? no: "" -> 1 as root sentinel)
    def depth(d):
        return 0 if d == "" else d.count("/") + 1
    order = sorted(dirs, key=depth, reverse=True)

    build = {}
    for d in order:
        entries = []
        added = set()
        prefix = d + "/" if d else ""
        for path, (mode, typ, sha) in all_entries.items():
            if path in deletes:
                continue
            if os.path.dirname(path) == d:
                base = os.path.basename(path)
                sub = os.path.join(d, base) if d else base
                # replace with rebuilt subtree if needed
                if sub in build:
                    sha = build[sub]
                    typ = "tree"
                    mode = "040000"
                entries.append({"path": base, "mode": mode, "type": typ, "sha": sha})
                added.add(base)
        for name, sha in by_dir.get(d, []):
            if sha is None:
                # deletion: skip this entry so it drops out of the rebuilt tree
                continue
            entries.append({"path": name, "mode": "100644", "type": "blob", "sha": sha})
        # New subtrees built in this run whose parent is d but that did NOT exist
        # on the remote (so the all_entries loop above never saw them): attach now.
        for sub in sorted(build):
            if os.path.dirname(sub) == d and os.path.basename(sub) not in added:
                entries.append({"path": os.path.basename(sub), "mode": "040000", "type": "tree", "sha": build[sub]})
        t = gh("POST", f"{API}/git/trees", {"tree": entries})
        build[d] = t["sha"]
        print(f"tree {'/' if d == '' else d} -> {t['sha'][:8]}")

    root = build[""]

    # 3. create commit
    c = gh("POST", f"{API}/git/commits", {
        "message": msg,
        "tree": root,
        "parents": [head_sha],
    })
    print(f"commit {c['sha'][:12]}")

    # 4. fast-forward main
    gh("PATCH", f"{API}/git/refs/heads/main", {"sha": c["sha"], "force": False})
    print(f"main -> {c['sha']}")


if __name__ == "__main__":
    main()
