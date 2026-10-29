#!/usr/bin/env node
/**
 * zoom-harness.mjs — 缩放实验的编排层（题目「度量」交付物之二）
 *
 * 它负责把「观测条件」固定下来，让 web-zoom-probe.mjs 只关心测量：
 *   1. 起一个独立 user-data-dir 的 Chrome（不污染你日常浏览器）；
 *   2. 用 CDP 把视口模拟成手机：宽高 + dpr + mobile=true；
 *   3. 带着 token 访问 dsh（303 换 cookie），等页面真正就绪；
 *   4. 调 probe 做缩放扫描，合并输出。
 *
 * 为什么要固定观测条件：380/384/412/512 宽、dpr 3.0/3.5/3.75 下，
 * 「缩放有没有生效」的答案可能不同。条件不写死，数值就没法比较。
 *
 * 用法（本机引擎对照）：
 *   node tools/zoom-harness.mjs --url "http://127.0.0.1:3080/?token=xxx" \
 *        --width 384 --height 832 --dpr 3.75 --port 9333 --out /tmp/zoom-chrome.json
 *
 * 用法（真机：页面已在手机上，跳过 launch/navigate）：
 *   node tools/zoom-harness.mjs --attach 127.0.0.1:9222 --skip-nav --out dev.json
 */
import { spawn } from "node:child_process";
import { execFileSync } from "node:child_process";
import { writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const argv = process.argv;
const arg = (n, d) => {
  const i = argv.indexOf("--" + n);
  return i > 0 && argv[i + 1] !== undefined ? argv[i + 1] : d;
};
const flag = (n) => argv.includes("--" + n);

const URL_ = arg("url", "http://127.0.0.1:3080/");
const W = Number(arg("width", "384"));
const H = Number(arg("height", "832"));
const DPR = Number(arg("dpr", "3.75"));
const PORT = arg("port", "9333");
const OUT = arg("out", "/tmp/zoom-harness.json");
const CHROME = arg("chrome", "google-chrome");
const ATTACH = arg("attach", "");        // 形如 127.0.0.1:9222（真机）
const SKIP_NAV = flag("skip-nav");
const SCALES = arg("scales", "1,0.9,0.85,0.8,0.75,0.5");

const target = ATTACH || ("127.0.0.1:" + PORT);
let chrome = null;

/**
 * 解析一次就缓存。
 *
 * ⚠️ 踩过的坑（2026-10-30）：早先每次都重新 GET /json/list 取 list[0]，
 * 而 probe 是另一次调用 —— 它可能选中**另一个 target**（Chrome 里常有多个 page），
 * 于是 emulation 设在一个页面、测量却在另一个页面，读到的 innerWidth=780
 * （≈800 减滚动条的默认窗口）而不是模拟的 384。仪器必须先自证观测的是同一个对象。
 */
let _wsUrl = null;
async function cdp() {
  if (_wsUrl) return _wsUrl;
  const list = await (await fetch("http://" + target + "/json/list")).json();
  const page = list.find((t) => t.type === "page") || list[0];
  if (!page) throw new Error("没有 page target");
  _wsUrl = page.webSocketDebuggerUrl;
  return _wsUrl;
}

async function withSession(fn) {
  const ws = new WebSocket(await cdp());
  let id = 0;
  const pending = new Map();
  ws.addEventListener("message", (ev) => {
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id); pending.delete(m.id);
      if (m.error) p.reject(new Error(m.error.message || JSON.stringify(m.error)));
      else p.resolve(m.result);
    }
  });
  await new Promise((res, rej) => {
    ws.addEventListener("open", res, { once: true });
    ws.addEventListener("error", () => rej(new Error("ws 连接失败")), { once: true });
  });
  const send = (method, params) => new Promise((resolve, reject) => {
    const mid = ++id; pending.set(mid, { resolve, reject });
    ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
  });
  const out = await fn(send);
  ws.close();
  return out;
}

const ev = async (send, expression) => {
  const r = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error("页面异常: " + JSON.stringify(r.exceptionDetails).slice(0, 200));
  return r.result.value;
};

async function waitReady(send, timeoutMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const st = await ev(send, `JSON.stringify({
      ready: document.readyState,
      els: document.querySelectorAll('*').length,
      title: document.title,
      ct: document.contentType,
      bodyLen: document.body ? document.body.innerText.length : 0
    })`);
    const s = JSON.parse(st);
    // 就绪判据：DOM 完成 + 至少有真实节点 + 不是 401 纯文本页
    if (s.ready === "complete" && s.els > 200 && s.ct !== "text/plain") return s;
    await new Promise((r) => setTimeout(r, 400));
  }
  return null;
}

async function main() {
  const userDir = mkdtempSync(join(tmpdir(), "zoomprof-"));
  if (!ATTACH) {
    chrome = spawn(CHROME, [
      "--headless=new",
      "--remote-debugging-port=" + PORT,
      "--user-data-dir=" + userDir,
      "--no-first-run", "--no-default-browser-check",
      "--disable-gpu", "--hide-scrollbars",
      "about:blank",
    ], { stdio: "ignore", detached: false });
  }

  // 等 devtools 端口起来
  for (let i = 0; i < 60; i++) {
    try { await fetch("http://" + target + "/json/version"); break; }
    catch { await new Promise((r) => setTimeout(r, 250)); }
  }

  const page = await withSession(async (send) => {
    await send("Page.enable");
    await send("Runtime.enable");
    // 固定观测条件：手机视口
    await send("Emulation.setDeviceMetricsOverride", {
      width: W, height: H, deviceScaleFactor: DPR, mobile: true,
    });
    // mobile:true 只有配上触摸模拟，Blink 才会走「移动端视口」那条路径
    await send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 5 });
    if (!SKIP_NAV) {
      await send("Page.navigate", { url: URL_ });
      // 等 load 事件 + 就绪判据
      await new Promise((r) => setTimeout(r, 1500));
    }
    const ready = await waitReady(send, 30000);
    const info = await ev(send, `JSON.stringify({
      url: location.href,
      title: document.title,
      contentType: document.contentType,
      els: document.querySelectorAll('*').length,
      innerWidth: window.innerWidth,
      dpr: window.devicePixelRatio,
      compatMode: document.compatMode,
      visualScale: window.visualViewport ? window.visualViewport.scale : null
    })`);
    return { ready, info: JSON.parse(info) };
  });

  // 跑测量（复用 probe，避免两份实现漂移）
  const probeOut = execFileSync("node", [
    join(HERE, "web-zoom-probe.mjs"),
    "--ws", await cdp(),
    "--scales", SCALES,
    // 观测条件必须交给 probe 自己设：它的会话一断，CDP 的 emulation 就失效。
    "--width", String(W), "--height", String(H), "--dpr", String(DPR),
    "--out", "/tmp/zoom-probe-raw.json",
  ], { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 });

  const probe = JSON.parse(probeOut);
  const merged = {
    conditions: { mode: ATTACH ? "device" : "chrome-engine-proxy", url: URL_, width: W, height: H, dpr: DPR, scales: SCALES },
    page: page,
    probe: probe,
    note: ATTACH
      ? "真机 WebView 测量"
      : "本机 Chrome 引擎对照：可验证缩放语义，但 Android WebView 的钳制规则可能不同，最终需真机复核",
  };
  writeFileSync(OUT, JSON.stringify(merged, null, 2));
  console.log(JSON.stringify({
    conditions: merged.conditions,
    page: { url: merged.page.info.url, title: merged.page.info.title, els: merged.page.info.els },
    verdict: probe.verdict,
    steps: probe.steps.map((s) => ({
      requested: s.requested, actual: s.actualScale,
      clamped: s.clamped, scrollWidth: s.geometry.scrollWidth,
      innerWidth: s.geometry.innerWidth, probeW: Math.round(s.geometry.rectCss.w),
    })),
  }, null, 2));
  console.log("\n完整结果已写入:", OUT);
}

main()
  .catch((e) => { console.error("harness 失败:", e.message); process.exit(1); })
  .finally(() => { if (chrome) { try { chrome.kill(); } catch {} } });
