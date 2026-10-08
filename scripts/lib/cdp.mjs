/*
 * 极简 CDP 客户端（零依赖）—— `ui-verify.mjs`（桌面 chromium）与
 * `device-audit.mjs`（真机 WebView，经 adb forward）共用这一份。
 *
 * 踩过的坑都在注释里：注册回调要在发送之前（响应可能极快）、chrome 崩了要让所有
 * 在等的 promise 立刻失败（否则整条流水线静默挂死）。
 */
export class CDP {
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
