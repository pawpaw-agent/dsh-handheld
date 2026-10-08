/*
 * 注入列表一致性 —— 防「改了 App 忘了改 harness」（反之亦然）。
 *
 * 为什么必须有：harness 验的是「手机上跑的那一份」。App 的注入列表在 Kotlin 里
 * （`MainActivity` 的注入块），harness 的在 `scripts/ui-verify.mjs` 的 SEGMENTS。
 * 两份一旦漂移，harness 就变成在验一个**不存在的东西** —— 旧层当年正是这么翻车的
 * （harness 对着一个已改名的标记判「插件没加载」）。
 *
 * 零依赖、不需要浏览器，所以能进 CI。
 */
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const MAIN = path.join(REPO, 'android/app/src/main/java/com/dshhandheld/app/MainActivity.kt');
const HARNESS = path.join(REPO, 'scripts/ui-verify.mjs');
const HANDHELD = path.join(REPO, 'android/app/src/main/assets/plugins/handheld');

const kt = readFileSync(MAIN, 'utf8');
const js = readFileSync(HARNESS, 'utf8');

// App 侧：注入块里那串 "plugins/handheld/xxx.js" 字面量，按出现顺序
const ktList = [...kt.matchAll(/"(plugins\/handheld\/[a-z.-]+\.js)"/g)].map((m) => m[1]);
// harness 侧：SEGMENTS 数组里的文件名
const segBlock = js.match(/const SEGMENTS = \[([\s\S]*?)\];/);
if (!segBlock) { console.error('✗ 找不到 harness 的 SEGMENTS'); process.exit(1); }
const jsList = [...segBlock[1].matchAll(/'([a-z.-]+\.js)'/g)].map((m) => 'plugins/handheld/' + m[1]);

const problems = [];
if (ktList.length === 0) problems.push('App 侧没解析出任何注入路径（注入块是不是改了写法？）');
if (jsList.length === 0) problems.push('harness 侧没解析出任何注入段');

const ktSet = ktList.join(','), jsSet = jsList.join(',');
if (ktSet !== jsSet) {
  problems.push(`顺序或内容不一致：\n    App     : ${ktList.join(' → ') || '（空）'}\n    harness : ${jsList.join(' → ') || '（空）'}`);
}
for (const p of [...new Set([...ktList, ...jsList])]) {
  if (!existsSync(path.join(REPO, 'android/app/src/main/assets', p))) problems.push(`文件不存在：${p}`);
}

// 样式那一环（harness 里是 null 占位）两边都得有
if (!kt.includes('plugins/handheld/styles.css')) problems.push('App 侧没读 styles.css');
if (!/null,\s*\/\/ ⑤/.test(js) && !/styles\.css/.test(js)) problems.push('harness 侧没读 styles.css');

console.log('── 注入列表一致性 ──');
console.log(`  App     : ${ktList.join(' → ')}`);
console.log(`  harness : ${jsList.join(' → ')}`);
console.log(`  样式    : styles.css（两边都读，且都剥注释）`);
if (problems.length) {
  console.log('');
  for (const p of problems) console.log(`  ✗ ${p}`);
  console.log('');
  console.log('注入列表不一致 ✗ —— harness 验的将不是手机上跑的那一份。');
  process.exit(1);
}
console.log('');
console.log('注入列表一致 ✓');
