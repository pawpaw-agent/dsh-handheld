# dsh web 前端插件 404 复查结案：无此缺陷，无需补丁

2026-09-10 复查结论，推翻此前 2026-09-09 的"client-modules batch 404 必现"
诊断。**dsh 0.1.2-rc.1 原生代码无批量 404 缺陷，本地补丁已移除。**

## 复查方法（关键）

用 npm 原包 `@deepseek-ai/dsh-client-modules@0.1.2-rc.1`（未打补丁的原生
`index.js`）替换后，全新实例 4 次启动（`--port 38082`，web-test 配置拷贝），
每次：取 token → 页面 → 提取注入 HTML 的批次 URL → 请求它：

| 请求的 URL 形式 | 结果 |
|---|---|
| 注入 HTML 里原样（`&amp;` 未解码） | **404** ← 之前"复现 404"就是这么测的 |
| `&amp;` 解码为 `&`（浏览器实际行为） | **200**，3.7MB body |

4/4 全部 200。`&amp;` 是 HTML 属性转义（`escapeHtmlAttribute` 对 `src`/`href`
转义了 `&`），浏览器解析属性时自动解码，**WebView 真实请求永远命中 200**。
之前我"复现"批量 404 时，curl 直接使用了 grep 抓到的原样字符串（带 `&amp;`），
服务端收到的是字面 `&amp;rev=…`，自然 miss —— 这是测量假象，不是产品缺陷。

之前判别依据同样不可靠：跨实例/跨进程比较了不同 rev（`rev` 与实例内存表绑定，
URL 里 rev 不对 → 必 404；连单条目 URL 用错 rev 也 404，见下）。

## 单条目验证的教训

- `/plugins/??<id>/client.js&rev=<对的rev>` → 200（同一实例、rev 匹配时）
- 单条目 URL 拿**另一个实例**的 rev → 404 —— 同样说明 rev 是实例绑定的，跨实例
  测试全部无效。

## 结论

1. 服务端 dsh 0.1.2-rc.1 原生可用，无插件批量 404。
2. 之前打的 serveBundle 兜底补丁**已从全局安装移除，恢复原生文件**（校验：
   `node --check` 通过，与 npm 原包 diff 为空）。
3. 手机端 WebView 加载失败的**唯一真根因**是 HTTP 431（cookie 累积 69 个 ≈15.5KB
   顶爆服务端 16KB 头部上限）—— 已由 1.6.3 的 `removeAllCookies` 修复，与补丁无关。

## 复测命令（不再需要补丁）

```bash
# 1. 新起实例
dsh --profile web --no-open --port 38082 &
# 2. 取 token（日志 "dsh web: ...token=XXX"），带 cookie 取页面
curl -s -c ck "http://127.0.0.1:38082/?token=$TOKEN" -o page.html
# 3. 提取批次 URL 并解码 &amp;（浏览器行为）
U=$(grep -oE 'href="(/plugins/\?\?[^"]+)"' page.html | head -1 \
  | sed 's/^href="//;s/"$//;s/&amp;/\&/g')
# 4. 请求 —— 永远 200
curl -s -b ck "http://127.0.0.1:38082$U" -o /dev/null -w "%{http_code}\n"
```
