#!/usr/bin/env node
/**
 * 离线校验 tools/mobile-audit.mjs 的页面内测量表达式：
 * ① 能否通过 new Function 解析（页面里由 CDP 求值，语法错会直接在设备上才暴露）
 * ② 在极简 DOM 桩上跑一遍，确认不抛运行时异常
 * ③ 确认关键字段都在返回值里
 *
 * 用法: node tools/verify-audit-expr.mjs
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, "mobile-audit.mjs"), "utf8");

// 抽出 MEASURE 模板字符串（从反引号到闭合反引号）
const m = src.match(/const MEASURE = `([\s\S]*?)`;/);
if (!m) { console.error("找不到 MEASURE 模板"); process.exit(1); }
const expr = m[1].replace("MIN_PLACEHOLDER", "44");
console.log("表达式长度:", expr.length, "字符");

// ① 语法检查
try {
  new Function("return " + expr);
  console.log("① 语法解析: 通过 ✓");
} catch (e) {
  console.error("① 语法解析失败 ✗:", e.message);
  const line = expr.slice(0, e.message.match(/(\d+)/) ? Number(e.message.match(/(\d+)/)[1]) * 40 : 200);
  console.error("   片段:", line.slice(-160));
  process.exit(1);
}

// ② 极简 DOM 桩
function makeEl(tag, opts = {}) {
  const { w = 100, h = 40, top = 10, left = 10, cls = "", label = "", attrs = {} } = opts;
  const el = {
    tagName: tag.toUpperCase(),
    className: cls,
    classList: { [Symbol.iterator]: function* () { for (const c of cls.split(" ").filter(Boolean)) yield c; }, contains: () => false },
    attributes: Object.entries(attrs).map(([k, v]) => ({ name: k, value: v })),
    textContent: label,
    getAttribute: (k) => (k === "aria-label" ? label : (attrs[k] ?? null)),
    hasAttribute: (k) => k in attrs,
    getBoundingClientRect: () => ({ width: w, height: h, top, left, right: left + w, bottom: top + h, x: left, y: top }),
  };
  return el;
}

const els = [
  makeEl("button", { w: 28, h: 28, cls: "_sm_1rv3m_28", label: "更多操作" }),
  makeEl("button", { w: 36, h: 36, cls: "hHd-Xa_toggle", label: "打开侧边栏" }),
  makeEl("button", { w: 90, h: 22, cls: "_pill_1bqmh_1", label: "12 轮 177 步" }),
  makeEl("div", { w: 257, h: 24, cls: "_row_jhda5_16", label: "思考", attrs: { "data-expandable": "" } }),
  makeEl("div", { w: 385, h: 30, cls: "_titleRow_x_1", label: "标题" }),
  makeEl("div", { w: 321, h: 170, cls: "_composerSeat_x_1", label: "输入" }),
  makeEl("div", { w: 384, h: 800, cls: "_frame_x_1", label: "" }),
];

const doc = {
  documentElement: {
    scrollWidth: 384, clientWidth: 384, scrollHeight: 2000,
    attributes: [{ name: "lang", value: "zh-CN" }, { name: "data-input-modality", value: "pointer" }],
  },
  querySelectorAll: (sel) => {
    if (sel.includes("_composerSeat")) return [els[5]];
    if (sel.includes("_row_")) return [els[3]];
    if (sel.includes("_titleRow")) return [els[4]];
    return els.slice(0, 4);
  },
  querySelector: (sel) => {
    if (sel.includes("_composerSeat")) return els[5];
    if (sel.includes("_titleRow")) return els[4];
    return null;
  },
};

const win = {
  innerWidth: 384, innerHeight: 832, devicePixelRatio: 3.75,
  screen: { width: 385, height: 833 },
  visualViewport: { width: 384, height: 832 },
  matchMedia: (q) => ({ matches: q.includes("coarse") }),
  getComputedStyle: (el, pseudo) => ({
    visibility: "visible", display: "block", pointerEvents: "auto",
    fontSize: "12px", position: "static", content: "none", width: "auto", height: "auto",
  }),
};

let result;
try {
  const fn = new Function("window", "document", "screen", "getComputedStyle", "innerWidth", "innerHeight", "matchMedia", "devicePixelRatio",
    "return " + expr + ";");
  result = fn(win, doc, win.screen, win.getComputedStyle, 384, 832, win.matchMedia, 3.75);
  console.log("② DOM 桩上执行: 通过 ✓");
} catch (e) {
  console.error("② 执行失败 ✗:", e.message);
  process.exit(1);
}

// ③ 字段完整性
const required = ["viewport", "overflow", "media", "rootAttrs", "touch", "budget", "expandableRows"];
const missing = required.filter(k => !(k in (result || {})));
if (missing.length) {
  console.error("③ 缺字段 ✗:", missing.join(", "));
  process.exit(1);
}
console.log("③ 返回字段: 完整 ✓");
console.log("");
console.log("采样输出:");
console.log("  视口:", result.viewport.cssW + "×" + result.viewport.cssH, "dpr", result.viewport.dpr);
console.log("  触控:", result.touch.total, "个元素,", result.touch.underCount, "个 < " + result.touch.threshold);
console.log("  预算:", JSON.stringify(result.budget));
console.log("  可折叠行:", JSON.stringify(result.expandableRows));
if (result.touch.under[0]) {
  const u = result.touch.under[0];
  console.log("  最小的:", u.label, u.effW + "×" + u.effH, "(" + u.cls + ")");
}
console.log("");
console.log("全部通过 ✓");
