# DeepSeek Harness (DSH) 浏览器线上协议 — 逆向规格

> **历史存档（0.1.2 起）**：本规格描述的是 dsh-handheld 早期「原生协议客户端」方案所用的线上协议。
> 该方案已在 0.1.2 中**完全移除**——App 现在是纯 WebView，不再自行实现 DSH 传输层，
> 因此仓库中**没有**实现此协议的代码。本文仅作为协议研究记录保留，供需要自行实现
> DSH 线上传输层的场景参考。
>
> **来源**：`dsh-client-connection@…/lib/client.js`（编译后 bundle），DSH 版本 `0.1.1-rc.2`。
> ⚠️ 该版本已过时（写作时 dsh 为 `0.1.5-rc.2`），协议可能已有变更，请以实际 bundle 为准。
>
> 结论先行：DSH 的浏览器端协议**不是 REST**，而是「HTTP POST（unary/respond）+ WebSocket（downlink 事件流）」之上的一层 **RPC envelope**，外层是四成员判别联合。

---

## 0. 传输总览（两个物理载体，一套逻辑消息）

| 方向 | 载体 | URL | 用途 |
|---|---|---|---|
| 客户端→服务端 (C→S) | HTTP `POST` | `/api/<method>` | 所有 unary RPC 请求 |
| 客户端→服务端 (C→S) | HTTP `POST` | `/api/respond` | 回答服务端发起的 `server-request`（如 approval/approve） |
| 服务端→客户端 (S→C) | WebSocket downlink | `/api/events.mux` | 会话事件流（session/event、approval、question、queue、jobs、projection） |
| 服务端→客户端 (S→C) | WebSocket downlink | `/api/events.host` | 全局状态流（host/session-*、workspace-*、agent-error…） |

> 注：`AbstractApiClient` 基类里也有一个 **SSE 回退**实现（`readSse`，`\n\n` 分帧 + `data: ` 前缀）。但**浏览器真实载体**（`WebApiClient`）用的是 **WebSocket**（`readWebSocket`，每帧一个 JSON 文本消息）。Kotlin 端**应以 WebSocket 为准**（这是服务器实际提供的；无 SSE fallback，`/api/events.mux` 普通 GET 返回 426）。

服务器 `/api` 前缀（`cordis` 侧）：`dsh-host-webserver` 的 `register(route)` + `registerUpgrade(route)`。事件路径是 WebSocket **upgrade** 路由：`/api` 和 `/api/<anything>` 归 FetchHandler，`/api/events.mux`、`/api/events.host` 归 upgrade。

---

## 1. RPC 消息模型（四象限 envelope）

物理载体无关，逻辑上是**四成员判别联合**，判别字段 `type`。

### 1.1 客户端→服务端请求（`client-request`）
```json
{ "type": "client-request", "rpcId": "<uuid>", "method": "session.list", "payload": {} }
```
- `rpcId`：客户端每次请求 mint 一个 UUID，用于回包相关性校验（客户端必须校验响应回包 `rpcId` 一致）。
- `method`：`"namespace.method"` 字符串。
- `payload`：业务参数对象（方法相关）。

### 1.2 服务端→客户端响应（`server-response`）
```json
{ "type": "server-response", "rpcId": "<uuid>", "result": { "ok": true, "value": {…} } }
```
或错误分支：`{ "ok": false, "error": {"code": "…", "message": "…", "details": {…}} }`

### 1.3 服务端→客户端请求（`server-request`）—— 事件/下行
```json
{ "type": "server-request", "rpcId": "<uuid>", "method": "…", "payload": {…帧…} }
```
- `payload` 由 frame schema 二次解析（MuxFrame / HostFrame 判别联合，见 §4）。
- 这类消息走**WebSocket 下行**，`rpcId` 是服务端 mint 的。

### 1.4 客户端→服务端响应（`client-response`）
```json
{ "type": "client-response", "rpcId": "<uuid>", "result": {"ok":true,"value":{…}} }
```
- 用于回 `server-request`（例如批准一个 approval）。POST 到 `/api/respond`。
- 回执：`{"accepted": true}` 或 `{"accepted": false, "reason": "not-pending"|"bad-response"}`。

### 1.5 统一错误体 `RpcResult.Error`
`details` 必填，按 `code` 判别：
```
bad-request          { issues: [...] }
cancelled            {}
session-not-found    { sessionId }
model-unavailable    { provider, model }
session-conflict     { sessionId, requestedCwd, existingCwd? }
invalid-time-zone    { value }
workspace-attach-failed { sessionId, workspaceId }
workspace-not-found  { workspaceId }
workspace-invalid-path { path }
workspace-name-conflict { name }
workspace-move-invalid { workspaceId, sessionId, beforeSessionId? }
directory-unreadable / directory-exists / directory-create-failed { path }
directory-picker-unavailable { capability }
agent-preset-read-only { agentPreset, reason }
agent-preset-locked  { sessionId, agentPreset }
agent-preset-conflict { sessionId, requestedPreset, existingPreset? }
agent-preset-not-found { agentPreset, available: [string] }
agent-preset-invalid { agentPreset, reason }
agent-busy           { reason }
attachment-error     { reason }
queue-item-not-found { itemId }
steer-unavailable    { itemId }
command-error        {}
unknown-command      {}
settings-rejected    { ns }
settings-conflict    { ns, expected, actual }
credential-rejected  { ref }
model-discovery-failed { settingsNs, baseURL? }
title-invalid        { sessionId }
fork-unavailable     { sessionId }
subagent-parent-unavailable { parentSessionId }
subagent-not-found   { parentSessionId, childSessionId }
subagent-catalog-diagnostic { parentSessionId, childSessionId, reason: corrupt|unsupported|unavailable }
subagent-not-resumable { childSessionId }
subagent-unauthorized { childSessionId }
subagent-delivery-unavailable { childSessionId }
internal             {}
```

---

## 2. 传输载体细节（浏览器端）

### 2.1 `AbstractApiClient` —— 协议不变量
- `mintRpcId()`：`crypto.randomUUID()`。
- `postJson(path, body, signal, timeoutPolicy)`：
  - `method: "POST"`，`headers: {"content-type": "application/json"}`，`body: JSON.stringify(message)`。
  - 默认 30s 超时（`AbortSignal.timeout`），非 2xx → throw `transport failure for <path>: HTTP <status>`。
- `callUnary(method, payload, signal)`：
  1. mint rpcId
  2. POST `client-request` 到 `/api/<method>`
  3. 解析 `server-response`，校验 `rpcId` 回显
  4. 若 `result.ok` → 二次解析 `value`（按 `UNARY_VALUE_SCHEMAS[method]`）
  5. 返回 `{ rpcId, result }`
- `respond(message)`：POST `client-response` 到 `/api/respond`，解析回执。

### 2.2 `WebApiClient` —— 浏览器真实载体
- `doFetch`：`globalThis.fetch`。
- `openMux/openHost`：`readWebSocket(path, signal, frameSchema, onOpen)`。
- `readWebSocket` 关键行为：
  - `url = new URL(path, resolveBase())`；`https:` 协议 → `wss:`，否则 `ws:`。
  - `socket = new WebSocket(url)`（浏览器原生 WebSocket；只收不发，downlink-only）。
  - 每帧：`typeof event.data === "string"` 否则丢弃（**不支持二进制帧**）。
  - 解析：`full = serverRequestSchema.parse(JSON.parse(event.data))`；`frame = frameSchema.parse(full.payload)`。
  - 交付给上层的 envelope：`{ rpcId: full.rpcId, payload: frame }`。
  - `abort` → 若 `CONNECTING||OPEN` 则 `socket.close()`。
  - **客户端不下发任何应用数据**（downlink-only）。

### 2.3 `createWebConnectionRpc(doFetch)` —— 通用 unary RPC caller
- 请求：`POST new URL(channel + "/" + endpoint, resolveBase())`，body = `client-request`。
- 校验 `response.ok`、`rpcId` 回显；返回 `full.result`。
- 用相同的 `client-request` / `server-response` envelope。可用于任意 channel（浏览器默认用 `/api`）。

### 2.4 就绪握手（readiness handshake）
连接循环（`ConnectionController`）：
1. 并行打开 `events.mux` + `events.host` 两个流（`streamOpenTimeoutMs` 默认 3000ms），
   同时调 `host.describe({})`。
2. 两个流都 open 且 `host.describe` 成功 → 状态 `connected`；否则重连（指数退避：
   base 500ms、倍率 2、上限 10s，抖动）。
3. `pumpStream` 对每个 envelope 调 sink；`stream/error` 帧终止该流。
4. 状态机：`connected` / `reconnecting`；`onConnected(describeValue)`。

### 2.5 路由与 CORS/权限门槛（`/api` browser-trust fence）
- 每个 `/api` 请求在 bridge 前必须呈现 `Host` 为回环 authority 或匹配 `trustedHosts` 条目（DNS 反绑定防御）。
- 明文 HTTP 下无 `Origin`/Fetch-Metadata 的请求按回环判定；WebSocket 握手带 `Origin` 且需过同一比较。
- **配置平面**（`settings.*`、`credentials.*`、`agentPreset` 写、`host.pickDirectory/openPath`、`llm.discoverModels`）**强制仅回环**（trust 列表为空时）。这是 `PRIVILEGED_METHODS`。
- `dsh web --host 0.0.0.0` 故意不支持（远程访问无认证层）。远程须知：`dsh-lan-access` 插件绑 `0.0.0.0` 但配置平面仍 403；合规远程方案是 SSH 端口转发（从服务器视角仍回环）。
> **对 Kotlin 客户端意义**：客户端应通过「SSH 端口转发（`ssh -L 3080:127.0.0.1:3080`）」或服务端授权 `trustedHosts` 来接入；否则配置平面接口必 403。

---

## 3. 业务方法清单（method → 请求 payload → 响应 value schema）

### 3.1 `sessions`（会话）
| method | 请求 payload | 响应 value |
|---|---|---|
| `session.list` | `{cursor?}` | `{items:[SessionSummary], ...}` |
| `session.search` | `{query}`（1..500，无 NUL） | `{items:[{sessionId,snippet}≤240], hasMore}` |
| `session.create` | `{workspaceId? 或 cwd?, sessionId?, agentPreset?}`（二者不可并存） | `{sessionId, agentPreset?}` |
| `session.history` | `{sessionId}` | `{events:[HistoryEntry], hasMore, projections?}` |
| `session.models` | `{sessionId}` | `{current, routable, groups, failures}` |
| `session.selectModel` | `{sessionId, provider, model, reasoningEffort?}` | `{selected:ModelSelection}` |
| `session.rename` | `{sessionId, title}` | `{title, seq}` |
| `session.fork` | `{sessionId, atSeq?}` | `{sessionId}`（子会话 id） |
| `session.prompt` | `{sessionId, mode: queue\|steer, content:[PromptPart], clientTimeZone?}` | `{accepted:true, command?:{kind:"success",text?}}` |
| `session.attachment` | `{sessionId, attachmentId}` | `{attachment:ImageRef, data: base64}` |
| `session.updateQueue` | `{sessionId, itemId, action:edit\|remove\|steer}` | `{accepted:true}` |
| `session.cancel` | `{sessionId}` | `{accepted:true}` |

**SessionSummary**：`{sessionId, updatedAt, running, blank, parentSessionId?, origin?, cwd?, agentPreset?, projections?}`
**ModelSelection**：`{provider, model, reasoningEffort?}`
**SessionHistory**：`{events:[{event:SessionEvent, view?:ToolEventView}], hasMore, projections?}`
**PromptPart**（判别 `type`）：`{type:"text", text}` 或 `{type:"image", mediaType, data, name?}`
**SessionEvent**（宽 envelope）：`{type, seq, time, data:unknown, sourceEventSeqs?, surfaceOp?, ignorable?}`

### 3.2 `subagents`（子代理）
| method | 请求 payload | 响应 value |
|---|---|---|
| `subagent.list` | `{parentSessionId}` | `{entries:[Child\|Diagnostic], parentAvailable}` |
| `subagent.history` | `{parentSessionId, childSessionId, mode, beforeSeq?, maxMessages?}` | 同 `session.history` |
| `subagent.prompt` | `{parentSessionId, childSessionId, mode:"continuable", content, clientTimeZone?}` | `{messageId}` |
| `subagent.interrupt` | `{parentSessionId, childSessionId, mode:"continuable"}` | `{accepted:true}` |

**ListEntry**（判别 `kind`）：child：`{kind:"child", id, mode:one-shot\|continuable, activity:running\|inactive, hasChildren, label?}`；diagnostic：`{kind:"diagnostic", id, reason:corrupt\|unsupported\|unavailable}`

### 3.3 `host`
| method | 请求 payload | 响应 value |
|---|---|---|
| `host.describe` | `{}` | `{version, cwd, provider?, model?, attachedSessions, home, canOpenPath}` |
| `host.pickDirectory` | `{path?}` | `{path: string\|null}`（null=取消） |
| `host.listDirectory` | `{path}` | `{path, home, crumbs:[DirectoryEntry], entries:[DirectoryEntry], truncated}` |
| `host.createDirectory` | `{path, name}`（name 单段非空白） | `{path}` |
| `host.openPath` | `{path}` | `{opened:true}` |

### 3.4 `workspace`
| method | 请求 payload | 响应 value |
|---|---|---|
| `workspace.list` | `{}` | `{items:[WorkspaceView], archivedSessionIds}` |
| `workspace.create` | `{path}` | `{workspace:WorkspaceView, created}` |
| `workspace.rename` | `{workspaceId, title}`（非空白） | `{workspace}` |
| `workspace.delete` | `{workspaceId}` | `{deleted:true}` |
| `workspace.insertBefore` | `{workspaceId, beforeWorkspaceId?}` | `{workspaceIds:[…]}` |
| `workspace.insertSessionBefore` | `{workspaceId, sessionId, beforeSessionId?}` | `{workspace}` |
| `workspace.archiveSession` | `{sessionId}` | `{archivedSessionIds}` |

**WorkspaceView**：`{workspaceId, path, title, sessionIds:[…], createdAt, updatedAt}`

### 3.5 `skills`
- `skill.list`：`{sessionId}` → `{skills:[{name, description, whenToUse?, modelInvocable}]}`

### 3.6 `agentPresets`
- `agentPreset.list`：`{}` → `{presets:[{id, trust:system\|user, isDefault, name?, description?, broken?}], authorable, hasDocument}`
- `agentPreset.select`：`{sessionId, agentPreset}` → `{agentPreset}`
- `agentPreset.read`：`{agentPreset}` → `{agentPreset, trust, content, name?, description?}`
- `agentPreset.copy`：`{from, agentPreset, name?}` → `{agentPreset}`
- `agentPreset.openDocument`：`{agentPreset}` → `{opened:true}` 或 `{opened:false, path}`
- `agentPreset.remove`：`{agentPreset}` → `{}`

### 3.7 `goals`（变异为主，状态走 session projection `goal`）
- `goal.create`：`{sessionId, objective, maxGoalRounds?}` → `{ref:{id, revision}}`
- `goal.edit`：`{sessionId, ref, objective?, maxGoalRounds?}` → `{ref}`
- `goal.pause/resume/complete`：`{sessionId, ref}` → `{ref}`
- `goal.clear`：`{sessionId, ref}` → `{cleared:true}`

### 3.8 `settings`（配置平面，仅回环）
- `settings.describe`：`{}` → `{writable, hasDocument, namespaces:[SettingsNamespaceView]}`
- `settings.openDocument`：`{}` → `{opened:true}`
- `settings.update`：`{ns, patch, expectedRevision?}` → NamespaceView
- `settings.replace`：`{ns, section, expectedRevision?}` → NamespaceView
- `settings.mutate`：`{ns, ops:[{op:set\|unset, path, value?}], expectedRevision?}` → NamespaceView

**SettingsNamespaceView**：`{ns, schema, value, base?, user?, applies:live\|restart, secrets:[{path,set}], revision}`

### 3.9 `credentials`（配置平面，仅回环）
- `credentials.describe`：`{refs:[…]}`（POSIX 名 `[A-Za-z_][A-Za-z0-9_]*`）→ `{credentials:{ref:{configured, source?, writable}}}`
- `credentials.set`：`{ref, value}` → `{}`
- `credentials.unset`：`{ref}` → `{}`

### 3.10 `llm`
- `llm.providers`：`{}` → `{providers:[{provider, displayName, settingsNs, settingsPath, active, declared?}]}`
- `llm.models`：`{}` → `{groups:[ProviderGroup], failures:[…]}`
- `llm.discoverModels`：`{settingsNs, provider?, baseURL?, api?, apiKey?}` → `{models:[{id, name?, contextWindow?, maxTokens?}]}`

---

## 4. 事件帧（WebSocket downlink payload）

### 4.1 MuxFrame（`/api/events.mux`，判别 `type`）
| type | 字段 |
|---|---|
| `session/event` | `{sessionId, event:SessionEvent, view?:ToolEventView}` |
| `session/subscribed` | `{sessionId, lastSeq}` |
| `approval/requested` | `{sessionId, approvalId, toolName, callId?, reason?}` |
| `approval/resolved` | `{sessionId, approvalId, outcome: allowed-once\|rejected\|cancelled\|unavailable}` |
| `question/requested` | `{sessionId, questions:[AskUserQuestionItem]}` |
| `question/resolved` | `{sessionId, questionRpcId, outcome: answered\|cancelled}` |
| `session/queue` | `{sessionId, items:[{id, placement: queued\|steering\|context, message:Message}]}` |
| `session/jobs` | `{sessionId, jobs:[TaskView]}` |
| `session/projection` | `{sessionId, key, value, seq}` |
| `stream/error` | `{error: RpcError}` |

**AskUserQuestionItem**：`{id, question, header?, detail?, options?:[{label, description?}], multiSelect?, intent?}`
**Message**：`{id, role:system\|user\|assistant, content:[ContentBlock], source:{kind}}`
**ContentBlock**：`{type:string, …}`（宽松：保留 type 判别字段，其余宽）
**TaskView**：`{id, kind, label, status:running\|stopping\|completed\|killed\|failed, detail?, startedAt, finishedAt?}`

### 4.2 HostFrame（`/api/events.host`，判别 `type`）
| type | 字段 |
|---|---|
| `host/session-added` | `{sessionId, blank, parentSessionId?, origin?, cwd?, agentPreset?}` |
| `host/session-removed` | `{sessionId}` |
| `host/session-status` | `{sessionId, running}` |
| `host/agent-error` | `{sessionId, message}` |
| `host/workspace-changed` | `{workspace:WorkspaceView}` |
| `host/workspace-removed` | `{workspaceId}` |
| `host/workspace-order-changed` | `{workspaceIds:[…]}` |
| `host/archived-sessions-changed` | `{archivedSessionIds:[…]}` |
| `host/remote-event` | `{event, args:[…]}` |
| `stream/error` | `{error: RpcError}` |

### 4.3 `approval/resolved` → 客户端回执（`client-response`）
批准 `approval/requested`：客户端 POST `server-request` 对应的 `client-response` 到 `/api/respond`，payload 形如 `{sessionId, approvalId, outcome: allowed-once\|rejected}`（见 approvals domain）。

---

## 5. 给 Kotlin 客户端的实现要点

1. **基础**：OkHttp（HTTP POST JSON + WebSocket）。
2. **`/api` 前缀**：所有 unary 请求 `POST https?://<host>:3080/api/<method>`，body = `client-request` JSON。
3. **rpcId**：每次请求生成 UUID，校验回包同 `rpcId`（否则丢弃/报错）。
4. **envelope 解析**：外层 `type` 四象限；`result.ok ? value : error`。value 按 §3 的 schema 解析成 Kotlin 数据类。
5. **两个 WebSocket**：`GET/upgrade` 到 `/api/events.mux`、`/api/events.host`（downlink-only，客户端不发数据）。每帧 JSON 解析为 `server-request` 后按 `payload.type` 分发到 MuxFrame/HostFrame。
6. **就绪握手**：连接后并行开两流 + `host.describe`，全成才 `connected`；失败按指数退避重连。
7. **浏览器身份**：WebSocket 握手带 `Origin`（`sec-fetch-site`）。非浏览器客户端（如后台 `DshClient`）需以回环 authority、LAN IP 字面量或 `trustedHosts` 授权通过 `/api` 信任栅栏。**配置平面接口远程必 403** —— 除非走 SSH 端口转发。
8. **crypto.randomUUID**：WebView 加载局域网明文 HTTP 时需要浏览器 polyfill；非浏览器协议客户端不需要。
9. **附件**：`session.attachment` 返回 base64 `data`；`session.prompt` 的 image part 传 base64。需注意服务端 `maxRequestBodyBytes`（默认 300 MiB，含图片 base64 膨胀后）。
10. **会话重建**：`session.history` 返回 events + projections；客户端用 `SessionEvent.surfaceOp`（append / replace）重建 message surface。事件有 `seq` 连续性校验。

---

## 6. 方法→响应 value 的一览（UNARY_VALUE_SCHEMAS 全集）

`session.list/search/create/history/models/selectModel/rename/fork/prompt/attachment/updateQueue/cancel`，
`subagent.list/history/prompt/interrupt`，`host.describe/pickDirectory/listDirectory/createDirectory/openPath`，
`workspace.list/create/rename/delete/insertBefore/insertSessionBefore/archiveSession`，
`skill.list`，`agentPreset.list/select/read/copy/openDocument/remove`，
`goal.create/edit/pause/resume/complete/clear`，
`settings.describe/openDocument/update/replace/mutate`，
`credentials.describe/set/unset`，
`llm.providers/models/discoverModels`。

对应 value schema 见 §3 各表。

---

*文档由对 `dsh-client-connection` 编译产物的逐段逆向整理；字段与判别联合直接取自 zod schema 定义。*
