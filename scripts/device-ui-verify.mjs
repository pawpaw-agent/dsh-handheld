#!/usr/bin/env node
/**
 * 在**真机**上验证移动端适配 —— 走 App 真实的注入链路。
 *
 * ## 与 ui-verify.mjs 的分工
 *
 * `ui-verify.mjs` 在桌面 Chromium 里*模拟*App 的注入（自己 addScriptToEvaluateOnNewDocument
 * + Fetch.fulfillRequest）。本脚本则**驱动真机上 App 自己的 WebView**：
 *
 *   - 注入是 App 干的（`addDocumentStartJavaScript` 读 assets 里的 bootstrap）
 *   - 插件是 App 喂的（`shouldInterceptRequest` 返回 assets 里的 bundle）
 *   - 视口是真的手机视口，不是模拟的
 *
 * 它只做一件事是 App 没做的：CDP 的 `Page.navigate` 把 WebView 指到一个可达的地址。
 * 其余全是 App 的真实行为 —— 所以这里通过，等于 App 的适配链路在真机上通过。
 *
 * ## 前置（脚本不会替你做）
 *
 * 1. 装 **debug** 包：只有 debuggable 才开 `setWebContentsDebuggingEnabled`，release 包
 *    拿不到 devtools socket（见 README「移动端界面适配」）。
 * 2. 一个 WebView 能访问到的 dsh 地址。App 自己的路径是 SSH 隧道，但那需要凭据；
 *    验证时用 `scripts/lan-proxy.mjs` 把靶子推到 LAN 更省事：
 *      dsh web --no-open --port 38083
 *      node scripts/lan-proxy.mjs --target 127.0.0.1:38083 --listen 0.0.0.0:38082 --token <token>
 * 3. `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
 *
 * 用法：
 *   node scripts/device-ui-verify.mjs --url http://192.168.0.135:38082/ [--label adapted]
 */

import { setTimeout as sleep } from 'node:timers/promises';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const argv = process.argv.slice(2);
const arg = (n, d) => {
  const i = argv.indexOf(`--${n}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};
const URL_ = arg('url', '');
const LABEL = arg('label', 'adapted');
const PORT = Number(arg('port', '9222'));
const OUT = arg('out', path.join(process.cwd(), 'device-verify-out'));
const SETTLE_MS = Number(arg('settle', '12000'));

if (!URL_) { console.error('用法：--url <设备可达的 dsh 地址>'); process.exit(2); }

// 与 ui-verify.mjs 相同的断言口径，便于横向对照
const PROBE = `(() => {
  const de = document.documentElement;
  const q = (s) => document.querySelectorAll(s).length;
  return {
    href: location.href,
    title: document.title,
    // 插件自建标记：出现即证明插件被加载并跑过适配
    mobileNav: q('[data-handheld]'),
    mobileNavValues: [...new Set([...document.querySelectorAll('[data-handheld]')]
      .map(e => e.getAttribute('data-handheld')))].slice(0, 14),
    // dsh 原生钩子：适配所依附的
    phase: q('[data-phase]'),
    composer: q('[data-composer-input], textarea'),
    slot: q('[data-slot]'),
    shellOverlay: q('[data-shell-overlay]'),
    // 布局症状
    scrollWidth: de.scrollWidth,
    clientWidth: de.clientWidth,
    overflowX: de.scrollWidth - de.clientWidth,
    bodyChildren: document.body.children.length,
    cssVars: getComputedStyle(de).getPropertyValue('--dsw-alias-border-l1').trim() ? 1 : 0,
    ua: navigator.userAgent.slice(0, 90),
  };
})()`;

class CDP {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.handlers = new Map(); this.closed = null;
    ws.addEventListener('message', (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id != null && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id);
        this.pending.delete(m.id);
        m.error ? reject(new Error(`${m.error.message}`)) : resolve(m.result);
      } else if (m.method) for (const h of this.handlers.get(m.method) ?? []) h(m.params);
    });
    const die = (why) => {
      this.closed = why;
      for (const [, { reject }] of this.pending) reject(new Error(`CDP 断开：${why}`));
      this.pending.clear();
    };
    ws.addEventListener('close', () => die('closed'), { once: true });
    ws.addEventListener('error', () => die('error'), { once: true });
  }
  send(method, params = {}, timeoutMs = 30000) {
    if (this.closed) return Promise.reject(new Error(`CDP 已断开：${this.closed}`));
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pending.delete(id)) reject(new Error(`CDP ${method} 超时`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      try { this.ws.send(JSON.stringify({ id, method, params })); }
      catch (e) { this.pending.delete(id); clearTimeout(timer); reject(e); }
    });
  }
  on(m, fn) { if (!this.handlers.has(m)) this.handlers.set(m, []); this.handlers.get(m).push(fn); }
}

const main = async () => {
  mkdirSync(OUT, { recursive: true });
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const page = list.find((t) => t.type === 'page');
  if (!page?.webSocketDebuggerUrl) throw new Error('没找到 WebView page 目标（App 在跑吗？forward 了吗？）');
  console.log(`WebView 目标  ${page.webSocketDebuggerUrl.slice(0, 58)}…`);

  const cdp = new CDP(new WebSocket(page.webSocketDebuggerUrl));
  await new Promise((res, rej) => {
    cdp.ws.addEventListener('open', res, { once: true });
    cdp.ws.addEventListener('error', () => rej(new Error('CDP 连接失败')), { once: true });
    setTimeout(() => rej(new Error('CDP 连接超时')), 8000);
  });

  // ⚠️ 顺序有讲究：Android WebView 的 devtools **只对首条 `Page.navigate` 有响应**；
  // 先发 `Page.enable` / `Runtime.enable` 会一直挂到超时（实测）。所以先导航，
  // enable 类命令放在之后并且允许失败（它们只是让事件更全，缺了不影响断言）。
  console.log(`导航到        ${URL_}`);
  await cdp.send('Page.navigate', { url: URL_ }, 20000);

  for (const m of ['Page.enable', 'Runtime.enable']) {
    try { await cdp.send(m, {}, 4000); } catch { /* WebView 可能不支持，忽略 */ }
  }
  cdp.on('Page.frameNavigated', (p) => console.log(`  帧导航 → ${(p.frame?.url ?? '').slice(0, 60)}`));
  console.log(`等待渲染      ${SETTLE_MS / 1000}s（含 App 的 bootstrap 注入 + 插件加载）`);
  await sleep(SETTLE_MS);

  const r = await cdp.send('Runtime.evaluate', { expression: PROBE, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`页面内求值失败：${r.exceptionDetails.text}`);
  const o = r.result.value;

  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' }, 20000);
  const shotPath = path.join(OUT, `${LABEL}.png`);
  writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));

  console.log('');
  console.log('── 观测结果 ──');
  console.log(`  URL            ${o.href.slice(0, 70)}`);
  console.log(`  标题           ${o.title.slice(0, 50)}`);
  console.log(`  UA             ${o.ua}`);
  console.log(`  视口           内容宽 ${o.clientWidth} / 滚动宽 ${o.scrollWidth}`);
  console.log('');
  console.log('── dsh 原生钩子（适配依附的）──');
  for (const k of ['phase', 'composer', 'slot', 'shellOverlay']) {
    console.log(`  ${k.padEnd(14)} ${o[k] > 0 ? `✓ ${o[k]}` : '✗ 0'}`);
  }
  console.log('');
  console.log('── 适配标记（插件自建）──');
  console.log(`  [data-handheld]    ${o.mobileNav > 0 ? `✓ ${o.mobileNav} 个` : '✗ 0 个 —— 插件没跑'}`);
  if (o.mobileNavValues.length) console.log(`  取值             ${o.mobileNavValues.join(', ')}`);
  console.log('');
  const checks = [
    ['✅', '插件已加载并建立移动导航', o.mobileNav > 0],
    ['✅', '页面已渲染', o.bodyChildren > 0],
    ['✅', '无横向溢出', o.overflowX <= 1],
    ['✅', 'dsh 钩子存在（适配前提）', o.phase > 0 && o.composer > 0],
  ];
  let bad = 0;
  for (const [, name, ok] of checks) { if (!ok) bad++; console.log(`  ${ok ? '✓' : '✗'} ${name}`); }
  writeFileSync(path.join(OUT, `${LABEL}.json`), JSON.stringify(o, null, 2));
  console.log('');
  console.log(`截图  ${shotPath}`);
  console.log(`报告  ${path.join(OUT, `${LABEL}.json`)}`);
  console.log('');
  console.log(bad === 0 ? '真机适配验证通过 ✓' : `${bad} 项未通过 ✗`);
  process.exit(bad === 0 ? 0 : 1);
};

main().catch((e) => { console.error(`\n失败：${e.message}`); process.exit(2); });
