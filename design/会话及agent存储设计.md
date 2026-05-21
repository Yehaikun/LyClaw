# 会话及Agent存储设计

---

## 1. 设计目标

- 用户可通过前端动态创建和管理 Agent
- Agent 可递归 spawn 子 Agent，子 Agent 分**临时**（任务完成后自动销毁）和**永久**（长期保留）两种生命周期
- 会话数据 append-only 持久化，服务重启后完整恢复
- 写入不阻塞 ReAct 循环的 SSE 响应延迟
- 同会话内消息写入顺序严格保证
- 子 Agent 协作对话独立存储、可追溯调用链
- 超长会话支持分段加载，前端无需一次性拉取全部消息

---

## 2. 设计范围

**包含**：

- Agent 注册与发现（动态创建，非注解扫描）
- 临时/永久 Agent 的完整生命周期
- 会话持久化（JSONL + SQLite 双轨）
- JSONL 行格式定义
- 异步写入模型
- 多 Agent 协作存储与调用链追溯
- 超长会话分段加载
- 后端 HTTP API

**不包含**：

- 记忆系统的持久化（三层记忆架构留待后续设计）
- 跨会话全文搜索
- Redis 缓存层（当前规模不需要）
- PostgreSQL 迁移（保留为未来演进方向，SQLite 通过标准 SQL + DAO 接口预留迁移路径）

---

## 3. 核心设计决策

### 3.1 一 Session 一 JSONL 文件

| 对比维度 | 一 Session 一文件 | 多 Session 合入一个文件 |
|----------|-------------------|-------------------------|
| 加载指定会话 | O(1) | O(N) 扫描 |
| 并发写入 | 无冲突 | 需要文件锁 |
| 删除会话 | rm 一个文件 | 全量读→过滤→写回 |
| 数据损坏影响 | 单文件隔离 | 波及全部 |

### 3.2 子 Agent 独立存储

Agent A 调用 `delegate_to_agent` spawn Agent B 时，B 的完整对话存为独立 JSONL，归属 B 的目录下。A 的 JSONL 中仅记录 `subagentSessionId` 引用。

**理由**：
- 子 Agent 的完整推理链可独立回放调试
- `parentSessionId` → `parentAgentId` 形成树状调用链
- 子 Agent 会话与普通会话存储格式完全一致，无分支逻辑

### 3.3 逐行追加，异步写入

ReAct 每轮结束后，消息行投递到 per-session 阻塞队列即返回。SSE 事件发出视为响应完成，磁盘写入在后台异步完成。

**顺序保证**：每 session 一个队列，单线程消费 → FIFO 天然保证。

### 3.4 超长会话分段加载

默认返回最近 50 条消息。前端滚动到顶部时按 messageIndex 偏移量翻页加载更早的消息。

---

## 4. SQLite 数据库设计

SQLite 是 Agent 和 Session 的**主注册中心**。JSONL 文件是消息数据的真实来源，SQLite 存元数据摘要。

### 4.1 数据库位置

```
{storageBasePath}/index/lyclaw.db
```

### 4.2 表结构

```sql
-- ============================================================
--  Agent 注册表
-- ============================================================
CREATE TABLE agents (
    agent_id           TEXT PRIMARY KEY,
    agent_name         TEXT NOT NULL,
    description        TEXT DEFAULT '',

    -- 生命周期：'permanent'（永久）| 'temporary'（临时）
    lifecycle          TEXT NOT NULL DEFAULT 'permanent',

    -- 创建来源：'user'（用户在页面创建）| '{parentAgentId}'（被其他Agent spawn）
    created_by         TEXT NOT NULL DEFAULT 'user',

    -- 临时Agent的父关系（永久Agent为NULL）
    parent_agent_id    TEXT,
    parent_session_id  TEXT,

    -- 模型与推理配置
    model              TEXT NOT NULL,
    provider           TEXT NOT NULL,
    thinking_level     TEXT DEFAULT 'medium',
    verbose_level      TEXT DEFAULT 'low',
    reasoning_level    TEXT DEFAULT 'medium',
    fast_mode          INTEGER DEFAULT 0,
    sandbox_level      TEXT DEFAULT 'PROCESS',

    -- 能力声明
    skills             TEXT DEFAULT '[]',       -- JSON array
    allow_agents       TEXT DEFAULT '["*"]',    -- JSON array, 可spawn的白名单

    -- 子Agent限制
    max_spawn_depth    INTEGER DEFAULT 1,
    max_children       INTEGER DEFAULT 5,

    -- 引导文件内容（从文件读入，启动时缓存）
    system_prompt      TEXT DEFAULT '',
    soul_prompt        TEXT DEFAULT '',
    identity_display_name TEXT DEFAULT '',

    -- 头像
    avatar_url         TEXT DEFAULT '',
    avatar_file_path   TEXT DEFAULT '',

    created_at         INTEGER NOT NULL,
    directory_path     TEXT NOT NULL,

    FOREIGN KEY (parent_agent_id) REFERENCES agents(agent_id)
);

-- ============================================================
--  Session 注册表
-- ============================================================
CREATE TABLE sessions (
    session_id          TEXT PRIMARY KEY,
    agent_id            TEXT NOT NULL,
    parent_session_id   TEXT,
    parent_agent_id     TEXT,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    first_msg_preview   TEXT DEFAULT '',       -- 首条用户消息前100字符
    message_count       INTEGER DEFAULT 0,
    tool_call_count     INTEGER DEFAULT 0,
    total_tokens        INTEGER DEFAULT 0,
    compaction_count    INTEGER DEFAULT 0,
    file_path           TEXT NOT NULL,         -- jsonl文件相对路径

    FOREIGN KEY (agent_id) REFERENCES agents(agent_id) ON DELETE CASCADE
);

-- ============================================================
--  审批表（工具执行前的用户确认）
-- ============================================================
CREATE TABLE approvals (
    approval_id    TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    agent_id       TEXT NOT NULL,
    tool_name      TEXT NOT NULL,
    tool_call_id   TEXT NOT NULL,
    arguments      TEXT DEFAULT '',       -- 工具参数 JSON
    status         TEXT DEFAULT 'pending', -- 'pending' | 'approved' | 'denied' | 'expired'
    requested_at   INTEGER NOT NULL,
    resolved_at    INTEGER,
    expires_at     INTEGER NOT NULL,      -- 超时自动拒绝
    resolved_by    TEXT DEFAULT '',       -- 'user' | 'timeout' | 'system'

    FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_approvals_session ON approvals(session_id);
CREATE INDEX idx_approvals_status   ON approvals(status);

-- ============================================================
--  索引
-- ============================================================
CREATE INDEX idx_agents_lifecycle  ON agents(lifecycle);
CREATE INDEX idx_agents_parent     ON agents(parent_agent_id);
CREATE INDEX idx_agents_created    ON agents(created_at DESC);

CREATE INDEX idx_sessions_agent    ON sessions(agent_id);
CREATE INDEX idx_sessions_updated  ON sessions(updated_at DESC);
CREATE INDEX idx_sessions_parent   ON sessions(parent_session_id);
```

### 4.3 Agent 路由集成

`AgentRouter`（Phase 3）采用 **YAML 静态绑定 + SQLite 动态校验** 的互补架构：

- **YAML 绑定** (`lyclaw.routing.bindings`) — 定义路由规则：哪个渠道/mention/acp 前缀匹配到哪个 agentId
- **SQLite agents 表** — 维护所有已注册 Agent 的元数据，路由解析时验证目标 agentId 是否确实存在

两者分工明确：
1. YAML 负责 **"谁处理什么"**（匹配条件 → agentId）
2. SQLite 负责 **"这个 Agent 是否存在"**（动态注册/过期检查）

**路由解析流程**：

1. `AgentRouter` 从 YAML 加载路由绑定（匹配规则），构建绑定表
2. 收到请求 → 按 specificity 匹配最合适的绑定 → 得到目标 agentId
3. **查询 SQLite agents 表** 验证该 agentId 是否存在且有效
4. 若目标 Agent 仅存在于 YAML 绑定但未在 SQLite 中注册（如临时 Agent 已过期）：
   - 记录 WARN 日志
   - 回退到默认 Agent（`routing.default-agent-id`）
5. 验证通过 → 正常路由

**运行时同步**：

- 创建 Agent（`POST /api/agents`）→ INSERT agents 表 → 若配置了 YAML routeBindings，写入对应 YAML 片段
- 删除 Agent（`DELETE /api/agents/{agentId}`）→ DELETE agents 表（级联删除 sessions）→ YAML 绑定需手动清理或标记失效
- 仅当存在显式渠道/角色匹配需求时，才需要在 Agent 配置中填写 `routeBindings`

### 4.4 Session 活跃缓存（SessionManager）

SQLite 存元数据，但在以下场景中频繁查 SQLite 会带来不必要的开销：

- `ContextPruningScheduler`（Phase 3）需要遍历所有活跃会话
- 启动恢复时需要快速判断哪些会话处于活跃状态
- 子 Agent spawn 时需要校验父 Agent 的活跃子 Agent 数量

因此维护一个内存缓存 `SessionManager`：

```
SessionManager
  ├── activeSessions: ConcurrentHashMap<String, SessionHandle>
  │     key = sessionId
  │     value = {sessionId, agentId, createdAt, messageCount, filePath}
  │
  ├── getActiveSessions(): Map<String, SessionHandle>   ← 供 ContextPruningScheduler 使用
  ├── register(sessionId, handle)                       ← 创建 session 时调用
  ├── unregister(sessionId)                             ← 删除 session 时调用
  └── getActiveCount(agentId): int                      ← 供 SubagentSpawner 校验子Agent上限
```

- 启动时从 SQLite sessions 表加载最近 7 天内活跃的 session 到缓存
- 超过 7 天未活跃的 session 不在缓存中，仅在用户显式请求时从 SQLite 懒加载
- 缓存是 SQLite 的只读镜像——写入始终先落 SQLite + jsonl，再更新缓存

### 4.5 与 JSONL 的关系

```
写入时:
  ReAct 轮次结束
    └── [异步队列]
          ├── ① append jsonl 行              ← 数据真实来源
          └── ② UPDATE sessions SET ...       ← 元数据摘要

查询时:
  列表/筛选 → SQLite（毫秒级）
  历史消息 → 直接读 jsonl（SQLite 只提供 file_path）
```

SQLite 可随时从 JSONL 全量重建——遍历所有 jsonl 文件，读首行和末行，重新 INSERT。

---

## 5. 目录结构

```
{storageBasePath}/
  │
  ├── agents/
  │   ├── coder/                              ← 永久Agent（用户创建）
  │   │   ├── agent.json                      ← 完整配置快照
  │   │   ├── AGENTS.md                       ← 系统提示增强
  │   │   ├── SOUL.md                         ← 人格与语气
  │   │   ├── BOOTSTRAP.md                    ← 一次性初始化
  │   │   ├── IDENTITY.md                     ← 身份描述
  │   │   ├── USER.md                         ← 用户偏好
  │   │   ├── HEARTBEAT.md                    ← 周期性自检
  │   │   └── sessions/
  │   │       ├── abc123.jsonl
  │   │       └── def456.jsonl
  │   │
  │   ├── reviewer/                           ← 永久Agent（用户创建）
  │   │   └── ...
  │   │
  │   └── tmp-a1b2c3d4/                       ← 临时Agent（spawn产生，任务完删除）
  │       ├── agent.json
  │       └── sessions/
  │           └── rev-001.jsonl
  │
  ├── workspaces/
  │   └── {workspaceName}/
  │       └── USER.md                         ← 项目级用户配置
  │
  └── index/
      └── lyclaw.db                            ← SQLite
```

临时和永久 Agent 目录结构**完全一致**，仅靠 `agent.json`（及 SQLite）中的 `lifecycle` 字段区分。

---

## 6. Agent 生命周期

### 6.1 创建永久 Agent

**入口**：用户在页面点击"创建 Agent"

**流程**：
1. 前端 `POST /api/agents`，提交名称、模型、提示词等配置
2. 后端生成 agentId → `INSERT INTO agents (lifecycle='permanent', created_by='user')`
3. 创建 `agents/{agentId}/` 目录
4. 写入 `agent.json`（完整配置快照）
5. 写入引导文件模板（AGENTS.md / SOUL.md / IDENTITY.md 等）
6. 返回 agentId，前端跳转到新 Agent 的对话页

**@Agent 注解的角色降级**：`@Agent` 注解仅用于开发/测试阶段注册默认 Agent。生产环境中，Agent 来源是 SQLite agents 表。启动时合并两个来源：注解注册的 Agent 如果 SQLite 中不存在则 INSERT；已存在则跳过。

### 6.2 创建临时 Agent（Spawn）

**入口**：Agent A 在 ReAct 循环中调用 `delegate_to_agent("reviewer", "审查代码")`

**流程**：
1. `SubagentSpawner` 生成 agentId
2. `INSERT INTO agents (lifecycle='temporary', created_by='{parentAgentId}', parent_agent_id='...', parent_session_id='...')`
3. 创建 `agents/{agentId}/` 目录 + `agent.json`（从父 Agent 继承配置，可覆盖 model/provider）
4. 执行子 Agent 的独立 ReAct 循环
5. 结果返回父 Agent

**生命周期参数**：子 Agent 的 `lifecycle` 由 spawn 时的参数决定，默认为 `'temporary'`。用户或父 Agent 可显式指定 `lifecycle='permanent'` 来保留子 Agent。

### 6.3 临时 Agent 清理

**触发时机**：子 Agent 的 ReAct 循环结束，结果返回父 Agent 后。

**清理流程**（级联递归）：

```
cleanupTemporaryAgent(agentId):
  1. 递归：查找所有 parent_agent_id = agentId 的子孙临时 Agent
     对每个子孙执行 cleanupTemporaryAgent(子孙)
  2. 删除本 Agent 的所有 session：
     - DELETE FROM sessions WHERE agent_id = agentId
     - rm 对应的 jsonl 文件
  3. DELETE FROM agents WHERE agent_id = agentId
  4. rm -rf agents/{agentId}/
```

**不清理的情况**：
- `lifecycle = 'permanent'` → 永久保留
- 临时 Agent spawn 的永久子 Agent → 保留，但 `parent_agent_id` 置 NULL

### 6.4 删除永久 Agent

**入口**：用户调用 `DELETE /api/agents/{agentId}`

**流程**：
1. 级联删除所有 session（jsonl + SQLite rows）
2. 递归删除所有子孙临时 Agent
3. 子孙永久 Agent 保留，`parent_agent_id` 置 NULL
4. 删除 agent 目录

---

## 7. Session 生命周期

### 7.1 创建

前端 `POST /api/chat/stream` 不携带 `sessionId` → 生成 8 字符 sessionId → `INSERT INTO sessions` → 创建 jsonl → 写入 `session_created` 行 → SSE 返回 sessionId。

### 7.2 活跃期间

每轮 ReAct 结束 → 构建消息 JSON 行 → 投递 per-session 队列 → 异步 append jsonl + UPDATE sessions 摘要字段。

### 7.3 恢复（服务重启）

启动时：从 SQLite sessions 表加载所有 session 摘要 → 重建内存活跃集合。历史消息懒加载（用户选择某会话时才读 jsonl）。

### 7.4 删除

用户 `DELETE /api/agents/{agentId}/sessions/{sessionId}` → 删除 jsonl 文件 + `DELETE FROM sessions`。子会话不级联删除，保留独立文件但 `parentSessionId` 指向不存在的会话。

### 7.5 清理策略

- 永久 Agent 的会话：不自动清理，用户手动删除
- 临时 Agent 的会话：随 Agent 清理时一并删除

### 7.6 心跳会话不持久化

Phase 4 `HeartbeatScheduler` 会为每个已注册 Agent 定期创建轻量级的"心跳会话"——仅加载 HEARTBEAT.md 作为上下文，执行一次自检提示，不产生工具调用，不参与 ReAct 循环。

心跳会话的特征：
- AgentContext 中 `heartbeatMode = true`
- `isolatedSession = true` 时，每次心跳使用独立的临时 sessionId
- 心跳输出通过事件总线投递，不写入会话历史

**存储层防护**：异步写入队列在投递消息行之前检查 `AgentContext` 的 `heartbeatMode` 标志。若为 true，跳过 jsonl 追加和 SQLite sessions 更新。心跳会话不产生持久化痕迹。

### 7.7 压缩持久化（Phase 3 CompactionStage 集成）

Phase 3 `CompactionEngine.compact()` 原地修改 `Session.messages` 列表——将中间轮次的旧消息替换为 LLM 生成的摘要消息。**压缩完成后，存储层需要感知这一变更。**

流程：
1. CompactionStage 调用 `compactionEngine.compact(session, ctx)`
2. `reconstructMessages()` 修改 `session.messages`（中间段 → 单条合成系统消息）
3. 存储层接收压缩结果 → 执行以下操作：
   - 追加一行 `compaction` 事件到 jsonl（记录被压缩的消息数、摘要长度、质量评分）
   - 更新 SQLite sessions 行：`compaction_count++`，更新 `message_count`（压缩后消息数减少），更新 `total_tokens`
   - 更新 SessionManager 缓存中的 session 摘要

关键：**jsonl 中旧的消息行不会物理删除**——它们保留在文件中，但后续加载时根据 compaction 事件行得知某段消息已被摘要替代。这保持了 append-only 语义。

### 7.8 工具结果 TTL 修剪（Phase 3 ContextPruner 集成）

Phase 3 `ContextPruner` 对过期的工具结果内容进行修剪——将超过 TTL （默认 30 分钟）的工具结果的 `content` 替换为占位符 `[earlier output trimmed for space]`。

**jsonl 中的处理**：与压缩相同，**不物理删除旧行**。修剪后追加一行 compaction 事件到 jsonl，标记哪些 messageIndex 范围的内容已被修剪。

**SQLite 中的处理**：`tool_call_count` 不变（工具调用仍发生过），但 `total_tokens` 更新为修剪后的值。

**为什么不做物理删除**：jsonl 是 append-only 设计——删除中间某行意味着重写整个文件，破坏写入性能。逻辑标记（compaction 事件 + 占位符）达到同样效果，且零写入开销。

---

## 8. JSONL 行格式

每行自包含，行间 `\n` 分隔。

### 8.1 session_created（jsonl 首行）

```json
{
  "type": "session_created",
  "sessionId": "abc12345",
  "agentId": "coder",
  "agentName": "代码助手",
  "parentSessionId": null,
  "parentAgentId": null,
  "workspaceDir": "/home/lyjew/projects/myapp",
  "systemPrompt": "你是一个专业的代码助手...",
  "thinkingLevel": "medium",
  "verboseLevel": "low",
  "reasoningLevel": "medium",
  "fastMode": false,
  "sandboxLevel": "PROCESS",
  "tools": [
    {"name": "read_file", "description": "读取文件内容"},
    {"name": "delegate_to_agent", "description": "委托任务"}
  ],
  "timestamp": 1716300000000
}
```

### 8.2 消息事件

用户消息：
```json
{"type":"message","role":"user","content":"帮我查bug","timestamp":1716300000000,"messageIndex":0}
```

助手回复：
```json
{"type":"message","role":"assistant","content":"我来读取...","thinking":"需要先看报错","model":"deepseek-v4-flash","toolCalls":[{"id":"call_001","name":"read_file","description":"读取文件","arguments":"{\"path\":\"/src/App.java\"}"}],"usage":{"promptTokens":1200,"completionTokens":300,"totalTokens":1500},"thinkingBudget":4096,"timestamp":1716300010000,"messageIndex":1}
```

工具结果：
```json
{"type":"tool_result","role":"tool","toolName":"read_file","toolCallId":"call_001","content":"public class App {...}","success":true,"durationMs":120,"timestamp":1716300020000,"messageIndex":2}
```

子Agent委托结果（仅父Agent jsonl）：
```json
{"type":"tool_result","role":"tool","toolName":"delegate_to_agent","toolCallId":"call_005","content":"发现3个问题...","subagentSessionId":"rev-001","subagentAgentId":"reviewer","success":true,"durationMs":35000,"timestamp":1716300100000,"messageIndex":14}
```

压缩事件（Phase 3）：
```json
{"type":"compaction","messagesCompacted":23,"summaryTokens":1500,"qualityScore":0.92,"timestamp":1716305000000}
```

### 8.3 字段总览

| 字段 | 适用于 | 类型 | 说明 |
|------|--------|------|------|
| `type` | 所有 | string | `session_created` / `message` / `tool_result` / `compaction` |
| `sessionId` | session_created | string | 会话 ID |
| `agentId` | session_created | string | 归属 Agent ID |
| `agentName` | session_created | string | Agent 显示名 |
| `parentSessionId` | session_created | string\|null | 父会话 ID |
| `parentAgentId` | session_created | string\|null | 父 Agent ID |
| `workspaceDir` | session_created | string | 工作区路径 |
| `systemPrompt` | session_created | string | 完整系统提示词 |
| `thinkingLevel` | session_created | string | `low` / `medium` / `high` |
| `verboseLevel` | session_created | string | `low` / `medium` / `high` |
| `reasoningLevel` | session_created | string | `low` / `medium` / `high` |
| `fastMode` | session_created | bool | 快速模式 |
| `sandboxLevel` | session_created | string | `DIRECT` / `SANDBOX` / `PROCESS` |
| `tools` | session_created | array | 可用工具定义列表 |
| `role` | message/tool_result | string | `user` / `assistant` / `system` / `tool` |
| `content` | message/tool_result | string | 消息正文 |
| `thinking` | assistant | string\|null | 推理模型思考链 |
| `model` | assistant | string\|null | 生成模型 |
| `toolCalls` | assistant | array\|null | 工具调用列表，每项含 `id`/`name`/`description`/`arguments` |
| `usage` | assistant | object\|null | `promptTokens`/`completionTokens`/`totalTokens` |
| `thinkingBudget` | assistant | int\|null | 思考链 token 预算 |
| `toolCallId` | tool_result | string | 关联的工具调用 ID |
| `toolName` | tool_result | string | 工具名称 |
| `subagentSessionId` | tool_result | string\|null | 子 Agent 会话 ID |
| `subagentAgentId` | tool_result | string\|null | 子 Agent ID |
| `success` | tool_result | bool | 工具执行是否成功 |
| `durationMs` | tool_result | long | 工具执行耗时 |
| `messageIndex` | message/tool_result | int | 会话内递增序号 |
| `timestamp` | 所有 | long | Unix 毫秒时间戳 |

---

## 9. 异步写入模型

```
Session A (abc123)       Session B (def456)       Session C (xyz789)
  │ ReAct轮次               │                        │
  ├→ JSON行 → 队列A        ├→ JSON行 → 队列B         ├→ JSON行 → 队列C
  │ (立即返回)              │                        │
  └─ 继续下一轮             └─ ...                   └─ ...

  队列A ──→ 线程1 ──→ append abc123.jsonl + UPDATE sessions
  队列B ──→ 线程2 ──→ append def456.jsonl + UPDATE sessions
  队列C ──→ 线程3 ──→ append xyz789.jsonl + UPDATE sessions
```

每个 Session 一个 BlockingQueue，单线程消费保证 FIFO。不同 Session 并行无冲突。

**失败处理**：
- 写入失败 → 重试缓冲区 → 下次同 session 写入时优先 flush
- 连续 3 次失败 → 标记降级 → SSE 通知前端"持久化异常"

---

## 10. 多 Agent 协作存储

### 10.1 调用链

```
Agent "coder" (session: abc123)
  ├─ 第5轮: delegate_to_agent("reviewer", "审查代码")
  │     │
  │     └→ Agent "reviewer" (session: rev-001, 临时)
  │          独立的 ReAct 循环，独立 jsonl
  │          session_created.parentSessionId = "abc123"
  │
  ├─ 收到审查结果，继续
  │
  └─ 第8轮: delegate_to_agent("tester", "写测试")
        └→ Agent "tester" (session: tst-001, 临时)
```

### 10.2 文件分布

```
agents/coder/sessions/abc123.jsonl       ← tool_result含subagentSessionId
agents/reviewer/sessions/rev-001.jsonl   ← session_created.parentSessionId="abc123"
agents/tester/sessions/tst-001.jsonl     ← session_created.parentSessionId="abc123"
```

### 10.3 关键规则

- 子 Agent 的 `session_created` 记录 `parentSessionId` + `parentAgentId`
- 父 Agent 的 `tool_result` 记录 `subagentSessionId` + `subagentAgentId`
- 子 Agent 可继续 spawn 孙子，深度由 `maxSpawnDepth` 控制
- 临时 Agent 完成任务后级联清理整个调用子树

---

## 11. 并发模型

| 场景 | 处理 |
|------|------|
| 同 Session 多轮追加 | ReAct 天然串行 + 单队列单线程，双保险 |
| 不同 Session 同时写 | 不同文件 + 不同队列线程，并行 |
| 父子 Agent 同时写 | 不同文件，并行 |
| 写 vs 读（加载历史） | 读只到当前已持久化的最大 messageIndex |
| SQLite 并发写 | SQLite WAL 模式，支持多读单写 |

---

## 12. 前端交互流

```
首次打开
  │
  ├─ GET /api/agents
  │     → Agent 列表，含 defaultAgent 标记
  │     → 自动选中默认 Agent（或上次使用的 Agent）
  │
  ├─ GET /api/agents/{agentId}/sessions
  │     → 该 Agent 的历史会话列表
  │
  ├─ 用户操作：
  │   ├─ 点已有 session → GET .../messages?offset=-1&limit=50 → 恢复
  │   ├─ 新建对话       → POST /api/chat/stream（不传sessionId）
  │   ├─ 新建 Agent     → POST /api/agents → 跳转新Agent页
  │   └─ 删除 Agent     → DELETE /api/agents/{agentId}
  │
  └─ 对话中发消息 → POST /api/chat/stream（带sessionId）
```

---

## 13. HTTP API

### 13.1 Agent 列表

```http
GET /api/agents
```

响应：
```json
{
  "agents": [
    {
      "agentId": "coder",
      "agentName": "代码助手",
      "description": "帮助编写和审查代码",
      "lifecycle": "permanent",
      "defaultAgent": true,
      "model": "deepseek-v4-flash",
      "skills": ["java", "python"],
      "sessionCount": 23,
      "lastActiveAt": 1716304800000
    }
  ],
  "defaultAgentId": "coder"
}
```

### 13.2 Agent 详情

```http
GET /api/agents/{agentId}
```

返回 agent.json 完整内容 + `sessionCount` + `lastActiveAt`。

### 13.3 创建 Agent

```http
POST /api/agents
```

请求体：
```json
{
  "agentName": "代码助手",
  "description": "帮助编写和审查代码",
  "model": "deepseek-v4-flash",
  "provider": "deepseek",
  "thinkingLevel": "medium",
  "verboseLevel": "low",
  "skills": ["java", "python"],
  "allowAgents": ["reviewer", "tester"],
  "sandboxLevel": "PROCESS",
  "systemPrompt": "你是一个专业的代码助手...",
  "soulPrompt": "耐心、喜欢用代码示例回答",
  "avatarUrl": "https://..."
}
```

响应：
```json
{"agentId":"a1b2c3d4","agentName":"代码助手","createdAt":1716300000000}
```

### 13.4 更新 Agent

```http
PUT /api/agents/{agentId}
```

请求体同创建。更新 agent.json + SQLite agents 行 + 引导文件。

### 13.5 删除 Agent

```http
DELETE /api/agents/{agentId}
```

级联删除所有 session + 子孙临时 Agent。子孙永久 Agent 保留但断开 parent 关系。

### 13.6 Agent 的会话列表

```http
GET /api/agents/{agentId}/sessions
```

响应：
```json
{
  "agentId": "coder",
  "sessions": [
    {
      "sessionId": "abc123",
      "createdAt": 1716300000000,
      "updatedAt": 1716304800000,
      "firstMessagePreview": "帮我修个bug...",
      "messageCount": 52,
      "toolCallCount": 18,
      "totalTokens": 45000,
      "hasParent": false,
      "parentSessionId": null,
      "parentAgentId": null,
      "childSessionIds": ["rev-001"],
      "childCount": 1
    }
  ]
}
```

### 13.7 会话历史消息（分页）

```http
GET /api/agents/{agentId}/sessions/{sessionId}/messages?offset=-1&limit=50
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `offset` | int | -1 | `-1`=最新N条；`0`=最早开始；正数=从指定 messageIndex |
| `limit` | int | 50 | 最大 200 |

响应：
```json
{
  "sessionId": "abc123",
  "agentId": "coder",
  "totalMessageCount": 230,
  "returnedRange": [180, 229],
  "hasMore": true,
  "nextOffset": 180,
  "messages": [
    {"type":"message","role":"user","content":"...","messageIndex":180,"timestamp":1716350000000}
  ]
}
```

### 13.8 删除会话

```http
DELETE /api/agents/{agentId}/sessions/{sessionId}
```

仅删除该会话，子会话不级联。

### 13.9 子会话列表

```http
GET /api/agents/{agentId}/sessions/{sessionId}/children
```

### 13.10 流式聊天

```http
POST /api/chat/stream
```

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `sessionId` | body | string | 否 | 不传创建新会话 |
| `agentId` | body | string | 否 | 不传用默认 Agent |
| `message` | body | string | 是 | 用户消息 |

SSE 事件：

```
event: session_created        → 新会话时，data: {"sessionId":"abc123","agentId":"coder"}
event: message                → 助手回复流
event: tool_call_start        → data: {"toolName":"read_file","toolCallId":"call_001"}
event: tool_call_result       → data: {"toolCallId":"call_001","success":true,"durationMs":120}
event: subagent_spawning       → data: {"subagentSessionId":"rev-001","agentId":"reviewer","task":"..."}
event: subagent_ended     → data: {"subagentSessionId":"rev-001","success":true}
```

---

## 14. 写入流程总结

```
POST /api/chat/stream {agentId?, sessionId?, message}
  │
  ├─ 无 sessionId → 生成 → INSERT sessions → 创建 jsonl → 写 session_created
  └─ 有 sessionId → SQLite 查 file_path → 读 jsonl 历史 → 注入 AgentContext
       │
       ▼
  Pipeline: ContextBuild → Plan → Respond (ReAct) → Reflection → Compaction → Metrics
       │
       └─ 每轮 ReAct 结束 → [异步队列] → append jsonl + UPDATE sessions
```

---

## 15. 实施顺序

| 步骤 | 内容 | 依赖 |
|------|------|------|
| 1 | 初始化 SQLite（建表 + WAL 模式 + 迁移框架） | 无 |
| 2 | 实现 Agent CRUD（注册/查询/更新/删除 + agent.json 读写 + 引导文件模板） | 1 |
| 3 | 异步写入基础设施（per-session BlockingQueue + 线程池 + FileBackend append） | 无 |
| 4 | JSONL 行序列化/反序列化（含全部字段） | 无 |
| 5 | ChatController 接入 session 创建/续接（SQLite + jsonl） | 1、3、4 |
| 6 | AgentInvocationHandler 接入异步写入 | 3、4 |
| 7 | SubagentSpawner 接入子 Agent 独立存储（SQLite insert + parent 关系） | 1、3、4 |
| 8 | 临时 Agent 级联清理（递归删除 + 文件清理） | 2、7 |
| 9 | Agent 列表 + 会话列表 API | 1、2 |
| 10 | 会话历史分段加载 API | 4 |
| 11 | 启动恢复（SQLite 加载摘要 + 懒加载历史） | 1、4 |
| 12 | Phase 3 CompactionStage + ContextPruner 接入 | 6 |
