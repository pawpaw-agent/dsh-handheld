#!/usr/bin/env node
/**
 * 把 dsh web 暴露到局域网，供**真机**验证移动端适配。
 *
 * 为什么需要它：dsh web 默认只绑回环，而真机要验的东西（手机浏览器 / App 的 WebView
 * 里页面被适配成什么样）必须在设备上渲染。App 自己的 SSH 隧道需要凭据，而验证不该
 * 依赖用户的密码 —— 所以这里起一个本地转发，把靶子推到 LAN 上。
 *
 * 特性：
 *  - HTTP + WebSocket upgrade 双向转发（dsh 的事件流走 WS，不转发就不是完整的页面）
 *  - 纯透传：token 由调用方显式带上（`/?token=…`），代理不改写路径
 *  - 只监听 LAN 地址，且**只在验证期间运行**
 *
 * 用法：
 *   node scripts/lan-proxy.mjs --target 127.0.0.1:38083 --listen 0.0.0.0:38082
 *   # 然后让设备打开 http://<本机 LAN IP>:38082/?token=<token>
 */

import http from 'node:http';
import net from 'node:net';

const argv = process.argv.slice(2);
const arg = (n, d) => {
  const i = argv.indexOf(`--${n}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};
const TARGET = arg('target', '127.0.0.1:38083');
const LISTEN = arg('listen', '0.0.0.0:38082');

const [thost, tport] = TARGET.split(':');
const [lhost, lport] = LISTEN.split(':');

const server = http.createServer((req, res) => {
  // 纯透传 —— 刻意**不**自动补 token。
  //
  // 试过两版自动补，都不行：
  //  1. 无条件补：token 交换成功后服务端 303 回 `/`，代理又补 token → 无限 303，
  //     WebView 报 ERR_TOO_MANY_REDIRECTS；
  //  2. 按 `dsh-auth` cookie 判断：curl 正常，但三星浏览器对 IP 主机的 cookie 处理
  //     不同，仍会循环。
  //
  // 透传则可预测：调用方显式带上 `/?token=…`，交换后 303 到 `/` 不再被改写。
  // cookie 若不被接受，得到的是诚实的 401，而不是一个看起来像故障的死循环。
  const proxied = http.request(
    { host: thost, port: tport, method: req.method, path: req.url, headers: { ...req.headers, host: `${thost}:${tport}` } },
    (upstream) => {
      res.writeHead(upstream.statusCode, upstream.headers);
      upstream.pipe(res);
    },
  );
  proxied.on('error', (e) => { res.writeHead(502); res.end(`proxy error: ${e.message}`); });
  req.pipe(proxied);
});

// WebSocket：dsh 的事件流全靠它，漏了页面就永远"连不上"
server.on('upgrade', (req, socket, head) => {
  const upstream = net.connect(Number(tport), thost, () => {
    const headers = Object.entries({ ...req.headers, host: `${thost}:${tport}` })
      .map(([k, v]) => `${k}: ${v}`).join('\r\n');
    upstream.write(`${req.method} ${req.url} HTTP/1.1\r\n${headers}\r\n\r\n`);
    if (head?.length) upstream.write(head);
    upstream.pipe(socket);
    socket.pipe(upstream);
  });
  upstream.on('error', () => socket.destroy());
  socket.on('error', () => upstream.destroy());
});

server.listen(Number(lport), lhost, () => {
  console.log(`LAN 代理已启动：http://${lhost === '0.0.0.0' ? '<本机 LAN IP>' : lhost}:${lport}/  →  ${TARGET}`);
  console.log('停止：Ctrl-C 或终止本作业');
});

process.on('SIGTERM', () => { server.close(); process.exit(0); });
