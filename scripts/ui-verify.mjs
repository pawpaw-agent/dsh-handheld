/*
 * 手机端适配层的**虚拟环境验证**（headless Chromium + CDP，零依赖）
 *
 * ## 它验什么
 *
 * 在**真实 dsh 页面**上、用**手机档视口 + 触屏**、按**与 App 完全相同的顺序**注入
 * `assets/plugins/handheld/` 那 6 段脚本，然后断言「交互」而不是「看起来对不对」：
 *
 *   V1 注入确实发生（且生产路径不带诊断）
 *   V2 frame 标记（整套 CSS 的前提）
 *   V3 样式生效（与不注入的基线 A/B 比几何）
 *   V4 --dsh-handheld-vh 与 visualViewport.height 一致
 *   V5 键盘弹起时 scrollIntoView 被调用（页面侧装 spy）
 *   V6 右侧栏工具栏**真的点得到**（elementFromPoint 命中面板自身，而不是盖在它上面的东西）
 *   V7 无横向溢出
 *   V8 桌面档下整层**不生效**（CSS 媒体查询是 (max-width:1023px) and (pointer:coarse)）
 *
 * ## 用法
 *
 *   node scripts/ui-verify.mjs [--base http://127.0.0.1:3080] [--token <t>] [--out <dir>]
 *
 * token 从 dsh 启动日志里取（与 App 同一套办法）：
 *   journalctl --user -u dsh-web.service -n 50 --no-pager | grep -oE 'token=[A-Za-z0-9_-]+'
 *
 * ## 环境限制（本机实测，2026-10-08）
 *
 * 这台机器上 **chromium 无法渲染 http 页面**（两个构建、headless 与有头都一样）：netlog 证明
 * 网络层成功（TCP 连上、收发过数据），但渲染进程在**导航提交**处卡死。详见
 * docs/known-issues.md 第十三节的完整证据链（含被推翻的 16K 页假设）。原始记录：
 * 「连不上任何 http」：连一个平凡的本地 python 服务也卡在建立连接
 * 之前（CDP 只报 `Network.requestWillBeSent`，之后没有任何事件），换过
 * `--no-zygote` / `--single-process` / `--no-proxy-server` / `NetworkServiceSandbox off`
 * / `NetworkServiceInProcess` / `host-resolver-rules` 全部无效；而 Node 与 curl 的网络是通的。
 *
 * 因此 harness 用 CDP 的 **Fetch 域**把每个请求接过来、由 **Node 代取**后回填 ——
 * 与 App 里 `shouldInterceptRequest` 是同一手法。代价：页面里的 **WebSocket**（dsh 的
 * 实时事件流）走不了这条路，页面会显示「未连接实时通道」；布局、DOM 钩子、CSS 与
 * 四条修复都不依赖它。
 *
 * ## 它**不能**替代的
 *
 *   - 真实 Android WebView 的渲染差异（这里是 Playwright chromium）；
 *   - 真实软键盘/IME（V5 只证明监听与滚动调用被触发）；
 *   - 真实手指的命中半径（V6 用 elementFromPoint 近似）；
 *   - App 侧与隧道相关的一切（权限/外链/通知/多主机）—— 仍需真机。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, openSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import path from 'node:path';
import os from 'node:os';

// ── 参数 ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const arg = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const BASE = arg('base', 'http://127.0.0.1:3080');
const TOKEN = arg('token', process.env.DSH_TOKEN ?? '');
const OUT = arg('out', path.join(os.homedir(), 'dsh-verify', `ui-verify-${Date.now()}`));
const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const HANDHELD = path.join(REPO, 'android/app/src/main/assets/plugins/handheld');
const MAIN_ACTIVITY = path.join(REPO, 'android/app/src/main/java/com/dshhandheld/app/MainActivity.kt');

const CHROME = process.env.CHROME_BIN
  || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux-arm64/chrome');

// 手机档：设备 2（SM-G7810，1080x2400@3.0 → CSS 360x800）
const MOBILE = { width: 360, height: 800, dsf: 3, mobile: true };
// 桌面档：给 V8 用（宽 > 1023 且非触屏 → 整层不该生效）
const DESKTOP = { width: 1280, height: 800, dsf: 1, mobile: false };

/**
 * 注入的 6 段，**顺序与 App 完全一致**（MainActivity 的注入块）。
 * 一致性由 CI 的 check 守着：两边都从这份列表出发（见 scripts/check-injection-parity.mjs）。
 */
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
        .replace(/\/\*[\s\S]*?\*\//g, '');   // 注释只在仓库里给人看（与 App 一致）
      scripts.push('window.__dshHandheldCss = ' + JSON.stringify(css) + ';');
    } else {
      scripts.push(readFileSync(path.join(HANDHELD, name), 'utf8'));
    }
  }
  return scripts;
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

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`页面内求值失败：${r.exceptionDetails.text}`);
  return r.result.value;
}

/** 一次运行：可注入、可指定视口档，返回观测 + 交互结果。 */
async function run({ inject, viewport, label, scripts }) {
  const { wsUrl, cleanup } = await launch();
  const cdp = await CDP.connect(wsUrl);
  try {
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: viewport.width, height: viewport.height,
      deviceScaleFactor: viewport.dsf, mobile: viewport.mobile,
    });
    await cdp.send('Emulation.setTouchEmulationEnabled', {
      enabled: viewport.mobile, maxTouchPoints: viewport.mobile ? 5 : 0,
    });
    if (viewport.mobile) {
      await cdp.send('Emulation.setUserAgentOverride', {
        userAgent: 'Mozilla/5.0 (Linux; Android 13; SM-G7810) AppleWebKit/537.36 '
          + '(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36',
      });
    }
    // = App 的 WebViewCompat.addDocumentStartJavaScript（按序注入 6 段）
    if (inject) {
      for (const source of scripts) {
        await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source });
      }
    }

    // ── 让 Node 充当网络（本机 chromium 的网络被环境限制，见文件头「环境限制」）──
    //
    // 本机实测：这个环境里 chromium **连不上任何 http**（连一个平凡的 python 本地服务
    // 也卡在连接前，只有 `Network.requestWillBeSent` 没有任何后续事件），而 Node/curl
    // 的网络是通的。于是用 CDP 的 Fetch 域把每个请求接过来，由 Node 代取后回填 ——
    // 与 App 里 `shouldInterceptRequest` 是同一手法。
    await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
    cdp.on('Fetch.requestPaused', async (p) => {
      const url = p.request.url;
      if (process.env.UI_VERIFY_TRACE) console.error(`    [net] ${p.request.method} ${url.slice(0, 90)}`);
      try {
        if (url.startsWith('data:') || url.startsWith('blob:')) {
          await cdp.send('Fetch.continueRequest', { requestId: p.requestId });
          return;
        }
        const init = { method: p.request.method, redirect: 'manual', headers: {} };
        for (const [k, v] of Object.entries(p.request.headers ?? {})) {
          if (k.toLowerCase() !== 'host') init.headers[k] = v;
        }
        if (p.request.postData) init.body = p.request.postData;
        const r = await fetch(url, init);
        const buf = Buffer.from(await r.arrayBuffer());
        const headers = [];
        r.headers.forEach((value, name) => headers.push({ name, value }));
        await cdp.send('Fetch.fulfillRequest', {
          requestId: p.requestId, responseCode: r.status,
          responseHeaders: headers, body: buf.toString('base64'),
        });
      } catch (e) {
        console.error(`    [net] 代理失败 ${url.slice(0, 80)} — ${e.message}`);
        try { await cdp.send('Fetch.failRequest', { requestId: p.requestId, errorReason: 'Failed' }); }
        catch { /* 请求可能已取消 */ }
      }
    });
    const url = TOKEN ? `${BASE}/?token=${encodeURIComponent(TOKEN)}` : `${BASE}/`;
    await cdp.send('Page.navigate', { url });
    await sleep(9000);

    // ── 探针（只读）──────────────────────────────────────────────
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
        title: document.title,
        hasHooks: typeof window.__dshHandheldHooks === 'object' && window.__dshHandheldHooks !== null,
        hasFixes: Array.isArray(window.__dshHandheldFixes),
        hasCss: typeof window.__dshHandheldCss === 'string',
        diagOn: !!(window.__dshHandheldDiag && window.__dshHandheldDiag.on),
        styleTags: [...document.querySelectorAll('style')].filter(s => s.dataset.handheldCss).length,
        frameTagged: q('[data-handheld="frame"]'),
        htmlClass: de.classList.contains('dsh-handheld-mobile'),
        vhVar: de.style.getPropertyValue('--dsh-handheld-vh') || null,
        vvHeight: window.visualViewport ? Math.round(window.visualViewport.height) : null,
        coverAttr: de.hasAttribute('data-dsh-cover'),
        overflowX: de.scrollWidth - de.clientWidth,
        // 几何：层改动最大的三处
        titleRow: box('[class*="_titleRow"]'),
        composerSeat: box('[class*="_composerSeat"]'),
        stats: box('[data-composer-stats]'),
        bodyChildren: document.body.children.length,
      };
    })()`);

    // ── V5：键盘弹起时 scrollIntoView 被调用 ──────────────────────
    const scroll = await evaluate(cdp, `(async () => {
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
      const el = document.querySelector('textarea, [data-composer-input]');
      if (!el) return { ok: false, why: '找不到输入框' };
      el.focus();
      document.dispatchEvent(new Event('focusin', { bubbles: true }));
      await new Promise(r => setTimeout(r, 400));
      return { ok: window.__scrollSpy.length > 0, calls: window.__scrollSpy.length,
               arg: window.__scrollSpy[0] ?? null };
    })()`);

    // ── V6：右侧栏工具栏真的点得到 ────────────────────────────────
    const panel = await evaluate(cdp, `(async () => {
      const panelOf = () => document.querySelector('[data-sidebar-right-panel]');
      const visible = (el) => el && el.getBoundingClientRect().width > 40
        && getComputedStyle(el).visibility !== 'hidden';
      // 逐个点候选按钮，直到面板出现（不猜按钮语义，只看结果）
      if (!visible(panelOf())) {
        const btns = [...document.querySelectorAll('button, [role="button"]')]
          .filter(b => { const r = b.getBoundingClientRect(); return r.width > 8 && r.height > 8; });
        for (const b of btns.slice(0, 12)) {
          try { b.click(); } catch (e) { /* ignore */ }
          await new Promise(r => setTimeout(r, 250));
          if (visible(panelOf())) break;
        }
      }
      const p = panelOf();
      if (!visible(p)) return { opened: false };
      const r = p.getBoundingClientRect();
      const x = Math.round(r.left + r.width / 2), y = Math.round(r.top + 22);
      const hit = document.elementFromPoint(x, y);
      const inside = !!(hit && (p.contains(hit) || hit === p));
      const blocked = [...document.querySelectorAll('[style*="pointer-events"]')]
        .filter(e => e.style.pointerEvents === 'none').length;
      return { opened: true, inside, hitTag: hit ? hit.tagName : null,
               hitDesc: hit ? (hit.getAttribute('data-handheld') || hit.className || '').toString().slice(0, 40) : null,
               blockedCount: blocked, panelBox: { left: Math.round(r.left), top: Math.round(r.top),
                 w: Math.round(r.width), h: Math.round(r.height) } };
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
  if (!existsSync(CHROME)) throw new Error(`找不到 chromium：${CHROME}\n（可用 CHROME_BIN 覆盖）`);
  for (const s of SEGMENTS) {
    if (s && !existsSync(path.join(HANDHELD, s))) throw new Error(`缺少注入段：${s}`);
  }
  const scripts = readInjection();
  const bytes = scripts.reduce((n, s) => n + Buffer.byteLength(s), 0);

  console.log(`靶子     ${BASE}`);
  console.log(`注入     ${scripts.length} 段 / ${(bytes / 1024).toFixed(1)} KB`);
  console.log(`手机档   ${MOBILE.width}x${MOBILE.height} @${MOBILE.dsf}x（触屏）`);
  console.log(`输出     ${OUT}`);
  console.log('');

  const baseline = await run({ inject: false, viewport: MOBILE, label: 'a-baseline-mobile' });
  console.log('[A 基线·不注入] 完成');
  const adapted = await run({ inject: true, viewport: MOBILE, label: 'b-adapted-mobile', scripts });
  console.log('[B 适配·注入]   完成');
  const desktop = await run({ inject: true, viewport: DESKTOP, label: 'c-desktop', scripts });
  console.log('[C 桌面档]      完成（V8：层不该生效）');
  console.log('');

  const o = adapted.observed, b = baseline.observed, d = desktop.observed;
  const geom = (x) => x ? `h=${x.h} padTop=${x.padTop}` : '（无）';
  const checks = [];
  const add = (id, name, ok, detail) => checks.push({ id, name, ok: !!ok, detail });

  add('V1', '注入确实发生（hooks/fixes/css 都在）',
    o.hasHooks && o.hasFixes && o.hasCss && o.styleTags > 0,
    `hooks=${o.hasHooks} fixes=${o.hasFixes} css=${o.hasCss} styleTags=${o.styleTags}`);
  add('V1b', '生产路径不带诊断', o.diagOn === false, `diag.on=${o.diagOn}`);
  add('V2', 'frame 标记 + html class',
    o.frameTagged > 0 && o.htmlClass === true,
    `frame=${o.frameTagged} htmlClass=${o.htmlClass} cover=${o.coverAttr}`);
  add('V3', '样式生效（几何与基线有差异）',
    JSON.stringify(o.titleRow) !== JSON.stringify(b.titleRow)
      || JSON.stringify(o.composerSeat) !== JSON.stringify(b.composerSeat)
      || JSON.stringify(o.stats) !== JSON.stringify(b.stats),
    `titleRow ${geom(b.titleRow)} → ${geom(o.titleRow)}；composer ${geom(b.composerSeat)} → ${geom(o.composerSeat)}`);
  add('V4', '--dsh-handheld-vh 与 visualViewport 一致',
    o.vhVar !== null && o.vvHeight !== null && Math.abs(parseInt(o.vhVar, 10) - o.vvHeight) <= 1,
    `--vh=${o.vhVar} visualViewport=${o.vvHeight}`);
  add('V5', '键盘弹起 → scrollIntoView 被调用',
    adapted.scroll?.ok === true,
    `调用 ${adapted.scroll?.calls ?? 0} 次，参数=${JSON.stringify(adapted.scroll?.arg ?? null)}`);
  add('V6', '右侧栏工具栏点得到（命中面板自身）',
    adapted.panel?.opened === true && adapted.panel?.inside === true,
    adapted.panel?.opened
      ? `命中 ${adapted.panel.hitTag}（面板内=${adapted.panel.inside}，被让开元素=${adapted.panel.blockedCount}）`
      : '面板没打开（环境未就绪）');
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
    base: BASE, mobile: MOBILE, desktop: DESKTOP, injectedBytes: bytes,
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
