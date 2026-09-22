# terminal-conformance

终端行为回归闸门 —— 用**差分对照**证明终端实现的行为没有漂移。

它是「终端自研」计划的**阶段 0**，但**不依赖那个计划**：自研已于 2026-09-11 中止
（见 [`docs/terminal-rewrite-plan.md`](../../docs/terminal-rewrite-plan.md)），这个模块被保留
下来，因为它是现成的起点：新实现只需在 `Harness.implementations()` 里注册一行。

⚠️ **它验的是注册在 `implementations()` 里的那份实现**（今天只有一份：`libs/` 里 vendored 的
Termux oracle）。所以准确的说法是「对 oracle 的回归」：**升级 app 的 `terminal-view` 不会自动
被它验到** —— 那要同时更新 oracle（`tools/fetch-oracle.sh`）与语料。2026-09-17 审计（M6）
为此加了一条断言 `conformanceOracleVersion`：oracle jar 文件名里的版本必须与
`android/app/build.gradle.kts` 里声明的 `terminal-view`/`terminal-emulator` 版本一致，
取不到任一边直接失败（原先两者零绑定，升级依赖后门禁照样全绿）。

它不实现任何终端功能，只回答一个问题——「什么叫做对了」。

本模块是**纯 JVM**（自带 `android.util` / `android.graphics` 桩类）、**不是 `:app` 的依赖**；
CI 里有不变量在守这条：`:app` 的 release runtime classpath 一旦出现 `terminal-conformance`，
构建直接失败。

## 覆盖到哪一层

- **逐片比对**：每个用例按 128 片喂入，每片都比一次屏幕摘要（不是只比最终屏幕）。
- **中途 resize**：用例名里带 `resize` 的（如 `syn-resize-baseline`）会在流中点改一次窗口尺寸
  （24×80 → 26×90），并**为尺寸变化本身记一步**。2026-09-17 审计（L12）发现
  `TerminalUnderTest.resize` 此前**零调用** —— 而真机上最容易撞到的回归（旋转、软键盘弹出改
  行列→重排）恰恰在那条路径上。
- **覆盖率**：`conformanceCoverage` 断言语料仍覆盖一份能力清单（含按 East Asian Width 判的
  「宽字符」；2026-09-17 审计 M7 之前那条判据是「有任一字节 ≥0x80」，等于没在守）。

## 为什么需要它

终端实现的风险不是写不出来，而是**正确性长尾**：`vim` / `htop` / `tmux` / `man`
会把每一个 escape 序列的 bug 都翻出来，而本项目没有真机自动化测试。
靠肉眼比对无法收敛。

做法：把参考实现（Termux `terminal-emulator`）当**黑盒**——同一段字节流喂给两边，
逐格比较屏幕。于是「感觉差不多」变成「**N/M 一致**」。

## 用法

```sh
cd android
./gradlew :terminal-conformance:conformance            # 全部门禁：覆盖率 + 自检 + 比对
./gradlew :terminal-conformance:conformanceCoverage    # 语料覆盖了哪些能力
./gradlew :terminal-conformance:conformanceSelftest    # 证明测试台「会失败」
./gradlew :terminal-conformance:conformanceCheck       # 逐用例比对
./gradlew :terminal-conformance:conformanceList        # 列出语料
```

看某个用例的实际屏幕（ANSI 渲染 + 规范 dump）：

```sh
java -cp <runtimeClasspath> dsh.conformance.Harness dump corpus real-man-page
```

## 三个命令各自的职责

| 命令 | 回答的问题 | 失败意味着 |
|---|---|---|
| `coverage` | 语料**真的**在练这些能力吗？ | 某个能力没有任何用例覆盖——测试在空转 |
| `selftest` | 这个测试台**还能失败吗**？ | 三条性质之一被破坏（见下） |
| `check` | 各实现一致吗？ | 真实漂移 |

`coverage` 同时报两类证据，含义不同：

- **in input** —— 字节流**含**该序列。精确，但只说明语料练到了，不说明谁处理对了。
- **on screen** —— 结果**可见于**末屏。这是可观测状态，也是差分真正比较的东西。

只出现在 input、不出现在 screen 的能力仍有区分力（两边必须对"结果没变"达成一致），
但漏报概率更高，所以报告把两者分开，不混为一谈。

`selftest` 的三条性质，各有不同失效模式：

1. **参考实现确定性** —— 分片喂入下两次结果必须逐片一致。否则没有任何东西可比。
2. **语料可区分性** —— 用例必须产出互不相同的轨迹（要求 ≥3/4 唯一）。
   否则"全部一致"毫无意义。
3. **已知错误实现能被发现** —— 一个**什么都不画**的实现必须被**每一个**用例
   标记为不同。否则说明比较根本没接上。

这三条都是实测出来的、不是设想：第 3 条最初是 **49/52**，暴露出两个真实缺陷——
（a）只比对末屏会漏掉中间状态（`CSI 2J` 结尾的用例末屏全空），
（b）一次性喂入没有测到序列跨包。改成**分片喂入 + 逐片轨迹比对**后变 52/52，
语料区分度也从 50/52 升到 52/52。

## 比较的是什么

`Trace`：每个用例按切片喂入，**每片之后**对屏幕取 SHA-256，得到一条轨迹。
最终 `Screen` 保留下来只用于生成可读 diff。

分片是刻意的：真实 pty 数据不会一次到齐，切片边界不对齐任何东西——
一次 `feed` 可能停在 escape 序列或 UTF-8 字符中间。一次性喂入永远测不到这个。

`Screen` 抓取用户能观察到的一切：

- 每格：字符、前景色、背景色、属性位（bold/italic/underline/blink/inverse/invisible/strike）
- 光标位置、备用屏状态、标题
- **每行的"自动换行"标志** —— 不是装饰：终端记录某行是"折行产生"还是"换行符产生"，
  resize 重排与选择范围都依赖它。两个实现在字符上完全一致但这里不同，
  用户一旋转屏幕就会分叉。

颜色用参考实现的编码作为**测试台契约**：`0..255` 是调色板索引，
`256` = 默认前景、`257` = 默认背景。被测实现必须把自己的内部默认色状态映射到这两个哨兵值，
就像必须把 SGR 31 映射到索引 1 一样。

## 语料

52 个用例，两类：

| 类型 | 来源 | 作用 |
|---|---|---|
| `real-*.bin`（12 个） | `tools/capture-corpus.sh` 用 `script` 分配 pty 录制真实程序输出 | 真实感——真实程序的真实序列 |
| `syn-*.bin`（40 个） | `tools/generate-corpus.py` 脚本生成 | 覆盖率——每个能力一个靶向用例 |

**为什么两类都要**：真实程序是糟糕的覆盖工具。ncurses 只用一小块稳定的 escape 子集，
于是整片能力族——滚动区域、origin 模式、DEC 图形字符集、字符集切换、插入/删除——
**要么从不出现，要么只是顺带经过**。合成用例一个能力一个用例，
失败时直接指出缺的是哪个能力，而不是"htop 看着不对"。

语料是**提交进仓库**的：即使 `top`/`htop` 重录会不同，测试结果也可复现。

两个脚本都是纯函数：同一个脚本产出同一份字节，所以语料的任何变化都是刻意的编辑。

```sh
./tools/generate-corpus.py corpus      # 重新生成合成用例
./tools/capture-corpus.sh corpus       # 重新录制真实程序输出（需要 pty）
```

## oracle（参考实现）

`libs/termux-terminal-emulator-0.118.1-classes.jar`（63,477 B，
sha256 `09ec707eb38e89ee8a7046201240f62fd68c5fbbc22116e187bf6a5d859de2dc`）
从 JitPack 的 `terminal-emulator-0.118.1.aar` 里取出 `classes.jar`。

**vendored 而非动态解析**，理由有二：离线可跑（几秒完成）；oracle 的字节被钉死——
否则一个静默变化的 oracle 会在没有任何测试改动的情况下挪动标尺。

```sh
./tools/fetch-oracle.sh    # 重新获取并校验 SHA-256
```

`TermuxOracle` 刻意放在 `com.termux.terminal` 包下：读屏需要访问包私有的
`TerminalBuffer.mLines`。一致性测试台正是"够到参考实现内部"合理的地方——
退而求其次只读文本，会静默漏掉颜色与属性差异，而终端的 bug 恰恰藏在那里。

## 这个模块为什么不是 Android 模块，也为什么不能进 APK

`src/main/java/android/{util,graphics}/` 是**桩类**：参考实现用了
`android.util.Base64`（仅 OSC 52 剪贴板一处）、`android.util.Log`、`android.graphics.Color`
的三个取值函数。桩让整套 emulator 能在纯 JVM 上链接运行。

代价是：本模块**绝不能**成为 `:app` 的依赖，否则这些 `android.*` 桩会与真正的
Android 框架类冲突。它现在是独立的 `java-library` 模块，`:app` 不依赖它。

同理，`libs/` 里的 oracle jar 也不得进入 APK——它只是测试夹具。

## 与 clean-room 的关系

**从规范写，把参考实现只当黑盒 oracle。**

- 依据规范：xterm ctlseqs、ECMA-48、VT510 手册、Unicode 数据文件
- **不逐行阅读参考实现源码再"改写"**——那会让自研代码成为其衍生作品
- 测试时只调用其编译产物、比较输出，属黑盒行为对照

好处不止法律：照规范写能学到「应该是什么样」，而不是继承「它是什么样」。

## 目录

```
build.gradle.kts    四个 JavaExec 任务 + conformance 聚合门禁
corpus/             52 个 .bin 用例（提交进仓库）
libs/               vendored oracle jar（仅测试用）
src/main/java/
  dsh/conformance/  Screen / Trace / Coverage / TerminalUnderTest / BrokenTerminal / Harness
  com/termux/terminal/TermuxOracle.java      参考实现适配器（需要包内访问）
  android/util/     Base64 / Log 桩
  android/graphics/ Color 桩
tools/              generate-corpus.py / capture-corpus.sh / fetch-oracle.sh
```

## 加入自研实现

自研目前不做；这一节是给「哪天重新考虑自研」或「想拿另一份实现来对照」时用的。

在 `Harness.implementations()` 里注册即可，测试台其余部分无需改动。
第一个条目是基线，其余与之逐片比较。
