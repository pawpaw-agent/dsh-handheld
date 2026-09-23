#!/usr/bin/env node
/**
 * 真机帧性能测量 —— 需要一台连着 adb 的设备。
 *
 * ## 为什么需要它
 *
 * 2026-09-22/23 那轮排查里，最花时间的不是改代码，而是**判断一组数字能不能用**：
 * 同一个包（同一份 APK、同一段内容）重跑 30s 滚动，janky 可以在 3.57% ↔ 7.61% 之间跳。
 * 拿单次采样去归因（「顶栏 sticky 让 1.0.60 掉到 6.51%」）是**站不住的** —— 那个结论
 * 至今没被证实。所以协议的约束必须写进工具里，而不是靠人记得：
 *
 *   1. 每个包**重复 N 次**（默认 3），报**中位数**；
 *   2. 同时报**极差**（max−min），并把「这组数字能不能用来归因」直接印出来；
 *   3. 场景要先把状态摆正 —— App 在前台、屏幕是亮的（设备 2 曾经在 Dozing，采出来的
 *      数字没有任何意义）。
 *
 * ## 它量什么
 *
 *   帧统计：`dumpsys gfxinfo`（janky% / 50-90-95-99th / missed vsync / slow UI thread）
 *   插件自报：日志里的 `页面耗时：{...}`（适配层自己测的每段耗时，如 `panel-sync`）——
 *             这条比帧统计**更适合看单点开销**：帧统计是全页面的总和，看不出是谁花的。
 *
 * 用法：
 *   node scripts/device-perf-verify.mjs --serial 192.168.0.175:40741
 *   node scripts/device-perf-verify.mjs --serial <addr> --scenario idle --seconds 20 --repeats 5
 *   node scripts/device-perf-verify.mjs --serial <addr> --strict    # 方差过大时以非 0 退出
 *
 * 场景：
 *   scroll（默认）—— 视口内匀速上滑，测滚动/流式渲染的整体帧表现；
 *   idle         —— 不发输入，测「什么都不干」时的常驻开销（适配层的定时器与观察者）。
 *
 * 退出码：测量可用 0；测量不可用（没渲染出帧 / App 不在前台 / --strict 下方差过大）1；
 *         用法或连接问题 2。
 */

import { execFileSync } from 'node:child_process';

const PKG = 'com.dshhandheld.app';
const argv = process.argv.slice(2);
const opt = (name, dflt) => {
  const eq = argv.find((a) => a.startsWith(`${name}=`));
  if (eq) return eq.slice(name.length + 1);
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const flag = (name) => argv.includes(name);

const SERIAL = opt('--serial', process.env.ADB_SERIAL);
const SCENARIO = opt('--scenario', 'scroll');
const SECONDS = Number(opt('--seconds', '30'));
const REPEATS = Number(opt('--repeats', '3'));
const STRICT = flag('--strict');

if (!SERIAL) {
  console.error('用法: node scripts/device-perf-verify.mjs --serial <host:port> [--scenario scroll|idle]');
  console.error('      [--seconds 30] [--repeats 3] [--strict]');
  console.error('      （设备地址从手机「无线调试」界面读；adb connect 用得了就行）');
  process.exit(2);
}
if (!['scroll', 'idle'].includes(SCENARIO)) {
  console.error(`未知场景: ${SCENARIO}（只支持 scroll / idle）`);
  process.exit(2);
}

const raw = (...a) => execFileSync('adb', ['-s', SERIAL, ...a], { encoding: 'utf8', maxBuffer: 1 << 26 });
const sh = (cmd) => raw('shell', cmd).trim();
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

try {
  execFileSync('adb', ['start-server'], { stdio: 'ignore' });
  execFileSync('adb', ['connect', SERIAL], { stdio: 'ignore' });
} catch { /* connect 失败会在下面第一次 shell 调用时暴露 */ }

/** 无线调试经常是「连得上但下一秒掉」——先握三次手，别让异常栈淹掉真正的错误。 */
function ensure() {
  for (let i = 0; i < 3; i++) {
    try { raw('shell', 'true'); return true; } catch { sleep(1500); }
  }
  return false;
}
if (!ensure()) {
  console.error(`连不上设备 ${SERIAL} ✗`);
  console.error('  · 无线调试的端口每次开关都会变，回手机「无线调试」界面读当前地址');
  console.error('  · 或者 `adb connect <host:port>` 手动确认一次能不能通');
  process.exit(2);
}

// ── 前置条件：状态不对的话，采出来的数字没有任何意义 ──────────────────────────
function preflight() {
  const problems = [];
  let size = null;

  const wake = sh('dumpsys power | grep -m1 mWakefulness=');
  const awake = /mWakefulness=Awake/.test(wake);
  if (!awake) problems.push(`屏幕不是亮的（${wake || '读不到 mWakefulness'}）—— 息屏/Doze 下 WebView 不渲染`);

  const focus = sh('dumpsys window | grep -m1 mCurrentFocus') || sh('dumpsys window | grep -m1 mFocusedApp');
  const foreground = focus.includes(PKG);
  if (!foreground) problems.push(`App 不在前台（${focus || '读不到焦点窗口'}）—— 请先手动回到 App 的会话页`);

  const wm = sh('wm size');
  const m = wm.match(/(\d+)x(\d+)/);
  if (m) size = { w: Number(m[1]), h: Number(m[2]) };
  else problems.push(`读不到屏幕尺寸（${wm}）`);

  return { problems, size, awake, foreground };
}

// ── gfxinfo 解析 ─────────────────────────────────────────────────────────────
function gfxinfo() {
  const out = raw('shell', 'dumpsys', 'gfxinfo', PKG);
  const num = (re) => {
    const m = out.match(re);
    return m ? Number(m[1]) : null;
  };
  const jankyLine = out.match(/Janky frames:\s*(\d+)\s*\(([\d.]+)%\)/);
  return {
    total: num(/Total frames rendered:\s*(\d+)/),
    janky: jankyLine ? Number(jankyLine[1]) : null,
    jankyPct: jankyLine ? Number(jankyLine[2]) : null,
    p50: num(/50th percentile:\s*(\d+)ms/),
    p90: num(/90th percentile:\s*(\d+)ms/),
    p95: num(/95th percentile:\s*(\d+)ms/),
    p99: num(/99th percentile:\s*(\d+)ms/),
    missed: num(/Number Missed Vsync:\s*(\d+)/),
    slowUi: num(/Number Slow UI thread:\s*(\d+)/),
    slowDraw: num(/Number Slow issue draw commands:\s*(\d+)/),
  };
}

// ── 场景驱动 ────────────────────────────────────────────────────────────────
function driveScroll(size, seconds) {
  const x = Math.round(size.w / 2);
  const y1 = Math.round(size.h * 0.72);
  const y2 = Math.round(size.h * 0.34);
  const deadline = Date.now() + seconds * 1000;
  let swipes = 0;
  while (Date.now() < deadline) {
    try {
      raw('shell', 'input', 'swipe', String(x), String(y1), String(x), String(y2), '180');
    } catch { /* 单次 swipe 失败不中断整轮 */ }
    swipes++;
    sleep(60);
  }
  return { swipes };
}

// ── 插件自报耗时（适配层自己测的每段开销）─────────────────────────────────────
function perfLines() {
  let log = '';
  try {
    log = raw('logcat', '-d', '-s', 'DshApp:V');
  } catch { return []; }
  const rows = [];
  for (const line of log.split('\n')) {
    const i = line.indexOf('页面耗时：');
    if (i < 0) continue;
    const payload = line.slice(i + '页面耗时：'.length).trim();
    // 载荷形如 {"type":"perf","what":"panel-sync","ms":10.9,"n":4}（见插件 postToApp）。
    let obj = null;
    try { obj = JSON.parse(payload); } catch { /* 截断/非 JSON 时退回正则 */ }
    if (obj && typeof obj === 'object') {
      rows.push({
        stage: String(obj.what ?? obj.stage ?? '?'),
        ms: typeof obj.ms === 'number' ? obj.ms : null,
      });
      continue;
    }
    const what = payload.match(/"(?:what|stage)"\s*:\s*"([^"]+)"/);
    const ms = payload.match(/"ms"\s*:\s*([\d.]+)/);
    rows.push({ stage: what ? what[1] : payload.slice(0, 60), ms: ms ? Number(ms[1]) : null });
  }
  return rows;
}

function summarizePerf(rows) {
  const byStage = new Map();
  for (const r of rows) {
    const key = r.stage;
    const cur = byStage.get(key) ?? { n: 0, max: 0, sum: 0 };
    cur.n++;
    if (r.ms !== null) { cur.sum += r.ms; cur.max = Math.max(cur.max, r.ms); }
    byStage.set(key, cur);
  }
  return [...byStage.entries()]
    .map(([stage, v]) => ({ stage, n: v.n, avg: v.n ? v.sum / v.n : null, max: v.max }))
    .sort((a, b) => (b.max ?? 0) - (a.max ?? 0))
    .slice(0, 6);
}

// ── 主流程 ───────────────────────────────────────────────────────────────────
console.log(`设备 ${SERIAL} · 场景 ${SCENARIO} · 每轮 ${SECONDS}s · 重复 ${REPEATS} 次`);
console.log('');

const pre = preflight();
if (pre.problems.length) {
  console.error('前置条件不满足，测量没有意义 ✗');
  for (const p of pre.problems) console.error(`  · ${p}`);
  console.error('');
  console.error('（这些是真实踩过的坑：设备 2 曾在 Dozing 下被采过一轮，数字全是废的。）');
  process.exit(2);
}
console.log(`前置条件 OK ✓ 屏幕 ${pre.size.w}×${pre.size.h}，App 在前台`);
console.log('');

const runs = [];
const perfRows = [];
for (let i = 0; i < REPEATS; i++) {
  sh(`logcat -c`);
  sh(`dumpsys gfxinfo ${PKG} reset`);
  const t0 = Date.now();
  let drive = { swipes: 0 };
  if (SCENARIO === 'scroll') drive = driveScroll(pre.size, SECONDS);
  else sleep(SECONDS * 1000);
  const stats = gfxinfo();
  perfRows.push(...perfLines());
  runs.push({ ...stats, secs: (Date.now() - t0) / 1000, swipes: drive.swipes });
  process.stdout.write(
    `  第 ${i + 1}/${REPEATS} 轮: ${stats.total ?? '?'} 帧, janky ${stats.jankyPct ?? '?'}%, `
    + `99th ${stats.p99 ?? '?'}ms, missed ${stats.missed ?? '?'}\n`,
  );
}

// ── 汇总：中位数 + 极差 ──────────────────────────────────────────────────────
const median = (xs) => {
  const v = xs.filter((x) => x !== null && x !== undefined).sort((a, b) => a - b);
  if (!v.length) return null;
  const mid = v.length >> 1;
  return v.length % 2 ? v[mid] : (v[mid - 1] + v[mid]) / 2;
};
const pick = (key) => runs.map((r) => r[key]);
const spread = (key) => {
  const v = pick(key).filter((x) => x !== null && x !== undefined);
  return v.length ? Math.max(...v) - Math.min(...v) : null;
};

const noFrames = runs.some((r) => !r.total);
console.log('');
console.log('  ── 汇总（中位数，括号内为极差）──');
const rows = [
  ['渲染帧数', 'total', ''],
  ['janky', 'jankyPct', '%'],
  ['50th', 'p50', 'ms'],
  ['90th', 'p90', 'ms'],
  ['95th', 'p95', 'ms'],
  ['99th', 'p99', 'ms'],
  ['missed vsync', 'missed', ''],
  ['slow UI thread', 'slowUi', ''],
];
for (const [label, key, unit] of rows) {
  const m = median(pick(key));
  const s = spread(key);
  console.log(`  ${label.padEnd(16)} ${String(m ?? '?').padStart(8)}${(unit + ' ').padEnd(4)}(极差 ${s ?? '?'}${unit})`);
}

const perfAll = summarizePerf(perfRows);
if (perfAll.length) {
  console.log('');
  console.log('  ── 插件自报耗时（适配层内部，按单次最大值排）──');
  for (const p of perfAll) {
    console.log(`  ${p.stage.padEnd(20)} n=${String(p.n).padStart(3)}  最大 ${p.max}ms  均 ${p.avg === null ? '?' : p.avg.toFixed(1)}ms`);
  }
} else {
  console.log('');
  console.log('  ── 插件自报耗时：没有 `页面耗时` 日志 ──');
  console.log('     （插件只在单次 ≥4ms、且距上次上报 ≥10s 时才写 —— 没有恰恰说明没有慢段落）');
}

// ── 可信度自检：这才是这个脚本存在的主要理由 ─────────────────────────────────
const jankySpread = spread('jankyPct');
const p99Spread = spread('p99');
const untrustworthy = (jankySpread !== null && jankySpread >= 2) || (p99Spread !== null && p99Spread >= 8);

console.log('');
if (noFrames) {
  console.error('测量不可用 ✗ 有轮次一帧都没渲染 —— 页面没在动/被冻结，换场景或确认页面内容 ✗');
  process.exit(1);
}
if (untrustworthy) {
  const msg = `方差过大（janky 极差 ${jankySpread}%，99th 极差 ${p99Spread}ms）`;
  console.log(`⚠️  ${msg} —— 这组数字**不能用来归因**。`);
  console.log('    要比两个包，请各自重复更多次，或者换更长的 --seconds；');
  console.log('    只在两组的**中位数区间不重叠**时才谈得上差异。');
  if (STRICT) process.exit(1);
} else {
  console.log('可信度检查通过 ✓ 轮间极差在可用范围内，中位数可以用于包间对比。');
}
console.log('');
console.log(`结果: janky 中位数 ${median(pick('jankyPct'))}%，99th 中位数 ${median(pick('p99'))}ms（${REPEATS} 轮）`);
process.exit(0);
