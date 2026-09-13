#!/usr/bin/env node
/**
 * 本地渲染回环：把候选 CSS 应用到一份 fixture 页面上，按手机视口截图。
 * 用途：自研手机适配时快速迭代 CSS —— 不必每改一行就等 CI 构建 + 装机。
 *
 * 用法：node scripts/css-lab.mjs --page file:///tmp/fixture/fixture.html --css /tmp/cand.css --out /tmp/shot.png
 *      [--width 384] [--height 832] [--dsf 2.625]
 *
 * 为什么是 fixture 而不是真页面：这个沙箱里 Chromium 发不出任何 HTTP（Page.navigate
 * 到 http:// 一律超时），file:// 与 data: 可以。fixture 的 class 名取自 dsh 真实产物，
 * 并加载真实的组件 CSS，所以层叠与盒子模型是真的 —— 但**它仍然是近似**，最终判据永远
 * 是真机截图。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, openSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import path from 'node:path';
import os from 'node:os';

const argv = process.argv.slice(2);
const arg = (n, d) => { const i = argv.indexOf(`--${n}`); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const PAGE = arg('page', 'file:///tmp/fixture/fixture.html');
const CSS_FILES = argv.reduce((a, v, i) => (argv[i - 1] === '--css' ? [...a, v] : a), []);
const OUT = arg('out', '/tmp/fixture/shot.png');
const W = Number(arg('width', '384'));
const H = Number(arg('height', '832'));
const DSF = Number(arg('dsf', '2.625'));
const MARK = arg('mark', 'frame');           // 给 frame 加 data-handheld 标记
const CHROME = process.env.CHROME_BIN
  || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux-arm64/chrome');

class CDP {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map();
    ws.addEventListener('message', (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id != null && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id); this.pending.delete(m.id);
        m.error ? reject(new Error(m.error.message)) : resolve(m.result);
      }
    });
    ws.addEventListener('close', () => { for (const [, { reject }] of this.pending) reject(new Error('CDP closed')); this.pending.clear(); });
  }
  send(method, params = {}, timeoutMs = 20000) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { if (this.pending.delete(id)) reject(new Error(`CDP ${method} 超时`)); }, timeoutMs);
      this.pending.set(id, { resolve: (v) => { clearTimeout(t); resolve(v); }, reject: (e) => { clearTimeout(t); reject(e); } });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
}

const main = async () => {
  if (!existsSync(CHROME)) throw new Error(`找不到 chromium：${CHROME}`);
  const port = 9600 + Math.floor(Math.random() * 300);
  const profile = `/tmp/fixture/chrome-${port}`;
  const errFd = openSync('/tmp/fixture/chrome-stderr.log', 'w');
  const proc = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    '--allow-file-access-from-files', '--hide-scrollbars',
    `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: ['ignore', 'ignore', errFd] });

  let target = null;
  for (let i = 0; i < 80 && !target; i++) {
    await sleep(250);
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      target = list.find((t) => t.type === 'page');
    } catch { /* 还没起来 */ }
  }
  if (!target) { proc.kill('SIGKILL'); throw new Error('chromium 没起来'); }

  const cdp = new CDP(new WebSocket(target.webSocketDebuggerUrl));
  await new Promise((res, rej) => {
    cdp.ws.addEventListener('open', res, { once: true });
    cdp.ws.addEventListener('error', () => rej(new Error('CDP 连接失败')), { once: true });
    setTimeout(() => rej(new Error('CDP 超时')), 8000);
  });

  try { await cdp.send('Page.enable', {}, 5000); } catch {}
  try { await cdp.send('Runtime.enable', {}, 5000); } catch {}
  const css = CSS_FILES.map((f) => `/* ${path.basename(f)} */\n` + readFileSync(f, 'utf8')).join('\n');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(function(){
      try {
        document.addEventListener('DOMContentLoaded', function(){
          var f = document.querySelector('[class*="_frame"]');
          if (f && ${JSON.stringify(MARK)} !== '') f.setAttribute('data-handheld', ${JSON.stringify(MARK)});
          if (f && ${JSON.stringify(arg('open', ''))} === '1') f.removeAttribute('data-sidebar-collapsed');
          if (${JSON.stringify(arg('hero', ''))} === '1') { var r = document.querySelector('[data-phase]'); if (r) r.setAttribute('data-phase','hero'); }
          ${css ? `var s = document.createElement('style'); s.textContent = ${JSON.stringify(css)}; document.head.appendChild(s);` : ''}
        });
      } catch (e) {}
    })();`,
  });
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: W, height: H, deviceScaleFactor: DSF, mobile: true,
  });
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  await cdp.send('Page.navigate', { url: PAGE }, 25000);
  await sleep(Number(arg('settle', '1200')));
  const probe = await cdp.send('Runtime.evaluate', {
    expression: `(() => { const de = document.documentElement; const f = document.querySelector('[data-handheld="frame"]');
      const col = document.querySelector('[class*="_sidebarCol"]');
      const cs = col ? getComputedStyle(col) : null;
      const q = (sel) => document.querySelector(sel);
      const disp = (sel) => { const e = q(sel); return e ? getComputedStyle(e).display : null; };
      const op = (sel) => { const e = q(sel); return e ? getComputedStyle(e).opacity : null; };
      return { scrollW: de.scrollWidth, clientW: de.clientWidth, frame: !!f,
               collapsed: f && f.hasAttribute('data-sidebar-collapsed'),
               colPos: cs && cs.position, colW: cs && cs.width,
               colLeft: col ? Math.round(col.getBoundingClientRect().left) : null,
               colRight: col ? Math.round(col.getBoundingClientRect().right) : null,
               backdrop: op('[data-handheld=\"backdrop\"]'),
               fab: disp('[data-handheld=\"fab\"]'),
               count: disp('[class*=\"_count\"]'),
               drawerBtn: disp('[data-handheld=\"toggle\"]'),
               menu: (function(){ var m=q('[class*=\"_menu\"]'); if(!m) return null; var r=m.getBoundingClientRect();
                 return {l:Math.round(r.left), t:Math.round(r.top), r:Math.round(r.right), b:Math.round(r.bottom)}; })(),
               chip: (function(){ var c=q('[class*=\"_trigger\"]'); if(!c) return null; var r=c.getBoundingClientRect();
                 return {l:Math.round(r.left), r:Math.round(r.right), b:Math.round(r.bottom)}; })() }; })()`,
    returnByValue: true,
  });
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' }, 20000);
  mkdirSync(path.dirname(OUT), { recursive: true });
  writeFileSync(OUT, Buffer.from(shot.data, 'base64'));
  console.log('探针', JSON.stringify(probe.result.value));
  console.log('截图', OUT);
  proc.kill('SIGKILL');
  process.exit(0);
};

main().catch((e) => { console.error('失败：', e.message); process.exit(1); });
