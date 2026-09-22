#!/bin/bash
# Capture the realistic half of the terminal conformance corpus.
#
# Each case records a real program's raw pty output. `script` is what makes this
# honest: it allocates a pty, so the program believes it is talking to a terminal
# and emits its real escape sequences — piping to a file would give plain text and
# test nothing.
#
# Determinism: the corpus is committed, so the tests are reproducible even though
# `top` or `htop` would differ if re-captured. Cases that would never terminate
# on their own are bounded with `timeout` and fed a quit key.
#
# Usage: capture-corpus.sh <outputDir>
set -u

OUT="${1:?usage: capture-corpus.sh <outputDir>}"
mkdir -p "$OUT"
export TERM=xterm-256color
export LC_ALL=C.UTF-8

# capture <name> <command>
capture() {
    local name="$1" cmd="$2"
    local path="$OUT/real-$name.bin"
    # stdin is closed so a pager reading input exits immediately, while stdout
    # stays the pty and keeps full control sequences.
    timeout 20 script -q -c "timeout 10 $cmd" /dev/null </dev/null >"$path" 2>/dev/null
    local size
    size=$(stat -c%s "$path" 2>/dev/null || echo 0)
    if [ "$size" -lt 64 ]; then
        echo "  !! $name produced only $size bytes (skipped)" >&2
        rm -f "$path"
        return 1
    fi
    printf '  %-22s %8d bytes\n' "real-$name.bin" "$size"
}

echo "capturing real program output into $OUT"
capture ls-colour    "ls --color=always -la /usr/bin"
capture man-page     "man ls"
capture top-batch    "top -b -n 2"
capture htop-screen  "htop -d 10"
capture vim-screen   "vi -c 'set nocompatible' -c 'normal ihello world' -c 'wq!' /tmp/corpus-vi-scratch"
capture less-pager   "less -R /etc/services"
capture git-log      "git -C /usr log --oneline --graph -30"
capture dpkg-list    "dpkg -l"
capture find-tree    "find /usr/share/doc -maxdepth 1"
capture python-repl  "python3 -c 'print(chr(27)+\"[31mred\"+chr(27)+\"[0m\")'"
capture bash-prompt  "bash --norc -c 'PS1=\"\\[\\e[32m\\]\\u@\\h\\[\\e[0m\\]:\\w\\$ \"; ls /usr'"
capture tput-colours "for i in \$(seq 0 255); do tput setaf \$i; printf '%3d ' \$i; done; tput sgr0"
capture watch-loop   "watch -n 1 -t 'echo tick' & sleep 3; kill %1"

echo
echo "total: $(cat "$OUT"/real-*.bin 2>/dev/null | wc -c) bytes across $(ls "$OUT"/real-*.bin 2>/dev/null | wc -l) cases"
