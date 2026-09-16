#!/usr/bin/env node
/**
 * 输入框上方那行统计（`[data-composer-stats]`）在手机宽度下的 A/B 断言。
 *
 * 为什么要这一条：手机端适配的失败模式是**静默**的 —— 宿主的盒子模型一变，我们的规则
 * 可能整条空转，而 CI 全绿、桌面看不出来。这一行尤其容易：宿主给它
 * `width:100% + max-width:--dsh-chat-content-width + 左右各 32px 留白 + 居中`，
 * 而 `--dsh-chat-content-width` 在窄屏恒取下限 680px（clamp 的下限），
 * `--dsh-composer-side-clearance` 又是固定的 16px —— 于是 384px 的手机上只剩 320px，
 * 两个胶囊各被省略号吃掉一截，两侧却还空着 32px。
 *
 * A/B 设计（与 scripts/ui-verify.mjs 同一套思路）：
 *   A 不注入适配层 CSS —— 断言**必须复现截断**（负对照。复现不了说明 fixture 已失真，
 *     这时 B 的「通过」毫无意义）；
 *   B 注入适配层 CSS —— 断言**不截断**且两个胶囊占满可用宽度。
 *
 * 它需要两样东西，缺一就明确报错，不静默跳过：
 *   1. 本机装有 dsh：宿主那两段 CSS 从**安装产物**里抽（不把 dsh 的 CSS 抄进仓库）；
 *   2. chromium（playwright 的 arm64 构建，或 CHROME_BIN）。
 * 沙箱里 Chromium 发不出 HTTP，所以页面是 file:// 的 fixture（真实 class 名 + 真实 CSS）。
 *
 * 用法：
 *   node scripts/plugin-css.mjs /tmp/plugin.css
 *   node scripts/composer-stats-lab.mjs --css /tmp/plugin.css [--widths 360,384,412]
 *
 * 判据来自 0.1.11 真机截图（两种胶囊都被截断：`111···` / `缓存命···`）。
 */
import { spawn, execSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, openSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import path from 'node:path';
import os from 'node:os';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const argv = process.argv.slice(2);
const arg = (n, d) => { const i = argv.indexOf(`--${n}`); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const CSS_FILES = argv.reduce((a, v, i) => (argv[i - 1] === '--css' ? [...a, v] : a), []);
const WIDTHS = arg('widths', '360,384,412').split(',').map((s) => Number(s.trim())).filter(Boolean);
const OUT_DIR = arg('out-dir', path.join(os.tmpdir(), 'composer-stats-lab'));
const CHROME = process.env.CHROME_BIN
  || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux-arm64/chrome');
// 真机上这一行显示的是这次会话的真实数字（0.1.11 截图里的那组）
const PILL_TIME = '9 轮 298 步';
const PILL_TPS = '111 tok/s';
const PILL_USAGE = '62.6M tok';
const PILL_CACHE = '缓存命中 98%';

// ── 定位 dsh 安装产物（与 check-mobile-hooks.mjs 同一套） ────────────────────
function findDshModules() {
  const explicit = process.env.DSH_MODULES || arg('dsh-modules', '');
  if (explicit) {
    if (!existsSync(explicit)) die(`--dsh-modules 不存在：${explicit}`);
    return explicit;
  }
  const looksRight = (dir) => existsSync(path.join(dir, 'dsh-client-ui-chat'));
  try {
    const bin = execSync('command -v dsh', { encoding: 'utf8' }).trim();
    if (bin) {
      let dir = path.dirname(execSync(`readlink -f ${bin}`, { encoding: 'utf8' }).trim());
      for (let i = 0; i < 6 && dir !== '/'; i++) {
        const cand = path.join(dir, 'node_modules/@deepseek-ai');
        if (existsSync(cand) && looksRight(cand)) return cand;
        dir = path.dirname(dir);
      }
    }
  } catch { /* 继续 */ }
  return null;
}

const die = (msg) => { console.error(`\n✗ ${msg}\n`); process.exit(1); };

/** 从宿主产物里抽出那一行的 CSS：`.bOPqQW_*` 与定义变量的 `.wSkVaW_root{...}`。 */
function extractHostCss(modules) {
  const read = (pkg) => {
    const f = path.join(modules, pkg, 'lib/client.js');
    if (!existsSync(f)) die(`${pkg}/lib/client.js 不存在（dsh 布局变了？）`);
    return readFileSync(f, 'utf8');
  };
  const statsSrc = read('dsh-client-ui-chat');
  const m = statsSrc.match(/const css\$\d+ = "([^"]*\.bOPqQW_root\{[^"]*)"/);
  if (!m) die('在 dsh-client-ui-chat 里找不到 .bOPqQW_root 那段 CSS —— 上游改名/搬家了？');
  const stats = m[1];
  const conv = read('dsh-client-ui-conversation');
  const r = conv.match(/const css\$\d+ = "(\.wSkVaW_root\{[^"]*)"/);
  if (!r) die('在 dsh-client-ui-conversation 里找不到 .wSkVaW_root 那段 CSS —— 上游改名/搬家了？');
  const root = r[1].split('.wSkVaW_header')[0];
  if (!root.includes('--dsh-composer-side-clearance') || !root.includes('--dsh-chat-content-width')) {
    die('.wSkVaW_root 里没有那两个变量了 —— 这条规则的前提变了，得重新判');
  }
  return { stats, root };
}

function fixtureHtml(host) {
  return `<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>${host.root}
  :root{--dsw-alias-bg-base:#0A0A0E;--dsw-alias-label-tertiary:#9AA0A6;
    --dsw-alias-label-secondary:#C7CBD1;--dsw-alias-separator-primary:#3A3A40;
    --dsw-alias-interactive-bg-hover:#1A1A1F;--dsw-alias-border-l1:#26262B;--dsw-alias-border-l3:#26262B}
  html,body{margin:0;padding:0;height:100%;background:#0A0A0E;color:#F5F5F7;
    font-family:system-ui,-apple-system,"Noto Sans CJK SC","Source Han Sans SC",sans-serif}
  /* 手机上适配层把外壳压成单列：会话列 = 视口宽（见 dsh-handheld-mobile.js 第 1 节） */
  .wSkVaW_root{width:100%;height:100%;display:flex;flex-direction:column;
    --dsh-conversation-column-width:100vw}
  .spacer{flex:1}
  .composerCard{height:112px;margin:0 var(--dsh-composer-side-clearance) 16px;
    border:1px solid #2A2A30;border-radius:12px;background:#121216}
</style>
<style>${host.stats}</style></head>
<body><div class="wSkVaW_root"><div class="spacer"></div>
<div class="bOPqQW_root" data-composer-stats>
  <span class="bOPqQW_anchor"><button type="button" class="bOPqQW_pill">
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <circle cx="8" cy="8" r="6.4" fill="none" stroke="currentColor" stroke-width="1.2"></circle>
      <path d="M8 4.6v3.9l2.6 1.5" fill="none" stroke="currentColor" stroke-width="1.2"></path></svg>
    <span class="bOPqQW_label">${PILL_TIME}<span class="bOPqQW_sep" aria-hidden="true">·</span>${PILL_TPS}</span>
  </button></span>
  <span class="bOPqQW_anchor"><button type="button" class="bOPqQW_pill">
    <span class="bOPqQW_label">${PILL_USAGE}<span class="bOPqQW_sep" aria-hidden="true">·</span>${PILL_CACHE}</span>
  </button></span>
</div>
<div class="composerCard"></div></div></body></html>`;
}

const PROBE = `(() => {
  const row = document.querySelector('[data-composer-stats]');
  if (!row) return { error: 'no [data-composer-stats]' };
  const rs = getComputedStyle(row);
  const rr = row.getBoundingClientRect();
  const pills = [...row.querySelectorAll('[class*="_pill"]')].map((el) => {
    const r = el.getBoundingClientRect();
    const lab = el.querySelector('[class*="_label"]');
    return {
      text: el.textContent.trim().replace(/\\s+/g, ' '),
      left: Math.round(r.left), right: Math.round(r.right), w: Math.round(r.width),
      truncated: lab ? lab.scrollWidth > lab.clientWidth + 1 : null,
    };
  });
  const contentL = rr.left + parseFloat(rs.paddingLeft);
  const contentR = rr.right - parseFloat(rs.paddingRight);
  const first = pills[0], last = pills[pills.length - 1];
  const avail = contentR - contentL;
  return {
    availW: Math.round(avail),
    spanW: first && last ? Math.round(last.right - first.left) : null,
    freeLeft: first ? Math.round(first.left - contentL) : null,
    freeRight: last ? Math.round(contentR - last.right) : null,
    justify: rs.justifyContent,
    anyTruncated: pills.some((p) => p.truncated === true),
    pills,
  };
})()`;

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

/** 开一个 headless chromium，量一个宽度下的一行。 */
async function measure(width, fixturePath, injectCss) {
  const port = 9700 + Math.floor(Math.random() * 200);
  mkdirSync(OUT_DIR, { recursive: true });
  const profile = path.join(OUT_DIR, `chrome-${port}`);
  const errFd = openSync(path.join(OUT_DIR, 'chrome-stderr.log'), 'w');
  const proc = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    '--allow-file-access-from-files', '--hide-scrollbars',
    `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: ['ignore', 'ignore', errFd] });
  try {
    let target = null;
    for (let i = 0; i < 80 && !target; i++) {
      await sleep(250);
      try {
        const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        target = list.find((t) => t.type === 'page');
      } catch { /* 还没起来 */ }
    }
    if (!target) die('chromium 没起来（CHROME_BIN 对吗？）');
    const cdp = new CDP(new WebSocket(target.webSocketDebuggerUrl));
    await new Promise((res, rej) => {
      cdp.ws.addEventListener('open', res, { once: true });
      cdp.ws.addEventListener('error', () => rej(new Error('CDP 连接失败')), { once: true });
      setTimeout(() => rej(new Error('CDP 超时')), 8000);
    });
    try { await cdp.send('Page.enable', {}, 5000); } catch {}
    try { await cdp.send('Runtime.enable', {}, 5000); } catch {}
    if (injectCss) {
      await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
        source: `(function(){try{document.addEventListener('DOMContentLoaded',function(){
          var s=document.createElement('style');s.textContent=${JSON.stringify(injectCss)};
          document.head.appendChild(s);});}catch(e){}})();`,
      });
    }
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width, height: 832, deviceScaleFactor: 2.625, mobile: true,
    });
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await cdp.send('Page.navigate', { url: `file://${fixturePath}` }, 25000);
    await sleep(900);
    const probe = await cdp.send('Runtime.evaluate', { expression: PROBE, returnByValue: true });
    return probe.result.value;
  } finally {
    proc.kill('SIGKILL');
  }
}

const main = async () => {
  if (!CSS_FILES.length) die('缺 --css：先 node scripts/plugin-css.mjs /tmp/plugin.css，再把它传进来');
  if (!existsSync(CHROME)) die(`找不到 chromium：${CHROME}（或设 CHROME_BIN）`);
  const modules = findDshModules();
  if (!modules) die('找不到 dsh 安装产物（设 DSH_MODULES，或让 `dsh` 在 PATH 上）');
  const host = extractHostCss(modules);
  mkdirSync(OUT_DIR, { recursive: true });
  const fixturePath = path.join(OUT_DIR, 'fixture.html');
  writeFileSync(fixturePath, fixtureHtml(host));
  const injected = CSS_FILES.map((f) => `/* ${path.basename(f)} */\n` + readFileSync(f, 'utf8')).join('\n');

  console.log('统计行 A/B（fixture：真实 class + 从安装产物抽的宿主 CSS）');
  console.log(`  dsh 产物   ${modules}`);
  console.log(`  注入       ${CSS_FILES.map((f) => path.basename(f)).join(', ')}`);
  console.log('');
  console.log('  宽度   A 不注入                  B 注入');
  const fails = [];
  for (const w of WIDTHS) {
    const a = await measure(w, fixturePath, '');
    const b = await measure(w, fixturePath, injected);
    const fmt = (r) => `可用 ${String(r.availW).padStart(3)}px 截断 ${r.anyTruncated ? '是' : '否'}`
      + ` 占满 ${r.spanW === null ? '?' : Math.round((r.spanW / r.availW) * 100)}%`;
    console.log(`  ${String(w).padStart(4)}   ${fmt(a).padEnd(26)} ${fmt(b)}`);
    // 负对照：不加适配层就必须复现截断，否则这个 fixture 证明不了任何事
    if (!a.anyTruncated) fails.push(`${w}px：不注入时也没有截断 —— fixture 失真，B 的结论无效`);
    if (b.anyTruncated) fails.push(`${w}px：注入后仍截断（${b.pills.map((p) => p.text).join(' / ')}）`);
    if (b.freeLeft !== 0 || b.freeRight !== 0) {
      fails.push(`${w}px：注入后两侧仍有留白（左 ${b.freeLeft}px / 右 ${b.freeRight}px）`);
    }
  }
  console.log('');
  if (fails.length) {
    for (const f of fails) console.log(`  ✗ ${f}`);
    die(`${fails.length} 项不通过`);
  }
  console.log('  ✓ A 复现截断、B 不截断且占满可用宽度 —— 适配规则生效');
};

main().catch((e) => die(e.message));