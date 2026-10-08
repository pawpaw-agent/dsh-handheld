/*
 * 宿主 DOM 钩子 —— **唯一出处**（2026-10-08 重新设计）。
 *
 * 适配层依赖一组 dsh 前端**没有版本契约**的 DOM 钩子：属性名、CSS 模块类名后缀。
 * dsh 独立演进，哪天改个名适配就静默失效（抽屉不弹、布局错位），只能在手机上肉眼发现。
 *
 * 所以：所有选择器只写在这里。`scripts/check-mobile-hooks.mjs` 从本文件生成/校验契约
 * （`scripts/mobile-hooks-contract.json`），CI 里跑 `--contract`；本机装了 dsh 时可跑完整
 * 模式，断言这些钩子确实存在于 dsh 前端产物里。
 *
 * 命名约定：
 *   - 带 `data-handheld` 的是**本层自己打**的标记（宿主不认识、也不会清）——最稳；
 *   - 带 `data-*` 的是宿主自己写的语义属性；
 *   - `[class*="_xxx"]` 是宿主 CSS 模块的类名后缀 —— **最脆的一类**，改版首当其冲。
 */
(function (root) {
  "use strict";
  root.__dshHandheldHooks = {
    // ── 本层自己打的标记 ──
    frame:            '[data-handheld="frame"]',
    cover:            '[data-dsh-cover]',

    // ── 宿主自己的钩子 ──
    frameEl:          '[class*="_frame"]',
    sidebarCol:       '[class*="_sidebarCol"]',
    sidebarCollapsed: '[data-sidebar-collapsed]',
    sidebarToggle:    'button[class*="_toggle"]',
    titleRow:         '[class*="_titleRow"]',
    tabs:             '[class*="_tabs"]',
    composerSeat:     '[class*="_composerSeat"]',
    composerStats:    '[data-composer-stats]',
    dock:             '[class*="_dock"]',
    rightPanel:       '[data-sidebar-right-panel]',
    rightPanelFull:   '[data-sidebar-right-panel="fullscreen"]',
    headerActions:    '[class*="_headerActions"]',
    iconButton:       '[class*="_iconButton"]',
  };
})(window);
