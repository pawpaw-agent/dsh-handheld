#!/usr/bin/env node
/**
 * 把适配层 bundle 里的那段 CSS 抽成一个 .css 文件。
 *
 * 为什么需要它：适配层的样式**整段写在 JS 模板字符串里**（这样注入只需一个文件、
 * 一个 rev），但本地渲染回环（`scripts/css-lab.mjs`）要的是 `.css` 文件。手工复制
 * 粘贴一定会漂移，所以从 bundle 里抽，并且把模板里的 `${MOBILE_QUERY}` / `${DRAWER_W}`
 * 按源码里的常量替换掉 —— 抽出来的 CSS 与 WebView 里跑的**逐字相同**。
 *
 * 用法：
 *   node scripts/plugin-css.mjs /tmp/plugin-css.css
 *   node scripts/plugin-css.mjs /tmp/plugin-css-legacy.css --drop-modal-guard
 *
 * `--drop-modal-guard` 是给「设置页点不动」那条回归用的**负对照**：把
 * 「抽屉里挂着模态时整列照旧吃指针」那条规则剥掉，故障（点 ✕ 无反应、点击穿透到
 * 背后的页面）应当在小回环里复现。一条永远通过的断言等于没有断言。
 * 见 docs/mobile-ui-verification.md 与 docs/known-issues.md §五。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const BUNDLE = path.join(REPO, 'android/app/src/main/assets/plugins/dsh-handheld-mobile.js');

const argv = process.argv.slice(2);
const out = argv.find((a) => !a.startsWith('--')) ?? '/tmp/plugin-css.css';
const dropGuard = argv.includes('--drop-modal-guard');

const src = readFileSync(BUNDLE, 'utf8');
const pick = (name) => {
  const m = new RegExp(`var ${name} = "([^"]+)"`).exec(src);
  if (!m) throw new Error(`bundle 里找不到 ${name}`);
  return m[1];
};
const block = /var CSS = `([\s\S]*?)`;/.exec(src);
if (!block) throw new Error('bundle 里找不到 CSS 模板字符串（var CSS = `…`）');

let css = block[1]
  .replaceAll('${MOBILE_QUERY}', pick('MOBILE_QUERY'))
  .replaceAll('${DRAWER_W}', pick('DRAWER_W'));

if (dropGuard) {
  // 那条规则以「……但抽屉里只要挂着模态」这句注释开头，到它自己的 } 结束 ——
  // 注释是这条规则的一部分（源码里就写着它为什么存在），剥掉时一起剥。
  const before = css;
  css = css.replace(/\n *\/\* ……但抽屉里只要挂着模态[\s\S]*?\n *\}\n/, '\n');
  if (css === before) throw new Error('没能剥掉模态兜底规则 —— 规则被改写过？');
}

writeFileSync(out, css);
console.log(`${dropGuard ? '负对照（剥掉模态兜底）' : '当前 CSS'} → ${out}（${css.length} 字节）`);
