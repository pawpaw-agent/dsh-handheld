/*
 * 修复集（2026-10-08 重新设计）。
 *
 * 每条修复是一个对象：id / 为什么 / 依赖哪些钩子 / 函数体。
 * 函数体由 runner.js 在**钩子齐了之后**调用一次（幂等由各条自己负责）；
 * 单条抛异常只影响它自己（runner 隔离 + 记日志）。
 *
 * 函数体签名：function (ctx, __wrap, postToApp)
 *   ctx        预留（当前为空对象）
 *   __wrap     高频回调的耗时包装；诊断关闭时是原样返回的替身
 *   postToApp  旧版有「页面 → App」的通知桥；桥已删，这里落诊断日志（关闭时空实现）
 *
 * ⚠️ 从旧层移植时**函数体一字未改** —— 只把两个外部依赖改成上面这两个参数。
 * 被有意丢掉的三条（不是遗漏，是它们依赖的东西已经不成立）：
 *   - 外壳浮层（遮罩 + 浮动入口）：走 slots.inject，且宿主 0.2.0 自己就有侧栏开关；
 *   - 会话头的目录按钮：同上，slot 版按钮随 fiber 死，宿主自己有；
 *   - 「连接 / 会话列表」复健：调宿主 connection/sessions 服务，纯注入拿不到；
 *   - 任务完成 / 需要你选择 → 通知 App：App 已改用事件流（api-session/*），桥已删。
 */
(function (root) {
  "use strict";
  var FIXES = [];
  function def(id, why, needs, body, opts) {
    var f = { id: id, why: why, needs: needs, body: body };
    // repeat：每轮 sweep 都跑（幂等且便宜的那些，如"宿主重渲染会把标记冲掉"的兜底）
    if (opts && opts.repeat) f.repeat = true;
    FIXES.push(f);
  }

  def('frame-tagging', '整套 CSS 都以 [data-handheld="frame"] 为前提（63 处）。框架一出现就打标记，不能把这件事绑在别的东西能不能渲染上。', ['sidebarCol'], function (ctx, __wrap, postToApp) {
            var frame = null;
            var raf = 0;
            var disposed = false;
            var mark = function () {
              if (disposed) return;
              var col = document.querySelector('[class*="_sidebarCol"]');
              var next = col === null ? null : col.parentElement;
              if (next !== null) {
                frame = next;
                if (frame.getAttribute("data-handheld") !== "frame") {
                  frame.setAttribute("data-handheld", "frame");
                }
                // 同时在 <html> 上打一个类：让「设置对话框」那 8 条规则可以用
                // html.dsh-handheld-mobile ... 这种普通属性选择器，而不必写
                // html.dsh-handheld-mobile —— 文档级 :has() 是样式重算里最贵的形式，
                // 而这一层要在每次 DOM 变动时参与重算。
                if (!document.documentElement.classList.contains("dsh-handheld-mobile")) {
                  document.documentElement.classList.add("dsh-handheld-mobile");
                  // ⚠️ 2026-10-09 补：宿主那颗「目录/侧栏」开关（button[class*="_toggle"]）。
                  // 层里有两条规则挂在 [data-handheld="toggle"] 上（绝对定位到头部左边缘、
                  // 与右角按钮对称、竖直跟标题行走；>=1024px 隐藏），
                  // 但**从来没有任何 JS 设置过这个属性** ⇒ 两条规则一直是死的：
                  // 那颗按钮保持宿主「桌面窗口标题栏」公式定位，真机实测只有 28x28。
                  // 用户报「会话页面切不了会话」，很可能就是点不中它。
                  // 这条修复是 repeat，宿主重渲染后会补回。
                  var tog = document.querySelector('button[class*="_toggle"]');
                  if (tog && tog.getAttribute("data-handheld") !== "toggle") {
                    tog.setAttribute("data-handheld", "toggle");
                  }
                }
              }
            };
            var markTimed = __wrap("mark", mark);
            var schedule = function () {
              if (raf !== 0 || disposed) return;
              raf = window.requestAnimationFrame(function () {
                raf = 0;
                markTimed();
              });
            };
            var observer = new MutationObserver(schedule);
            // attributes 也要听（审计 L17）：`data-handheld` 有两个写者 —— ShellOverlay 的 cleanup
            // 会 `removeAttribute`，而这里原先只订阅 childList，看不见属性删除；卸载顺序不利时
            // 标记会短暂丢失（整套移动 CSS 失效）。
            observer.observe(document.documentElement, {
              childList: true, subtree: true,
              attributes: true, attributeFilter: ["data-handheld"],
            });
            markTimed();
            return function () {
              disposed = true;
              observer.disconnect();
              if (raf !== 0) window.cancelAnimationFrame(raf);
              document.documentElement.classList.remove("dsh-handheld-mobile");
            };
      
  });

  def('keyboard-scroll', '软键盘弹起会遮住输入框（宿主不滚动）。focusin 与 visualViewport.resize 各补一次 scrollIntoView，两个延时是给键盘动画留的。', [], function (ctx, __wrap, postToApp) {
            var timers = [];
            var fix = function () {
              for (var i = 0; i < timers.length; i++) window.clearTimeout(timers[i]);
              timers = [];
              // 键盘动画要几帧才落定，分两次采：立刻一次、200ms 后一次。
              timers.push(window.setTimeout(doScroll, 40));
              timers.push(window.setTimeout(doScroll, 220));
            };
            var doScroll = function () {
              var el = document.activeElement;
              if (!el || el === document.body || !el.scrollIntoView) return;
              try {
                el.scrollIntoView({ block: "end", inline: "nearest" });
              } catch (e) {
                el.scrollIntoView(false);   // 老引擎只认布尔参数
              }
            };
            document.addEventListener("focusin", fix, true);
            if (window.visualViewport) window.visualViewport.addEventListener("resize", fix);
            return function () {
              document.removeEventListener("focusin", fix, true);
              if (window.visualViewport) window.visualViewport.removeEventListener("resize", fix);
              for (var i = 0; i < timers.length; i++) window.clearTimeout(timers[i]);
            };
      
  });

  def('visual-viewport-height', '把视觉视口高度写进 --dsh-handheld-vh，给 CSS 里需要「键盘弹起后的可视高度」的地方用。', [], function (ctx, __wrap, postToApp) {
            var root = document.documentElement;
            var lastH = 0;
            var apply = function () {
              var vv = window.visualViewport;
              var h = vv && vv.height ? vv.height : window.innerHeight;
              if (!h) return;
              h = Math.round(h);
              // 只在高度真的变了才写：visualViewport.scroll 触发很密，而写 CSS 变量会让样式树
              // 失效 —— 这是本轮新增代码里唯一一处会随滚动反复付钱的地方。
              if (h === lastH) return;
              lastH = h;
              root.style.setProperty("--dsh-handheld-vh", h + "px");
            };
            apply();
            if (window.visualViewport) {
              window.visualViewport.addEventListener("resize", apply);
              window.visualViewport.addEventListener("scroll", apply);
            }
            window.addEventListener("resize", apply);
            window.addEventListener("orientationchange", apply);
            return function () {
              if (window.visualViewport) {
                window.visualViewport.removeEventListener("resize", apply);
                window.visualViewport.removeEventListener("scroll", apply);
              }
              window.removeEventListener("resize", apply);
              window.removeEventListener("orientationchange", apply);
              root.style.removeProperty("--dsh-handheld-vh");
            };
      
  });

  // ── hero / inert 阶段的目录入口（2026-10-08 真机取证）────────────────────
  //
  // 空白会话没有会话头，也就没有宿主那颗目录按钮；而层的单列布局把侧栏压成了抽屉，
  // 于是**新会话页根本打不开会话列表** —— 真机取证：无障碍树里那一页只剩
  // 「打开右侧边栏」，宿主那颗「打开侧边栏」bounds 是 [0,0][0,0]（被层压成零尺寸）。
  //
  // 旧版靠注入层的浮动入口兜底；重设计时浮层随 slots 一起去掉了，CSS 第 6 节却还在
  // —— 于是成了死规则。这条修复把它接回来：造一颗浮动按钮（标记 data-handheld="fab"，
  // 由第 6 节的 CSS 按 phase 显隐），点它时对**宿主那颗 0×0 的按钮**派发 click ——
  // 程序化 click 不受尺寸/可见性限制，处理器照常跑。
  def('hero-drawer-entry',
    '空白会话（hero/inert）没有会话头 → 没有宿主那颗目录按钮 → 新会话页打不开会话列表。'
    + '补一颗浮动入口，点它时把 click 转给宿主那颗被压成 0×0 的按钮。',
    ['sidebarCol'], function (ctx, __wrap, postToApp) {
      var MARK = 'data-handheld';
      // 宿主那颗按钮的找法：类名后缀 + 无障碍标签双路（标签是中文，可能随语言变，故只作兜底）
      function findHostToggle() {
        var sels = [
          'button[class*="_toggle"]',
          '[class*="_sidebarCol"] button[aria-label*="侧边栏"]',
          '[class*="_sidebarCol"] button[aria-label*="目录"]',
          'button[aria-label*="侧边栏"]',
          'button[aria-label*="目录"]',
        ];
        for (var i = 0; i < sels.length; i++) {
          var el = document.querySelector(sels[i]);
          if (el) return el;
        }
        return null;
      }
      function ensure() {
        var frame = document.querySelector('[' + MARK + '="frame"]');
        if (!frame) return;
        if (frame.querySelector('[' + MARK + '="fab"]')) return;   // 幂等
        var btn = document.createElement('button');
        btn.setAttribute(MARK, 'fab');
        btn.setAttribute('type', 'button');
        btn.setAttribute('aria-label', '打开目录');
        btn.textContent = '\u2630';                                 // ☰
        btn.addEventListener('click', function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          var host = findHostToggle();
          if (host) { try { host.click(); } catch (e) { /* ignore */ } }
        });
        frame.appendChild(btn);
      }
      ensure();
    }, { repeat: true });

  def('right-panel-obstructions', '右侧栏展开时，它工具栏上方有其它层级的元素盖着 —— 那一片点不到（用户报「最顶部无法点击」）。按几何关系找出真正盖住它的元素，给它们 pointer-events:none，面板收起时恢复。', ['rightPanel'], function (ctx, __wrap, postToApp) {
            // 自证：这一条**无条件**上报，用来区分「effect 体压根没跑到」与
            // 「跑到了但一个元素都没标到 / 中途抛异常」—— 前两轮就卡在这个盲区里。
            postToApp({ type: "panel-blockers", stage: "init" });
            try {
              // 只用自己的数组记账，**不往 DOM 上写标记属性** —— 写 data-* 会被移动端适配
              // 契约检查当成新的 dsh 钩子（那是我自己的标记，不该混进契约）。
              var marked = [];
              var restoreAll = function () {
                for (var i = 0; i < marked.length; i++) {
                  marked[i].style.removeProperty("pointer-events");
                }
                marked = [];
                window.__dshHandheldPeOff = 0;
              };
              var last = "";
              var slowPosted = 0;
              var lastScan = 0;
              var wasOpen = false;
              // 轮转采样（rev 1.0.68）：一次 elementsFromPoint 就是一次**强制布局**，原来每次扫
              // 3 行 × 4 列 = 12 次，真机实测 5.3~17.2ms。采样点一个不少，只是把它们摊到时间上：
              // 开合瞬间（justToggled）扫满 3 行，稳态每 2 秒只扫 1 行 → 一次扫描只有 1 次强制布局，
              // 三行轮完仍是一个 6 秒内的完整覆盖。
              var fullScan = true;
              var scanCursor = 0;
              var cycleTags = [];
              var panelEl = null;
              var getPanel = function () {
                // 面板元素在一段会话里是稳定的：缓存它，省掉每次 sync 的一次全树属性选择器遍历。
                if (panelEl !== null && panelEl.isConnected) return panelEl;
                panelEl = document.querySelector("[data-sidebar-right-panel]");
                return panelEl;
              };
              var closed = function () {
                wasOpen = false;
                if (marked.length > 0 || last !== "") {
                  restoreAll();
                  last = "";
                  postToApp({ type: "panel-blockers", open: false, n: 0 });
                }
              };
              var sync = function () {
                var t0 = window.performance && performance.now ? performance.now() : 0;
                var panel = getPanel();
                // ⚠️ 便宜的门必须排在读几何**之前**（rev 1.0.67）。宿主把 dock 的 expanded 状态
                // 直接写在面板根上（client-ui-sidebar-right：`"data-sidebar-right-open": expanded || void 0`，
                // 与 `aria-hidden` 互反），所以「没展开」**一个布局都不用读**就能判掉。
                // 改之前是反过来的：先 `panel.getBoundingClientRect()` 再判 2 秒节流 —— 于是每次被叫醒
                // 都强制刷一次布局，只换来一句「2 秒内扫过了」。而叫醒源是 setInterval(1s) 加上流式
                // 期间 body 的 class/style 抖动（200ms 防抖）：闲置 1 次/秒、流式 ~5 次/秒的强制布局，
                // 面板还关着的时候就全发生在空处。
                // ⚠️ 订正（rev 1.0.68，真机取证，推翻 rev 1.0.67 的 commit 说明）：1.0.67 里我写过
                // 「顺带修掉一个语义错 —— `visibility:hidden` 的元素仍然有盒，所以收起状态的 fullscreen
                // 面板会被旧判据当成展开」。**这条是错的**：2026-09-24 用本插件自己的 right-probe 拍到
                // 收起态面板的几何是
                //   {top:0, left:384, w:384, h:832}   // CSS px，视口 384×832
                // —— 基础规则 `.P3OORG_panel{transform:translate(100%)}` 把 rect 整个推到视口外，而旧判据
                // 要求 `left <= 1`，所以它**一直**判的是「没展开」（日志侧同样只有 stage:"init" 一条，
                // 从来没有收起态的 open:true）。老判据没有语义错，这次改的只是**成本**。
                //
                // 因此这道门的收益只有一个（但确凿）：面板关着时省掉一次 `querySelector` 全树遍历
                // 和一次**可能强制布局**的 rect 读 —— 那是 1 次/秒（定时器）+ 流式期间 ~5 次/秒
                // （body 抖动 200ms 防抖）的全部内容。
                if (panel === null || !panel.hasAttribute("data-sidebar-right-open")) {
                  closed();
                  return;
                }
                var b = panel.getBoundingClientRect();
                // 「展开」= 面板铺满视口。属性只说**该**展开，滑入动画那 ~300ms 几何还没落定 ——
                // 此刻采样点会打偏到面板外，命中后面的无关元素，所以「落定」仍旧只认几何。
                var open = b.width >= window.innerWidth - 1 && b.left <= 1;
                var top = b.top;
                // ⚠️ 采样那 12 个点要调 elementsFromPoint，而它每次都**强制一次布局**（页面在持续
                // 变动时实测 4~11ms）。原先每个 sync 都扫（最多 5 次/秒）→ 面板开着时等于每 10 秒
                // 就往日志里写一条 ≥4ms。改成：**最多 2 秒扫一次**，面板刚打开/刚收起时强制扫一次
                // （遮挡是宿主 DOM 的一部分，被重渲染掉时最多 2 秒后自愈）。
                var nowTs = Date.now();
                var justToggled = open !== wasOpen;
                wasOpen = open;
                if (!open) {
                  closed();
                  return;
                }
                if (!justToggled && nowTs - lastScan < 2000) {
                  return;
                }
                lastScan = nowTs;
                if (justToggled) fullScan = true;   // 刚打开/刚收起：这一轮扫满，别让用户等轮转
                // 面板工具栏那条带子（实测 CSS y40–68）：取 3×4 个点问「谁在最上面」。
              //
              // ⚠️ 判据不能用「面板之外的才算遮挡」—— 真机读数（rev 1.0.58）显示每个取样点上
              // 第一个元素**本来就在面板里**，于是「遇到面板内元素就停」会一路 break、一个都标不到
              // （peOff=0）。压在面板按钮上面的那层，本身就是面板 DOM 里的一份东西。
              // 所以改成按「这一点本该由谁接住」来判：从最上层往下走，遇到的第一个**面板内的
              // 可交互元素**（button/a/[role]/[tabindex]）或面板根，才是目标；它上面的都是遮挡。
              // 另有两条保险：页面根不碰；**目标控件的祖先也不碰**（关掉祖先等于把它一起关掉）。
              var INTERACTIVE_TAGS = { A: 1, BUTTON: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1, LABEL: 1 };
              var isInteractive = function (e) {
                if (INTERACTIVE_TAGS[e.tagName]) return true;
                var role = e.getAttribute ? e.getAttribute("role") : null;
                if (role === "button" || role === "tab" || role === "link" || role === "menuitem") return true;
                return !!(e.hasAttribute && e.hasAttribute("tabindex"));
              };
              var ys = [top + 44, top + 55, top + 66];
              var xs = [0.08, 0.35, 0.6, 0.92];
              var found = [];
              var rows = fullScan ? ys.length : 1;
              var rowBase = fullScan ? 0 : scanCursor % ys.length;
              for (var i = 0; i < rows; i++) {
                var y = Math.round(ys[(rowBase + i) % ys.length]);
                for (var k = 0; k < xs.length; k++) {
                  var x = Math.round(window.innerWidth * xs[k]);
                  var els = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
                  // 目标 = 最上层那个「面板内的可交互元素」（或面板根）
                  var stop = els.length;
                  for (var t = 0; t < els.length; t++) {
                    if (panel.contains(els[t]) && (isInteractive(els[t]) || els[t] === panel)) { stop = t; break; }
                  }
                  for (var j = 0; j < stop; j++) {
                    var e = els[j];
                    if (e === document.documentElement || e === document.body) break;
                    var wrapsTarget = false;
                    for (var m = stop; m < els.length; m++) {
                      if (e.contains && e.contains(els[m])) { wrapsTarget = true; break; }
                    }
                    if (wrapsTarget) continue;
                    // 只关「贴在顶部这条带子里的薄层」。面板自己的内容容器
                    // （_pane_ / _surface_ / P3OORG_panelBody 都是 30→800 那种整块）也满足
                    // 「在目标之上」，一刀切会把文件列表一起关死 —— rev 1.0.57 真机回归
                    // 实测：点文件夹只命中 div.P3OORG_panel，列表整块失效。
                    var eb = e.getBoundingClientRect ? e.getBoundingClientRect() : null;
                    if (eb === null || eb.top < top - 2 || eb.bottom > top + 84) continue;
                    var tag = e.tagName.toLowerCase() + "." + String(e.className || "").slice(0, 24);
                    if (found.indexOf(tag) < 0) found.push(tag);
                    if (marked.indexOf(e) < 0) {
                      e.style.setProperty("pointer-events", "none", "important");
                      marked.push(e);
                    }
                  }
                }
              }
              scanCursor++;
              // 每轮到一个新周期（采样行回到第 0 行）才重开累计 —— 这样 `key` 的语义仍是
              // 「上一次完整覆盖里见到的遮挡」，与改动前（每次扫满 3 行看一次）等价；
              // 否则单行扫描会让 key 每 2 秒变一次，日志被刷爆。
              if (rowBase === 0) cycleTags = [];
              for (var q = 0; q < found.length; q++) {
                if (cycleTags.indexOf(found[q]) < 0) cycleTags.push(found[q]);
              }
              fullScan = false;
              window.__dshHandheldPeOff = marked.length;
              // 自证开销（用户问过「现在 web 性能会不会有问题」）：这条 effect 每 200ms 最多算一次，
              // 面板关着时只做一次查询 + 一次 rect；只有真算久了才上报，免得刷屏。
              if (t0) {
                var ms = performance.now() - t0;
                var nowMs = Date.now();
                if (ms >= 4 && nowMs - slowPosted > 10000) {
                  slowPosted = nowMs;
                  postToApp({ type: "perf", what: "panel-sync", ms: Math.round(ms * 10) / 10, n: found.length });
                }
              }
                // 上报按「一个轮转周期里累计到的集合」比 —— 单行扫描会让 key 每 2 秒变一次。
                var key = cycleTags.slice().sort().join("|");
                if (key !== last) {
                  last = key;
                  postToApp({ type: "panel-blockers", open: true, n: marked.length, at: cycleTags });
                }
              };
              // 合并突发变更：宿主在流式输出时 DOM 抖得很厉害，不能每个 mutation 都算一遍几何。
              var pending = false;
              var schedule = function () {
                if (pending) return;
                pending = true;
                window.setTimeout(function () { pending = false; sync(); }, 200);
              };
              var obs = null;
              if (window.MutationObserver && document.body) {
                obs = new MutationObserver(schedule);
                obs.observe(document.body, {
                  subtree: true, childList: true, attributes: true,
                  attributeFilter: ["class", "style", "data-sidebar-right-panel", "data-sidebar-right-open"]
                });
              }
              // 兜底轮询：面板是滑入的，靠属性变化不一定能拍到「落定」那一帧。
              var poll = window.setInterval(sync, 1000);
              window.addEventListener("resize", schedule);
              sync();
              return function () {
                window.clearInterval(poll);
                window.removeEventListener("resize", schedule);
                if (obs) obs.disconnect();
                restoreAll();
              };

            } catch (err) {
              postToApp({ type: "panel-blockers", stage: "throw", err: String((err && err.message) || err) });
            }
      
  });

  root.__dshHandheldFixes = FIXES;
})(window);
