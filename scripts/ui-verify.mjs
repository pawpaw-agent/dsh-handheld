/*
 * 手机端适配层的**虚拟环境验证**（headless Chromium + CDP + **file:// fixture**）
 *
 * ## 为什么是 fixture 而不是真实 dsh 页面
 *
 * 这台机器上 **chromium 发不出任何 HTTP**：`Page.navigate` 到任何 `http://`（哪怕是一个
 * 平凡的本地 python 服务）都卡在**创建渲染进程之前**，而 `file://` 与 `data:` 正常。
 * 这不是本 harness 的发明 —— 仓库里被归档的两个实验脚本（`css-lab.mjs` /
 * `composer-stats-lab.mjs`）的注释里就写着同一件事，它们也是用 **file:// fixture** 跑的：
 *
 *   > 沙箱里 Chromium 发不出 HTTP，所以页面是 file:// 的 fixture（真实 class 名 + 真实 CSS）。
 *
 * 证据链与排除过程见 `docs/known-issues.md` 第十三节（含被推翻的 16K 页假设、内核回退实验）。
 *
 * ## fixture 的真实性从哪来
 *
 *   - **class 名**：从**已安装的 dsh 产物**里读（`node_modules/@deepseek-ai/dsh-client-…/lib/client.js`），
 *     与 `check-mobile-hooks.mjs` 用的是同一套定位逻辑 —— 不把 dsh 的类名抄进仓库；
 *   - **CSS**：把客户端产物里**全部** `const css$N = "..."` 段抽出来（当前 60 个包 / 97 段 /
 *     ~265 KB），原样放进 fixture —— 适配层的样式是对**真实规则**的覆盖，脱离真实 CSS 就没有意义；
 *   - **DOM 结构**：按适配层钩子需要的最小嵌套手工搭（外壳 → 侧栏列 / 标题行 / 输入区 /
 *     统计行 / 右侧栏），不追求与真实页面逐节点一致。
 *
 * ## 它验什么（V1–V8）
 *
 *   V1  注入确实发生（hooks/fixes/css 都在；生产路径不带诊断）
 *   V2  frame 标记（整套 CSS 的前提）
 *   V3  样式生效：与不注入的基线 A/B 比几何（标题行 / 输入区 / 统计行）
 *   V4  --dsh-handheld-vh 与 visualViewport.height 一致
 *   V5  键盘弹起时 scrollIntoView 被调用（页面侧装 spy）
 *   V6  右侧栏工具栏**真的点得到**（elementFromPoint 命中面板自身，而不是盖在它上面的元素）
 *   V7  无横向溢出
 *   V8  桌面档下整层**不生效**（媒体查询是 (max-width:1023px) and (pointer:coarse)）
 *
 * ## 它**不能**替代的
 *
 *   - 真实 Android WebView 的渲染（这里只有 Playwright/Debian chromium）；
 *   - 真实 dsh 页面的完整 DOM（fixture 是**模型**，几何与层叠可信、视觉不可信）；
 *   - 真实软键盘/IME（V5 只证明监听与滚动调用被触发）；
 *   - 真实手指命中半径（V6 用 elementFromPoint 近似）；
 *   - App 侧与隧道相关的一切（权限/外链/通知/多主机）—— 仍需真机。
 *
 * ## 用法
 *
 *   node scripts/ui-verify.mjs [--out <dir>] [--chrome <path>]
 *
 * 不需要 dsh 在跑、不需要 token、不需要网络。
 */
import { spawn, execSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, openSync, readdirSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import path from 'node:path';
import os from 'node:os';

const argv = process.argv.slice(2);
const arg = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const OUT = arg('out', path.join(os.homedir(), 'dsh-verify', `ui-verify-${Date.now()}`));
const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const HANDHELD = path.join(REPO, 'android/app/src/main/assets/plugins/handheld');
const MAIN_ACTIVITY = path.join(REPO, 'android/app/src/main/java/com/dshhandheld/app/MainActivity.kt');

const CHROME = arg('chrome', process.env.CHROME_BIN
  || '/usr/bin/chromium');

// 手机档：设备 2（SM-G7810，1080x2400@3.0 → CSS 360x800）；桌面档给 V8
const MOBILE = { width: 360, height: 800, dsf: 3, mobile: true };
const DESKTOP = { width: 1280, height: 800, dsf: 1, mobile: false };

/** 注入的 6 段，**顺序与 App 完全一致**（MainActivity 的注入块）。CI 的
 *  `check-injection-parity.mjs` 比对这两份列表。 */
const SEGMENTS = [
  'bootstrap.js',   // ① document-start 补 viewport-fit=cover
  'hooks.js',       // ② 宿主 DOM 钩子唯一出处
  'diag.js',        // ③ 自证与开销（默认关）
  'fixes.js',       // ④ 修复集
  null,             // ⑤ 样式（读 styles.css 包成一行赋值 —— 与 App 同样剥注释）
  'runner.js',      // ⑥ 挂样式 / 观察 / 应用 / 隔离
];

function readInjection() {
  const scripts = [];
  for (const name of SEGMENTS) {
    if (name === null) {
      const css = readFileSync(path.join(HANDHELD, 'styles.css'), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '');
      scripts.push('window.__dshHandheldCss = ' + JSON.stringify(css) + ';');
    } else {
      scripts.push(readFileSync(path.join(HANDHELD, name), 'utf8'));
    }
  }
  return scripts;
}

// ── 定位 dsh 安装产物（与 check-mobile-hooks.mjs 同一套）────────────────────
function findDshModules() {
  const explicit = process.env.DSH_MODULES || arg('dsh-modules', '');
  if (explicit) return existsSync(explicit) ? explicit : null;
  try {
    const bin = execSync('command -v dsh', { encoding: 'utf8' }).trim();
    if (!bin) return null;
    let dir = path.dirname(execSync(`readlink -f ${bin}`, { encoding: 'utf8' }).trim());
    for (let i = 0; i < 6 && dir !== '/'; i++) {
      const cand = path.join(dir, 'node_modules/@deepseek-ai');
      if (existsSync(cand) && existsSync(path.join(cand, 'dsh-client-ui-chat'))) return cand;
      dir = path.dirname(dir);
    }
  } catch { /* 继续 */ }
  return null;
}

/** 把客户端产物里全部 `const css$N = "..."` 段抽出来 —— 适配层的样式是对**真实规则**的
 *  覆盖，所以 fixture 必须带上真实 CSS，否则测的就不是同一件事。 */
function extractHostCss(modules) {
  let all = '';
  let pkgs = 0, chunks = 0;
  const dirs = existsSync(modules) ? readdirSync(modules) : [];
  for (const p of dirs) {
    if (!/^dsh-(client-ui|web)/.test(p)) continue;
    const f = path.join(modules, p, 'lib/client.js');
    if (!existsSync(f)) continue;
    pkgs++;
    const src = readFileSync(f, 'utf8');
    for (const m of src.matchAll(/const css\$\d+ = "((?:[^"\\]|\\.)*)"/g)) { all += m[1] + '\n'; chunks++; }
  }
  return { css: all, pkgs, chunks };
}

// ── 极简 CDP 客户端（零依赖）────────────────────────────────────────────────
class CDP {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.handlers = new Map();
    this.closed = null;      // 关闭原因（null = 仍开着）
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id != null && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? reject(new Error(`${msg.error.message} (${JSON.stringify(msg.error.data ?? '')})`)) : resolve(msg.result);
      } else if (msg.method) {
        for (const h of this.handlers.get(msg.method) ?? []) h(msg.params);
      }
    });
    // chrome 崩溃/退出时必须让所有在等的 promise 立刻失败。
    // 否则 send() 永远不返回，表现为整条流水线静默挂死（本 harness 踩过）。
    const die = (why) => {
      this.closed = why;
      for (const [, { reject }] of this.pending) reject(new Error(`CDP 连接已断开：${why}`));
      this.pending.clear();
    };
    ws.addEventListener('close', () => die('socket closed'), { once: true });
    ws.addEventListener('error', () => die('socket error'), { once: true });
  }
  send(method, params = {}, timeoutMs = 30000) {
    if (this.closed) return Promise.reject(new Error(`CDP 连接已断开：${this.closed}`));
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      // 注册在前、发送在后：即便响应极快也不会漏
      const timer = setTimeout(() => {
        if (this.pending.delete(id)) reject(new Error(`CDP ${method} 超时（${timeoutMs}ms）`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      try { this.ws.send(JSON.stringify({ id, method, params })); }
      catch (e) { this.pending.delete(id); clearTimeout(timer); reject(e); }
    });
  }
  on(method, fn) {
    if (!this.handlers.has(method)) this.handlers.set(method, []);
    this.handlers.get(method).push(fn);
  }
  static async connect(wsUrl) {
    const ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => {
      ws.addEventListener('open', res, { once: true });
      ws.addEventListener('error', () => rej(new Error('CDP WebSocket 连接失败')), { once: true });
    });
    return new CDP(ws);
  }
}

// ── 浏览器 ──────────────────────────────────────────────────────────────────
async function launch() {
  if (!existsSync(CHROME)) throw new Error(`找不到 chromium：${CHROME}\n可用 CHROME_BIN 覆盖`);
  const profile = path.join(OUT, 'chrome-profile');
  const port = 9333 + Math.floor(Math.random() * 400);  // 避免与残留实例抢端口
  // chrome 的 stderr 必须落到文件：留成管道而不消费，写满后 chrome 会阻塞。
  const errPath = path.join(OUT, 'chrome-stderr.log');
  const errFd = openSync(errPath, 'w');
  const proc = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu',
    '--disable-dev-shm-usage',
    // 本机实测（2026-10-08）：默认会 `Zygote could not fork: process_type renderer`
    // —— 渲染进程起不来，页面根本不渲染，表现为 `Page.navigate` 超时 30s。
    // 这台机器上 zygote 派生子进程受限，用这两个开关绕开（单进程 + 不用 zygote）。
    '--no-zygote', '--single-process',
    `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: ['ignore', 'ignore', errFd] });

  const cleanup = () => { try { proc.kill('SIGKILL'); } catch { /* ignore */ } };

  for (let i = 0; i < 80; i++) {
    if (proc.exitCode !== null) {
      cleanup();
      throw new Error(`chromium 提前退出（code ${proc.exitCode}），见 ${errPath}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await r.json();
      const page = targets.find((t) => t.type === 'page');
      if (page?.webSocketDebuggerUrl) return { proc, wsUrl: page.webSocketDebuggerUrl, cleanup };
    } catch { /* 还没起来 */ }
    await sleep(250);
  }
  cleanup();
  throw new Error(`chromium devtools 端点未就绪（20s），见 ${errPath}`);
}


// ── fixture：真实 class 名 + 真实宿主 CSS + 适配层钩子需要的最小嵌套 ──────────
function buildFixture(hostCss) {
  return `<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>${hostCss}</style>
<style>
  /* fixture 自己的脚手架：只保证「有这些东西、层级对」，不模仿真实页面的视觉 */
  html,body{margin:0;padding:0;height:100%;background:#0A0A0E;color:#F5F5F7;
    font-family:system-ui,-apple-system,"Noto Sans CJK SC",sans-serif}
  #frame{display:flex;height:100%;width:100%}
  .fixture_sidebarCol{width:56px;flex:0 0 56px;background:#141418}
  #main{flex:1;min-width:0;display:flex;flex-direction:column}
  .wSkVaW_titleRow{display:flex;align-items:center;justify-content:space-between;
    height:52px;padding:0 16px;border-bottom:1px solid #26262B}
  .bhn1Oq_headerActions{display:flex;gap:8px}
  .rtSEdW_iconButton{width:36px;height:36px;border:0;border-radius:8px;background:#1A1A1F;color:#C7CBD1}
  .wSkVaW_tabs{display:flex;gap:8px;height:40px;padding:0 16px;align-items:center;border-bottom:1px solid #26262B}
  .EvIC1a_scroll{flex:1;overflow:auto;padding:16px}
  .wSkVaW_composerSeat{padding:0 0 8px}
  .uV2eYG_dock{padding:0 16px}
  [data-composer-stats]{display:flex;gap:12px;align-items:center;padding:6px 0 10px;
    font-size:12px;color:#9AA0A6}
  textarea{width:100%;height:88px;box-sizing:border-box;background:#121216;color:#F5F5F7;
    border:1px solid #2A2A30;border-radius:12px;padding:10px}
  /* 右侧栏：真机手机档是**全屏**的（层的修复据此判「已展开」：
     b.width >= innerWidth-1 && b.left <= 1），属性值也与宿主一致（fullscreen） */
  [data-sidebar-right-panel]{position:fixed;top:0;left:0;width:100%;height:100%;
    background:#101014;z-index:5}
  /* 面板工具栏：真机读数（层注释里记着）是 CSS y≈40–68，层的采样点就是 top+44/55/66 */
  [data-sidebar-right-panel] .bhn1Oq_headerActions{position:absolute;top:40px;left:0;
    right:0;height:28px;display:flex;gap:8px;align-items:center;padding:0 10px}
  /* 故意盖住那条带子的同级遮挡 —— V6 要断言它被让开 */
  .fixture_obstruction{position:fixed;top:0;left:0;width:100%;height:68px;
    background:rgba(255,0,0,.18);z-index:9}
</style></head>
<body>
  <!-- data-sidebar-collapsed：手机上抽屉默认收起（层据此把侧栏列移出屏幕并让出指针） -->
  <div id="frame" data-sidebar-collapsed>
    <aside class="fixture_sidebarCol" aria-label="侧栏"></aside>
    <div id="main">
      <div class="wSkVaW_titleRow"><span>会话标题</span>
        <div class="bhn1Oq_headerActions">
          <button class="rtSEdW_iconButton" aria-label="设置">⚙</button>
          <button class="fV0t5q_toggle" aria-label="面板">▤</button>
        </div>
      </div>
      <div class="wSkVaW_tabs"><span>标签一</span><span>标签二</span></div>
      <div class="EvIC1a_scroll"><p>消息内容占位</p><p>再一条</p></div>
      <div class="wSkVaW_composerSeat">
        <div class="uV2eYG_dock">
          <div data-composer-stats><span>101 轮 314 步 · 179 tok/s</span><span>77.4M tok · 缓存命中 98%</span></div>
          <textarea data-composer-input placeholder="输入…"></textarea>
        </div>
      </div>
    </div>
  </div>
  <div data-sidebar-right-panel="fullscreen" data-sidebar-right-open>
    <div class="bhn1Oq_headerActions">
      <button class="rtSEdW_iconButton" aria-label="面板工具一">✕</button>
      <button class="rtSEdW_iconButton" aria-label="面板工具二">⤢</button>
      <button class="rtSEdW_iconButton" aria-label="面板工具三">⋯</button>
    </div>
  </div>
  <!-- 遮挡层是**面板外**的同级元素：层的修复要认的就是这种（面板内的不算） -->
  <div class="fixture_obstruction"></div>
</body></html>`;
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`页面内求值失败：${r.exceptionDetails.text}`);
  return r.result.value;
}

/** 一次运行：可选注入、可指定视口档。 */
async function run({ inject, viewport, label, scripts, pageUrl }) {
  const { wsUrl, cleanup } = await launch();
  const cdp = await CDP.connect(wsUrl);
  try {
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: viewport.width, height: viewport.height,
      deviceScaleFactor: viewport.dsf, mobile: viewport.mobile,
    });
    // maxTouchPoints 必须 1..16 —— 桌面档只关 enabled，不传 0（CDP 会拒绝）
    await cdp.send('Emulation.setTouchEmulationEnabled', viewport.mobile
      ? { enabled: true, maxTouchPoints: 5 }
      : { enabled: false });
    if (inject) for (const source of scripts) {
      await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source });
    }
    await cdp.send('Page.navigate', { url: pageUrl });
    await sleep(1500);

    const observed = await evaluate(cdp, `(() => {
      const de = document.documentElement;
      const q = (s) => document.querySelectorAll(s).length;
      const box = (s) => {
        const el = document.querySelector(s);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        return { h: Math.round(r.height * 10) / 10, top: Math.round(r.top * 10) / 10,
                 padTop: cs.paddingTop, padBottom: cs.paddingBottom };
      };
      return {
        hasHooks: typeof window.__dshHandheldHooks === 'object' && window.__dshHandheldHooks !== null,
        hasFixes: Array.isArray(window.__dshHandheldFixes),
        hasCss: typeof window.__dshHandheldCss === 'string',
        diagOn: !!(window.__dshHandheldDiag && window.__dshHandheldDiag.on),
        styleTags: [...document.querySelectorAll('style')].filter(s => s.dataset.handheldCss).length,
        frameTagged: q('[data-handheld="frame"]'),
        htmlClass: de.classList.contains('dsh-handheld-mobile'),
        vhVar: de.style.getPropertyValue('--dsh-handheld-vh') || null,
        vvHeight: window.visualViewport ? Math.round(window.visualViewport.height) : null,
        overflowX: de.scrollWidth - de.clientWidth,
        titleRow: box('.wSkVaW_titleRow'),
        composerSeat: box('.wSkVaW_composerSeat'),
        stats: box('[data-composer-stats]'),
        textarea: box('[data-composer-input]'),
      };
    })()`);

    const scroll = inject ? await evaluate(cdp, `(async () => {
      window.__scrollSpy = [];
      const proto = Element.prototype;
      if (!proto.__handheldSpy) {
        const orig = proto.scrollIntoView;
        proto.scrollIntoView = function () {
          window.__scrollSpy.push(arguments[0] === undefined ? null : arguments[0]);
          return orig.apply(this, arguments);
        };
        proto.__handheldSpy = true;
      }
      const el = document.querySelector('[data-composer-input]');
      if (!el) return { ok: false, why: '找不到输入框' };
      el.focus();
      document.dispatchEvent(new Event('focusin', { bubbles: true }));
      await new Promise(r => setTimeout(r, 500));
      return { ok: window.__scrollSpy.length > 0, calls: window.__scrollSpy.length,
               arg: window.__scrollSpy[0] ?? null };
    })()`) : null;

    const panel = await evaluate(cdp, `(() => {
      const p = document.querySelector('[data-sidebar-right-panel]');
      if (!p) return { opened: false, why: '没有面板' };
      const r = p.getBoundingClientRect();
      // 打在**面板工具栏那条带子**上（层按真机读数只处理 y≈40–68；top+22 在带子外面）
      const x = Math.round(r.left + r.width / 2), y = Math.round(r.top + 55);
      const hit = document.elementFromPoint(x, y);
      const inside = !!(hit && (p.contains(hit) || hit === p));
      const none = [...document.querySelectorAll('*')].filter(e => e.style.pointerEvents === 'none').length;
      return { opened: true, inside, hitTag: hit ? hit.tagName : null,
               hitClass: hit ? String(hit.className).slice(0, 40) : null, noneCount: none };
    })()`);

    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
    const shotPath = path.join(OUT, `${label}.png`);
    writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));
    return { observed, scroll, panel, shotPath };
  } finally {
    try { await Promise.race([cdp.send('Browser.close'), sleep(2000)]); } catch { /* ignore */ }
    cleanup();
  }
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
const main = async () => {
  mkdirSync(OUT, { recursive: true });
  if (!existsSync(CHROME)) throw new Error(`找不到 chromium：${CHROME}\n（--chrome 或 CHROME_BIN 可覆盖）`);
  for (const s of SEGMENTS) {
    if (s && !existsSync(path.join(HANDHELD, s))) throw new Error(`缺少注入段：${s}`);
  }
  const modules = findDshModules();
  if (!modules) throw new Error('找不到 dsh 安装产物（DSH_MODULES 可指定）');
  const host = extractHostCss(modules);
  if (host.css.length < 10000) throw new Error(`宿主 CSS 抽出来只有 ${host.css.length} 字节，不对劲`);
  const fixturePath = path.join(OUT, 'fixture.html');
  writeFileSync(fixturePath, buildFixture(host.css));
  const pageUrl = `file://${fixturePath}`;
  const scripts = readInjection();
  const bytes = scripts.reduce((n, s) => n + Buffer.byteLength(s), 0);

  console.log(`浏览器   ${CHROME}`);
  console.log(`宿主 CSS ${host.pkgs} 个包 / ${host.chunks} 段 / ${(host.css.length / 1024).toFixed(1)} KB（从 dsh 安装产物抽）`);
  console.log(`fixture  ${fixturePath}`);
  console.log(`注入     ${scripts.length} 段 / ${(bytes / 1024).toFixed(1)} KB`);
  console.log(`视口     手机 ${MOBILE.width}x${MOBILE.height}@${MOBILE.dsf}x（触屏） / 桌面 ${DESKTOP.width}x${DESKTOP.height}`);
  console.log('');

  const baseline = await run({ inject: false, viewport: MOBILE, label: 'a-baseline-mobile', scripts, pageUrl });
  console.log('[A 基线·不注入] 完成');
  const adapted = await run({ inject: true, viewport: MOBILE, label: 'b-adapted-mobile', scripts, pageUrl });
  console.log('[B 适配·注入]   完成');
  const desktop = await run({ inject: true, viewport: DESKTOP, label: 'c-desktop', scripts, pageUrl });
  console.log('[C 桌面档]      完成（V8：层不该生效）');
  console.log('');

  const o = adapted.observed, b = baseline.observed, d = desktop.observed;
  const geom = (x) => x ? `h=${x.h} top=${x.top}` : '（无）';
  const checks = [];
  const add = (id, name, ok, detail) => checks.push({ id, name, ok: !!ok, detail });
  add('V1', '注入确实发生（hooks/fixes/css 都在）',
    o.hasHooks && o.hasFixes && o.hasCss && o.styleTags > 0,
    `hooks=${o.hasHooks} fixes=${o.hasFixes} css=${o.hasCss} styleTags=${o.styleTags}`);
  add('V1b', '生产路径不带诊断', o.diagOn === false, `diag.on=${o.diagOn}`);
  add('V2', 'frame 标记 + html class',
    o.frameTagged > 0 && o.htmlClass === true, `frame=${o.frameTagged} htmlClass=${o.htmlClass}`);
  add('V3', '样式生效（几何与基线有差异）',
    JSON.stringify(o.titleRow) !== JSON.stringify(b.titleRow)
      || JSON.stringify(o.composerSeat) !== JSON.stringify(b.composerSeat)
      || JSON.stringify(o.stats) !== JSON.stringify(b.stats),
    `titleRow ${geom(b.titleRow)} → ${geom(o.titleRow)}；composer ${geom(b.composerSeat)} → ${geom(o.composerSeat)}；stats ${geom(b.stats)} → ${geom(o.stats)}`);
  add('V4', '--dsh-handheld-vh 与 visualViewport 一致',
    o.vhVar !== null && o.vvHeight !== null && Math.abs(parseInt(o.vhVar, 10) - o.vvHeight) <= 1,
    `--vh=${o.vhVar} visualViewport=${o.vvHeight}`);
  add('V5', '键盘弹起 → scrollIntoView 被调用', adapted.scroll?.ok === true,
    `调用 ${adapted.scroll?.calls ?? 0} 次，参数=${JSON.stringify(adapted.scroll?.arg ?? null)}`);
  add('V6', '右侧栏工具栏点得到（命中面板自身）',
    adapted.panel?.opened === true && adapted.panel?.inside === true,
    adapted.panel?.opened
      ? `命中 ${adapted.panel.hitTag}.${adapted.panel.hitClass}（面板内=${adapted.panel.inside}，被让开元素=${adapted.panel.noneCount}）`
      : `面板没打开：${adapted.panel?.why}`);
  // 负对照：**不注入**时同一条带子必须点不到 —— 否则 V6 是自证（本来就通）
  add('V6b', '负对照：不注入时该带子被遮挡（点不到）',
    baseline.panel?.opened === true && baseline.panel?.inside === false,
    baseline.panel?.opened
      ? `命中 ${baseline.panel.hitTag}.${baseline.panel.hitClass}（面板内=${baseline.panel.inside}）`
      : `面板没打开：${baseline.panel?.why}`);
  add('V7', '无横向溢出', o.overflowX <= 1, `overflowX=${o.overflowX}`);
  add('V8', '桌面档下整层不生效',
    d.frameTagged === 0 && d.styleTags === 0 && !d.vhVar,
    `frame=${d.frameTagged} styleTags=${d.styleTags} vh=${d.vhVar}`);

  console.log('── 断言 ──');
  let failed = 0;
  for (const c of checks) {
    if (!c.ok) failed++;
    console.log(`  ${c.ok ? '✓' : '✗'} ${c.id.padEnd(4)} ${c.name}`);
    if (c.detail) console.log(`         ${c.detail}`);
  }
  console.log('');
  writeFileSync(path.join(OUT, 'report.json'), JSON.stringify({
    chrome: CHROME, mobile: MOBILE, desktop: DESKTOP, injectedBytes: bytes,
    hostCss: { pkgs: host.pkgs, chunks: host.chunks, bytes: host.css.length },
    baseline, adapted, desktop, checks,
  }, null, 2));
  console.log(`截图   ${adapted.shotPath}`);
  console.log(`       ${baseline.shotPath}`);
  console.log(`       ${desktop.shotPath}`);
  console.log(`报告   ${path.join(OUT, 'report.json')}`);
  console.log('');
  console.log(failed === 0 ? '交互验证通过 ✓' : `${failed} 项未通过 ✗`);
  process.exit(failed === 0 ? 0 : 1);
};

main().catch((e) => { console.error(`\n失败：${e.message}`); process.exit(2); });
