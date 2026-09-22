#!/usr/bin/env python3
"""Generate the synthetic half of the terminal conformance corpus.

Real programs are the realistic half of the corpus (see capture-corpus.sh), but
they are a poor coverage instrument: ncurses uses a small, stable subset of the
escape vocabulary, so whole feature families — scroll regions, origin mode, the
DEC graphics charset, character-set selection, insert/delete — are either never
exercised or exercised only in passing. These cases target one capability each,
so a failure names the missing feature instead of "htop looks wrong".

Every case is a pure function of this file: same script, same bytes, so a diff in
the corpus is always a deliberate edit.

Usage: generate-corpus.py <outputDir>
"""

import os
import sys

ESC = b"\x1b"
CSI = ESC + b"["
OSC = ESC + b"]"
ST = ESC + b"\\"
BEL = b"\x07"


def case(name, body):
    """One corpus entry: a name and the exact bytes to feed the terminal."""
    if isinstance(body, str):
        body = body.encode("utf-8")
    return name, body


def cases():
    out = []

    # ── SGR: colour and attribute vocabulary ────────────────────────────────
    out.append(case("sgr-16color", CSI + b"0m" + b"".join(
        CSI + f"{code}m{code:>3} ".encode() for code in list(range(30, 38)) + list(range(90, 98)))
        + CSI + b"0m\r\n"
        + b"".join(CSI + f"{code}m{code:>3} ".encode() for code in list(range(40, 48)) + list(range(100, 108)))
        + CSI + b"0m\r\n"))

    out.append(case("sgr-256color", CSI + b"0m" + b"".join(
        CSI + f"38;5;{n}m{n:>4} ".encode() for n in range(0, 256, 8)) + CSI + b"0m\r\n"))

    out.append(case("sgr-truecolor",
                    CSI + b"0m"
                    + CSI + b"38;2;255;0;0mred "
                    + CSI + b"38;2;0;255;0mgreen "
                    + CSI + b"38;2;0;0;255mblue "
                    + CSI + b"48;2;80;0;120;38;2;255;255;0myellow-on-purple"
                    + CSI + b"0m\r\n"))

    out.append(case("sgr-attributes",
                    CSI + b"0mplain "
                    + CSI + b"1mbold" + CSI + b"22m "
                    + CSI + b"3mitalic" + CSI + b"23m "
                    + CSI + b"4munderline" + CSI + b"24m "
                    + CSI + b"5mblink" + CSI + b"25m "
                    + CSI + b"7minverse" + CSI + b"27m "
                    + CSI + b"8mhidden" + CSI + b"28m "
                    + CSI + b"9mstrike" + CSI + b"29m "
                    + CSI + b"2mdim" + CSI + b"22m"
                    + CSI + b"0m\r\n"))

    out.append(case("sgr-reset-variants",
                    CSI + b"1;4;31mstyled" + CSI + b"m"          # bare CSI m == reset
                    + b" after-bare-m " + CSI + b"31;44m" + CSI + b"0m" + b"reset\r\n"))

    # ── Cursor movement ────────────────────────────────────────────────────
    out.append(case("cursor-moves",
                    CSI + b"2J" + CSI + b"H"
                    + b"0123456789" * 8
                    + CSI + b"5;10H" + b"@"
                    + CSI + b"3A" + b"^"
                    + CSI + b"4B" + b"v"
                    + CSI + b"7C" + b">"
                    + CSI + b"9D" + b"<"
                    + CSI + b"1;1H" + b"1"
                    + CSI + b"12;40H" + b"M"
                    + CSI + b"24;80H" + b"E"
                    + CSI + b"10d" + b"D"
                    + CSI + b"20G" + b"G"
                    + CSI + b"2;2H" + b"x"))

    out.append(case("cursor-save-restore",
                    CSI + b"5;20H" + b"ORIGIN"
                    + CSI + b"s" + CSI + b"15;5H" + b"elsewhere"
                    + CSI + b"u" + b"|back"))

    # ── Erase ──────────────────────────────────────────────────────────────
    out.append(case("erase-line",
                    b"AAAAAAAAAA\r\nBBBBBBBBBB\r\nCCCCCCCCCC\r\n"
                    + CSI + b"1;3H" + CSI + b"0K"          # erase to end of line
                    + CSI + b"2;3H" + CSI + b"1K"          # erase to start of line
                    + CSI + b"3;3H" + CSI + b"2K"          # erase whole line
                    + CSI + b"4;1H"))

    out.append(case("erase-display",
                    b"".join(b"row %-74d\r\n" % n for n in range(1, 20))
                    + CSI + b"5;5H" + CSI + b"0J"
                    + CSI + b"10;10H" + CSI + b"1J"
                    + CSI + b"2J"))

    out.append(case("erase-chars",
                    b"ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n"
                    + CSI + b"1;5H" + CSI + b"10X"          # ECH: blank 10 cells, no shift
                    + CSI + b"2;1H" + b"0123456789" + CSI + b"1;1H" + CSI + b"3X"))

    # ── Insert / delete ────────────────────────────────────────────────────
    out.append(case("insert-delete-chars",
                    b"ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\nKEEP\r\n"
                    + CSI + b"1;5H" + CSI + b"4@"           # ICH: open a 4-cell gap
                    + CSI + b"2;1H" + CSI + b"2P"           # DCH: remove 2 cells
                    + CSI + b"3;1H"))

    out.append(case("insert-delete-lines",
                    b"".join(b"line %-72d\r\n" % n for n in range(1, 11))
                    + CSI + b"1;1H" + CSI + b"3L"           # IL: push 3 blank lines in
                    + CSI + b"6;1H" + CSI + b"2M"           # DL: pull 2 lines out
                    + CSI + b"1;1H"))

    # ── Scrolling regions and origin mode ──────────────────────────────────
    out.append(case("scroll-region",
                    CSI + b"5;15r"                          # DECSTBM rows 5..15
                    + CSI + b"5;1H"
                    + b"".join(b"scrolling %-60d\r\n" % n for n in range(1, 16))
                    + CSI + b"r"                            # reset region
                    + CSI + b"1;1H"))

    out.append(case("scroll-region-origin",
                    CSI + b"6;18r" + CSI + b"?6h"           # DECOM on: cursor is region-relative
                    + CSI + b"1;1H" + b"origin-relative-home"
                    + CSI + b"13;1H" + b"bottom-of-region"
                    + CSI + b"?6l" + CSI + b"r"))

    out.append(case("scroll-region-partial",
                    # Writing on the last region row must scroll only the region.
                    CSI + b"3;8r" + CSI + b"3;1H"
                    + b"".join(b"r%-70d\r\n" % n for n in range(1, 10))
                    + CSI + b"r" + CSI + b"1;1H" + b"OUTSIDE"))

    # ── Alternate screen ───────────────────────────────────────────────────
    out.append(case("alt-screen",
                    b"primary line 1\r\nprimary line 2\r\n"
                    + CSI + b"?1049h"                       # enter alt screen
                    + CSI + b"2J" + CSI + b"H" + b"alt screen content"
                    + CSI + b"?1049l"                       # leave: primary must be restored
                    + b" <back"))

    out.append(case("alt-screen-1047",
                    b"PRIMARY\r\n"
                    + CSI + b"?1047h" + CSI + b"2J" + b"H" + b"ALT-1047"
                    + CSI + b"?1047l" + b"|"))

    # ── DEC graphics charset (box drawing; ncurses depends on it) ──────────
    out.append(case("charset-dec-graphics",
                    ESC + b"(0"                              # designate G0 = DEC special graphics
                    + b"lqqqqqqqw\r\nx Hello x\r\nmqqqqqqqj\r\n"
                    + ESC + b"(B"                            # back to ASCII
                    + b"plain again\r\n"))

    out.append(case("charset-shift-in-out",
                    ESC + b")0"                              # G1 = graphics
                    + b"ascii " + b"\x0e" + b"lqwqk" + b"\x0f" + b" ascii\r\n"))

    # ── Autowrap behaviour at the right margin ─────────────────────────────
    out.append(case("wrap-exact-width",
                    b"X" * 80 + b"Y"                        # 81st char wraps to next row
                    + b"\r\n" + b"Z" * 79 + b"\r\n"))

    out.append(case("wrap-pending",
                    # Printing the last column leaves the cursor "pending wrap":
                    # CR must clear that state rather than wrap first.
                    b"A" * 80 + b"\r" + b"B\r\n"))

    out.append(case("wrap-disabled",
                    CSI + b"?7l" + b"W" * 90 + CSI + b"?7h" + b"\r\n"))

    # ── Control characters ─────────────────────────────────────────────────
    out.append(case("controls-basic",
                    b"col1\tcol2\tcol3\r\n"
                    b"backspace: ABC\b\b\bXYZ\r\n"
                    b"vertical: \x0b next\r\n"
                    b"formfeed: \x0c after\r\n"
                    b"bell: \x07 done\r\n"))

    out.append(case("carriage-return-overwrite",
                    b"progress:   0%\r" b"progress:  50%\r" b"progress: 100%\r\n"))

    # ── OSC ────────────────────────────────────────────────────────────────
    out.append(case("osc-title",
                    OSC + b"0;window title" + ST
                    + OSC + b"2;icon and title" + BEL
                    + b"title was set\r\n"))

    out.append(case("osc-hyperlink",
                    OSC + b"8;;https://example.com/" + ST + b"link text"
                    + OSC + b"8;;" + ST + b" after link\r\n"))

    # ── Tabs ───────────────────────────────────────────────────────────────
    out.append(case("tab-stops",
                    b"a\tb\tc\td\r\n"
                    + CSI + b"3g"                            # clear all tab stops
                    + CSI + b"5G" + ESC + b"H"               # set one at column 5
                    + b"1\t2\t3\r\n"))

    # ── UTF-8 and wide characters ──────────────────────────────────────────
    out.append(case("utf8-wide-cjk",
                    "中文宽字符测试｜全角ＡＢＣ\r\n"
                    "混合 mixed 文本 with ASCII\r\n"
                    "日本語のテキスト、韓国語テキスト\r\n"))

    out.append(case("utf8-combining",
                    "e\u0301 combining acute\r\n"
                    "a\u0300a\u0301a\u0302a\u0303\r\n"
                    "Z\u0335\u0336\u0337 stacked\r\n"))

    out.append(case("utf8-emoji",
                    "emoji: \U0001f600\U0001f680\U0001f4a1\r\n"
                    "zwj: \U0001f469\u200d\U0001f4bb\r\n"
                    "flags: \U0001f1e8\U0001f1f3\r\n"))

    out.append(case("utf8-box-drawing",
                    "┌─────────┬─────────┐\r\n"
                    "│ unicorn │ box     │\r\n"
                    "├─────────┼─────────┤\r\n"
                    "│ drawing │ ✓ ✗ ● ○ │\r\n"
                    "└─────────┴─────────┘\r\n"))

    out.append(case("utf8-boundary-split",
                    # A multi-byte sequence split across two feeds: the decoder must
                    # hold the partial sequence rather than emit a replacement char.
                    "中".encode("utf-8")[:1] + "中".encode("utf-8")[1:] + b"\r\n"
                    + "文".encode("utf-8")))

    # ── Scrolling and scrollback ───────────────────────────────────────────
    out.append(case("scroll-many-lines",
                    b"".join(b"scroll line %-60d\r\n" % n for n in range(1, 121))))

    out.append(case("scroll-single-line-no-newline",
                    b"".join(b"no newline %-58d" % n for n in range(1, 120))))

    # ── Modes ──────────────────────────────────────────────────────────────
    out.append(case("mode-appcursor-keys",
                    CSI + b"?1h" + b"app cursor keys on" + CSI + b"?1l + off\r\n"))

    out.append(case("mode-reverse-video",
                    CSI + b"?5h" + b"reverse video" + CSI + b"?5l + normal\r\n"))

    out.append(case("mode-mouse-enable",
                    # The emulator keeps mode state; nothing is drawn, but a
                    # decoder that mishandles ?1000/?1006 desynchronises on the rest.
                    CSI + b"?1000h" + b"mouse 1000 on "
                    + CSI + b"?1002h" + b"1002 on "
                    + CSI + b"?1006h" + b"1006 on "
                    + CSI + b"?1000l?1002l?1006l" + b"all off\r\n"))

    out.append(case("mode-bracketed-paste",
                    CSI + b"?2004h" + b"bracketed paste on"
                    + CSI + b"?2004l" + b" off\r\n"))

    out.append(case("mode-cursor-visibility",
                    CSI + b"?25l" + b"cursor hidden"
                    + CSI + b"?25h" + b" shown\r\n"))

    # ── Resize behaviour (driven by the harness, not by bytes) ─────────────
    out.append(case("resize-baseline",
                    b"".join(b"resize row %-60d\r\n" % n for n in range(1, 21))))

    return out


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: generate-corpus.py <outputDir>")
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    written = 0
    for name, body in cases():
        path = os.path.join(outdir, "syn-" + name + ".bin")
        with open(path, "wb") as handle:
            handle.write(body)
        written += 1
    print(f"wrote {written} synthetic cases to {outdir}")


if __name__ == "__main__":
    main()
