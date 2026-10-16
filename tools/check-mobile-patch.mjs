#!/usr/bin/env node
/**
 * 触控补丁守卫 —— 检查「补丁是否还真的生效」，把静默失效变成响亮的报警。
 *
 * 为什么需要：补丁写在 dsh 的**构建产物**里（dist/assets/index-<hash>.css），
 * 而类名带构建哈希（._sm_1rv3m_28 的 28 就是原高度）。dsh 一升级：
 *   - 文件名哈希变 → 补丁文件整个换了 → 我们写的那段直接消失；
 *   - 类名哈希变   → 选择器再也匹配不到 → 补丁在、但不生效。
 * 两种情况都**不会报错**，只会「手机上按钮又变小了」—— 必须主动查。
 *
 * 用法:
 *   node tools/check-mobile-patch.mjs            # 检查（人读输出）
 *   node tools/check-mobile-patch.mjs --selftest # 自测：确认这个检查器本身有效
 *
 * 退出码: 0=全部正常  3=补丁未应用  4=dsh 升级导致失效  5=找不到 dist
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const DIST = "/home/xsj/.local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets";
const MARK = "/* dsh-handheld mobile touch patch";

// 补丁依赖的选择器：(类名, 是否必须存在于 dist，说明)
//   optional=true 的类来自 /plugins/ 注入的内联样式，dist 里天然没有 —— 靠 !important 生效
const REQUIRED = [
  ["_sm_1rv3m_28", true, "紧凑按钮（更多操作 / 打开右侧边栏）"],
  ["_iconButton_6nhg2_511", true, "右栏图标按钮"],
  ["_row_jhda5_16", true, "消息区可折叠行"],
  ["hHd-Xa_toggle", false, "侧栏开合（插件内联）"],
  ["hHd-Xa_newSession", false, "新建会话（插件内联）"],
  ["hHd-Xa_panelRow", false, "插件面板入口（插件内联）"],
  ["nLMEza_iconBtn", false, "目标操作按钮（插件内联）"],
  ["wSkVaW_tab", false, "页签（插件内联）"],
  ["uV2eYG_add", false, "输入区附件（插件内联）"],
  ["uV2eYG_primary", false, "输入区发送（插件内联）"],
  ["iWlSmW_trigger", false, "访问模式触发器（插件内联）"],
  ["_7KE1Ra_trigger", false, "模型选择触发器（插件内联）"],
];

function findIndexCss(dir) {
  if (!existsSync(dir)) return { error: "dist/assets 不存在: " + dir };
  const files = readdirSync(dir).filter((f) => /^index-.*\.css$/.test(f));
  if (files.length === 0) return { error: "没找到 index-*.css" };
  if (files.length > 1) {
    // 多个 index-*.css 通常意味着升级残留；挑最新的那个，但明确告警
    const sorted = files.map((f) => ({ f, m: statSafe(join(dir, f)) })).sort((a, b) => b.m - a.m);
    return { path: join(dir, sorted[0].f), name: sorted[0].f, multiple: files };
  }
  return { path: join(dir, files[0]), name: files[0] };
}

function statSafe(p) {
  try { return require("node:fs").statSync(p).mtimeMs; } catch { return 0; }
}

function inspect() {
  const found = findIndexCss(DIST);
  if (found.error) return { status: "no-dist", detail: found.error };
  const css = readFileSync(found.path, "utf8");
  const patched = css.includes(MARK);
  // 基准：优先用未打补丁的 .orig，否则从当前文件剥掉补丁段再判断类是否存在
  const origPath = found.path + ".orig";
  let base = css;
  if (existsSync(origPath)) {
    base = readFileSync(origPath, "utf8");
  } else if (patched) {
    base = css.slice(0, css.indexOf(MARK));
  }
  const missing = [];
  const present = [];
  const optionalMissing = [];
  for (const [cls, mustBeInDist, desc] of REQUIRED) {
    const inDist = base.includes("." + cls);
    if (inDist) present.push([cls, desc]);
    else if (mustBeInDist) missing.push([cls, desc]);
    else optionalMissing.push([cls, desc]);
  }
  return {
    status: missing.length ? "stale" : (patched ? "ok" : "not-applied"),
    file: found.name,
    multiple: found.multiple || null,
    patched,
    missing,
    present,
    optionalMissing,
    cssSize: css.length,
  };
}

function report(r, verbose = true) {
  if (r.status === "no-dist") {
    console.error("✗ " + r.detail);
    console.error("  dsh 可能没装、或换了安装位置。检查: " + DIST);
    return 5;
  }
  if (verbose) {
    console.log("dist CSS : " + r.file + "  (" + Math.round(r.cssSize / 1024) + " KB)");
    if (r.multiple) console.log("⚠ 存在多个 index-*.css（升级残留？）: " + r.multiple.join(", "));
    console.log("补丁标记 : " + (r.patched ? "已应用 ✓" : "未应用 ✗"));
    console.log("必须存在的选择器: " + r.present.filter(([c]) => REQUIRED.find(([x, m]) => x === c && m)).length + "/" + REQUIRED.filter(([, m]) => m).length);
    if (r.optionalMissing.length) {
      console.log("插件内联类（dist 里天然没有，靠 !important）: " + r.optionalMissing.length + " 个");
    }
  }
  if (r.status === "stale") {
    console.error("");
    console.error("✗ dsh 升级导致补丁失效 —— 以下选择器在 dist CSS 里找不到了：");
    for (const [cls, desc] of r.missing) console.error("    ." + cls + "   (" + desc + ")");
    console.error("");
    console.error("  处理：重新在真机上实测类名，更新 tools/gen-mobile-touch-patch.py 的 GROUPS，");
    console.error("        然后 python3 tools/gen-mobile-touch-patch.py --apply");
    console.error("  提示：手机上新版按钮会明显变小，这正是失效的现场表现。");
    return 4;
  }
  if (r.status === "not-applied") {
    console.error("");
    console.error("✗ 补丁未应用（dist CSS 里没有标记）。");
    console.error("  处理: python3 tools/gen-mobile-touch-patch.py --apply");
    return 3;
  }
  if (verbose) {
    console.log("");
    console.log("✓ 补丁在位，且依赖的选择器都还在 —— 手机上是生效状态。");
  }
  return 0;
}

// ── 自测：用合成 CSS 驱动 inspect 的核心判定，确认检查器真的会报警 ──
function selftest() {
  let pass = 0, fail = 0;
  const check = (name, cond) => {
    if (cond) { pass++; console.log("  ✓ " + name); }
    else { fail++; console.error("  ✗ " + name); }
  };
  console.log("自测：检查器能否正确区分三种状态");
  console.log("");

  // 复刻 inspect 的判定逻辑，喂合成输入
  const judge = (base, cssHasMark) => {
    const missing = REQUIRED.filter(([cls, must]) => must && !base.includes("." + cls));
    return missing.length ? "stale" : (cssHasMark ? "ok" : "not-applied");
  };

  const goodBase = REQUIRED.filter(([, m]) => m).map(([c]) => "." + c + "{height:28px}").join("");
  check("补丁在位 + 类都在 => ok", judge(goodBase, true) === "ok");
  check("补丁不在 + 类都在 => not-applied", judge(goodBase, false) === "not-applied");
  check("类缺一个 => stale（模拟升级改名）",
    judge(goodBase.replace("._sm_1rv3m_28", "._sm_NEWHASH_40"), true) === "stale");
  check("全部类都缺 => stale", judge("", true) === "stale");

  // 确认 REQUIRED 里确实有必须项（否则 stale 永远不会触发）
  const mustCount = REQUIRED.filter(([, m]) => m).length;
  check("REQUIRED 里有 " + mustCount + " 个必须项（>0 才有意义）", mustCount > 0);

  // 确认可选项不会误报 stale
  const onlyOptionalMissing = judge(goodBase, true);
  check("可选类缺失不影响判定", onlyOptionalMissing === "ok");

  console.log("");
  console.log("自测结果: " + pass + " 通过, " + fail + " 失败");
  return fail === 0 ? 0 : 1;
}

if (process.argv.includes("--selftest")) {
  process.exit(selftest());
}

const result = inspect();
const code = report(result);
process.exit(code);
