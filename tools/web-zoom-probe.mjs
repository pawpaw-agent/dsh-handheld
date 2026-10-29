#!/usr/bin/env node
/**
 * web-zoom-probe.mjs — 缩放语义探针（题目的「度量」交付物）
 *
 * 目的：回答「把页面缩放设成 N，WebView 真的会变成 N 吗，还是被引擎钳制在某个值」。
 * 这是 H1 的直接判据：读**引擎自己报告的** visualViewport.scale，而不是相信 API 调用成功。
 *
 * 为什么用 CDP 而不是重编译 APK 试：缩放是**渲染层**行为，CDP 的
 * Emulation.setPageScaleFactor 就能改，装一轮 CI 只为了试数值是浪费。
 *
 * 用法：
 *   node tools/web-zoom-probe.mjs --ws <wsUrl> [--scales 1,0.9,0.8,0.75,0.5] --out out.json
 *   node tools/web-zoom-probe.mjs --http 127.0.0.1:9222 ...   # 从 /json/list 自己挑页面
 *
 * 输出 JSON：每个 scale 档位的「设定值 vs 实际值」，以及几何量（innerWidth/scrollWidth/
 * 被测元素矩形/字号）。判据全部可机器比较，不看图。
 */
import { writeFileSync } from "node:fs";
import { argv } from "node:process";

function arg(name, dflt) {
  const i = argv.indexOf("--" + name);
  return i > 0 && argv[i + 1] !== undefined ? argv[i + 1] : dflt;
}

const WANTED = String(arg("scales", "1,0.9,0.85,0.8,0.75,0.5"))
  .split(",").map((s) => Number(s.trim())).filter((n) => n > 0 && n <= 2);
const OUT = arg("out", "");
const TOLERANCE = Number(arg("tol", "0.02")); // 2%，题目里 A1/A2 的容差

// 观测条件由 probe 自己设：CDP 的 setDeviceMetricsOverride 会随**发起它的会话断开**
// 而被清除（2026-10-30 踩到：编排脚本先设、后断开、probe 再连 → 页面退回 780 宽的默认窗口，
// 量出来的数是假的）。谁测量，谁拥有会话。
const EMU_W = arg("width", "");
const EMU_H = arg("height", "");
const EMU_DPR = arg("dpr", "");
const EMU_ON = EMU_W !== "" && EMU_H !== "" && EMU_DPR !== "";

async function resolveWs() {
  const direct = arg("ws", "");
  if (direct) return direct;
  const http = arg("http", "127.0.0.1:9222");
  const res = await fetch("http://" + http + "/json/list");
  const list = await res.json();
  const page = list.find((t) => t.type === "page") || list[0];
  if (!page) throw new Error("没有可用的 page target");
  return page.webSocketDebuggerUrl;
}

/** 被测元素：优先输入区，退到 body —— 都是宿主自己的节点，不注入任何东西。 */
const MEASURE_EXPR = `(function(){
  var pick = document.querySelector('[class*="_composerSeat"]')
          || document.querySelector('[class*="_row_"]')
          || document.body;
  var r = pick.getBoundingClientRect();
  var cs = getComputedStyle(pick);
  var txt = document.body ? document.body.innerText : "";
  var lines = txt ? txt.split("\\n").filter(function(s){return s.trim().length;}).length : 0;
  var vv = window.visualViewport;
  return {
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    dpr: window.devicePixelRatio,
    visualScale: vv ? vv.scale : null,
    visualWidth: vv ? vv.width : null,
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    probeClass: String(pick.className || pick.tagName).slice(0, 48),
    rectCss: { x: r.x, y: r.y, w: r.width, h: r.height },
    fontSizePx: parseFloat(cs.fontSize),
    textLines: lines,
    textChars: txt.length
  };
})()`;

async function main() {
  const wsUrl = await resolveWs();
  const ws = new WebSocket(wsUrl);
  let id = 0;
  const pending = new Map();
  ws.addEventListener("message", (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      if (m.error) p.reject(new Error(m.error.message || JSON.stringify(m.error)));
      else p.resolve(m.result);
    }
  });
  await new Promise((res, rej) => {
    ws.addEventListener("open", res, { once: true });
    ws.addEventListener("error", () => rej(new Error("WebSocket 连接失败")), { once: true });
  });

  const send = (method, params) =>
    new Promise((resolve, reject) => {
      const mid = ++id;
      pending.set(mid, { resolve, reject });
      ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
    });

  const evaluate = async (expression) => {
    const r = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      const d = r.exceptionDetails.exception && r.exceptionDetails.exception.description;
      throw new Error("页面内异常: " + String(d || JSON.stringify(r.exceptionDetails)).slice(0, 300));
    }
    return r.result.value;
  };

  await send("Runtime.enable");
  await send("Page.enable");

  if (EMU_ON) {
    await send("Emulation.setDeviceMetricsOverride", {
      width: Number(EMU_W), height: Number(EMU_H),
      deviceScaleFactor: Number(EMU_DPR), mobile: true,
    });
    await send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 5 });
    await new Promise((r) => setTimeout(r, 300));
  }

  const result = {
    ws: wsUrl,
    tolerance: TOLERANCE,
    emulation: EMU_ON ? { width: Number(EMU_W), height: Number(EMU_H), dpr: Number(EMU_DPR) } : null,
    baseline: null,
    steps: [],
    verdict: null,
  };

  /** 把页面缩放设成 pct（1.0 = 原样），读回引擎实际采用的值。 */
  async function setPageScale(scale) {
    await send("Emulation.setPageScaleFactor", { pageScaleFactor: scale });
    // 让渲染跑一帧再读，避免读到旧几何
    await new Promise((r) => setTimeout(r, 220));
    return evaluate(MEASURE_EXPR);
  }

  result.baseline = await evaluate(MEASURE_EXPR);

  for (const s of WANTED) {
    const m = await setPageScale(s);
    const actual = m.visualScale;
    const dev = actual === null ? null : Math.abs(actual - s) / s;
    result.steps.push({
      requested: s,
      actualScale: actual,
      deviation: dev,
      clamped: dev !== null && dev > TOLERANCE,
      geometry: m,
    });
  }

  // 复原，避免把调试用的缩放留在页面上
  try { await send("Emulation.setPageScaleFactor", { pageScaleFactor: 1 }); } catch {}

  const clampedSteps = result.steps.filter((x) => x.clamped);
  result.verdict = {
    anyClamped: clampedSteps.length > 0,
    clampedAt: clampedSteps.map((x) => x.requested),
    // H1 的判据：请求 0.75 时实际值是否仍为 1
    request075Effective:
      result.steps.find((x) => x.requested === 0.75)
        ? !result.steps.find((x) => x.requested === 0.75).clamped
        : null,
    // H2 的判据：缩小时 scrollWidth（布局宽度）是否变化 —— 不变则「只是画小」
    layoutWidthChanged:
      result.steps.some((x) => x.geometry.scrollWidth !== result.baseline.scrollWidth),
  };

  if (OUT) writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
  ws.close();
}

main().catch((e) => {
  console.error("探针失败:", e.message);
  process.exit(1);
});
