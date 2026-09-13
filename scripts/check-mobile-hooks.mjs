#!/usr/bin/env node
/**
 * 移动端适配的「宿主契约」金丝雀 —— **不需要浏览器、不需要网络**，可进 CI。
 *
 * ## 它防的是什么
 *
 * 这个 App 的价值是「在手机上用 dsh 网页」，而那个适配完全建立在
 * **dsh 前端的一组 DOM 钩子**之上（自研适配层通过 `data-phase`、
 * `data-composer-input` 之类的属性找到 dsh 的界面结构，再改造它）。
 *
 * 这些钩子**没有任何版本契约**：dsh 独立演进，插件是我们 vendor 下来钉死的。
 * 哪天 dsh 把属性改个名，适配就静默失效 —— 抽屉不弹、布局错位，只能在手机上发现。
 * 本脚本把「插件依赖 dsh 的哪些钩子」变成一份**可断言的清单**：dsh 一改，CI 就红。
 *
 * ## 它断言什么
 *
 * 1. 插件 bundle **读取**的每个 dsh 钩子，在当前 dsh 前端产物里都存在；
 * 2. 插件 bundle 内部的 `id` 与 App 常量 `MOBILE_PLUGIN_ID` 一致；
 * 3. 注入引导模板 `mobile-bootstrap.js` 的占位符能被完整替换（无残留）。
 *
 * 第 2、3 条是纯静态不变量，防的是「改了 App 忘了改 bundle」这类漂移。
 *
 * ## 为什么不是浏览器测试
 *
 * 更完整的验证是用 CDP 驱动 Chromium 真的把页面跑起来（见 `scripts/ui-verify.mjs`）。
 * 但浏览器必须能发 HTTP 请求，而构建沙箱里 Chromium 发不出任何 HTTP（`data:` URL 能渲染，
 * `http://` 一律无响应）—— 因此那条路只能在**有网络能力的机器**上跑。
 * 本脚本是它在 CI 里能落地的、覆盖最重要失效模式的那一部分。
 *
 * 用法：
 *   node scripts/check-mobile-hooks.mjs [--dsh-modules <path>]
 * 环境变量：
 *   DSH_MODULES   dsh 的 node_modules/@deepseek-ai 目录（默认自动探测）
 */

import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { execSync } from 'node:child_process';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const BUNDLE = path.join(REPO, 'android/app/src/main/assets/plugins/dsh-handheld-mobile.js');
const BOOTSTRAP = path.join(REPO, 'android/app/src/main/assets/plugins/mobile-bootstrap.js');
const MAIN_ACTIVITY = path.join(
  REPO, 'android/app/src/main/java/com/dshhandheld/app/MainActivity.kt');
/**
 * 契约文件：把「我们依赖 dsh 的哪些钩子」变成提交在仓库里的声明，供 CI 校验。
 *
 * ⚠️ 放在 `scripts/` 而不是 `assets/` —— `assets/` 会被整个打进 APK，这是一份
 * 只在 CI 里用的声明，不该随应用分发（曾经放错位置并真的进了发布包）。
 * CI 里有一条断言守着这件事。
 */
const CONTRACT = path.join(REPO, 'scripts/mobile-hooks-contract.json');

/**
 * 属于**别的宿主产品**的钩子 —— 这个插件是个「通用适配器」，同时支持若干产品。
 * 它们不是 dsh 的契约，因此不做断言（列在这里是为了让分类是显式的、可审计的，
 * 而不是靠"猜不到的就不检查"）。
 *
 * ⚠️ 分类必须准确：**误报会让这个金丝雀失去信任，进而被忽略**。
 * 两条实测教训：
 *  - `data-genui` / `data-genui-panel` 属于 genui，且在插件里只出现在 **CSS 选择器**
 *    与**一行诊断计数**里，不是功能依赖 —— 早先写成 `data-genui-` 前缀没盖住裸名，
 *    导致误报。这里改为按前缀 `data-genui` 匹配，两种情况都覆盖。
 */
const OTHER_HOST_PREFIXES = [
  'data-aionui-',        // AionUi
  'data-genui',          // genui（含 data-genui-panel）
  'data-dsh-market-',    // dsh 插件市场
  'data-dsh-ssh-',       // 另一个 dsh 插件
  'data-dsh-taskboard-', // 任务板插件
  'data-gitgraph-',      // git 图谱插件
];

/**
 * 属于**这个插件自己**的标记 —— 不是对 dsh 的依赖，因此不参与契约断言。
 *
 * 自研层（2026-09-13 起）只用一个命名空间：`data-handheld`，取值是
 * `frame` / `backdrop` / `fab` / `toggle`。它由我们自己写、自己读，dsh 前端里当然找不到。
 *
 * ⚠️ 与 `OTHER_HOST_PREFIXES` 的区别：那些是**别的宿主产品**（AionUi / genui / …）的钩子。
 * 分类必须准确 —— 误报会让金丝雀失去信任，而漏报会让它对着一个根本不该存在的钩子永远报红。
 *
 * 历史：vendored 的第三方 dsh-web-mobile 用的是 `data-mobile-nav*` 命名空间
 * （`data-mobile-nav-dragging` 还是它与桌宠类插件之间的协作协议）。那一层已删除，
 * 这些名字现在一个都不该再出现。
 */
const PLUGIN_OWN = ['data-handheld'];

/**
 * 从插件 bundle 里读出它依赖的宿主 DOM 钩子（`data-*` 属性选择器）。
 *
 * 扫的是**整份文件**而不只是 `querySelector('...[data-x]')`：2026-09-13 起适配层是
 * 自己写的，大量规则直接写在 CSS 里（`[data-phase] header`、`:has([data-phase="hero"])`），
 * 只认 JS 里的 querySelector 会让这些依赖从契约里漏掉 —— 金丝雀就哑了。
 *
 * `dataset.foo = ...` 这种写法不算：它不产生字面量 `data-foo`，也就不是「读宿主钩子」。
 */
function readHostHooks(text) {
  const found = new Set();
  for (const m of text.matchAll(/\[(data-[a-z-]+)(?:[=\]])/g)) found.add(m[1]);
  return found;
}

const die = (msg) => { console.error(`\n✗ ${msg}\n`); process.exit(1); };

// ── 定位 dsh 前端产物 ───────────────────────────────────────────────────────
function findDshModules() {
  const explicit = process.env.DSH_MODULES
    || (process.argv.includes('--dsh-modules')
      ? process.argv[process.argv.indexOf('--dsh-modules') + 1] : null);
  if (explicit) {
    if (!existsSync(explicit)) die(`DSH_MODULES 不存在：${explicit}`);
    return explicit;
  }
  const looksRight = (dir) => {
    try {
      return existsSync(dir) && readdirSync(dir).some((d) => d.startsWith('dsh-web'));
    } catch { return false; }
  };
  try {
    const bin = execSync('command -v dsh', { encoding: 'utf8' }).trim();
    if (bin) {
      // dsh 的 bin 指向 <pkg>/lib/bin.js，所以要从解析后的路径**逐级向上**找，
      // 不能只取 dirname（那样会停在 lib/）。
      let dir = path.dirname(execSync(`readlink -f ${bin}`, { encoding: 'utf8' }).trim());
      for (let i = 0; i < 6 && dir !== '/'; i++) {
        const cand = path.join(dir, 'node_modules/@deepseek-ai');
        if (looksRight(cand)) return cand;
        dir = path.dirname(dir);
      }
    }
  } catch { /* 继续 */ }
  return null;
}

// ── 契约模式：不需要 dsh，可在 CI 跑 ──────────────────────────────────────
// 校验插件【实际读取】的 dsh 钩子与提交在仓库里的契约文件一致。
// 意义：重新 vendoring 插件会改变依赖集合，那必须是显式动作（更新契约），
// 而不是悄悄多依赖几个没人验证过的钩子。
if (process.argv.includes('--contract')) {
  const b = readFileSync(BUNDLE, 'utf8');
  const read = readHostHooks(b);
  const otherPrefixes = OTHER_HOST_PREFIXES;
  const actual = [...read]
    .filter((h) => !otherPrefixes.some((p) => h.startsWith(p)))
    .filter((h) => !PLUGIN_OWN.includes(h)).sort();
  const contract = JSON.parse(readFileSync(CONTRACT, 'utf8'));
  const declared = [...contract.dshHooks].sort();
  const extra = actual.filter((h) => !declared.includes(h));
  const gone = declared.filter((h) => !actual.includes(h));
  console.log('移动端适配 · 契约校验（静态，无需 dsh）');
  console.log(`  契约文件   ${path.relative(REPO, CONTRACT)}`);
  console.log(`  声明依赖   ${declared.length} 个 dsh 钩子`);
  console.log(`  插件实读   ${actual.length} 个`);
  console.log('');
  for (const h of declared) {
    console.log(`  ${actual.includes(h) ? '✓' : '✗'} ${h}`);
  }
  if (extra.length) {
    console.log('');
    console.log('插件读了契约里没有的钩子（重新 vendoring 后需显式更新契约）：');
    for (const h of extra) console.log(`  + ${h}`);
  }
  if (gone.length) {
    console.log('');
    console.log('契约声明了但插件已不再读取（可从契约移除）：');
    for (const h of gone) console.log(`  - ${h}`);
  }
  console.log('');
  if (extra.length === 0 && gone.length === 0) {
    console.log('契约与插件一致 ✓');
    process.exit(0);
  }
  console.log('契约与插件不一致 ✗');
  console.log('');
  console.log(`更新方式：node scripts/check-mobile-hooks.mjs --update-contract`);
  console.log('（更新前请确认新钩子在真实 dsh 上存在：在同机跑不带参数的完整检查）');
  process.exit(1);
}

const modules = findDshModules();
if (!modules) {
  die('找不到 dsh 的模块目录。请设置 DSH_MODULES，例如：\n'
    + '  DSH_MODULES=/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai \\\n'
    + '    node scripts/check-mobile-hooks.mjs');
}

// 收集 dsh 前端的全部产物文本
function collectFrontendText(root) {
  const chunks = [];
  const walk = (dir, depth = 0) => {
    if (depth > 4) return;
    let entries;
    try { entries = readdirSync(dir); } catch { return; }
    for (const e of entries) {
      const p = path.join(dir, e);
      let st;
      try { st = statSync(p); } catch { continue; }
      if (st.isDirectory()) {
        if (e === 'node_modules' || e === '.git') continue;
        walk(p, depth + 1);
      } else if (/\.(js|mjs|cjs|html|css)$/.test(e) && st.size < 12 * 1024 * 1024) {
        try { chunks.push(readFileSync(p, 'utf8')); } catch { /* skip */ }
      }
    }
  };
  // 只看 dsh 自己的前端包，别把整棵 node_modules 拖进来
  for (const e of readdirSync(root)) {
    if (!/^dsh-(web-frontend|client-ui|web-app|client-modules)/.test(e)) continue;
    walk(path.join(root, e));
  }
  return chunks.join('\n');
}

// ── 从插件 bundle 提取「读取的钩子」────────────────────────────────────────
const bundle = readFileSync(BUNDLE, 'utf8');
const readHooks = readHostHooks(bundle);

const isOtherHost = (h) => OTHER_HOST_PREFIXES.some((p) => h.startsWith(p));
const dshHooks = [...readHooks].filter((h) => !isOtherHost(h) && !PLUGIN_OWN.includes(h)).sort();
const otherHooks = [...readHooks].filter(isOtherHost).sort();

// ── 断言 1：dsh 钩子在当前前端产物里都存在 ────────────────────────────────
const frontend = collectFrontendText(modules);
if (frontend.length < 1000) {
  die(`在 ${modules} 下没读到 dsh 前端产物（${frontend.length} 字节）—— 路径可能不对`);
}

console.log('移动端适配 · 宿主契约检查');
console.log(`  dsh 模块     ${modules}`);
console.log(`  前端产物     ${(frontend.length / 1024).toFixed(0)} KB`);
console.log(`  插件 bundle  ${(bundle.length / 1024).toFixed(0)} KB`);
console.log('');
console.log(`── 插件依赖 dsh 的钩子（${dshHooks.length} 个）──`);

const missing = [];
for (const h of dshHooks) {
  const n = frontend.split(h).length - 1;
  const ok = n > 0;
  if (!ok) missing.push(h);
  console.log(`  ${ok ? '✓' : '✗'} ${h.padEnd(34)} ${ok ? `${n} 处` : '**在 dsh 前端里找不到**'}`);
}

if (otherHooks.length) {
  console.log('');
  console.log(`── 属于其它宿主的钩子（${otherHooks.length} 个，不做断言）──`);
  console.log(`  ${otherHooks.join(', ')}`);
}

// ── 断言 2：插件 id 与 App 常量一致 ────────────────────────────────────────
console.log('');
console.log('── 静态不变量 ──');
const kt = readFileSync(MAIN_ACTIVITY, 'utf8');
const ktId = kt.match(/MOBILE_PLUGIN_ID\s*=\s*"([^"]+)"/)?.[1];
const ktRev = kt.match(/MOBILE_PLUGIN_REV\s*=\s*"([^"]+)"/)?.[1];
const bundleId = bundle.match(/id:\s*"([a-z0-9-]+)"/)?.[1];
const idOk = ktId && bundleId && ktId === bundleId;
console.log(`  ${idOk ? '✓' : '✗'} 插件 id 一致：App="${ktId}" bundle="${bundleId}"`);
console.log(`      rev（缓存键）: ${ktRev}`);

// ── 断言 3：引导模板占位符可完整替换 ──────────────────────────────────────
const tpl = readFileSync(BOOTSTRAP, 'utf8');
const filled = tpl.replaceAll('{{ID}}', ktId ?? '').replaceAll('{{URL}}', 'X').replaceAll('{{REV}}', 'X');
const leftover = ['{{ID}}', '{{URL}}', '{{REV}}'].filter((p) => filled.includes(p));
console.log(`  ${leftover.length === 0 ? '✓' : '✗'} 引导模板占位符完整替换`
  + (leftover.length ? `（残留 ${leftover.join(', ')}）` : ''));
// 模板必须真的用到三个占位符，否则是"改了 App 忘了改模板"
const usedAll = ['{{ID}}', '{{URL}}', '{{REV}}'].every((p) => tpl.includes(p));
console.log(`  ${usedAll ? '✓' : '✗'} 引导模板用到全部三个占位符`);

// ── 结论 ───────────────────────────────────────────────────────────────────
console.log('');
const failures = missing.length + (idOk ? 0 : 1) + leftover.length + (usedAll ? 0 : 1);
if (failures === 0) {
  if (process.argv.includes('--update-contract')) {
    let dshVersion = 'unknown';
    try {
      dshVersion = execSync('dsh --version', { encoding: 'utf8' }).trim();
    } catch { /* 取不到就记 unknown */ }
    writeFileSync(CONTRACT, JSON.stringify({
      note: '移动端适配所依赖的 dsh DOM 钩子。由 scripts/check-mobile-hooks.mjs 维护；'
        + 'CI 用 --contract 模式校验插件与它一致，完整检查需在本机装有 dsh 时运行。',
      verifiedAgainst: { dsh: dshVersion, date: new Date().toISOString().slice(0, 10) },
      dshHooks,
      otherHostHooks: otherHooks,
    }, null, 2) + '\n');
    console.log(`契约已更新 → ${path.relative(REPO, CONTRACT)}（对照 ${dshVersion}）`);
  }
  console.log(`适配契约完好：${dshHooks.length} 个 dsh 钩子全部存在 ✓`);
  process.exit(0);
}
console.log(`${failures} 项不通过 ✗`);
if (missing.length) {
  console.log('');
  console.log('dsh 前端里找不到这些钩子 —— 移动端适配很可能已失效：');
  for (const h of missing) console.log(`  - ${h}`);
  console.log('');
  console.log('处理方式：确认 dsh 是否改了属性名。若改了，需要更新');
  console.log('  android/app/src/main/assets/plugins/dsh-handheld-mobile.js');
  console.log('（重新 vendoring 上游插件，或修好选择器后再打补丁）。');
  console.log('详见 docs/vendored-plugin-patches.md。');
}
process.exit(1);
