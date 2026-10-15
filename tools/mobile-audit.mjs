#!/usr/bin/env node
/**
 * 手机端可用性审计 —— 连到 App WebView 的 CDP，在**真实页面**上测量。
 *
 * 为什么需要它：第一轮之前，判断「手机上好不好用」只能靠肉眼和桌面模拟；
 * 而桌面 chromium 与真机 WebView 有已知差异（known-issues 第十六节）。这个工具直接读
 * 手机上那份 DOM/CSSOM，量的是屏幕真正呈现的东西。
 *
 * 前置（缺一步都会连不上）：
 *   1) 手机开无线调试，adb connect <ip>:<port>
 *   2) 装 **debug** 版 APK（release 不可调试，拿不到 devtools socket）
 *   3) adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
 *      pid 从 `adb shell ps -A | grep dshhandheld` 取
 *
 * 用法:
 *   node tools/mobile-audit.mjs                      # 默认 http://127.0.0.1:9222
 *   node tools/mobile-audit.mjs --port 9333          # 换端口
 *   node tools/mobile-audit.mjs --json out.json      # 存 JSON（便于前后对比）
 *   node tools/mobile-audit.mjs --diff before.json   # 与上次结果对比
 */

const args = process.argv.slice(2);
const getArg = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};
const PORT = getArg("--port", "9222");
const OUT = getArg("--json", null);
const DIFF = getArg("--diff", null);
const MIN = parseInt(getArg("--min", "44"), 10);

// ── 在页面里执行的测量脚本（自包含，不依赖页面全局）──
const MEASURE = `(function(){
  const out = {};
  const de = document.documentElement, vv = window.visualViewport;
  out.viewport = {
    cssW: innerWidth, cssH: innerHeight, dpr: devicePixelRatio,
    visualW: vv ? Math.round(vv.width) : null, visualH: vv ? Math.round(vv.height) : null,
    screenW: screen.width, screenH: screen.height
  };
  out.overflow = {
    horizontal: de.scrollWidth > de.clientWidth,
    scrollW: de.scrollWidth, clientW: de.clientWidth,
    docH: de.scrollHeight
  };
  out.media = {
    coarse: matchMedia("(pointer: coarse)").matches,
    hoverNone: matchMedia("(hover: none)").matches
  };
  out.rootAttrs = {};
  for (const a of de.attributes) out.rootAttrs[a.name] = (a.value || "").slice(0, 60);

  // 有效命中区 = 元素盒子 ∪ 其 ::after/::before，再 ∩ 视口
  // （只看盒子会低估：注入层当年就是用 ::after 扩命中区的）
  function effective(el) {
    const base = el.getBoundingClientRect();
    let L = base.left, T = base.top, R = base.right, B = base.bottom;
    for (const p of ["::after", "::before"]) {
      const st = getComputedStyle(el, p);
      if (!st || st.content === "none") continue;
      const pos = st.position;
      const num = (v) => { const n = parseFloat(v); return isNaN(n) ? null : n; };
      let l = null, t = null, w = num(st.width), h = num(st.height);
      const li = num(st.left), ti = num(st.top);
      if (pos === "absolute") { l = base.left + (li || 0); t = base.top + (ti || 0); }
      if (l === null && w !== null) { l = base.left; t = base.top; }
      if (l === null || w === null || h === null) continue;
      L = Math.min(L, l); T = Math.min(T, t);
      R = Math.max(R, l + w); B = Math.max(B, t + h);
    }
    const vw = innerWidth, vh = innerHeight;
    const cl = Math.max(0, L), ct = Math.max(0, T);
    const cr = Math.min(vw, R), cb = Math.min(vh, B);
    return { w: Math.round(Math.max(0, cr - cl)), h: Math.round(Math.max(0, cb - ct)) };
  }

  const INTERACTIVE = "button,a,input,select,textarea,summary,[role],[tabindex],[onclick]";
  const ROLES = ["button","link","tab","menuitem","checkbox","radio","switch","option"];
  const seen = new Set();
  const items = [];
  for (const el of document.querySelectorAll(INTERACTIVE)) {
    if (seen.has(el)) continue;
    seen.add(el);
    const tag = el.tagName.toLowerCase();
    const role = el.getAttribute("role");
    const ti = el.getAttribute("tabindex");
    const ok = ["button","a","input","select","textarea","summary"].includes(tag)
      || (role && ROLES.includes(role)) || (ti !== null && ti !== "-1") || el.hasAttribute("onclick");
    if (!ok) continue;
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    const st = getComputedStyle(el);
    if (st.visibility === "hidden" || st.display === "none" || st.pointerEvents === "none") continue;
    if (r.bottom <= 0 || r.top >= innerHeight || r.right <= 0 || r.left >= innerWidth) continue;
    const eff = effective(el);
    const label = (el.getAttribute("aria-label") || el.getAttribute("title")
      || (el.textContent || "").replace(/\\s+/g, " ").trim() || tag).slice(0, 34);
    items.push({
      tag, label,
      boxW: Math.round(r.width), boxH: Math.round(r.height),
      effW: eff.w, effH: eff.h,
      minSide: Math.min(eff.w, eff.h),
      fontSize: st.fontSize,
      cls: (el.className || "").toString().replace(/\\s+/g, " ").slice(0, 56),
      y: Math.round(r.top)
    });
  }
  items.sort((a, b) => a.minSide - b.minSide);
  const min = MIN_PLACEHOLDER;
  const under = items.filter(i => i.minSide < min);
  out.touch = {
    threshold: min,
    total: items.length,
    underCount: under.length,
    underPct: items.length ? Math.round(100 * under.length / items.length) : 0,
    under: under.slice(0, 40)
  };

  // 竖向预算：主要区域各占多少
  const seat = document.querySelector("[class*=\"_composerSeat\"]");
  const head = document.querySelector("[class*=\"_titleRow\"]");
  out.budget = {
    headerH: head ? Math.round(head.getBoundingClientRect().height) : null,
    composerH: seat ? Math.round(seat.getBoundingClientRect().height) : null,
    viewportH: innerHeight
  };
  if (out.budget.headerH !== null && out.budget.composerH !== null) {
    out.budget.messageAreaH = innerHeight - out.budget.headerH - out.budget.composerH;
  }

  // 消息区可折叠行（本项目的重点观察对象）
  const rows = [...document.querySelectorAll("[class*=\"_row_\"]")];
  out.expandableRows = {
    total: rows.length,
    expandable: rows.filter(r => r.hasAttribute("data-expandable")).length,
    heights: [...new Set(rows.map(r => Math.round(r.getBoundingClientRect().height)))].sort((a,b)=>a-b).slice(0, 8)
  };
  return out;
})()`;

const expression = MEASURE.replace("MIN_PLACEHOLDER", String(MIN));

async function cdpList() {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
  if (!res.ok) throw new Error(`devtools 列表失败: HTTP ${res.status}`);
  return res.json();
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const pending = new Map();
    let id = 0;
    ws.addEventListener("open", () => resolve({
      send(method, params = {}) {
        return new Promise((res, rej) => {
          const mid = ++id;
          pending.set(mid, { res, rej });
          ws.send(JSON.stringify({ id: mid, method, params }));
        });
      },
      close: () => ws.close()
    }));
    ws.addEventListener("error", (e) => reject(new Error("WS 连接失败: " + (e.message || "unknown"))));
    ws.addEventListener("message", (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id && pending.has(m.id)) {
        const { res, rej } = pending.get(m.id);
        pending.delete(m.id);
        if (m.error) rej(new Error(JSON.stringify(m.error))); else res(m.result);
      }
    });
  });
}

function bar(v, max, width = 24) {
  const n = Math.max(0, Math.min(width, Math.round(width * v / max)));
  return "█".repeat(n) + "·".repeat(width - n);
}

(async () => {
  let pages;
  try {
    pages = await cdpList();
  } catch (e) {
    console.error("拿不到 devtools 页面列表:", e.message);
    console.error("检查: adb forward tcp:" + PORT + " localabstract:webview_devtools_remote_<pid>");
    process.exit(2);
  }
  const page = pages.find(p => p.type === "page") || pages[0];
  if (!page) { console.error("没有可调试页面"); process.exit(2); }
  const c = await connect(page.webSocketDebuggerUrl);
  await c.send("Runtime.enable");
  const r = await c.send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) {
    console.error("页面内异常:", JSON.stringify(r.exceptionDetails).slice(0, 600));
    process.exit(3);
  }
  const d = r.result.value;
  if (OUT) {
    const fs = await import("node:fs");
    fs.writeFileSync(OUT, JSON.stringify(d, null, 2));
    console.log("已保存 JSON:", OUT);
  }

  console.log("");
  console.log("═══ 手机端可用性审计 ═══");
  console.log("页面:", page.title);
  console.log("URL :", page.url);
  console.log("");
  console.log("视口      : " + d.viewport.cssW + "×" + d.viewport.cssH + "  dpr " + d.viewport.dpr +
    (d.viewport.visualW ? "  (visual " + d.viewport.visualW + "×" + d.viewport.visualH + ")" : ""));
  console.log("粗指针    : " + d.media.coarse + "   hover:none " + d.media.hoverNone);
  console.log("横向溢出  : " + (d.overflow.horizontal ? "有 ✗ (" + d.overflow.scrollW + " > " + d.overflow.clientW + ")" : "无 ✓"));
  console.log("根属性    : " + JSON.stringify(d.rootAttrs));
  console.log("");
  const b = d.budget;
  if (b.headerH !== null) {
    console.log("── 竖向预算 ──");
    console.log("  头部   " + bar(b.headerH, b.viewportH, 20) + " " + b.headerH + "px");
    console.log("  消息区 " + bar(b.messageAreaH, b.viewportH, 20) + " " + b.messageAreaH + "px");
    console.log("  输入区 " + bar(b.composerH, b.viewportH, 20) + " " + b.composerH + "px");
    console.log("");
  }
  const t = d.touch;
  console.log("── 触控目标（阈值 " + t.threshold + "px）──");
  console.log("  可点元素 " + t.total + " 个，其中 " + t.underCount + " 个小于阈值 (" + t.underPct + "%)");
  if (t.under.length) {
    console.log("  最小的一批:");
    for (const i of t.under.slice(0, 18)) {
      console.log("    " + String(i.effW + "×" + i.effH).padEnd(11) +
        ("盒 " + i.boxW + "×" + i.boxH).padEnd(13) +
        i.label.slice(0, 24).padEnd(26) + " y=" + i.y);
      if (i.cls) console.log("      " + i.cls);
    }
  } else {
    console.log("  全部达标 ✓");
  }
  console.log("");
  const er = d.expandableRows;
  console.log("── 消息区可折叠行 ──");
  console.log("  行总数 " + er.total + "，其中 data-expandable " + er.expandable + " 个");
  console.log("  出现的高度: " + er.heights.join(", ") + " px");
  console.log("");

  if (DIFF) {
    const fs = await import("node:fs");
    if (fs.existsSync(DIFF)) {
      const prev = JSON.parse(fs.readFileSync(DIFF, "utf8"));
      console.log("═══ 与 " + DIFF + " 对比 ═══");
      const pt = prev.touch, ct = d.touch;
      console.log("  小于阈值的元素: " + pt.underCount + " -> " + ct.underCount +
        (ct.underCount < pt.underCount ? "  ✓ 改善" : ct.underCount > pt.underCount ? "  ✗ 变差" : "  持平"));
      const key = (i) => i.cls + "|" + i.label;
      const prevMap = new Map((pt.under || []).map(i => [key(i), i]));
      for (const i of ct.under || []) {
        const p = prevMap.get(key(i));
        if (p && (p.effW !== i.effW || p.effH !== i.effH)) {
          console.log("    " + i.label.slice(0, 22).padEnd(24) + p.effW + "×" + p.effH + " -> " + i.effW + "×" + i.effH);
        }
      }
    } else {
      console.log("对比文件不存在:", DIFF);
    }
  }
  c.close();
  process.exit(0);
})().catch(e => { console.error("失败:", e.message); process.exit(1); });
