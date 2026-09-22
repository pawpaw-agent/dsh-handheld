#!/usr/bin/env node
/**
 * 真机隧道不变量检查 —— 需要一台连着 adb 的设备。
 *
 * ## 为什么需要它
 *
 * 隧道失效的表现是**静默**的：App 说 "connected"，页面却永远加载不完。这类问题在
 * 2026-09-12 的真机上抓到过一整条链（假 connected / dbclient 进程泄漏 / 端口漂移），
 * 修完之后需要能**重复地**验证它们没有回来 —— 而不是每次靠人肉 adb 敲一遍。
 *
 * ## 它断言什么（四条不变量）
 *
 *   1. 隧道**真的能过流量**：往本地转发口发一个 HTTP 请求，拿到任意状态行才算通。
 *      （dsh web 对无令牌请求回 401，这也算通。）
 *   2. 本地端口**没有漂移**：应当是 3080；13080 只允许出现在 3080 被别的程序占用时。
 *      端口一变 WebView 的 origin 就变，localStorage / 会话草稿按 origin 分家。
 *   3. dbclient 进程**只有一个**：重连不回收旧进程会积累幽灵进程，它们占着端口、
 *      占着 SSH 会话，还会让"端口能连"这种假健康检查通过。
 *   4. 日志里**没有** bind 失败（"Address already in use" / "Failed local port forward"），
 *      且最后一次状态是 connected。
 *
 * 用法：
 *   node scripts/device-tunnel-verify.mjs --serial 192.168.0.175:33199
 *   node scripts/device-tunnel-verify.mjs --serial 192.168.0.186:45151 --port 3080
 *
 * 退出码：全部通过 0；任一失败 1；用法/连接问题 2。
 */

import { execFileSync } from 'node:child_process';

const PKG = 'com.dshhandheld.app';
const argv = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};

const SERIAL = opt('--serial', process.env.ADB_SERIAL);
const PRIMARY = Number(opt('--port', '3080'));
const FALLBACK = Number(opt('--fallback-port', '13080'));

if (!SERIAL) {
  console.error('用法: node scripts/device-tunnel-verify.mjs --serial <host:port>');
  console.error('      （设备地址从手机「无线调试」界面读；adb connect 用得了就行）');
  process.exit(2);
}

const raw = (...a) => execFileSync('adb', ['-s', SERIAL, ...a], { encoding: 'utf8' });
const sh = (cmd) => raw('shell', cmd).trim();

try {
  execFileSync('adb', ['start-server'], { stdio: 'ignore' });
  execFileSync('adb', ['connect', SERIAL], { stdio: 'ignore' });
} catch { /* connect 失败会在下面第一次 shell 调用时暴露 */ }

const results = [];
/**
 * ok === null 表示**无法判定**（例如 logcat 缓冲区已经滚过那段日志）——
 * 那既不是通过也不是失败，必须显式区分：把"没证据"当成失败会让这个检查变得
 * 不可信，进而被忽略。
 */
const check = (name, ok, detail = '') => {
  results.push(ok);
  const mark = ok === null ? '–' : (ok ? '✓' : '✗');
  console.log(`  ${mark} ${name}${detail ? `   ${detail}` : ''}`);
};

console.log(`隧道不变量检查 · ${SERIAL}`);
console.log('');

// ── 前置：设备在、App 在 ────────────────────────────────────────────────────
let pid = '';
try {
  pid = sh(`pidof ${PKG}`).split(/\s+/)[0] ?? '';
} catch (e) {
  console.error(`✗ 连不上设备或 shell 不可用：${e.message}`);
  process.exit(2);
}
if (!pid) {
  console.error(`✗ ${PKG} 没在运行 —— 先在这台手机上打开 App 并连上，再跑本检查`);
  process.exit(2);
}

let version = '';
try {
  version = (sh(`dumpsys package ${PKG}`).match(/versionName=(\S+)/) ?? [])[1] ?? '?';
} catch { /* 忽略 */ }
console.log(`  App pid=${pid} version=${version}`);
console.log('');

// ── 1. 真流量 ──────────────────────────────────────────────────────────────
// 这是最核心的一条：本地端口能 connect 并不代表隧道可用。
let httpCode = '';
for (const p of [PRIMARY, FALLBACK]) {
  try {
    const code = sh(`curl -s -m 6 -o /dev/null -w '%{http_code}' http://127.0.0.1:${p}/`);
    if (/^\d{3}$/.test(code) && code !== '000') { httpCode = code; break; }
  } catch { /* 试下一个 */ }
}
check('隧道能过流量（HTTP 拿到状态行）', httpCode !== '',
  httpCode ? `HTTP ${httpCode}` : '两个候选端口都拿不到响应');

// ── 2. 端口没有漂移 ────────────────────────────────────────────────────────
const listening = new Set();
try {
  for (const line of sh('cat /proc/net/tcp /proc/net/tcp6 2>/dev/null').split('\n')) {
    const f = line.trim().split(/\s+/);
    if (f.length < 4 || f[0] === 'sl' || f[3] !== '0A') continue;   // 0A = LISTEN
    const hex = f[1].split(':')[1];
    if (hex) listening.add(parseInt(hex, 16));
  }
} catch { /* 下面会判定为空 */ }
check(`本地转发端口是 ${PRIMARY}（未漂移到 ${FALLBACK}）`,
  listening.has(PRIMARY) && !listening.has(FALLBACK),
  `LISTEN: ${[...listening].sort((a, b) => a - b).join(', ') || '(无)'}`);

// ── 3. dbclient 只有一个 ───────────────────────────────────────────────────
let dbclients = [];
try {
  dbclients = sh('ps -A -o PID,PPID,NAME')
    .split('\n')
    .filter((l) => l.includes('libdbclient.so'))
    .map((l) => l.trim().split(/\s+/)[0]);
} catch { /* 当作没有 */ }
check('dbclient 进程只有 1 个（没有泄漏的幽灵）', dbclients.length === 1,
  dbclients.length ? `pid ${dbclients.join(', ')}` : '一个都没有');

// ── 4. 日志：没有 bind 失败，最后状态是 connected ──────────────────────────
let log = '';
try {
  log = raw('logcat', '-d', `--pid=${pid}`, '-v', 'brief');
} catch { /* 忽略 */ }
const bindFails = (log.match(/Address already in use|Failed local port forward/g) ?? []).length;
check('日志里没有本地转发 bind 失败', bindFails === 0, bindFails ? `${bindFails} 次` : '');

const states = [...log.matchAll(/tunnel state: (\S+)/g)].map((m) => m[1]);
const lastState = states.at(-1);
check('最后一次隧道状态是 connected',
  states.length === 0 ? null : lastState === 'connected',
  states.length === 0
    ? '(日志缓冲区已滚过，无法判定)'
    : `最近状态序列: ${states.slice(-4).join(' → ')}`);

// ── 结论 ───────────────────────────────────────────────────────────────────
const failed = results.filter((ok) => ok === false).length;
const skipped = results.filter((ok) => ok === null).length;
console.log('');
if (failed === 0) {
  console.log(`隧道不变量全部通过 ✓${skipped ? `（${skipped} 项无法判定）` : ''}`);
  process.exit(0);
}
console.log(`${failed}/${results.length} 项不通过 ✗${skipped ? `（另有 ${skipped} 项无法判定）` : ''}`);
console.log('');
console.log('排查提示：');
console.log('  · 真流量拿不到响应 → 看 logcat 里 "dbclient tune #" 与 "failed:" 行');
console.log('  · 端口漂移 / 多个 dbclient → 重连没有回收旧进程');
console.log('  · 状态停在 connecting → 连接或认证卡住（SshTunnel 的 waitForLocal 超时是 15s×3）');
process.exit(1);
