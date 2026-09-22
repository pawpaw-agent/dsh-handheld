# 终端自研计划（Termux 替换）

> ## ⛔ 已中止（2026-09-11）
>
> **决定：保留 Termux，不继续自研。** 阶段 2–5 不做。
>
> **理由**：自研换不来性能收益。阶段 1 只替换了 pty 层——它每会话被调用约 5 次、实测
> **0.50 ms/会话**，且不在热路径上（字节到达 → 解析 → 渲染全在 Java 侧，一行未动）。
> 既然拿不到可感知的改善，就没有理由为它承担长期维护成本。详见下面「中止记录」。
>
> **保留**：[`android/terminal-conformance`](../android/terminal-conformance/README.md)
> （阶段 0 的差异测试台）。它是纯 JVM 测试模块、不进 APK，且对**任何**终端改动都有用
> ——包括将来升级 `terminal-view` 时验证行为没有漂移。CI 里仍是门禁。
>
> **回退**：阶段 1（自研 pty）已从 `main` 回退（`46ed573` / `eff827e` / `70e97b1`）。
> 代码保留在 git 历史里，若将来需要可取回。
>
> **原计划全文**（分阶段方案、工程结构调整、规模与顺序、风险登记、可以不做的部分）在
> git 历史里，`1c07e0d` 及更早。本文只保留中止记录、仍然有效的结论与两个附录。

---

## 中止记录

### 为什么停

| 问题 | 答案 |
|---|---|
| 自研能更快吗？ | **不能。** pty 层不在热路径；热路径（emit 解析 + 视图渲染）仍 100% 是 Termux 的代码 |
| 自研买到了什么？ | 一层可测试、可修的原生代码（17 项宿主测试）；但**性能上零收益** |
| 代价是什么？ | 阶段 2 占总工作量 **60%**，且自研 emulator 很可能**先比 Termux 慢**（十年打磨的字节级状态机） |
| 那还做吗？ | 不做。没有可感知收益支撑这个体量 |

### 阶段 1 做了什么、为什么回退

阶段 1 替换了 `libtermux.so`（pty 分配 + 子进程启动），实现要点与发现：

- 契约取自**产物**而非上游 master——两者已分叉（产物的 `setPtyWindowSize` 3 参、
  `createSubprocess` 7 参；master 分别多出 cell size 与两个参数）
- 两处写错也不会编译失败的语义：`waitFor` 对信号终止返回 **`-WTERMSIG`（负数）**、
  控制终端靠 `setsid()` 后 `open(pts)` **自动获得**（无显式 `TIOCSCTTY`）
- **发现并修掉自己引入的回归**：漏了 `O_CLOEXEC`（Termux 有）。App 会在会话存活期间
  启动 `ProcessBuilder` 辅助进程，它们会继承 pty master，导致已关闭的会话收不到 hangup
- 验证三层：宿主行为测试 17/17（含 200 次会话零 fd 泄漏、0.50 ms/会话）、CI 全绿、
  APK 内 `.so` 含构建标记（11,528 字节 vs upstream 9,928）

**回退的理由不是它有缺陷，而是它没有收益。** 而且它带来一个名实不符的陷阱：Termux 的
`JNI` 类写死了 `System.loadLibrary("termux")`，所以自研实现**必须**仍叫 `libtermux.so`
——一个名叫 `libtermux.so` 却不含 Termux 的文件，将来任何人都（包括写它的我）会困惑。

### 原计划里仍然有效的结论

分阶段细节已删（在 git 历史里），下面这些在重新评估时仍然成立：

- **规模**：待替换约 38 万字节 Java（≈9,000–10,000 行）+ 约 200 行 C。相对工作量：阶段 2
  （emulator 核心）**60%**、阶段 4（视图层）15%、阶段 3（会话/IO）10%、阶段 0/1/5 各 5%
  ——阶段 2 是唯一的长杆。
- **合并纪律**：逐阶段独立合并，每阶段结束时 App 都保持可用；任何阶段可停，停下时旧实现
  仍在，不会烂尾。若拆出 `terminal-core`，它不得出现任何 `import android.*`，由 CI 静态检查
  强制——这条一破，JVM 差异测试台就没了。
- **native pty 无法回避**：pty 不能在 Java 层创建，那 ~200 行 C 是唯一必须 native 的部分。
  **`WcWidth` 的 39.9 KB 表是数据不是逻辑**，从 Unicode 数据文件生成即可；**额外键栏重写
  收益最小**（1,493 行、纯 GridLayout），且已 vendored 并改过。
- **无真机自动化测试**：差异测试台跑在 CI；实机部分需要可复现的 adb 检查脚本。

### 如果将来要重新评估

先回答一个问题：**你要的是「更快」，还是「能做 Termux 做不到的事」？**

- 只要更快 → 别重写。真正的性能杠杆在**视图层渲染**（阶段 4）与 **emulator 的 damage
  模型**（阶段 2），而不是替换代码本身
- 想要新能力（终端内搜索/链接跳转/内联图片/把 dsh 输出投射成原生 UI）→ 那些**只有**自研
  才可能；此时阶段 0 的测试台是现成的起点，阶段 1 的代码也在 git 历史里

重开之前请先加**吞吐基准**：把「感觉不卡」变成「N MB/s」「M ms/帧」，否则又会陷入「写完
凭感觉判断」。

**阶段 0 保留**：`android/terminal-conformance/` 是纯 JVM 的差分测试台，不进 APK，CI 里仍是
门禁——它是任何终端改动（包括升级 `terminal-view`）的行为回归闸门。

---

## 附录 A：现在踩过的 6 个 API 陷阱

自研后应全部消失（记录在此以免重蹈）：

| 陷阱 | 现象 |
|---|---|
| `setTextSize()` | KDoc 说 dp，**实际吃 px** |
| `setTerminalCursorBlinkerRate()` | **必须先调**，否则随后的 `setState(true,true)` 静默失效、光标不闪 |
| `onTextChanged` | 不手动调 `onScreenUpdated()` → 字节进了 emulator 但无人重绘 → **打字卡顿**（只能等 500 ms 光标闪烁蹭刷） |
| `requestFocus()` | 不够，必须显式 `showSoftInput` 才弹键盘 |
| `isAcceptingText` | 焦点在 TerminalView 时**恒为 true**，据此判断会误判 |
| `onScale` | 必须返回 `1f` 关掉它自带的缩放手势 |

## 附录 B：许可证核对结论

**结论：GPL-3.0 这个声明结果上是对的，但当时给的理由是错的。**
（2026-09-11 核对；本条经过一次反转，下面是订正后的版本）

### 两个 Termux AAR：Apache-2.0 ✔

- Termux 根 `LICENSE.md` 整个仓库 GPLv3，但 **`terminal-view` 与 `terminal-emulator` 在
  「Exceptions」中列为 Apache-2.0**（`v0.118.1` 与 `master` 措辞一致）
- 上游 [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)
  本身即 Apache-2.0（带 `LICENSE` + `MODULE_LICENSE_APACHE2` + `NOTICE`）；其 `libtermexec/`
  （JNI pty）与 `emulatorview/`（Java 模拟器）正是 Termux 这两个模块的来源，源码文件
  **无任何 GPL 头**
- 因此**打包进 APK 的 43 个 `com.termux.terminal` / `com.termux.view` 类是 Apache-2.0**

### vendored 的 7 个 extrakeys 文件：GPLv3-only ✘（关键订正）

先前记的「MIT，不在 GPLv3-only 范围内」**是错的**。错误来源：核的是 **`master`**，而项目
pin 的是 **`v0.118.1`**，两者 `termux-shared/LICENSE.md` 的结构**正好相反**：

| tag | `termux-shared` 主许可 | MIT 例外清单 |
|---|---|---|
| `v0.118.1`（本项目所用） | **GPLv3 only** | **逐文件列举**，不含 `terminal/io/*` |
| `v0.118.2` / `v0.118.3` | GPLv3 only | 同上 |
| `v0.119.0-beta.1` 起 | MIT | 反向：GPLv3-only 收窄为默认目录 `shared/termux/*` |

而 extrakeys 在 0.119 恰好被**移进** `com/termux/shared/termux/extrakeys/` —— 即收窄后
仍属 GPLv3-only 的那个目录，且不在 MIT 例外里。**所以升级 Termux 也解不开这个约束：
这 7 个文件在所有版本下都是 GPLv3-only。**

### 对项目的实际影响

| 组件 | 许可 | 依据 |
|---|---|---|
| `terminal-view` / `terminal-emulator`（43 类 + `libtermux.so`） | Apache-2.0 | 根 `LICENSE.md` 例外条款 |
| 7 个 vendored `shared/terminal/io/**`（含 `extrakeys`） | **GPLv3-only** | `termux-shared/LICENSE.md` @ `v0.118.1` |
| `com.termux.shared.termux.*` | **0 个类** | 未打包 |
| Dropbear `dbclient` / `dbkey` | MIT 风格 | 随附 `LICENSE` |

⇒ 只要那 7 个文件还在源码里，**整个项目就必须是 GPL-3.0**。想改用 MIT/Apache-2.0，唯一
合法路径是**先用自研实现替掉它们**（额外键栏约 250–350 行 Kotlin），此后源码里不再有
GPL 组件。这已不是「补个 NOTICE 的事」，而是一笔要真机回归的独立工程。

**未决**：是否值得为许可证自由做这次重写，仍未决定。

### 合规缺口（与选哪个许可证无关，两边都该补）

`terminal-view`/`terminal-emulator` 是 **Apache-2.0，要求随附许可证文本**，但当前 APK 里
**没有**：CI 把 dropbear 的 `LICENSE.txt` 拷进 `jniLibs/`，而 AGP 只打包那里的 `.so`，
那个 `.txt` 进不了 APK。
