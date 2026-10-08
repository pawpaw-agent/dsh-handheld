/*
 * UI 预算探针（页面侧求值的一段 JS）—— `ui-verify.mjs`（fixture）与 `device-audit.mjs`
 * （真机真实 DOM）共用，保证两边量的是同一套指标，数字可比。
 *
 * ## 三条可信度约定（2026-10-09 修尺子时定下）
 *
 * 1. **选择器走宿主类名后缀**，不认 fixture 的 id/类 —— 早先探针里写死 `#msgArea`、
 *    `.fixture_card`、`[data-composer-input]`，真机上全不存在 ⇒ 读数是 `null`，白量。
 * 2. **有效命中区** = 元素盒子 ∪ 它的 `::after`/`::before`（层就用 ::after 扩命中区），
 *    再 ∩ **裁剪祖先**（overflow ≠ visible）∩ 视口；屏外元素排除。只看盒子会低估。
 * 3. **重叠只在"同一裁剪容器内"比较** —— 否则会报出跨视图假阳性（抽屉里的「新建会话」
 *    与会话里的页签根本不同屏；实测那种假阳性把数字从 4 抬到 171）。
 *
 * 另外：测量前必须先跑 `RESET_JS`（关面板、关抽屉、滚到顶、清掉试装样式），
 * 否则上一次跑留下的状态会污染这一次（实测可点数在 41/62 之间飘）。
 */

/** 测量前归一化状态：保证"同一个起点"，读数才可比。 */
export const RESET_JS = `(function () {
  try {
    // 关掉右侧栏（它铺满时下面的东西都不该参与测量）
    var close = document.querySelector('button[aria-label*="收起右侧边栏"]');
    if (close) close.click();
    // 收起抽屉
    var frame = document.querySelector('[data-handheld="frame"]');
    if (frame && !frame.hasAttribute('data-sidebar-collapsed')) {
      var t = document.querySelector('button[aria-label*="收起侧边栏"]')
        || document.querySelector('button[aria-label*="侧边栏"]');
      if (t) t.click();
    }
    // 滚到顶
    var sc = null;
    var all = document.querySelectorAll('[class*="_scroll"]');
    for (var i = 0; i < all.length; i++) {
      if (all[i].scrollHeight > all[i].clientHeight + 4) { sc = all[i]; break; }
    }
    if (sc) sc.scrollTop = 0;
    // 清掉试装样式
    var try1 = document.getElementById('__try_css');
    if (try1) try1.textContent = '';
    return 'reset';
  } catch (e) { return 'reset-err:' + e.message; }
})()`;

export const AUDIT_PROBE = `(function () {
  var vw = window.innerWidth, vh = window.innerHeight;
  var qa = function (suf) { return [].slice.call(document.querySelectorAll('[class*="' + suf + '"]')); };
  var q = function (suf) { return document.querySelector('[class*="' + suf + '"]'); };
  var R = function (el) { var r = el.getBoundingClientRect();
    return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }; };

  var visible = function (el) {
    if (!el) return false;
    var cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    return r.right > 0 && r.bottom > 0 && r.left < vw && r.top < vh;
  };

  // 最近的裁剪祖先（overflow ≠ visible）：跨视图的重叠比较靠它区分
  var clipAncestor = function (el) {
    for (var p = el.parentElement; p; p = p.parentElement) {
      var cs = getComputedStyle(p);
      if (cs.overflow !== 'visible' || cs.overflowX !== 'visible' || cs.overflowY !== 'visible') return p;
    }
    return null;
  };
  var clipKey = function (el) {
    var c = clipAncestor(el);
    if (!c) return 'viewport';
    return c.tagName + '#' + (c.id || '') + '.' + String(c.className || '').slice(0, 24);
  };
  // 元素盒子 ∪ 伪元素 → ∩ 裁剪祖先 → ∩ 视口
  var effBox = function (el) {
    var r = el.getBoundingClientRect();
    var x1 = r.left, y1 = r.top, x2 = r.right, y2 = r.bottom;
    ['::after', '::before'].forEach(function (pe) {
      var cs = getComputedStyle(el, pe);
      if (!cs || cs.content === 'none' || cs.display === 'none') return;
      var w = parseFloat(cs.width), h = parseFloat(cs.height);
      if (!w || !h) return;
      var l = parseFloat(cs.left), t = parseFloat(cs.top);
      var px = r.left + (isNaN(l) ? 0 : l), py = r.top + (isNaN(t) ? 0 : t);
      x1 = Math.min(x1, px); y1 = Math.min(y1, py);
      x2 = Math.max(x2, px + w); y2 = Math.max(y2, py + h);
    });
    // 裁剪祖先：只在**祖先矩形有效且确实相交**时才夹 —— 否则会把合法元素夹成 0
    // （实测踩过：334x44 被夹成 334x0 —— 那是探针的假零，不是页面的问题）
    var c = clipAncestor(el);
    if (c) {
      var cr = c.getBoundingClientRect();
      if (cr.width > 1 && cr.height > 1) {
        var nx1 = Math.max(x1, cr.left), ny1 = Math.max(y1, cr.top);
        var nx2 = Math.min(x2, cr.right), ny2 = Math.min(y2, cr.bottom);
        var before = Math.max(0, (x2 - x1)) * Math.max(0, (y2 - y1));
        var after = Math.max(0, (nx2 - nx1)) * Math.max(0, (ny2 - ny1));
        // 夹掉超过 3/4 就认为是误判（祖先只是"恰好包着"，不是真的裁），退回不夹
        if (after > before * 0.25) { x1 = nx1; y1 = ny1; x2 = nx2; y2 = ny2; }
      }
    }
    x1 = Math.max(x1, 0); y1 = Math.max(y1, 0);
    x2 = Math.min(x2, vw); y2 = Math.min(y2, vh);
    var w = Math.max(0, Math.round(x2 - x1)), h = Math.max(0, Math.round(y2 - y1));
    return { w: w, h: h, x: Math.round(x1), y: Math.round(y1) };
  };

  // ── 竖向预算：头部 / 消息区 / 输入区（全走宿主类名后缀） ──
  var header = document.querySelector('header');
  var seat = q('_composerSeat');
  var dock = q('_dock');
  var scroll = null, sa = qa('_scroll');
  for (var i = 0; i < sa.length; i++) {
    if (sa[i].scrollHeight > sa[i].clientHeight + 4) { scroll = sa[i]; break; }
  }
  if (!scroll && sa.length) scroll = sa[0];

  // ── 横向：正文 ink（消息区里最宽的带文字块）与输入区 ──
  var ink = null;
  if (scroll) {
    var nodes = scroll.querySelectorAll('div,p,span,li,pre,code');
    for (var k = 0; k < nodes.length; k++) {
      var n = nodes[k];
      if (!visible(n)) continue;
      var tx = (n.textContent || '').trim();
      if (tx.length < 8) continue;
      if (n.children.length > 4) continue;          // 只看"叶子块"，别把整页容器算进来
      var r2 = n.getBoundingClientRect();
      if (!ink || r2.width > ink.w) ink = { w: Math.round(r2.width), x: Math.round(r2.left),
        h: Math.round(r2.height), tag: n.tagName + '.' + String(n.className || '').slice(0, 18) };
    }
  }
  var input = document.querySelector('textarea,[data-composer-input],[contenteditable="true"]');
  var inputBox = input ? R(input) : null;

  // ── 命中区 ──
  var interactive = [].slice.call(document.querySelectorAll(
    'button,a[href],[role="button"],[role="tab"],[tabindex]:not([tabindex="-1"])'
  )).filter(function (e) { return visible(e) && !e.disabled; })
    .map(function (e) {
      var r = e.getBoundingClientRect(), ef = effBox(e);
      return { el: e.tagName + '.' + String(e.className || '').slice(0, 26),
        label: (e.getAttribute('aria-label') || e.textContent || '').trim().slice(0, 16),
        w: Math.round(r.width), h: Math.round(r.height),
        vx: Math.round(r.left), vy: Math.round(r.top),
        ew: ef.w, eh: ef.h, ex: ef.x, ey: ef.y,
        clip: clipKey(e) };
    });
  var small = interactive.filter(function (t) { return t.ew < 44 || t.eh < 44; });

  // ── 截断 ──
  var truncated = [].slice.call(document.querySelectorAll('*'))
    .filter(function (e) { return e.children.length === 0 && e.clientWidth > 0 && e.scrollWidth > e.clientWidth + 1; })
    .slice(0, 12)
    .map(function (e) { return { el: e.tagName + '.' + String(e.className || '').slice(0, 22),
      sl: e.scrollWidth, cl: e.clientWidth, text: (e.textContent || '').trim().slice(0, 18) }; });

  return {
    vw: vw, vh: vh,
    header: header ? R(header) : null,
    tabsShown: (function () { var t = q('_tabs'); return !!t && getComputedStyle(t).display !== 'none'; })(),
    msg: scroll ? R(scroll) : null,
    composer: seat ? R(seat) : null,
    dock: dock ? R(dock) : null,
    input: inputBox,
    ink: ink,
    statsH: (function () { var s = document.querySelector('[data-composer-stats]');
      return s ? Math.round(s.getBoundingClientRect().height) : null; })(),
    taps: { total: interactive.length, small: small, boxes: interactive },
    truncated: truncated,
  };
})()`;

export function fmtAudit(res) {
  const d = res.data;
  const lines = [];
  lines.push(`视口 ${d.vw}x${d.vh}（${res.width} CSS px 档${res.openDrawer ? '，**抽屉展开**' : '，抽屉收起'}）`);
  const chromeH = (d.header ? d.header.h : 0) + (d.composer ? d.composer.h : 0);
  lines.push(`  竖向  头部 ${d.header ? d.header.h : '?'} + 消息区 ${d.msg ? d.msg.h : '?'}`
    + ` + 输入区 ${d.composer ? d.composer.h : '?'} = ${chromeH + (d.msg ? d.msg.h : 0)}`
    + `（固定占 ${d.vh ? (100 * chromeH / d.vh).toFixed(1) : '?'}%）`);
  lines.push(`         头部明细：页签${d.tabsShown ? '显示' : '**被层隐藏**'}；统计行 ${d.statsH}px`
    + `；dock ${d.dock ? d.dock.h : '?'}px`);
  lines.push(`  横向  视口 ${d.vw} → 正文 ink ${d.ink ? d.ink.w + '（左 ' + d.ink.x + '，' + d.ink.tag + '）' : '?'}`
    + ` ｜ 输入框 ${d.input ? d.input.w : '?'} ｜ 消息区 ${d.msg ? d.msg.w : '?'}`);
  lines.push(`  命中区 可点 ${d.taps.total} 个，其中**有效命中区 <44px 的 ${d.taps.small.length} 个**（视觉 → 有效）`);
  for (const t of d.taps.small.slice(0, 10)) {
    lines.push(`           ${String(t.w + 'x' + t.h).padEnd(9)} → ${String(t.ew + 'x' + t.eh).padEnd(9)}`
      + ` ${t.el.padEnd(26)} ${t.label}`);
  }
  // 负对照：**同一裁剪容器内**、扩展区咬到邻居**可见盒子**
  const B = d.taps.boxes || [];
  const hit = (a, b) => !(a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y);
  const stolen = [];
  for (let i = 0; i < B.length; i++) {
    for (let j = 0; j < B.length; j++) {
      if (i === j) continue;
      const a = B[i], b = B[j];
      if (a.clip !== b.clip) continue;                      // 跨视图不比（假阳性来源）
      if (hit({ x: a.ex, y: a.ey, w: a.ew, h: a.eh }, { x: b.vx, y: b.vy, w: b.w, h: b.h })) {
        stolen.push(`${a.el}(${a.label}) 咬到 ${b.el}(${b.label})`);
      }
    }
  }
  lines.push(`  负对照 **同容器内咬到邻居可见区 ${stolen.length} 处**`
    + `${stolen.length ? '：' + stolen.slice(0, 3).join(' ｜ ') : ''}`);
  lines.push(`  截断  ${d.truncated.length} 处`);
  for (const t of d.truncated.slice(0, 6)) {
    lines.push(`           ${t.el.padEnd(26)} ${t.sl}>${t.cl}  「${t.text}」`);
  }
  return lines.join('\n');
}
