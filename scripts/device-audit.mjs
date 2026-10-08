/*
 * 真机审计：把 UI 预算探针跑到**手机上 App 自己的 WebView** 里（不是 fixture）。
 *
 * 与 `ui-verify.mjs` 的分工：那个在桌面 chromium 里用 fixture（真实宿主 CSS，但 DOM 是我们搭的）；
 * 这个连**真实页面**（真实的 DOM、真实的视口、真实的层注入），共用同一套探针与格式化，
 * 所以两边的数字可比。
 *
 * ## 前提（一次）
 *
 * 1. App 里：连接屏右上「诊断」→ 点**开调试口** → 重启 App
 *    （release 包默认关；开关见 MainActivity 的 PREF_WEBVIEW_DEBUG）
 * 2. `adb forward tcp:9222 localabstract:$(adb shell cat /proc/net/unix | grep -o 'webview_devtools_remote[^ ]*' | head -1)`
 *    —— 注意套接字名**带 pid 后缀**，每次重启 App 都会变
 * 3. `node scripts/device-audit.mjs`
 *
 * ## 用法
 *
 *   node scripts/device-audit.mjs                     # 当前相位，打印 UI 预算表
 *   node scripts/device-audit.mjs --phase drawer      # 先切相位再量（hero/drawer/panel/session）
 *   node scripts/device-audit.mjs --js "document.title"   # 任意求值（临时问一句）
 */
import { setTimeout as sleep } from 'node:timers/promises';
import { writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { CDP } from './lib/cdp.mjs';
import { AUDIT_PROBE, RESET_JS, fmtAudit } from './lib/audit-probe.mjs';

const argv = process.argv.slice(2);
const arg = (n, d) => { const i = argv.indexOf(`--${n}`); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const PORT = arg('port', '9222');
const PHASE = arg('phase', '');
const JS = arg('js', '');
const CSS = arg('css', '');   // 现场试一版 CSS（A/B 用，不落盘、不重启）
const OUT = arg('out', path.join(os.homedir(), 'dsh-verify', `device-audit-${Date.now()}`));

const PHASES = {
  // 每个相位就是一段"把界面切过去"的 JS（用 DOM 点击，比 adb tap 稳）
  hero: '1',
  // 相位要**保证**目标状态，不是盲目切换（盲切会让连续几次运行的读数漂移）
  drawer: `(() => {
    const frame = document.querySelector('[data-handheld="frame"]');
    const open = frame && !frame.hasAttribute('data-sidebar-collapsed');
    if (open) return 'already-open';
    const t = [...document.querySelectorAll('button')].find(b => /侧边栏|目录/.test(b.getAttribute('aria-label') || ''));
    if (t) { t.click(); return 'opened'; }
    const fab = document.querySelector('[data-handheld="fab"]');
    if (fab) { fab.click(); return 'opened-via-fab'; }
    return 'no-toggle';
  })()`,
  panel: `(() => {
    const opening = !!document.querySelector('button[aria-label*="打开右侧边栏"]');
    const t = [...document.querySelectorAll('button')].find(b =>
      /右侧边栏/.test(b.getAttribute('aria-label') || ''));
    if (!t) return 'no-toggle';
    if (opening) { t.click(); return 'opened'; }
    return 'already-open';
  })()`,
  // 会话中：先开抽屉，再点第一条会话（hero 页没有会话头，必须走抽屉）
  conversation: `(() => {
    const frame = document.querySelector('[data-handheld="frame"]');
    if (frame && frame.hasAttribute('data-sidebar-collapsed')) {
      const t = [...document.querySelectorAll('button')].find(b => /侧边栏|目录/.test(b.getAttribute('aria-label') || ''));
      if (t) t.click();
      else { const fab = document.querySelector('[data-handheld="fab"]'); if (fab) fab.click(); }
    }
    return 'drawer-opened';
  })()`,
  session: `(() => {
    const rows = [...document.querySelectorAll('[class*="_sessionRow"]')];
    const row = rows.find(r => r.getBoundingClientRect().width > 0) || rows[0];
    if (row) { row.click(); return 'clicked-row'; }
    return 'no-session-row';
  })()`,
};

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`页面内求值失败：${r.exceptionDetails.text}`);
  return r.result.value;
}

const main = async () => {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const page = list.find((t) => t.type === 'page');
  if (!page?.webSocketDebuggerUrl) throw new Error(`在 127.0.0.1:${PORT} 上没找到可调试页面 —— 调试口开了吗？forward 还在吗？`);
  const cdp = await CDP.connect(page.webSocketDebuggerUrl);
  await cdp.send('Runtime.enable');
  console.log(`靶子  ${page.title}  ${page.url}`);
  console.log('');

  if (JS) { console.log(JSON.stringify(await evaluate(cdp, JS), null, 2)); return; }
  if (CSS) {
    // 现场注入一版样式（改的是运行中的页面，不落盘）—— 用来在真机上 A/B 候选值
    await evaluate(cdp, `(function (css) {
      var s = document.getElementById('__try_css');
      if (!s) { s = document.createElement('style'); s.id = '__try_css'; document.head.appendChild(s); }
      s.textContent = css;
      return 'ok:' + css.length;
    })(${JSON.stringify(CSS)})`);
    await sleep(300);
    console.log('试装 CSS:', CSS.slice(0, 80) + (CSS.length > 80 ? '…' : ''));
    console.log('');
  }
  // 测量前归一化状态（关面板/关抽屉/滚到顶/清试装样式）—— 否则上一次的状态会污染读数
  console.log('归一化 →', await evaluate(cdp, RESET_JS));
  await sleep(500);
  if (PHASE) { console.log(`切相位 ${PHASE} →`, await evaluate(cdp, PHASES[PHASE])); await sleep(700); }
  const data = await evaluate(cdp, AUDIT_PROBE);
  console.log(fmtAudit({ width: data.vw, data, openDrawer: PHASE === 'drawer' }));
  mkdirSync(OUT, { recursive: true });
  writeFileSync(path.join(OUT, `audit${PHASE ? '-' + PHASE : ''}.json`), JSON.stringify(data, null, 2));
  console.log('');
  console.log(`读数  ${path.join(OUT, `audit${PHASE ? '-' + PHASE : ''}.json`)}`);
  try { await Promise.race([cdp.send('Browser.close'), sleep(500)]); } catch { /* WebView 不受 Browser.close 管 */ }
};

main().catch((e) => { console.error(`\n失败：${e.message}`); process.exit(1); });
