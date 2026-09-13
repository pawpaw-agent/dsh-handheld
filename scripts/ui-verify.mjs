#!/usr/bin/env node
/**
 * 手机 Web UI 适配验证 —— 用真实 dsh 页面 + 真实注入路径，测「适配到底生效没有」。
 *
 * 为什么需要它：这个 App 的价值就是「在手机上用 dsh 网页」，而移动端适配此前
 * **零验证**（对比：终端有 52 个一致性用例）。失败模式还是静默的 —— dsh 改个属性名，
 * 抽屉不弹了/布局错位，只能在手机上发现。
 *
 * 它与 App 的对应关系是逐项的，不是"近似"：
 *
 *   Android WebView 侧                             本 harness（CDP）
 *   ─────────────────────────────────────────────  ──────────────────────────────────
 *   addDocumentStartJavaScript(bootstrap)          Page.addScriptToEvaluateOnNewDocument
 *   shouldInterceptRequest 返回 assets 的 bundle   Fetch.fulfillRequest 返回同一份 bytes
 *   手机尺寸 / 触屏                                 Emulation.setDeviceMetricsOverride
 *   addDocumentStartJavaScript 的 bootstrap 源码   scripts/mobile-bootstrap.js（同一份）
 *
 * A/B 设计：同一页面跑两遍 —— 不注入（桌面临界基线）与注入（适配后），**差异本身就是
 * 适配生效的证据**，而不是靠"看起来对"。
 *
 * 用法：
 *   node ui-verify.mjs --base http://127.0.0.1:38082 --token <token> [--out <dir>]
 *
 * 零依赖：Node 22 原生 WebSocket + fetch。CI 里不需要 npm install。
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
const BASE = arg('base', 'http://127.0.0.1:38082');
const TOKEN = arg('token', process.env.DSH_TOKEN ?? '');
const OUT = arg('out', path.join(process.cwd(), 'ui-verify-out'));
const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');

const BUNDLE = path.join(REPO, 'android/app/src/main/assets/plugins/dsh-handheld-mobile.js');
const BOOTSTRAP_SRC = path.join(REPO, 'android/app/src/main/assets/plugins/mobile-bootstrap.js');

// 与 MainActivity 的常量保持一致（id 必须与 bundle 内部一致；rev 是缓存键）
const PLUGIN_ID = 'dsh-handheld-mobile';
const PLUGIN_REV = 'dsh-handheld-mobile-1.0.12';
const PLUGIN_URL = `/plugins/??${PLUGIN_ID}/client.js&rev=${PLUGIN_REV}`;

const CHROME = process.env.CHROME_BIN
  || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux-arm64/chrome');

// 手机视口：与 README 记录的真机调试尺寸一致
const VIEWPORT = { width: 384, height: 832, dsf: 2.625, mobile: true };

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
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
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

// ── 一次运行：可选注入，返回观测结果 ────────────────────────────────────────
async function run({ inject, bundleBytes, bootstrap, label }) {
  const { wsUrl, cleanup } = await launch();
  const cdp = await CDP.connect(wsUrl);
  try {
    const step = (m) => console.error(`  · ${label} ${m}`);
    step('CDP 已连接');
    for (const ev of ['Page.frameNavigated', 'Page.loadEventFired', 'Page.javascriptDialogOpening', 'Inspector.targetCrashed']) {
      cdp.on(ev, () => step(ev));
    }
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Network.enable');

    // 手机视口 + 触屏
    step('启用域完成');
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: VIEWPORT.width, height: VIEWPORT.height,
      deviceScaleFactor: VIEWPORT.dsf, mobile: VIEWPORT.mobile,
    });
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await cdp.send('Emulation.setUserAgentOverride', {
      userAgent: 'Mozilla/5.0 (Linux; Android 13; SM-G7810) AppleWebKit/537.36 '
        + '(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36',
    });

    step('视口/触屏/UA 已设置');
    let intercepted = 0;
    if (inject) {
      // = shouldInterceptRequest：命中插件 URL 就返回 APK assets 里那份 bytes
      await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*/plugins/*' }] });
      cdp.on('Fetch.requestPaused', async (p) => {
        try {
          if (p.request.url.includes(`${PLUGIN_ID}/client.js`)) {
            intercepted++;
            await cdp.send('Fetch.fulfillRequest', {
              requestId: p.requestId,
              responseCode: 200,
              responseHeaders: [
                { name: 'Content-Type', value: 'text/javascript; charset=utf-8' },
                { name: 'Access-Control-Allow-Origin', value: '*' },
              ],
              body: Buffer.from(bundleBytes).toString('base64'),
            });
          } else {
            await cdp.send('Fetch.continueRequest', { requestId: p.requestId });
          }
        } catch { /* 请求可能已取消 */ }
      });
      // = addDocumentStartJavaScript
      await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: bootstrap });
    }

    step('注入已装配');
    const url = TOKEN ? `${BASE}/?token=${encodeURIComponent(TOKEN)}` : `${BASE}/`;
    await cdp.send('Page.navigate', { url });

    step('已发起导航，等待渲染');
    await sleep(9000);
    step('等待结束，开始采样');

    const observed = await evaluate(cdp, `(() => {
      const de = document.documentElement;
      const q = (s) => document.querySelectorAll(s).length;
      return {
        title: document.title,
        // 适配是否落地：插件自己打的标记（自研层用 data-handheld；vendored 时代是
        // data-mobile-nav —— 换层时这里漏改过一次，于是 harness 对着一个不存在的标记
        // 判「插件没加载」。标记名与 assets/plugins 里的实现同源，改一处就该改这里。）
        handheld: q('[data-handheld]'),
        handheldValues: [...new Set([...document.querySelectorAll('[data-handheld]')]
          .map(e => e.getAttribute('data-handheld')))].slice(0, 12),
        // dsh 原生钩子（适配所依附的）
        phase: q('[data-phase]'),
        composer: q('[data-composer-input], textarea'),
        slot: q('[data-slot]'),
        // 布局：横向溢出是移动端最典型的失配症状
        scrollWidth: de.scrollWidth,
        clientWidth: de.clientWidth,
        overflowX: de.scrollWidth - de.clientWidth,
        bodyChildren: document.body.children.length,
        // 桌面侧栏是否还在占位
        sidebarish: [...document.querySelectorAll('div,nav,aside')]
          .filter(e => { const r = e.getBoundingClientRect();
            return r.width > 200 && r.height > 400 && r.left < 40; }).length,
      };
    })()`);

    step('采样完成，截图');
    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
    const shotPath = path.join(OUT, `${label}.png`);
    writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));

    return { observed, intercepted, shotPath };
  } finally {
    // chrome 可能已退出，Browser.close 会永远等不到响应 —— 加超时兜底
    try { await Promise.race([cdp.send('Browser.close'), sleep(2000)]); } catch { /* ignore */ }
    cleanup();
  }
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`页面内求值失败：${r.exceptionDetails.text}`);
  return r.result.value;
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
const main = async () => {
  mkdirSync(OUT, { recursive: true });

  if (!existsSync(BUNDLE)) throw new Error(`找不到插件 bundle：${BUNDLE}`);
  if (!existsSync(BOOTSTRAP_SRC)) throw new Error(`找不到 bootstrap：${BOOTSTRAP_SRC}`);
  const bundleBytes = readFileSync(BUNDLE);
  // 与 App 用同一份模板 + 同一套占位符替换（App 侧见 MainActivity.mobileBootstrapJs）
  const bootstrap = readFileSync(BOOTSTRAP_SRC, 'utf8')
    .replaceAll('{{ID}}', PLUGIN_ID)
    .replaceAll('{{URL}}', PLUGIN_URL)
    .replaceAll('{{REV}}', PLUGIN_REV);
  const leftover = ['{{ID}}', '{{URL}}', '{{REV}}'].filter((p) => bootstrap.includes(p));
  if (leftover.length) throw new Error(`bootstrap 残留占位符：${leftover.join(', ')}`);

  console.log(`靶子      ${BASE}`);
  console.log(`视口      ${VIEWPORT.width}x${VIEWPORT.height} @${VIEWPORT.dsf}x（mobile=${VIEWPORT.mobile}）`);
  console.log(`bundle    ${bundleBytes.length} 字节`);
  console.log('');

  const baseline = await run({ inject: false, bundleBytes, bootstrap, label: 'a-baseline-desktop' });
  console.log(`[A 基线·不注入]  横向溢出=${baseline.observed.overflowX}  `
    + `handheld 标记=${baseline.observed.handheld}  左侧宽栏=${baseline.observed.sidebarish}`);

  const adapted = await run({ inject: true, bundleBytes, bootstrap, label: 'b-adapted-mobile' });
  console.log(`[B 适配·注入]    横向溢出=${adapted.observed.overflowX}  `
    + `handheld 标记=${adapted.observed.handheld}  左侧宽栏=${adapted.observed.sidebarish}`);
  console.log(`                 插件被拦截并下发 ${adapted.intercepted} 次`);

  console.log('');
  console.log('── dsh 原生钩子（适配所依附的）──');
  for (const k of ['phase', 'composer', 'slot']) {
    const v = adapted.observed[k];
    console.log(`  ${k.padEnd(10)} ${v > 0 ? `✓ ${v} 处` : '✗ 0 处 —— 插件依赖的钩子不见了'}`);
  }

  console.log('');
  console.log('── 适配是否生效 ──');
  const checks = [
    ['插件已加载并打出适配标记', adapted.observed.handheld > 0],
    ['适配标记', adapted.observed.handheldValues.length > 0
      ? adapted.observed.handheldValues.join(', ') : '（无）'],
    ['无横向溢出', adapted.observed.overflowX <= 1],
    ['页面已渲染', adapted.observed.bodyChildren > 0],
  ];
  let failed = 0;
  for (const [name, ok] of checks) {
    const pass = ok === true || (typeof ok === 'string' && ok !== '（无）');
    if (!pass) failed++;
    console.log(`  ${pass ? '✓' : '✗'} ${name}${typeof ok === 'string' ? `: ${ok}` : ''}`);
  }

  writeFileSync(path.join(OUT, 'report.json'), JSON.stringify({
    base: BASE, viewport: VIEWPORT, bundleBytes: bundleBytes.length,
    baseline: baseline.observed, adapted: adapted.observed,
    intercepted: adapted.intercepted,
  }, null, 2));

  console.log('');
  console.log(`截图   ${baseline.shotPath}`);
  console.log(`       ${adapted.shotPath}`);
  console.log(`报告   ${path.join(OUT, 'report.json')}`);
  console.log('');
  console.log(failed === 0 ? '适配验证通过 ✓' : `${failed} 项未通过 ✗`);
  process.exit(failed === 0 ? 0 : 1);
};

main().catch((e) => { console.error(`\n失败：${e.message}`); process.exit(2); });
