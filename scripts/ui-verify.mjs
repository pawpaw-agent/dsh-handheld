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
  'boot.js',        // ⓪ 引导队列：document-start 只定义，呈现型工作等 DOM（必须最先）
  'bootstrap.js',   // ① 解析期补 viewport-fit=cover
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


// ── 类名映射：层的每个类名后缀 → 产物里的真实类名 ────────────────────────────
//
// 层用 `[class*="_suffix"]` 匹配，所以 fixture 只要有**同后缀**的类名就能被层命中；
// 但只有**真实类名**才能让宿主那 265 KB CSS 也一起作用上去 —— 那样量出来的几何才可信
// （我自己脚手架里的尺寸不是真机事实）。所以这里在运行时从 dsh 产物里现算映射，
// 不把 dsh 的类名写进仓库（与「不 vendor 宿主 CSS」同一约定）。
// 动态拼接、产物里配不出前缀的后缀（如 `_sidebarCol`）退化为 `fixture<suffix>`：
// 层照样命中，只是宿主那条基础规则不参与（这些元素本来就是层的覆盖重点）。
function buildClassMap(modules, suffixes) {
  let all = '';
  for (const p of readdirSync(modules)) {
    if (!/^dsh-(client-ui|web)/.test(p)) continue;
    const f = path.join(modules, p, 'lib/client.js');
    if (existsSync(f)) all += readFileSync(f, 'utf8') + '\n';
  }
  const map = {};
  const resolved = [];
  for (const suf of suffixes) {
    const re = new RegExp('([A-Za-z0-9]{4,10})' + suf + '(?=[^A-Za-z0-9_])', 'g');
    const cnt = {};
    for (const m of all.matchAll(re)) cnt[m[1]] = (cnt[m[1]] || 0) + 1;
    const top = Object.entries(cnt).sort((a, b) => b[1] - a[1])[0];
    if (top) { map[suf] = top[0] + suf; resolved.push(suf); }
    else map[suf] = 'fixture' + suf;
  }
  return { map, resolved };
}

/** 层用到的类名后缀（从 styles.css 里现读，层加一条规则这里自动跟上）。 */
function layerSuffixes() {
  const css = readFileSync(path.join(HANDHELD, 'styles.css'), 'utf8');
  const fromLayer = [...css.matchAll(/\[class\*=["'](_[A-Za-z0-9_-]+)["']\]/g)].map((m) => m[1]);
  // 另外几个后缀层没直接引用，但**宿主真实形状**需要它们（统计胶囊那条是
  // `_anchor > button._pill > span._label`，旧实验脚本里记着），否则形状不对、
  // 宿主 CSS 不作用，量出来的尺寸就是我们自己的。
  const hooks = readFileSync(path.join(HANDHELD, 'hooks.js'), 'utf8');
  const fromHooks = [...hooks.matchAll(/class\*=["'](_[A-Za-z0-9_-]+)["']/g)].map((m) => m[1]);
  const forShape = ['_anchor', '_pill', '_label', '_root', '_panel', '_panelBody'];
  return [...new Set([...fromLayer, ...fromHooks, ...forShape])].sort();
}

// ── fixture：真实 class 名 + 真实宿主 CSS + 适配层钩子需要的**真实组件形状** ──
//
// 为什么要"形状"而不是"最小嵌套"：UI 预算（竖向/横向/命中区）只有在宿主 CSS 真正作用
// 到对应结构上时才有意义。这里按层的选择器要求搭出：外壳 → 侧栏抽屉（品牌/工作区/会话行）
// → 会话头（data-phase + header + 标题行 + 头部按钮 + 页签）→ 消息区 → 输入区（统计行 + 卡片）
// → 右侧栏全屏面板 → 设置对话框（role=dialog aria-modal）。
function buildFixture(hostCss, C) {
  const cl = (suf) => C[suf] || ('fixture' + suf);
  return `<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>${hostCss}</style>
<style>
  /* fixture 自己的脚手架：只保证"结构存在、层级对、有内容可量"。
     尺寸尽量**交给宿主 CSS**（那样量出来才是真机量级）；宿主没有规则的（多为动态类名）
     才在这里给个能用的兜底。 */
  html,body{margin:0;padding:0;height:100%;background:#0A0A0E;color:#F5F5F7;
    font-family:system-ui,-apple-system,"Noto Sans CJK SC",sans-serif}
  #frame{display:flex;height:100%;width:100%}
  .${cl('_sidebarCol')}{width:56px;flex:0 0 56px;background:#141418;overflow:hidden}
  #main{flex:1;min-width:0;display:flex;flex-direction:column;min-height:0}
  #msgArea{flex:1;min-height:0;overflow:auto;padding:12px}
  .fixture_card{background:#121216;border:1px solid #26262B;border-radius:12px;
    padding:12px;margin:0 0 12px;font-size:14px;line-height:21px}
  .fixture_code{background:#0E0E12;border:1px solid #26262B;border-radius:8px;
    padding:10px;margin:0 0 12px;font-size:12px;line-height:18px;overflow:auto;white-space:pre}
  .fixture_composerCard{background:#121216;border:1px solid #2A2A30;border-radius:12px;padding:10px}
  textarea{width:100%;height:72px;box-sizing:border-box;background:transparent;color:#F5F5F7;
    border:0;outline:0;font:inherit;resize:none}
  /* ⚠️ 这些类名宿主 CSS 里有规则 —— **不在这里覆盖**，否则量出来的尺寸是我们的而不是宿主的。
     只在宿主没有规则的地方兜底（动态类名，映射为 fixture 前缀的那些）。 */
  .fixture_rowActions{margin-left:auto;display:none}
  .fixture_newSession{margin:8px;height:40px;width:calc(100% - 16px);border:0;border-radius:10px;
    background:#1A1A1F;color:#C7CBD1}
  /* 右侧栏：真机手机档是**全屏**的（层的修复据此判「已展开」），属性值与宿主一致 */
  [data-sidebar-right-panel]{position:fixed;top:0;left:0;width:100%;height:100%;background:#101014;z-index:5}
  [data-sidebar-right-panel] .${cl('_headerActions')}{position:absolute;top:40px;left:0;right:0;
    height:28px;display:flex;gap:8px;align-items:center;padding:0 10px}
  /* 盖住那条带子的同级遮挡 —— V6 要断言它被让开 */
  .fixture_obstruction{position:fixed;top:0;left:0;width:100%;height:68px;
    background:rgba(255,0,0,.18);z-index:9}
  /* 设置对话框：**逻辑上住在侧栏里**（层那条 :has([role=dialog][aria-modal]) 规则要它） */
  .fixture_dialog{position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:8;display:none}
</style></head>
<body>
  <div class="${cl('_frame')}" id="frame" data-sidebar-collapsed>
    <aside class="${cl('_sidebarCol')}" aria-label="侧栏">
      <div class="${cl('_logoRow')}">dsh</div>
      <button class="fixture_newSession">新建会话</button>
      <div class="${cl('_projectRow')}">
        <span class="${cl('_title')}">工作区 A 的名字很长很长</span>
        <span class="${cl('_chevron')}" role="button" tabindex="0" aria-label="展开">›</span>
        <span class="fixture_rowActions">
          <button class="${cl('_iconButton')}" aria-label="更多">⋯</button>
        </span>
      </div>
      <div class="${cl('_sessionRow')}">
        <span class="${cl('_title')}">会话标题一</span><span class="${cl('_time')}">12:30</span>
        <span class="fixture_rowActions"><button class="${cl('_iconButton')}" aria-label="更多">⋯</button></span>
      </div>
      <div class="${cl('_sessionRow')}">
        <span class="${cl('_title')}">会话标题二（很长很长很长很长）</span><span class="${cl('_time')}">昨天</span>
        <span class="fixture_rowActions"><button class="${cl('_iconButton')}" aria-label="更多">⋯</button></span>
      </div>
      <div role="dialog" aria-modal="true" class="fixture_dialog"></div>
    </aside>
    <div class="${cl('_root')}" id="main" data-phase="active">
      <header>
        <div class="${cl('_titleRow')}">
          <span class="${cl('_title')}">会话标题</span>
          <div class="${cl('_headerActions')}">
            <button class="${cl('_iconButton')}" aria-label="搜索">🔍</button>
            <button class="${cl('_toggle')}" aria-label="目录">▤</button>
          </div>
        </div>
        <div class="${cl('_tabs')}">
          <button class="${cl('_tab')}">对话</button>
          <button class="${cl('_tab')}">轨迹</button>
          <button class="${cl('_tabClose')}" aria-label="关闭页签">✕</button>
        </div>
      </header>
      <div class="${cl('_scroll')} ${cl('_scrollBody')}" id="msgArea">
        <article class="fixture_card">这是一条消息，用来量正文的可用宽度与左右留白。</article>
        <pre class="fixture_code">const x = 1;  // 一段代码块，用来量横向是否溢出</pre>
        <article class="fixture_card">第二条消息。</article>
      </div>
      <div class="${cl('_composerSeat')}">
        <div class="${cl('_dock')}">
          <div data-composer-stats>
            <span class="${cl('_anchor')}"><button type="button" class="${cl('_pill')}">
              <span class="${cl('_label')}">101 轮 314 步<span class="${cl('_sep')}">·</span>179 tok/s</span>
            </button></span>
            <span class="${cl('_anchor')}"><button type="button" class="${cl('_pill')}">
              <span class="${cl('_label')}">77.4M tok<span class="${cl('_sep')}">·</span>缓存命中 98%</span>
            </button></span>
          </div>
          <div class="fixture_composerCard"><textarea data-composer-input placeholder="输入…"></textarea></div>
        </div>
      </div>
    </div>
  </div>
  <div data-sidebar-right-panel="fullscreen" data-sidebar-right-open>
    <div class="${cl('_headerActions')}">
      <button class="${cl('_iconButton')}" aria-label="面板工具一">✕</button>
      <button class="${cl('_iconButton')}" aria-label="面板工具二">⤢</button>
      <button class="${cl('_iconButton')}" aria-label="面板工具三">⋯</button>
    </div>
  </div>
  <div class="fixture_obstruction"></div>
</body></html>`;
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`页面内求值失败：${r.exceptionDetails.text}`);
  return r.result.value;
}

/** 一次运行：可选注入、可指定视口档。 */
async function run({ inject, viewport, label, scripts, pageUrl, probeExpr, before }) {
  const { wsUrl, cleanup } = await launch();
  const cdp = await CDP.connect(wsUrl);
  try {
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    // 层的信标走 console.warn（App 的 onConsoleMessage 只收 WARNING+）；未捕获异常
    // 必须为 0 —— 2026-10-08 那次整层静默失效就是一条被吞掉的 TypeError。
    const consoleMsgs = [];
    const exceptions = [];
    cdp.on('Runtime.consoleAPICalled', (p) => {
      consoleMsgs.push({
        level: p.type,
        text: (p.args || []).map((a) => a.value ?? a.description ?? '').join(' '),
      });
    });
    cdp.on('Runtime.exceptionThrown', (p) => {
      const d = p.exceptionDetails || {};
      exceptions.push(String((d.exception && d.exception.description) || d.text || '').split('\n')[0]);
    });
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

    if (before) { await evaluate(cdp, before); await sleep(450); }

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
        fab: (() => { const f = document.querySelector('[data-handheld="fab"]');
          return f ? { exists: true, display: getComputedStyle(f).display } : { exists: false, display: null }; })(),
        fixesLen: (window.__dshHandheldFixes || []).length,
        statusRaw: de.getAttribute('data-handheld-status'),
        bootWhen: window.__dshHandheldBootState ? window.__dshHandheldBootState.when : null,
        bootErrors: window.__dshHandheldBootState ? window.__dshHandheldBootState.errors.length : null,
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

    // --audit 用：同一趟里多取一份预算读数（不额外启动浏览器）
    const auditData = probeExpr
      ? await evaluate(cdp, probeExpr)
      : null;

    // hero 相位下的浮动入口显隐（CSS 第 6 节的契约：hero/inert 才显示）
    const heroFab = inject ? await evaluate(cdp, `(() => {
      const f = document.querySelector('[data-handheld="fab"]');
      const host = document.querySelector('[data-phase]');
      if (!f || !host) return { ok: false, why: !f ? '没有 fab' : '没有 [data-phase]' };
      const before = host.getAttribute('data-phase');
      host.setAttribute('data-phase', 'hero');
      const inHero = getComputedStyle(f).display;
      const drawer = document.querySelector('[data-sidebar-right-panel]');
      host.setAttribute('data-phase', before);
      return { ok: true, inHero: inHero, inActive: getComputedStyle(f).display };
    })()`) : null;

    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
    const shotPath = path.join(OUT, `${label}.png`);
    writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));
    return { observed, scroll, panel, heroFab, shotPath, consoleMsgs, exceptions, auditData };
  } finally {
    try { await Promise.race([cdp.send('Browser.close'), sleep(2000)]); } catch { /* ignore */ }
    cleanup();
  }
}

// ── UI 预算（--audit）：只读读数，不做断言 ───────────────────────────────────
//
// 数字怎么读：**宿主 CSS 决定的量**（标题行、统计行、正文留白…）是真机量级；
// fixture 脚手架自己给的尺寸（消息卡高度之类）不是。所以这张表主要用于
// ① 前后对比（delta 永远可信）② 找"明显不对"的项（如命中区 < 44px、文本被截断）。
const AUDIT_PROBE = `(() => {
  const R = (el) => { const r = el.getBoundingClientRect();
    return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }; };
  const q = (sel) => document.querySelector(sel);
  const vw = window.innerWidth, vh = window.innerHeight;
  const header = q('header'), msg = q('#msgArea');
  const seat = q('[class*="_composerSeat"]'), card = q('.fixture_card');
  const code = q('.fixture_code'), cc = q('.fixture_composerCard');
  const innerW = (el) => { if (!el) return null; const cs = getComputedStyle(el);
    return Math.round(el.getBoundingClientRect().width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)); };
  const S = '[class*="_"]';
  // 有效命中区 = 元素盒子 ∪ 它的 ::after/::before（层就是用 ::after 扩命中区的，
  // 只看盒子会**低估**）。伪元素测不了，但它的 computed width/height/left/top 可读。
  const effBox = (el) => {
    const r = el.getBoundingClientRect();
    let x1 = r.left, y1 = r.top, x2 = r.right, y2 = r.bottom;
    for (const pe of ['::after', '::before']) {
      const cs = getComputedStyle(el, pe);
      if (!cs || cs.content === 'none' || cs.display === 'none') continue;
      const w = parseFloat(cs.width), h = parseFloat(cs.height);
      if (!w || !h) continue;
      const l = parseFloat(cs.left), t = parseFloat(cs.top);
      const px = r.left + (isNaN(l) ? 0 : l), py = r.top + (isNaN(t) ? 0 : t);
      x1 = Math.min(x1, px); y1 = Math.min(y1, py);
      x2 = Math.max(x2, px + w); y2 = Math.max(y2, py + h);
    }
    // ∩ 视口：屏外的东西不可点（收起态抽屉就在屏外）
    x1 = Math.max(x1, 0); y1 = Math.max(y1, 0);
    x2 = Math.min(x2, window.innerWidth); y2 = Math.min(y2, window.innerHeight);
    const w = Math.max(0, Math.round(x2 - x1)), h = Math.max(0, Math.round(y2 - y1));
    return { w: w, h: h, bx: Math.round(x1), by: Math.round(y1),
      bx2: Math.round(x1 + w), by2: Math.round(y1 + h) };
  };
  const interactive = [...document.querySelectorAll('button,a[href],[role="button"],[role="tab"],[tabindex]:not([tabindex="-1"])')]
    .filter((e) => { const cs = getComputedStyle(e); const r = e.getBoundingClientRect();
      if (cs.display === 'none' || cs.visibility === 'hidden' || r.width <= 0 || r.height <= 0) return false;
      // 完全在屏幕外的不算可点（收起态抽屉里的行就是这种）
      return r.right > 0 && r.bottom > 0 && r.left < window.innerWidth && r.top < window.innerHeight; })
    .map((e) => { const r = e.getBoundingClientRect(); const ef = effBox(e);
      return { el: e.tagName + '.' + String(e.className || '').slice(0, 22),
        w: Math.round(r.width), h: Math.round(r.height),
        vx: Math.round(r.left), vy: Math.round(r.top),
        ew: ef.w, eh: ef.h,
        bx: ef.bx, by: ef.by, bx2: ef.bx2, by2: ef.by2,
        label: (e.getAttribute('aria-label') || e.textContent || '').trim().slice(0, 14) }; });
  const small = interactive.filter((i) => i.ew < 44 || i.eh < 44);
  // 有效盒子（含伪元素扩展）留给 Node 侧做重叠/越界判定 —— 扩命中区不能变成制造误触
  const boxes = interactive.map((i, k) => ({ k: k, el: i.el, label: i.label,
    x: i.bx, y: i.by, w: i.ew, h: i.eh,
    vx: i.vx, vy: i.vy, vw: i.w, vh: i.h }));
  const truncated = [...document.querySelectorAll('*')]
    .filter((e) => e.children.length === 0 && e.clientWidth > 0 && e.scrollWidth > e.clientWidth + 1)
    .slice(0, 12)
    .map((e) => ({ el: e.tagName + '.' + String(e.className || '').slice(0, 20),
      sl: e.scrollWidth, cl: e.clientWidth, text: (e.textContent || '').trim().slice(0, 18) }));
  return {
    vw: vw, vh: vh,
    header: header ? R(header) : null,
    tabsShown: !!document.querySelector('[class*="_tabs"]')
      && getComputedStyle(document.querySelector('[class*="_tabs"]')).display !== 'none',
    msg: msg ? R(msg) : null, msgInnerW: innerW(msg),
    composer: seat ? R(seat) : null,
    textCardW: card ? R(card).w : null, textGapL: card ? R(card).x : null,
    codeW: code ? R(code).w : null, composerCardW: cc ? R(cc).w : null,
    statsH: (() => { const s = document.querySelector('[data-composer-stats]'); return s ? R(s).h : null; })(),
    taps: { total: interactive.length, small: small, boxes: boxes },
    truncated: truncated,
  };
})()`;

async function audit(width, { scripts, pageUrl, label, openDrawer }) {
  const viewport = { width, height: width <= 400 ? 800 : 915, dsf: 3, mobile: true };
  // 抽屉展开态：层读 data-sidebar-collapsed 决定抽屉位置（CSS 里有 .22s 过渡，等一下）
  const before = openDrawer
    ? "(() => { const f = document.getElementById('frame'); if (f) f.removeAttribute('data-sidebar-collapsed'); })()"
    : null;
  const r = await run({ inject: true, viewport, label, scripts, pageUrl, probeExpr: AUDIT_PROBE, before });
  return { width, viewport, data: r.auditData, openDrawer: !!openDrawer };
}

function fmtAudit(res) {
  const d = res.data;
  const lines = [];
  lines.push(`视口 ${d.vw}x${d.vh}（${res.width} CSS px 档${res.openDrawer ? '，**抽屉展开**' : '，抽屉收起'}）`);
  const chromeH = (d.header ? d.header.h : 0) + (d.composer ? d.composer.h : 0);
  lines.push(`  竖向  头部 ${d.header ? d.header.h : '?'} + 消息区 ${d.msg ? d.msg.h : '?'}`
    + ` + 输入区 ${d.composer ? d.composer.h : '?'} = ${chromeH + (d.msg ? d.msg.h : 0)}`
    + `（固定占 ${(100 * chromeH / d.vh).toFixed(1)}%）`);
  lines.push(`         头部明细：页签${d.tabsShown ? '显示' : '**被层隐藏**'}；统计行 ${d.statsH}px`);
  lines.push(`  横向  视口 ${d.vw} → 正文卡 ${d.textCardW}（左边距 ${d.textGapL}）`
    + ` ｜ 代码块 ${d.codeW} ｜ 输入卡 ${d.composerCardW} ｜ 消息区内容宽 ${d.msgInnerW}`);
  lines.push(`  命中区 可点 ${d.taps.total} 个，其中**有效命中区 <44px 的 ${d.taps.small.length} 个**`
    + `（视觉 → 有效）`);
  for (const t of d.taps.small.slice(0, 10)) {
    lines.push(`           ${String(t.w + 'x' + t.h).padEnd(9)} → ${String(t.ew + 'x' + t.eh).padEnd(9)}`
      + ` ${t.el.padEnd(24)} ${t.label}`);
  }
  // 负对照：**扩展出来的命中区盖住了邻居的可见盒子** = 真误触。
  // （两个命中区在控件之间的空隙里相接**不算问题** —— 那只是把死区变成可点。）
  const hits = (A, b) => !(A.x + A.w <= b.x || b.x + b.w <= A.x || A.y + A.h <= b.y || b.y + b.h <= A.y);
  const stolen = [];
  const B = d.taps.boxes || [];
  for (let i = 0; i < B.length; i++) {
    for (let j = 0; j < B.length; j++) {
      if (i === j) continue;
      const a = B[i], b = B[j];
      // a 的扩展区是否咬到 b 自己的可见盒子（b 是 a 的祖先/后代则不算）
      if (a.x <= b.x && a.y <= b.y && a.x + a.w >= b.vx + b.vw && a.y + a.h >= b.vy + b.vh) continue;
      if (b.x <= a.x && b.y <= a.y && b.x + b.w >= a.vx + a.vw && b.y + b.h >= a.vy + a.vh) continue;
      if (hits(a, { x: b.vx, y: b.vy, w: b.vw, h: b.vh })) {
        stolen.push(`${a.el}(${a.label}) 咬到 ${b.el}(${b.label}) 的可见区`);
      }
    }
  }
  const overlap = stolen;
  // 负对照②：有效区跑到视口外（顶部/两侧超界）
  const oob = B.filter((b) => b.x < 0 || b.y < 0 || b.x + b.w > d.vw + 1 || b.y + b.h > d.vh + 1)
    .map((b) => `${b.el}(${b.label}) ${b.x},${b.y} ${b.w}x${b.h}`);
  lines.push(`  负对照 **扩展区咬到邻居可见区 ${overlap.length} 处**${overlap.length ? '：' + overlap.slice(0, 3).join(' ｜ ') : ''}`
    + `；**越界 ${oob.length} 个**${oob.length ? '：' + oob.slice(0, 3).join(' ｜ ') : ''}`);
  lines.push(`  截断  ${d.truncated.length} 处`);
  for (const t of d.truncated.slice(0, 6)) {
    lines.push(`           ${t.el.padEnd(26)} ${t.sl}>${t.cl}  「${t.text}」`);
  }
  return lines.join('\n');
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
  // 层用到的类名后缀 → 产物里的真实类名（量出真机量级的前提）
  const cls = buildClassMap(modules, layerSuffixes());
  const fixturePath = path.join(OUT, 'fixture.html');
  writeFileSync(fixturePath, buildFixture(host.css, cls.map));
  const pageUrl = `file://${fixturePath}`;
  const scripts = readInjection();
  const bytes = scripts.reduce((n, s) => n + Buffer.byteLength(s), 0);

  console.log(`浏览器   ${CHROME}`);
  console.log(`宿主 CSS ${host.pkgs} 个包 / ${host.chunks} 段 / ${(host.css.length / 1024).toFixed(1)} KB（从 dsh 安装产物抽）`);
  console.log(`类名映射 ${cls.resolved.length}/${Object.keys(cls.map).length} 个后缀解析到真实类名`
    + `（其余为动态拼接，用 fixture 前缀 —— 层按后缀匹配仍命中）`);
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
  // 失败负对照：喂一条**必定抛异常**的修复（runner 会隔离它）。信标必须写 FAILED，
  // 且其余修复照常应用 —— 否则"静默失效"这个问题并没有真正被解决。
  const boom = 'window.__dshHandheldFixes.push({ id: "harness-boom", why: "负对照", needs: [],'
    + ' body: function () { throw new Error("harness-boom"); } });';
  const boomScripts = scripts.slice(0, -1).concat([boom, scripts[scripts.length - 1]]);
  const failure = await run({ inject: true, viewport: MOBILE, label: 'd-failure', scripts: boomScripts, pageUrl });
  console.log('[D 失败负对照]  完成（V11：信标必须 FAILED，其余修复仍应用）');
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
  add('V8', '桌面档下整层不生效（信标写 skipped）',
    d.frameTagged === 0 && d.styleTags === 0 && !d.vhVar
      && (() => { try { return JSON.parse(d.statusRaw || '{}').skipped === 'non-mobile'; } catch { return false; } })(),
    `frame=${d.frameTagged} styleTags=${d.styleTags} vh=${d.vhVar} status=${d.statusRaw}`);

  // ── 信标（接缝 3）：把静默失效变成诊断页上的一行 ──
  const st = (() => { try { return JSON.parse(o.statusRaw || '{}'); } catch { return {}; } })();
  const warnLine = adapted.consoleMsgs.find((m) => m.level === 'warning' && m.text.includes('[handheld]'));
  add('V9', '注入期间零未捕获异常（今天那次事故的直接判据）',
    adapted.exceptions.length === 0 && (o.bootErrors === 0),
    `未捕获异常=${adapted.exceptions.length}${adapted.exceptions.length ? '：' + adapted.exceptions[0].slice(0, 90) : ''}；boot 任务异常=${o.bootErrors}`);
  add('V10', '信标合法（<html> 属性 + 能穿过 App 的 WARNING 门槛）',
    st.ok === true && st.styles === 'ok' && st.fixes === `${o.fixesLen}/${o.fixesLen}` && !!warnLine,
    `status=${o.statusRaw}；console.warn=${warnLine ? '✓ ' + warnLine.text.slice(0, 60) : '✗ 没有'}`);
  add('V12', 'hero/inert 相位的目录浮动入口（新会话页唯一的会话列表入口）',
    o.fab?.exists === true && adapted.heroFab?.ok === true
      && adapted.heroFab.inHero !== 'none' && adapted.heroFab.inActive === 'none',
    `fab=${o.fab?.exists ? '在' : '不在'}；active 相位 display=${adapted.heroFab?.inActive}`
      + `；hero 相位 display=${adapted.heroFab?.inHero}`);
  add('V11', '失败负对照：一条修复抛错 → 信标 FAILED 且其余仍应用',
    (() => {
      const fs2 = (() => { try { return JSON.parse(failure.observed.statusRaw || '{}'); } catch { return {}; } })();
      return fs2.ok === false && String(fs2.stage || '').includes('harness-boom')
        && failure.observed.frameTagged > 0;
    })(),
    `status=${failure.observed.statusRaw}；frame=${failure.observed.frameTagged}（其余修复是否仍应用）`);

  // ── --audit：UI 预算表（两档宽度）────────────────────────────────────────
  let audits = null;
  if (argv.includes('--audit')) {
    console.log('── UI 预算（注入后，两档宽度）──');
    audits = [];
    for (const spec of [{ w: 360 }, { w: 360, openDrawer: true }, { w: 412 }]) {
      const res = await audit(spec.w, {
        scripts, pageUrl, openDrawer: spec.openDrawer,
        label: `audit-${spec.w}${spec.openDrawer ? '-drawer' : ''}`,
      });
      audits.push(res);
      console.log(fmtAudit(res));
      console.log('');
    }
  }

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
    baseline, adapted, desktop, failure, audits, checks,
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
