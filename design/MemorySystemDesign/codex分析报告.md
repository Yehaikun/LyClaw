# Codex 项目深度分析报告

> 分析日期: 2026-05-23
> 分析对象: `/tmp/agent-research/codex-main/`
> 分析范围: 记忆系统、Agent编排、Agent实现

---

## 一、记忆系统如何做

### 1.1 是否存在记忆系统

**Codex 拥有一套完整的、两阶段（Phase 1 + Phase 2）记忆系统。** 该系统是精心设计的、高度工程化的，从代码规模上看非常庞大。

关键证据：

- 记忆系统的代码分散在多个 crate 中，核心文件包括：
  - `/tmp/agent-research/codex-main/codex-rs/memories/write/src/lib.rs` — 记忆写路径主入口
  - `/tmp/agent-research/codex-main/codex-rs/memories/read/src/lib.rs` — 记忆读路径主入口
  - `/tmp/agent-research/codex-main/codex-rs/memories/mcp/src/lib.rs` — 通过 MCP 协议暴露记忆
  - `/tmp/agent-research/codex-main/codex-rs/ext/memories/src/lib.rs` — 记忆扩展
  - `/tmp/agent-research/codex-main/codex-rs/state/src/model/memories.rs` — 记忆持久化数据模型
  - `/tmp/agent-research/codex-main/codex-rs/state/src/runtime/memories.rs` — 记忆状态运行时

### 1.2 记忆架构

**架构：文件系统 + SQLite 双存储，两阶段 LLM 管道**

流程图：

```
Rollout (会话日志)
    |
    v
Phase 1 (提取) - 使用 gpt-5.4-mini
    |-- 读取 rollout JSONL 文件
    |-- 为每个线程提取 raw_memory + rollout_summary
    |-- 结果存入 SQLite 数据库
    |-- 并发因子: 8 (CONCURRENCY_LIMIT)
    |
    v
Phase 2 (整合) - 使用 gpt-5.4 (默认)
    |-- 从 SQLite 加载 Stage1 输出
    |-- 同步到文件系统 (raw_memories.md + rollout_summaries/*)
    |-- 使用 Git 基线检测变更
    |-- 生成 workspace diff
    |-- 生成最终 MEMORY.md + memory_summary.md + skills/
    |
    v
读取时
    |-- memory_summary.md 注入到系统提示词 (<=2500 token)
    |-- Agent 可通过文件工具 grep/search MEMORY.md
    |-- 也可通过 MCP 工具 list/read/search 记忆
```

文件引用：
- Phase 1 入口: `/tmp/agent-research/codex-main/codex-rs/memories/write/src/phase1.rs` 第 70 行 `pub async fn run(context: Arc<MemoryStartupContext>, config: Arc<Config>)`
- Phase 2 入口: `/tmp/agent-research/codex-main/codex-rs/memories/write/src/phase2.rs` 第 45 行 `pub async fn run(context: Arc<MemoryStartupContext>, config: Arc<Config>)`
- 启动入口: `/tmp/agent-research/codex-main/codex-rs/memories/write/src/start.rs` 第 22 行 `pub fn start_memories_startup_task`

**Phase 1 模型**: gpt-5.4-mini, reasoning_effort=Low (`/tmp/agent-research/codex-main/codex-rs/memories/write/src/lib.rs` 第 79-80 行)

**Phase 2 模型**: gpt-5.4 (默认), reasoning_effort=Medium (`/tmp/agent-research/codex-main/codex-rs/memories/write/src/lib.rs` 第 104-106 行)

**Phase 2 运行模式**:
- INIT 模式：首次创建 MEMORY.md、memory_summary.md 和 skills/
- INCREMENTAL UPDATE 模式：增量更新已有记忆

详见 `/tmp/agent-research/codex-main/codex-rs/memories/write/templates/memories/consolidation.md`。

### 1.3 记忆的数据模型

#### 核心数据结构

**Stage1Output** (Phase 1 产出，持久化到 SQLite)

文件路径: `/tmp/agent-research/codex-main/codex-rs/state/src/model/memories.rs` 第 13-23 行

```rust
pub struct Stage1Output {
    pub thread_id: ThreadId,       // 线程UUID
    pub rollout_path: PathBuf,     // rollout文件路径
    pub source_updated_at: DateTime<Utc>,  // 源更新时间
    pub raw_memory: String,        // 原始记忆 (markdown)
    pub rollout_summary: String,   // rollout摘要
    pub rollout_slug: Option<String>,  // 文件命名slug
    pub cwd: PathBuf,              // 工作目录
    pub git_branch: Option<String>, // git分支
    pub generated_at: DateTime<Utc>,  // 生成时间
}
```

**ThreadMetadata** (线程元数据，也存储在 SQLite 中)

文件路径: `/tmp/agent-research/codex-main/codex-rs/state/src/model/thread_metadata.rs` 第 61-110 行

```rust
pub struct ThreadMetadata {
    pub id: ThreadId,
    pub rollout_path: PathBuf,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub source: String,           // 会话来源 (cli/vscode/...)
    pub thread_source: Option<ThreadSource>,
    pub agent_nickname: Option<String>,  // sub-agent 随机昵称
    pub agent_role: Option<String>,      // agent角色
    pub agent_path: Option<String>,      // agent路径
    pub model_provider: String,
    pub model: Option<String>,
    pub reasoning_effort: Option<ReasoningEffort>,
    pub cwd: PathBuf,
    pub cli_version: String,
    pub title: String,
    pub preview: Option<String>,
    pub sandbox_policy: String,
    pub approval_mode: String,
    pub tokens_used: i64,
    pub first_user_message: Option<String>,
    pub archived_at: Option<DateTime<Utc>>,
    pub git_sha: Option<String>,
    pub git_branch: Option<String>,
    pub git_origin_url: Option<String>,
}
```

#### 文件系统产物

记忆文件存放在 `<codex_home>/memories/` 下:

1. **MEMORY.md** — 手册条目，可被 grep 搜索
2. **memory_summary.md** — 概要索引，总是注入到系统提示词中。第一行必须是 `v1`
3. **raw_memories.md** — 临时文件，Phase 1 产出的原始记忆合并
4. **rollout_summaries/<slug>.md** — 每个 rollout 的摘要
5. **skills/<skill-name>/SKILL.md** — 可复用的流程技能
6. **extensions/<name>/instructions.md** — 记忆扩展的指导文件

文件路径定义: `/tmp/agent-research/codex-main/codex-rs/memories/write/src/lib.rs` 第 35-47 行

```
mod artifacts {
    pub(super) const EXTENSIONS_SUBDIR: &str = "extensions";
    pub(super) const ROLLOUT_SUMMARIES_SUBDIR: &str = "rollout_summaries";
    pub(super) const RAW_MEMORIES_FILENAME: &str = "raw_memories.md";
}
```

### 1.4 记忆的生命周期

#### 写入时机

1. **触发条件** (`/tmp/agent-research/codex-main/codex-rs/memories/write/src/start.rs` 第 22-35 行):
   - 非 ephemeral (临时) 会话 (`!config.ephemeral`)
   - 启用了 MemoryTool 功能 (`config.features.enabled(Feature::MemoryTool)`)
   - 非 sub-agent 会话 (`!source.is_non_root_agent()`)
   - **仅 root 会话触发记忆生成**

2. **启动时异步执行** (`start.rs` 第 51 行 `tokio::spawn`):
   - 先执行清理 (prune) > Phase 1 > Phase 2
   - 在检查 API 速率限制通过后才开始

3. **Phase 1 任务分配** (`phase1.rs`):
   - 从 SQLite 中筛选候选 rollouts (最多 `max_rollouts_per_startup` 个)
   - 使用租赁锁 (lease lock) 防止重复处理
   - 并发处理 (最多 8 个并行)

4. **Phase 2 任务** (`phase2.rs`):
   - 需要全局锁 (global lock)
   - 支持 cooldown (冷却期)
   - 支持 heartbeat 心跳续租

#### 检索时机

1. **系统提示词注入**: `memory_summary.md` 在每次对话开始时截断到 2500 token 后注入到开发者指令中
   - 代码: `/tmp/agent-research/codex-main/codex-rs/memories/read/src/prompts.rs` 第 29-52 行
   - 限制常量: `MEMORY_TOOL_DEVELOPER_INSTRUCTIONS_SUMMARY_TOKEN_LIMIT: usize = 2_500`

2. **Agent 主动搜索**: Agent 可以被引导执行 "Quick memory pass"：
   - 读取 memory_summary.md 提取关键词
   - grep/搜索 MEMORY.md
   - 必要时打开 rollout_summaries/ 下的文件
   - 模板文件: `/tmp/agent-research/codex-main/codex-rs/memories/read/templates/memories/read_path.md`

3. **MCP 工具访问**: `/tmp/agent-research/codex-main/codex-rs/memories/mcp/src/backend.rs`
   - `list(path, cursor, max_results)` — 列出记忆文件
   - `read(path, line_offset, max_lines, max_tokens)` — 读取文件内容
   - `search(queries, match_mode, path, context_lines, case_sensitive)` — 搜索记忆

#### 清理时机

1. **Phase 1 清理** (`phase1.rs` 第 111 行):
   - `prune_stage1_outputs_for_retention(max_unused_days, batch_size)`
   - 删除超过 `max_unused_days` 天未使用的 Stage1 输出

2. **Phase 2 清理**:
   - 使用 Git 基线 diff 检测删除的信号
   - 从 MEMORY.md 中移除已删除 rollout 对应的条目
   - 清理过期的扩展资源文件 (`prune_old_extension_resources`)
   - 文件: `/tmp/agent-research/codex-main/codex-rs/memories/write/src/extensions/prune.rs`

### 1.5 记忆检索策略

**检索是多层级的"渐进式披露" (Progressive Disclosure)**：

1. **第一层 — memory_summary.md（总是加载）**：
   - 包含 `## User Profile`、`## User preferences`、`## General Tips`、`## What's in Memory`
   - 格式严格：第一行必须是 `v1`
   - 在此层使用关键词搜索指引到下一层

2. **第二层 — MEMORY.md（Agent 主动 grep）**：
   - 按 `# Task Group` 组织，每个块包含 `scope:`、`applies_to:`、`## User preferences`、`## Reusable knowledge`、`## Failures and how to do differently`
   - 每个任务块包含 `### rollout_summary_files` 和 `### keywords`

3. **第三层 — rollout_summaries/<slug>.md（按需读取）**：
   - 包含详细的 rollout 摘要和证据片段

4. **第四层 — skills/<skill-name>/SKILL.md（流程化知识）**：
   - 可复用的"斜杠命令"包

**检索策略特征**：关键词 grep + 渐进式深入，不使用向量检索。

官方模板中明确写了 "Quick memory pass" 流程 (`read_path.md` 第 33-41 行)：

```
Quick memory pass (when applicable):
1. Skim the MEMORY_SUMMARY below and extract task-relevant keywords.
2. Search {{ base_path }}/MEMORY.md using those keywords.
3. Only if MEMORY.md directly points to rollout summaries/skills, open the 1-2
   most relevant files...
4. If above are not clear and you need exact commands, error text, or
   precise evidence, search over `rollout_path` for more evidence.
5. If there are no relevant hits, stop memory lookup and continue normally.
```

**关键发现：记忆检索完全依赖 Agent 的文本搜索能力（grep），而不是向量数据库或语义搜索。** 这是一个基于 LLM 自身理解能力的设计——让 Agent 自己去搜索和阅读记忆文件。

### 1.6 Agent 之间的记忆共享或隔离

**记忆是全局共享的，但写入仅在 root 会话触发。**

1. **写入隔离** (`/tmp/agent-research/codex-main/codex-rs/memories/write/src/start.rs` 第 33 行):
   - `source.is_non_root_agent()` 检查：sub-agent、内部 session（如内存整合 agent）不会触发新的记忆写入
   - 只有 CLI/VSCode/Exec/MCP 等 root 用户会话才触发记忆生成

2. **读取共享**:
   - 所有 Agent（root 和 sub-agent）都共享同一个 `memory_summary.md` 的注入
   - 它们都可以访问同一个 `codex_home/memories/` 目录

3. **内存整合 Agent 特殊处理** (`phase2.rs` 第 299-303 行):
   ```rust
   agent_config.memories.generate_memories = false;
   agent_config.memories.use_memories = false;
   agent_config.ephemeral = true;
   ```
   整合 Agent 不会触发新一轮的记忆提取，防止递归。

4. **记忆扩展 (extensions) 机制**提供了外部记忆源：
   - `/tmp/agent-research/codex-main/codex-rs/ext/memories/src/`
   - 支持 ad_hoc 扩展（用户可以手动添加记忆笔记）

### 1.7 持久化方案

**双存储：SQLite + 文件系统**

1. **SQLite 数据库**:
   - 存储在 state database 中
   - 使用 `sqlx` Rust crate 访问 (代码中可见 `sqlx::sqlite::SqliteRow`)
   - 数据模型文件: `/tmp/agent-research/codex-main/codex-rs/state/src/model/memories.rs`
   - 运行时文件: `/tmp/agent-research/codex-main/codex-rs/state/src/runtime/memories.rs`
   - 数据库迁移: `/tmp/agent-research/codex-main/codex-rs/state/migrations/`
   - 表结构包括 threads、stage1_outputs、agent_jobs 等

2. **文件系统**:
   - 记忆文件存储在 `<codex_home>/memories/` 目录下
   - 格式为 Markdown (.md)
   - 使用 git 仓库管理记忆版本 (diff/commit/reset)
   - Git 操作文件: `/tmp/agent-research/codex-main/codex-rs/memories/write/src/workspace.rs`

3. **Rollout 文件**:
   - JSONL 格式
   - 路径: `<codex_home>/rollouts/<thread-id>.jsonl`
   - 内容: SessionMeta, TurnContext, EventMsg, ResponseItem

4. **进程内状态** (不持久化):
   - AgentRegistry (线程安全的 HashMap + Mutex)
   - ThreadManagerState 中的活跃线程

---

## 二、Agent 编排如何做

### 2.1 Agent 怎么定义的

**通过代码中的角色配置（TOML 文件 + 代码内置定义），而不是 YAML/JSON 文件。**

**内置角色定义**（硬编码在 Rust 代码中）:

文件路径: `/tmp/agent-research/codex-main/codex-rs/core/src/agent/role.rs` 第 305-367 行

```rust
// 内置三个角色:
1. "default" — 默认代理，无配置文件
2. "explorer" — 探索者，配置在 explorer.toml 中
3. "worker" — 执行者，无配置文件
// "awaiter" — 已被注释掉，临时移除
```

角色数据结构 `AgentRoleConfig` 定义在 config 类型中:
- `description`: 角色描述文本
- `config_file`: 可选的 TOML 配置文件路径
- `nickname_candidates`: 可选的昵称候选列表

**Explorer 角色的 TOML 配置**:

文件路径: `/tmp/agent-research/codex-main/codex-rs/core/src/agent/builtins/explorer.toml`

**用户也可以自定义角色**，通过 `config.toml` 的 `[agent_roles.<name>]` 段定义，可指定 description、config_file、nickname_candidates。

**角色加载流程** (`role.rs` 第 38-54 行):
1. 通过 `resolve_role_config` 查找角色（优先用户自定义，其次内置）
2. 将角色的 TOML 配置作为配置层插入到 session config 中
3. 角色层位于 SessionFlags 优先级
4. 保留当前 provider 和 service_tier（除非角色层显式设置）

### 2.2 有没有 Agent 注册表/发现机制

**有。存在 `AgentRegistry`。**

文件路径: `/tmp/agent-research/codex-main/codex-rs/core/src/agent/registry.rs`

**AgentRegistry 数据结构** (第 22-33 行):

```rust
pub(crate) struct AgentRegistry {
    active_agents: Mutex<ActiveAgents>,  // 活跃代理的线程安全表
    total_count: AtomicUsize,            // 代理总数
}

struct ActiveAgents {
    agent_tree: HashMap<String, AgentMetadata>,  // 代理树 (key为agent_path)
    used_agent_nicknames: HashSet<String>,        // 已使用的昵称
    nickname_reset_count: usize,                  // 昵称重置计数
}
```

**AgentMetadata 数据结构** (第 35-42 行):

```rust
pub(crate) struct AgentMetadata {
    pub(crate) agent_id: Option<ThreadId>,     // 线程ID
    pub(crate) agent_path: Option<AgentPath>,  // 代理路径 (如 /root/researcher)
    pub(crate) agent_nickname: Option<String>,  // 随机昵称
    pub(crate) agent_role: Option<String>,     // 角色名 (explorer/worker/...)
    pub(crate) last_task_message: Option<String>, // 最近任务消息
}
```

**核心功能**:
- `reserve_spawn_slot()` — 预留生成槽位，检查限制
- `register_root_thread()` — 注册根线程
- `agent_id_for_path()` — 按路径查找代理ID
- `live_agents()` — 列出活跃代理（排除root）
- `list_live_agent_subtree_thread_ids()` — 列出子树中所有线程ID
- `release_spawned_thread()` — 释放生成的线程
- `agent_metadata_for_thread()` — 按线程ID获取元数据

### 2.3 Agent 间通信/委派/协作

**有完整的 Agent 间通信协议和多版本支持。**

#### 通信机制

**InterAgentCommunication** 数据结构:

文件路径: `/tmp/agent-research/codex-main/codex-rs/protocol/src/protocol.rs` 第 664-671 行

```rust
pub struct InterAgentCommunication {
    pub author: AgentPath,           // 发送者路径
    pub recipient: AgentPath,        // 接收者路径
    pub other_recipients: Vec<AgentPath>, // 其他接收者
    pub content: String,             // 消息内容
    pub trigger_turn: bool,          // 是否触发新的turn
}
```

**通信方式** (`control.rs`):
1. **send_input()** — 向一个已经存在的 agent 发送用户输入
2. **send_inter_agent_communication()** — 发送 Agent 间通信消息
3. **interrupt_agent()** — 中断某个 agent 当前任务
4. **shutdown_live_agent()** / **close_agent()** — 关闭 agent 及子树
5. **subscribe_status()** — 订阅 agent 状态变更

**协作/委派**: 通过 Multi-agent 工具系统实现

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/tools/handlers/multi_agents/`

V1 工具（命名空间 `collab/`，在 `MULTI_AGENT_V1_NAMESPACE`）:
- `spawn_agent` — 生成子代理
- `send_input` — 发送输入到子代理
- `resume_agent` — 恢复已暂停的代理
- `wait` — 等待代理完成
- `close_agent` — 关闭代理

V2 工具（新的多代理系统，在 `Feature::MultiAgentV2` 下):
文件: `/tmp/agent-research/codex-main/codex-rs/core/src/tools/handlers/multi_agents_v2/`
- `spawn` — 生成子代理
- `send_message` — 向子代理发送消息
- `followup_task` — 后续任务
- `list_agents` — 列出活跃代理
- `close_agent` — 关闭代理
- `wait` — 等待代理

**Nickname 系统**: Agent 从预定义列表中分配随机昵称 (`/tmp/agent-research/codex-main/codex-rs/core/src/agent/agent_names.txt`)，如有重复则添加 "the 2nd", "the 3rd" 后缀。

**AgentPath 路径系统**:

文件: `/tmp/agent-research/codex-main/codex-rs/protocol/src/agent_path.rs`

所有 Agent 路径以 `/root` 开头，支持层次化路径:
- `/root` — 根代理
- `/root/researcher` — 子代理
- `/root/researcher/worker` — 孙代理
- `/morpheus` — 特殊系统代理

命名规则: 只允许小写字母、数字、下划线

### 2.4 动态 Agent 创建还是静态定义

**动态创建。** Agent 在运行时通过工具调用动态生成。

**生成流程** (`control.rs` 第 213-358 行):

1. LLM 调用 `spawn_agent` (v1) 或 `spawn` (v2) 工具
2. `AgentControl::spawn_agent_internal()` 被调用
3. 检查限制：`agent_max_threads` (总数限制)、`agent_max_depth` (深度限制)
4. 预留生成槽位 (`reserve_spawn_slot`)
5. 分配随机昵称 (来自 agent_names.txt)
6. 创建新线程 (通过 `ThreadManagerState`)
7. 可能有 Fork 模式 (从父线程拷贝历史)
8. 可选：应用角色配置层
9. 注入初始 prompt
10. 启动完成监听器 (watcher)
11. 持久化生成边到 SQLite

**两种 Fork 模式** (`control.rs` 第 48-51 行):

```rust
pub(crate) enum SpawnAgentForkMode {
    FullHistory,           // 完整拷贝父线程历史
    LastNTurns(usize),     // 仅最近N轮
}
```

### 2.5 有没有计划-执行-评估的 Harness 系统

**没有显式的 "Plan-Execute-Assess" harness 系统，但有相关机制：**

1. **没有** 显式的 Harness 抽象层（如 Plan step -> Execute step -> Evaluate step 的编排器）

2. **有 Goal 系统**:
   - 文件: `/tmp/agent-research/codex-main/codex-rs/core/src/tools/handlers/goal/`
   - 工具: `create_goal`, `update_goal`, `get_goal`
   - 支持目标跟踪和预算限制

3. **有 Plan 工具**:
   - 文件: `/tmp/agent-research/codex-main/codex-rs/core/src/tools/handlers/plan.rs`
   - 允许 Agent 通过工具调用进行显式规划

4. **有 Guardian 审查系统**:
   - 文件: `/tmp/agent-research/codex-main/codex-rs/core/src/guardian/`
   - 代码执行前进行安全审查

5. **有 Review 系统**:
   - 文件: `/tmp/agent-research/codex-main/codex-rs/core/src/session/review.rs`
   - Agent 可以在完成后进行代码审查

6. **有 Event 驱动的生命周期管理**:
   - AgentStatus 通过事件流推导
   - Turn 的开始/完成/中断都有对应事件

### 2.6 工具怎么绑定到 Agent 上

**工具通过 `ToolRegistry` 绑定到 Session/Agent 上，是每个 session 级别注册的。**

#### 工具注册表 (ToolRegistry)

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/tools/registry.rs` 第 320-323 行

```rust
pub struct ToolRegistry {
    tools: HashMap<ToolName, Arc<dyn CoreToolRuntime>>,
}
```

**工具注册流程**:
1. Session 创建时，根据配置创建 ToolRegistry
2. 工具通过 `CoreToolRuntime` trait 统一管理
3. 每个工具实现:
   - `tool_name()` — 工具名称
   - `spec()` — 工具规格 (ToolSpec)
   - `exposure()` — 暴露级别 (Full/Hidden)
   - `handle()` — 执行逻辑
   - `supports_parallel_tool_calls()` — 是否支持并行调用

**工具暴露级别** (`ToolExposure`):
- `Full` — 完全暴露给模型
- `Hidden` — 对模型隐藏

**工具分发流程** (`registry.rs` 第 397-676 行):
1. 模型调用工具 (function call)
2. `ToolRegistry::dispatch_any_with_terminal_outcome()` 被调用
3. 查找工具处理器
4. 检查 payload 兼容性
5. 触发 PreToolUse hooks
6. 执行工具处理器
7. 收集遥测数据
8. 触发 PostToolUse hooks
9. 返回结果给模型

**所有支持的工具（tool handlers）目录**:

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/tools/handlers/`

| 处理器 | 功能 |
|--------|------|
| shell/shell_command | 执行 shell 命令 |
| unified_exec/exec_command | 统一执行命令 |
| unified_exec/write_stdin | 写入 stdin |
| apply_patch | 应用代码补丁 |
| multi_agents/spawn | 生成子代理 |
| multi_agents/send_input | 向子代理发送输入 |
| multi_agents/close_agent | 关闭子代理 |
| multi_agents/wait | 等待子代理 |
| multi_agents_v2/* | V2 多代理工具 |
| mcp_resource/* | MCP 资源工具 |
| goal/create_goal | 创建目标 |
| request_user_input | 请求用户输入 |
| request_permissions | 请求权限 |
| tool_search | 工具搜索 |
| view_image | 查看图片 |
| test_sync | 测试同步 |
| plan | 规划工具 |
| list_available_plugins_to_install | 列出可安装插件 |
| request_plugin_install | 请求安装插件 |
| extension_tools | 扩展工具 |
| dynamic | 动态工具 |
| mcp | MCP 工具 |

### 2.7 会话管理和 Agent 生命周期管理

**有完整的会话管理和 Agent 生命周期管理。**

#### Session 核心结构

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/session/session.rs` 第 19-40 行

```rust
pub(crate) struct Session {
    pub(crate) conversation_id: ThreadId,
    pub(crate) installation_id: String,
    pub(super) tx_event: Sender<Event>,
    pub(super) agent_status: watch::Sender<AgentStatus>,
    pub(super) state: Mutex<SessionState>,
    pub(super) features: ManagedFeatures,
    pub(crate) active_turn: Mutex<Option<ActiveTurn>>,
    pub(crate) input_queue: InputQueue,
    pub(crate) goal_runtime: GoalRuntimeState,
    pub(crate) guardian_review_session: GuardianReviewSessionManager,
    pub(crate) services: SessionServices,
}
```

#### Agent 生命周期状态

文件: `/tmp/agent-research/codex-main/codex-rs/protocol/src/protocol.rs` 第 1550-1566 行

```rust
pub enum AgentStatus {
    PendingInit,        // 等待初始化
    Running,            // 运行中
    Interrupted,        // 被中断
    Completed(Option<String>), // 完成 (可选最终消息)
    Errored(String),    // 错误
    Shutdown,           // 已关闭
    NotFound,           // 未找到
}
```

#### 生命周期事件流

1. **TurnStarted** -> AgentStatus::Running
2. **TurnComplete** -> AgentStatus::Completed(...)
3. **TurnAborted(Interrupted)** -> AgentStatus::Interrupted
4. **TurnAborted(other)** -> AgentStatus::Errored(...)
5. **Error** -> AgentStatus::Errored(...)
6. **ShutdownComplete** -> AgentStatus::Shutdown

详见 `/tmp/agent-research/codex-main/codex-rs/core/src/agent/status.rs`

#### Input Queue 管理

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/session/input_queue.rs`

支持：
- 用户输入队列
- 邮箱式 Agent 间通信 (`InterAgentCommunication`)
- 空闲时挂起输入队列

#### Thread Manager

文件: `/tmp/agent-research/codex-main/codex-rs/codex-rs/core/src/` (ThreadManager)

核心功能:
- `spawn_new_thread()` — 创建新线程
- `spawn_new_thread_with_source()` — 带来源创建
- `fork_thread_with_source()` — Fork 线程
- `resume_thread_with_history_with_source()` — 恢复线程
- `remove_thread()` — 移除线程
- `get_thread()` — 获取线程
- `send_op()` — 发送操作

#### 线程配置 (ThreadConfig)

文件: `/tmp/agent-research/codex-main/codex-rs/config/src/thread_config.rs`

支持远程和本地配置的持久化线程设置。

---

## 三、一个Agent实现了哪些内容

### 3.1 Agent 的数据结构

**核心数据结构分布在多个文件中：**

1. **Session** — Agent 的会话状态

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/session/session.rs` 第 19-40 行

关键字段:
- `conversation_id: ThreadId` — 会话标识
- `agent_status: watch::Sender<AgentStatus>` — 状态广播通道
- `state: Mutex<SessionState>` — 会话可变状态
- `active_turn: Mutex<Option<ActiveTurn>>` — 活跃的turn
- `input_queue: InputQueue` — 输入队列
- `services: SessionServices` — 会话服务集合

2. **SessionConfiguration** — Agent 的配置

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/session/session.rs` 第 42-104 行

关键字段:
- `provider: ModelProviderInfo` — 模型提供商
- `base_instructions: String` — 基础指令
- `developer_instructions: Option<String>` — 开发者指令
- `user_instructions: Option<String>` — 用户指令
- `personality: Option<Personality>` — 人格设置
- `approval_policy: Constrained<AskForApproval>` — 审批策略
- `cwd: AbsolutePathBuf` — 工作目录
- `session_source: SessionSource` — 会话来源
- `collaboration_mode: CollaborationMode` — 协作模式

3. **ContextManager** — 上下文管理

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/context_manager/history.rs` 第 33-51 行

```rust
pub(crate) struct ContextManager {
    items: Vec<ResponseItem>,              // 历史对话条目
    history_version: u64,                  // 历史版本号
    token_info: Option<TokenUsageInfo>,    // Token使用信息
    reference_context_item: Option<TurnContextItem>, // 参考上下文
}
```

4. **AgentControl** — 多代理控制面

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/agent/control.rs` 第 153-163 行

```rust
pub(crate) struct AgentControl {
    session_id: SessionId,
    manager: Weak<ThreadManagerState>,  // 回指全局线程管理器
    state: Arc<AgentRegistry>,          // Agent注册表
}
```

5. **LiveAgent** — 活跃代理

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/agent/control.rs` 第 60-65 行

```rust
pub(crate) struct LiveAgent {
    pub(crate) thread_id: ThreadId,
    pub(crate) metadata: AgentMetadata,
    pub(crate) status: AgentStatus,
}
```

### 3.2 Agent 的执行循环

**执行循环是 "ReAct 模式 + 事件驱动" 的混合体。**

虽然没有找到单一的 "run loop" 函数，但整个执行由事件驱动：

1. **Op 提交** (Submission Queue):
   - 用户通过 `Op::UserInput` 提交输入
   - Agent 间通信通过 `Op::InterAgentCommunication`
   - 文件: `/tmp/agent-research/codex-main/codex-rs/protocol/src/protocol.rs` 第 479-642 行

2. **Turn 执行** (`session::turn::TurnContext`):
   - 每个 Turn 处理一个模型推理轮次
   - 模型响应可能包含工具调用

3. **工具调用循环** (ReAct 模式):
   - 模型调用工具 -> 工具执行 -> 结果返回 -> 模型继续推理
   - 工具分发: `ToolRegistry::dispatch_any_with_terminal_outcome()`
   - 循环直到模型返回最终答案 (MessagePhase::FinalAnswer)

4. **状态流转**:
   - TurnStarted -> 工具调用循环 -> TurnComplete / TurnAborted

**执行循环不是显式的 Plan-Execute 模式，而是标准的 ReAct (Reasoning + Acting) 模式，模型交替进行推理和工具调用。**

### 3.3 Agent 能调用哪些工具

详见 2.6 节。完整列表包括但不限于：

**执行类工具**:
- Shell 命令执行 (bash/zsh/sh/cmd/powershell)
- 统一执行 (Unified Exec)
- Apply Patch (代码补丁)

**Agent 管理工具** (v1 + v2):
- spawn_agent / spawn — 生成子代理
- send_input / send_message — 向子代理发送消息
- close_agent — 关闭代理
- wait — 等待代理完成
- list_agents(v2) — 列出代理
- followup_task(v2) — 后续任务

**用户交互工具**:
- request_user_input — 请求用户输入
- request_permissions — 请求权限

**目标管理工具**:
- create_goal / update_goal / get_goal — 目标管理

**规划工具**:
- plan — 规划

**文件/搜索工具** (通过 code-mode 子系统):
- 文件读取、搜索、列表

**MCP 工具**:
- 外部 MCP 服务器提供的工具

**其他**:
- view_image — 查看图片
- tool_search — 搜索可用工具
- plugin 安装工具

### 3.4 Agent 有没有反思/自我评估

**有，但有限。**

1. **Guardian 审查** — 在执行 shell 命令/代码补丁前进行安全审查
   - 文件: `/tmp/agent-research/codex-main/codex-rs/core/src/guardian/`
   - 命令执行前进行风险评估
   - 可以拒绝危险操作

2. **Review 功能** — Agent 可以在完成代码后进行自我审查
   - 文件: `/tmp/agent-research/codex-main/codex-rs/core/src/session/review.rs`
   - 模板: `/tmp/agent-research/codex-main/codex-rs/core/templates/review/`

3. **Goal 系统** — Agent 可以跟踪目标进度
   - 创建、更新、获取目标状态

4. **Plan 工具** — Agent 可以显式地规划步骤

5. **没有显式的 "反思" (Reflection) 循环**，如 LangChain 的 Reflection 或 OpenAI 的 Self-Critique 模式。Agent 主要依赖 LLM 自身的推理能力在 ReAct 循环中进行隐式的自我修正。

### 3.5 Agent 的上下文怎么管理

**有复杂的上下文管理系统。**

#### ContextManager

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/context_manager/history.rs`

核心方法:
- `append_items()` — 追加对话条目
- `rollback_last_user_turn()` — 回滚最近的用户轮次
- `compact()` — 压缩上下文
- `set_reference_context_item()` — 设置 diff 基线
- `token_info()` — 获取 token 使用信息

#### 上下文压缩 (Compaction)

文件: `/tmp/agent-research/codex-main/codex-rs/core/templates/compact/prompt.md`

- 当上下文接近 token 限制时自动触发
- 生成对话摘要替换原始历史
- 保留工具输出和关键信息

#### Token 管理

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/context_manager/history.rs`

- 跟踪 TokenUsageInfo
- 估计模型可见字节数
- 截断函数输出 (`truncate_function_output_payload`)

#### 上下文注入层

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/context/`

注入的上下文类型:
- `environment_context` — 环境信息 (cwd, git, shell)
- `skill_instructions` — 技能指令
- `plugin_instructions` — 插件指令
- `apps_instructions` — 应用指令
- `permissions_instructions` — 权限指令
- `collaboration_mode_instructions` — 协作模式指令
- `personality_spec_instructions` — 人格指令
- `goal_context` — 目标上下文
- `subagent_notification` — 子代理通知
- `realtime_start_instructions` / `realtime_end_instructions` — 实时会话指令
- `model_switch_instructions` — 模型切换指令
- `contextual_user_message` — 上下文化用户消息

**特殊标记格式**：使用 XML-like 标记包装:
```rust
pub const ENVIRONMENT_CONTEXT_OPEN_TAG: &str = "<environment_context>";
pub const ENVIRONMENT_CONTEXT_CLOSE_TAG: &str = "</environment_context>";
```

#### Session Prefix 格式化

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/session/turn_context.rs`

- 每个 turn 开始时格式化完整的指令和上下文
- 包括: 基础指令 + 开发者指令 + 用户指令 + 人格 + 环境 + 技能 + 插件 + 记忆摘要 + 协作模式

### 3.6 Agent 有没有独立的身份/人格/系统提示词

**有。Agent 有明确的多层身份系统：**

#### 1. 人格 (Personality)

文件: `/tmp/agent-research/codex-main/codex-rs/core/templates/personalities/`

内置两种人格:
- `gpt-5.2-codex_friendly.md` — 友好型
- `gpt-5.2-codex_pragmatic.md` — 务实型

通过 `SessionConfiguration.personality` 配置。

#### 2. 基础指令 (Base Instructions)

文件: `/tmp/agent-research/codex-main/codex-rs/core/templates/model_instructions/gpt-5.2-codex_instructions_template.md`

这是 Agent 的核心系统提示词模板，定义了 Agent 的角色和行为准则。

#### 3. 开发者指令 (Developer Instructions)

通过 `SessionConfiguration.developer_instructions` 和 `user_instructions` 配置，在基础指令之上追加。

#### 4. 协作模式 (Collaboration Mode)

文件: `/tmp/agent-research/codex-main/codex-rs/collaboration-mode-templates/templates/`

不同的协作模式（如 Plan-Execute、Chat 等）提供不同的指令和行为模式。

#### 5. Agent 角色 (Agent Role)

每个 agent 生成时可以指定角色（`agent_type`），角色会作为配置层覆盖到 Agent 的配置上，影响模型、推理强度、服务层级等。

内置角色:
- **default** — "Default agent." 无特殊配置
- **explorer** — 用于特定代码库问题，快速且有权威性，可以并行生成多个
- **worker** — 用于执行和生产工作，明确划分所有权

#### 6. Agent 昵称 (Agent Nickname)

从预定义的名称列表中随机分配，给 Agent 一个"身份"。文件名: `/tmp/agent-research/codex-main/codex-rs/core/src/agent/agent_names.txt`

#### 7. 实时会话 (Realtime Conversation)

- 语音/文本对话模式
- 独立的系统提示词：`/tmp/agent-research/codex-main/codex-rs/core/templates/realtime/backend_prompt.md`

---

## 总结

### 记忆系统总结

| 方面 | 实现情况 |
|------|----------|
| 架构 | 两阶段 LLM 管道 (Phase 1 提取 + Phase 2 整合)，文件系统 + SQLite 双存储 |
| 数据模型 | Stage1Output (SQLite) + 多层文件 (MEMORY.md, memory_summary.md, rollout_summaries/, skills/) |
| 生命周期 | 写入：root 会话启动时异步执行；检索：系统提示词注入 + Agent grep |
| 检索策略 | 关键词 grep + 渐进式披露，无向量检索/语义搜索 |
| Agent 共享 | 全局共享 (文件系统)，仅 root 写入，sub-agent 只读 |
| 持久化 | SQLite + Markdown 文件系统 + Git 版本管理 |

### Agent 编排总结

| 方面 | 实现情况 |
|------|----------|
| 定义方式 | TOML 文件 + Rust 代码内置定义 |
| 注册表 | AgentRegistry (HashMap + 昵称管理) |
| 通信/委派 | InterAgentCommunication 协议 + 多版本工具 (v1/v2) |
| 动态/静态 | 动态创建 (SpawnAgent) |
| Harness | 无显式 P-E-A Harness，有 Goal/Plan/Guardian/Review |
| 工具绑定 | ToolRegistry (HashMap<ToolName, CoreToolRuntime>) |
| 生命周期 | AgentStatus 状态机 + Session 管理 |

### Agent 实现总结

| 方面 | 实现情况 |
|------|----------|
| 执行循环 | ReAct 模式 (Reasoning + Acting) + 事件驱动 |
| 工具调用 | 丰富的工具集 (shell, agent管理, MCP, 文件, 用户交互等) |
| 反思/自评 | Guardian 审查 + Review 功能 + Goal 跟踪，无显式 Reflection 循环 |
| 上下文管理 | ContextManager + Compaction + Token 限制 + 多层注入 |
| 身份/人格 | Personality + Base Instructions + Developer Instructions + Agent Role + Nickname |

---

## 补充详细分析

---

### 四、记忆系统深度分析

#### 4.1 记忆写入管线的完整流程

记忆系统由三个 crate 组成，形成 `memories/write` -> `memories/read` -> `memories/mcp` 的管道：

```
codex-rs/memories/
  write/   -- Phase 1 提取 + Phase 2 整合 + 启动调度 + 文件管理
  read/    -- 记忆检索 + 系统提示词注入
  mcp/     -- 通过 MCP 协议暴露记忆为可调用工具
```

**启动入口完整代码路径：**

文件 `/tmp/agent-research/codex-main/codex-rs/memories/write/src/start.rs` 第 22 行的 `start_memories_startup_task` 函数是唯一公开的入口。它的调用链是：

```
start_memories_startup_task()
  -> 检查前置条件 (非 ephemeral, MemoryTool 启用, 非 sub-agent)
  -> 等待 API 速率限制检查通过 (DEFAULT_MEMORIES_MIN_RATE_LIMIT_REMAINING_PERCENT = 25%)
  -> tokio::spawn 异步启动 Prune -> Phase 1 -> Phase 2
```

**Phase 1 详细流程** (`phase1.rs`):

1. **构建请求上下文** (`build_request_context`):
   - 确定模型配置: 使用 `gpt-5.4-mini`, `reasoning_effort=Low`
   - 此阶段直接调用 LLM API（不走 Codex session），因此没有工具调用循环

2. **申请启动任务** (`claim_startup_jobs`):
   - 从 SQLite 中读取候选 rollout
   - 筛选条件: `max_rollouts_per_startup` (默认 2), `max_rollout_age_days` (默认 10), `min_rollout_idle_hours` (默认 6)
   - 使用**租赁锁** (lease lock) 机制: 每个 Stage1 任务获得 3600 秒 (`JOB_LEASE_SECONDS`) 的租约
   - 如果任务已被其他进程租用，则跳过

3. **并发执行** (`run_jobs`):
   - 使用 `futures::StreamExt` + `buffer_unordered(CONCURRENCY_LIMIT)` 实现
   - `CONCURRENCY_LIMIT = 8` (定义在 `stage_one` 模块)
   - 每个 job 独立调用模型，互不依赖

4. **结果持久化**:
   - LLM 输出被反序列化为 `StageOneOutput` (JSON 结构)
   - 字段: `raw_memory` (markdown 原始记忆), `rollout_summary` (摘要), `rollout_slug` (文件名 slug)
   - 成功的结果通过 SQLite 持久化到 `stage1_outputs` 表

5. **度量发射**:
   - 计数器: `MEMORY_PHASE_ONE_JOBS`, `MEMORY_PHASE_ONE_OUTPUT`
   - 计时器: `MEMORY_PHASE_ONE_E2E_MS`
   - Token 使用追踪: `MEMORY_PHASE_ONE_TOKEN_USAGE`

**Phase 1 的数据模型 (LLM 输出结构)**:

```rust
// 文件: phase1.rs 第 51-63 行
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct StageOneOutput {
    #[serde(rename = "raw_memory")]
    pub(crate) raw_memory: String,       // 原始记忆 (markdown)
    #[serde(rename = "rollout_summary")]
    pub(crate) rollout_summary: String,   // 摘要
    #[serde(default, rename = "rollout_slug")]
    pub(crate) rollout_slug: Option<String>, // 文件名 slug
}
```

**Phase 2 详细流程** (`phase2.rs`):

Phase 2 比 Phase 1 复杂得多，有 8 个串行步骤：

1. **申请全局锁** (`job::claim`):
   - Phase 2 使用全局锁，同一时间只有一个进程执行整合
   - 租约: `JOB_LEASE_SECONDS = 3600` 秒
   - 支持 **cooldown** 机制: 如果前一次整合完成后冷却期未到，跳过本次

2. **准备 Git 工作区** (`prepare_memory_workspace`):
   - 确保 `codex_home/memories/` 是一个 git 仓库
   - 如果不存在则 `git init`
   - 这个仓库用于跟踪记忆文件的变更

3. **构建 Agent 配置** (`agent::get_config`):
   - 构建一个**锁定配置**用于整合 Agent
   - `memories.generate_memories = false` (防止递归)
   - `memories.use_memories = false`
   - `ephemeral = true` (不持久化此会话)
   - `sandbox_policy = "read-only"` (仅读取)
   - `approval_policy = AskForApproval::Never` (不需要用户批准)

4. **加载 DB 输入** (`db.get_phase2_input_selection`):
   - 从 SQLite 加载 `raw_memories` (Stage1 产出)
   - 受 `max_raw_memories_for_consolidation` (默认 256) 限制
   - 过滤掉超过 `max_unused_days` (默认 30) 的条目

5. **同步文件系统**:
   - `sync_rollout_summaries_from_memories` — 将 DB 中的 rollout 摘要写入文件
   - `rebuild_raw_memories_file_from_memories` — 重建 `raw_memories.md`

6. **生成工作区 diff** (`memory_workspace_diff`):
   - 对比当前状态与 git 基线
   - 生成 `phase2_workspace_diff.md` 文件 (最大 4MB)
   - 包含: 新增的 rollout_summaries, 删除的信号, 文件变更

7. **启动整合 Agent** (`SpawnedConsolidationAgent`):
   - 使用 `gpt-5.4` 模型, `reasoning_effort=Medium`
   - 整合 Agent 是**一个完整的 Codex Agent 会话**（有 TurnContext、工具调用能力）
   - 它读取 `raw_memories.md`、`rollout_summaries/`、workspace diff
   - 产出: 更新或创建 `MEMORY.md`、`memory_summary.md`、`skills/`

8. **后处理**:
   - `prune_old_extension_resources` — 清理超过 7 天的扩展资源
   - 更新 git 基线 (`reset_memory_workspace_baseline`)
   - 释放全局锁
   - 发射度量

**Phase 2 关键配置常量**:

```rust
// 文件: lib.rs, stage_two 模块
pub(super) const MODEL: &str = "gpt-5.4";
pub(super) const REASONING_EFFORT: ReasoningEffort = ReasoningEffort::Medium;
pub(super) const JOB_LEASE_SECONDS: i64 = 3_600;
pub(super) const JOB_RETRY_DELAY_SECONDS: i64 = 3_600;
pub(super) const JOB_HEARTBEAT_SECONDS: u64 = 90;  // 心跳续租间隔
```

#### 4.2 记忆存储的 SQLite 数据模型

Stage1 输出的 SQLite 表字段 (来自 `state/src/model/memories.rs`):

| 字段 | 类型 | 说明 |
|------|------|------|
| `thread_id` | UUID | 关联的会话线程 |
| `rollout_path` | PathBuf | rollout JSONL 文件路径 |
| `source_updated_at` | DateTime\<Utc\> | 源更新时间 |
| `raw_memory` | String | Phase 1 LLM 提取的原始记忆 |
| `rollout_summary` | String | 摘要文本（用于索引/路由） |
| `rollout_slug` | Option\<String\> | 文件名 slug |
| `cwd` | PathBuf | 当时的工作目录 |
| `git_branch` | Option\<String\> | 当时的 git 分支 |
| `generated_at` | DateTime\<Utc\> | Phase 1 生成时间 |
| `leased_until` | Option\<DateTime\> | 租赁锁到期时间 |
| `latest_rollout_history_item_created_at` | Option\<DateTime\> | 最新 rollout 条目时间 |

还有 `agent_jobs` 表用于跟踪 Phase 2 整合任务的租约。

**ThreadMetadata 中与记忆相关的字段**:

```rust
pub memory_mode: ThreadMemoryMode,  // Enabled 或 Disabled
```
此字段在 `Session::new` 初始化时设置 (session.rs 第 557-561 行):
```rust
memory_mode: if config.memories.generate_memories {
    ThreadMemoryMode::Enabled
} else {
    ThreadMemoryMode::Disabled
},
```

#### 4.3 记忆检索的完整路径

**检索分为三种模式**：

1. **自动注入模式** (每次对话开始时):
   - 文件: `memories/read/src/prompts.rs`
   - 读取 `memory_summary.md` 文件
   - 截断到 `MEMORY_TOOL_DEVELOPER_INSTRUCTIONS_SUMMARY_TOKEN_LIMIT = 2,500` tokens
   - 格式化为 developer message 注入到系统提示词

2. **Agent 主动搜索模式** (Agent 被引导去搜索):
   - 模板文件: `memories/read/templates/memories/read_path.md`
   - "Quick memory pass" 流程 (5 步):
     1. Skim `memory_summary.md` 提取任务相关关键词
     2. 用关键词 grep 搜索 `MEMORY.md`
     3. 如果 MEMORY.md 指向 rollout_summaries/skills，打开 1-2 个最相关文件
     4. 如需精确命令/错误文本/证据，搜索 `rollout_path`
     5. 如无相关命中，停止检索，正常继续

3. **MCP 工具模式** (`memories/mcp/src/backend.rs`):
   - `list(path, cursor, max_results)` — 列出 `<codex_home>/memories/` 下的文件
   - `read(path, line_offset, max_lines, max_tokens)` — 读取记忆文件
   - `search(queries, match_mode, path, context_lines, case_sensitive)` — 全文搜索

#### 4.4 记忆扩展 (Extensions) 机制

文件: `memories/write/src/extensions/`

扩展允许外部源向记忆系统提供额外的上下文：
- 扩展定义在 `<codex_home>/memories/extensions/<name>/instructions.md`
- Phase 2 整合 Agent 被指示首先读取每个扩展的 `instructions.md`
- 扩展的资源文件有时效性: `RETENTION_DAYS = 7` (7天后自动清理)
- 清理函数: `prune_old_extension_resources`

#### 4.5 记忆系统的 Guard/控制面

文件: `memories/write/src/guard.rs` 和 `control.rs`

- `guard.rs` 提供速率限制检查: 确保 API 速率限制剩余百分比不低于 `DEFAULT_MEMORIES_MIN_RATE_LIMIT_REMAINING_PERCENT` (25%)
- `control.rs` 提供 `clear_memory_roots_contents` 清除所有记忆文件
- Phase 2 使用 `JOB_HEARTBEAT_SECONDS = 90` 秒的心跳机制来防止锁超时

#### 4.6 记忆的 Git 版本管理

文件: `memories/write/src/workspace.rs` 提供完整的 Git 操作：

- `prepare_memory_workspace` — 初始化 git 仓库（如果不存在）
- `memory_workspace_diff` — 生成工作区变更的 diff
- `write_workspace_diff` — 将 diff 写入 `phase2_workspace_diff.md`
- `reset_memory_workspace_baseline` — 提交变更，重置基线

**关键发现：记忆系统使用 git 来跟踪文件级变更，而不是数据库的增量更新。** 这使得整合 Agent 能够看到 "这次有哪些新 rollout" 和 "有哪些 rollout 被删除了"。

---

### 五、Agent 编排深度分析

#### 5.1 Agent 生成的完整生命周期

以下是 `spawn_agent_internal` (control.rs 第 213-358 行) 的完整执行路径：

```
Step 1: 升级弱引用 (self.upgrade())
  -> 从 Weak<ThreadManagerState> 获取 Arc<ThreadManagerState>

Step 2: 预留生成槽位 (reserve_spawn_slot)
  -> 检查 agent_max_threads 限制
  -> 使用 CAS 循环安全递增 total_count
  -> 如果超限 -> 返回 AgentLimitReached 错误

Step 3: 继承父线程的 Shell 快照
  -> inherited_shell_snapshot_for_source()
  -> Sub-agent 继承父的 ShellSnapshot

Step 4: 继承父线程的执行策略
  -> inherited_exec_policy_for_source()
  -> 如果 child_uses_parent_exec_policy 则继承

Step 5: 准备线程生成元数据 (prepare_thread_spawn)
  -> 如果是 root 的直接子节点 (depth==1)，注册 root
  -> 预留 AgentPath (确保不重复)
  -> 分配随机昵称 (从 agent_names.txt 中选取，如重复则加 "the 2nd" 后缀)
  -> 构造 SubAgentSource::ThreadSpawn { parent_thread_id, depth, agent_path, agent_nickname, agent_role }

Step 6: 创建线程
  -> Fork 模式 (SpawnAgentForkMode):
     - FullHistory: 完整拷贝父线程历史
     - LastNTurns(n): 仅最近 n 轮
     - Fork 前刷新父 rollout
     - 过滤掉多 Agent 使用提示消息
  -> 非 Fork 模式:
     - spawn_new_thread_with_source (全新线程)
  -> 无 SessionSource 时:
     - spawn_new_thread (根线程)

Step 7: 提交预留 (reservation.commit)
  -> 将 AgentMetadata 写入 AgentRegistry
  -> 设置 reservation.active = false (防止 Drop 时回滚)

Step 8: 持久化生成边
  -> persist_thread_spawn_edge_for_source
  -> 写入 SQLite: upsert_thread_spawn_edge(parent_thread_id, child_thread_id, Open)

Step 9: 发送初始操作
  -> self.send_input(new_thread.thread_id, initial_operation)
  -> 将 Op (UserInput 或 InterAgentCommunication) 发送到新线程

Step 10: 启动完成监视器 (非 MultiAgentV2)
  -> maybe_start_completion_watcher
  -> 在新 tokio 任务中订阅子 Agent 状态
  -> 当子 Agent 完成时，向父 Agent 发送通知消息
```

**Agent 的关闭/销毁流程**:

```rust
// control.rs 第 783-808 行
pub(crate) async fn close_agent(&self, agent_id: ThreadId) -> CodexResult<String> {
    // 1. 持久化关闭状态: set_thread_spawn_edge_status(agent_id, Closed)
    // 2. 递归关闭整个子树: shutdown_agent_tree(agent_id)
    //    - shutdown_live_agent(agent_id)
    //      -> 发送 Op::Shutdown
    //      -> 等待线程终止 (wait_until_terminated)
    //      -> remove_thread
    //      -> release_spawned_thread (从注册表移除，递减计数)
    //    - 遍历所有后代，递归关闭
}
```

**内部 Agent 死亡处理**:

```rust
// control.rs 第 748-759 行
async fn handle_thread_request_result(...) -> CodexResult<String> {
    if matches!(result, Err(CodexErr::InternalAgentDied)) {
        let _ = state.remove_thread(&agent_id).await;
        self.state.release_spawned_thread(agent_id);
    }
    result
}
```

#### 5.2 AgentRegistry 的并发控制细节

`AgentRegistry` 使用三层并发控制：

1. **进程内 Mutex** (`active_agents: Mutex<ActiveAgents>`):
   - 保护 agent_tree (HashMap<String, AgentMetadata>)
   - 保护 used_agent_nicknames (HashSet<String>)

2. **原子计数器** (`total_count: AtomicUsize`):
   - 使用 `compare_exchange_weak` 循环进行无锁递增
   - 限制总线程数不超过 `agent_max_threads`

3. **SpawnReservation 守卫模式**:
   - `reserve_spawn_slot()` 返回 `SpawnReservation`
   - 如果初始化失败（Drop），自动回滚：递减计数、释放 AgentPath
   - 如果成功，调用 `commit()` 标记 inactive 防止回滚

**昵称系统的完整流程**:

```
1. 加载 agent_names.txt (AGENT_NAMES 静态字符串)
2. 按行分割，trim 空行
3. 角色配置可覆盖昵称候选列表 (agent_nickname_candidates)
4. 每个候选名尝试 format_agent_nickname(name, nickname_reset_count)
5. 如果所有候选名都被占用:
   - 清空 used_agent_nicknames
   - 递增 nickname_reset_count
   - 使用 "name the 2nd", "name the 3rd" 格式
   - 发射 codex.multi_agent.nickname_pool_reset 度量
```

#### 5.3 Agent 间通信协议详解

**InterAgentCommunication 结构**:

```rust
pub struct InterAgentCommunication {
    pub author: AgentPath,                 // 发送者路径
    pub recipient: AgentPath,              // 主接收者路径
    pub other_recipients: Vec<AgentPath>,  // 副接收者
    pub content: String,                   // 消息内容（自然语言）
    pub trigger_turn: bool,                // 是否触发接收者的新 turn
}
```

**发送路径**:

```
Agent A 完成 -> maybe_start_completion_watcher 触发
  -> format_subagent_notification_message(reference, &status)
  -> 构建 InterAgentCommunication { author: child_path, recipient: parent_path, ... }
  -> send_inter_agent_communication(parent_thread_id, communication)
  -> 在 ThreadManagerState 中发送 Op::InterAgentCommunication
  -> 进入接收者的 InputQueue
  -> 在下一个处理周期被消费
```

**通信消息序列化**:
- `to_response_input_item()` 将消息序列化为 JSON 字符串嵌入到 `OutputText` 中
- 角色为 `assistant`, phase 为 `Commentary`
- `from_message_content` 从单个 `InputText` 或 `OutputText` 反序列化

#### 5.4 多 Agent 工具系统 (V1 vs V2)

**V1 工具** (命名空间 `collab/`):

| 工具 | 命名空间路径 | 说明 |
|------|-------------|------|
| spawn_agent | `collab/spawn_agent` | 生成子代理 (指定 agent_type, task_description) |
| send_input | `collab/send_input` | 发送输入到子代理 |
| resume_agent | `collab/resume_agent` | 恢复暂停的代理 |
| wait | `collab/wait` | 等待代理完成 |
| close_agent | `collab/close_agent` | 关闭代理 |

特征开关: `Feature::Collab`

**V2 工具** (无命名空间前缀):

| 工具 | 名称 | 说明 |
|------|------|------|
| spawn | `spawn` | 生成子代理 (支持 agent_type, task, path 参数) |
| send_message | `send_message` | 向子代理发送消息 (比 send_input 语义更清晰) |
| followup_task | `followup_task` | 追加后续任务 |
| list_agents | `list_agents` | 列出当前活跃代理 (支持 path_prefix 过滤) |
| close_agent | `close_agent` | 关闭代理 |
| wait | `wait` | 等待代理完成 |

特征开关: `Feature::MultiAgentV2`

**V2 与 V1 的关键差异**:
- V2 不使用命名空间前缀，工具名更短
- V2 有 `list_agents` 工具（V1 没有）
- V2 使用 `send_message` 替代 `send_input`，语义更清晰
- V2 不在生成后启动 completion watcher (使用 InterAgentCommunication 代替)

#### 5.5 会话恢复 (Session Resume) 机制

当恢复已保存的会话时，会级联恢复整个 Agent 子树：

```rust
// control.rs 第 499-572 行
pub(crate) async fn resume_agent_from_rollout(...)
    -> resume_single_agent_from_rollout (恢复单个)
    -> 使用 BFS (VecDeque) 遍历子节点
    -> 从 SQLite 读取 list_thread_spawn_children_with_status(parent, Open)
    -> 对每个 Open 状态的子节点，递归调用 resume_single_agent_from_rollout
    -> 跳过已经存在于内存中的线程
```

恢复时的特殊处理：
- 从 `ThreadMetadata` 中恢复 `agent_nickname` 和 `agent_role`
- 如果 depth >= agent_max_depth 且非 MultiAgentV2，禁用 Collab 和 SpawnCsv 特征
- 重新持久化 spawn edge (以防恢复过程中有变化)

#### 5.6 Task Queue 和并发控制

Codex 不使用传统的 Task Queue。它的并发模型是：

1. **Submission Queue - Event Queue 模式**:
   - 客户端通过 `Op` 枚举提交操作（Submission）
   - 服务端通过 `Event` 枚举推送事件（Event）
   - 每个 Submission 有唯一的 `id`，用于关联响应

2. **Tokio 并发原语**:
   - `RwLock<HashMap<ThreadId, Arc<CodexThread>>>` — 线程安全表
   - `broadcast::Sender<ThreadId>` — 线程创建通知
   - `watch::Sender<AgentStatus>` — 状态变更广播
   - `tokio::sync::Semaphore` — 网络代理刷新序列化

3. **ThreadManagerState** 是全局单例:
   - 由 `Arc<ThreadManagerState>` 共享
   - AgentControl 持有 `Weak<ThreadManagerState>` 避免循环引用

4. **InputQueue** 管理:
   - 每个 Session 有自己的 `InputQueue`
   - 支持: 用户输入、Agent 间通信、空闲挂起
   - 通过 `session::steer_input` 将输入导向正确的处理流程

#### 5.7 超时管理

**多级别超时控制**:

1. **Agent Job 超时**:
   - `agent_job_max_runtime_seconds: Option<u64>` (配置项)
   - 在 `handle_thread_request_result` 中处理超时

2. **命令执行超时**:
   - 沙箱命令: 在 `SandboxErr::Timeout` 中处理
   - `run_command_stream` 默认 10 秒超时

3. **HTTP 请求超时**:
   - `CodexErr::Timeout` (通用超时)
   - `CodexErr::RequestTimeout` (请求超时)
   - SSE 流超时: `stream_idle_timeout_ms` (配置项，默认 5000ms)

4. **Phase 2 心跳**:
   - `JOB_HEARTBEAT_SECONDS = 90` 秒
   - 定期续租以防止长时间运行的整合任务锁超时

5. **Shutdown 超时**:
   - `shutdown_and_wait()` 等待会话循环终止
   - `wait_until_terminated()` 通过 `session_loop_termination` 信号等待

---

### 六、Agent 执行循环深入分析

#### 6.1 Codex 的执行架构

Codex 的核心执行架构是 **Submission Queue / Event Queue (SQ/EQ)** 模式 + **ReAct 循环**：

```
                       Submission Queue (SQ)
  Client/CLI ──────────────────────────────────> Agent/Session
     │                                                │
     │  Op::UserInput                                 │ Turn Starts
     │  Op::InterAgentCommunication                   │   ├─ 格式化 Session Prefix
     │  Op::Interrupt                                 │   ├─ 构建 TurnContext
     │  Op::Shutdown                                  │   ├─ ContextManager.for_prompt()
     │  ...                                           │   ├─ 调用 Model API
     │                                                │   ├─ 模型响应 (文本/工具调用)
  Client/CLI <────────────────────────────────── Agent/Session
     │              Event Queue (EQ)                  │
     │  EventMsg::SessionConfigured                   │   ├─ 如果是工具调用:
     │  EventMsg::TurnStarted                         │   │   ├─ ToolRegistry::dispatch()
     │  EventMsg::AgentMessage                        │   │   ├─ PreToolUse hooks
     │  EventMsg::AgentReasoning                      │   │   ├─ 执行工具
     │  EventMsg::TurnComplete                        │   │   ├─ PostToolUse hooks
     │  EventMsg::Error                               │   │   └─ 结果追加到 ContextManager
     │  EventMsg::ExecApprovalRequest                 │   │   └─ 继续循环 (再次调用模型)
     │  EventMsg::ShutdownComplete                    │   ├─ 如果是 FinalAnswer:
     │  ...                                           │   │   └─ TurnComplete
     │                                                │
                                                     Turn Ends
```

#### 6.2 Op 枚举的完整定义

`Op` 枚举 (protocol.rs 第 479-642 行) 包含 **23 个变体**：

| 操作 | 类型 | 说明 |
|------|------|------|
| `Interrupt` | 控制 | 中断当前任务 |
| `CleanBackgroundTerminals` | 控制 | 清理后台终端进程 |
| `RealtimeConversationStart` | 实时 | 启动实时语音会话 |
| `RealtimeConversationAudio` | 实时 | 发送音频输入 |
| `RealtimeConversationText` | 实时 | 发送文本输入 |
| `RealtimeConversationClose` | 实时 | 关闭实时会话 |
| `RealtimeConversationListVoices` | 实时 | 列出可用语音 |
| **`UserInput`** | 核心 | 用户输入（含文本、图片、技能调用、mention） |
| `ThreadSettings` | 配置 | 应用线程设置覆盖（不触发 turn） |
| `InterAgentCommunication` | 多Agent | Agent 间通信消息 |
| `ExecApproval` | 审批 | 批准命令执行 |
| `PatchApproval` | 审批 | 批准补丁应用 |
| `ResolveElicitation` | 审批 | 解决 MCP 引导请求 |
| `UserInputAnswer` | 审批 | 回答 request_user_input |
| `RequestPermissionsResponse` | 审批 | 响应权限请求 |
| `DynamicToolResponse` | 审批 | 动态工具响应 |
| `RefreshMcpServers` | 配置 | 刷新 MCP 服务器 |
| `ReloadUserConfig` | 配置 | 重新加载用户配置 |
| `Compact` | 上下文 | 请求压缩上下文 |
| `SetThreadMemoryMode` | 记忆 | 设置线程记忆模式 |
| `ThreadRollback` | 控制 | 回滚最后 N 轮 |
| `Review` | 审查 | 请求代码审查 |
| `ApproveGuardianDeniedAction` | 审批 | 批准 Guardian 拒绝的操作 |
| `Shutdown` | 控制 | 关闭会话 |
| `RunUserShellCommand` | 执行 | 运行用户 shell 命令 (!cmd) |

#### 6.3 TurnContext 的完整字段

`TurnContext` (turn_context.rs 第 51-99 行) 是每个 Turn 中所有代码都可以访问的上下文载体，包含 **44 个字段**：

```rust
pub struct TurnContext {
    pub sub_id: String,                           // Turn 子 ID
    pub trace_id: Option<String>,                 // 分布式跟踪 ID
    pub realtime_active: bool,                    // 实时模式激活标志
    pub config: Arc<Config>,                      // 会话配置快照
    pub auth_manager: Option<Arc<AuthManager>>,   // 认证管理器
    pub model_info: ModelInfo,                    // 模型信息（上下文窗口等）
    pub session_telemetry: SessionTelemetry,      // 遥测句柄
    pub provider: SharedModelProvider,            // 模型提供商
    pub reasoning_effort: Option<ReasoningEffortConfig>, // 推理强度
    pub reasoning_summary: ReasoningSummaryConfig,        // 推理摘要模式
    pub session_source: SessionSource,            // 会话来源
    pub thread_source: Option<ThreadSource>,      // 线程来源
    pub environments: ResolvedTurnEnvironments,   // 解析后的环境
    pub cwd: AbsolutePathBuf,                     // 工作目录 (已弃用)
    pub current_date: Option<String>,             // 当前日期
    pub timezone: Option<String>,                 // 时区
    pub developer_instructions: Option<String>,   // 开发者指令
    pub compact_prompt: Option<String>,           // 压缩提示词覆盖
    pub user_instructions: Option<String>,        // 用户指令
    pub collaboration_mode: CollaborationMode,    // 协作模式
    pub personality: Option<Personality>,         // 人格
    pub approval_policy: Constrained<AskForApproval>, // 审批策略
    pub permission_profile: PermissionProfile,    // 权限配置
    pub network: Option<NetworkProxy>,            // 网络代理配置
    pub windows_sandbox_level: WindowsSandboxLevel, // Windows 沙箱级别
    pub shell_environment_policy: ShellEnvironmentPolicy, // Shell 环境策略
    pub available_models: Vec<ModelPreset>,       // 可用模型列表
    pub unified_exec_shell_mode: UnifiedExecShellMode, // 执行 Shell 模式
    pub goal_tools_supported: bool,               // 目标工具是否支持
    pub features: ManagedFeatures,                // 特征开关
    pub ghost_snapshot: GhostSnapshotConfig,      // Ghost 快照配置
    pub final_output_json_schema: Option<Value>,  // 最终输出 JSON Schema
    pub codex_self_exe: Option<PathBuf>,          // Codex 自身可执行路径
    pub codex_linux_sandbox_exe: Option<PathBuf>, // Linux 沙箱可执行路径
    pub truncation_policy: TruncationPolicy,      // 输出截断策略
    pub dynamic_tools: Vec<DynamicToolSpec>,      // 动态工具规格
    pub turn_metadata_state: Arc<TurnMetadataState>, // Turn 元数据状态
    pub extension_data: Arc<ExtensionData>,       // 扩展数据
    pub turn_skills: TurnSkillsContext,            // Turn 技能上下文
    pub turn_timing_state: Arc<TurnTimingState>,  // Turn 计时状态
    pub server_model_warning_emitted: AtomicBool, // 服务器模型警告已发射
    pub model_verification_emitted: AtomicBool,   // 模型验证已发射
}
```

#### 6.4 Session Prefix 格式化

每个 Turn 开始时，Session 会格式化一个完整的指令和上下文块，它注入到用户消息之前。这包括：

```
<developer_instructions>
  [基础指令模板] (来自 model_instructions/gpt-5.2-codex_instructions_template.md)
  [记忆摘要] (memory_summary.md 截断到 2500 tokens)
  [人格指令] (如果配置了)
  [用户指令] (来自 AGENTS.md)
</developer_instructions>

<collaboration_mode>
  [协作模式指令] (Plan-Execute / Chat / ...)
</collaboration_mode>

<skills_instructions>
  [已加载的技能指令]
</skills_instructions>

<plugins_instructions>
  [已加载的插件指令]
</plugins_instructions>

<environment_context>
  [当前日期、时间、工作目录、Git 分支、Shell 类型]
  [活跃子 Agent 列表]
</environment_context>

<permissions_instructions>
  [权限/沙箱策略说明]
</permissions_instructions>

<user_instructions>
  [用户的实际输入]
</user_instructions>
```

特殊的 XML-like 标记包装 (protocol.rs):
```rust
pub const ENVIRONMENT_CONTEXT_OPEN_TAG: &str = "<environment_context>";
pub const SKILLS_INSTRUCTIONS_OPEN_TAG: &str = "<skills_instructions>";
pub const COLLABORATION_MODE_OPEN_TAG: &str = "<collaboration_mode>";
pub const APPS_INSTRUCTIONS_OPEN_TAG: &str = "<apps_instructions>";
// ... 等等
```

#### 6.5 工具分发的完整流程

`ToolRegistry::dispatch_any_with_terminal_outcome` (registry.rs 第 397-676 行) 的详细步骤：

```
1. 提取工具调用信息:
   - tool_name, call_id
   - 会话遥测 (session_telemetry)
   - 沙箱标签 (permission_profile_sandbox_tag)

2. 递增工具调用计数:
   - active_turn.turn_state.tool_calls += 1

3. 启动分发跟踪:
   - ToolDispatchTrace::start(&invocation)

4. 查找工具处理器:
   - self.tool(&tool_name) -> Option<Arc<dyn CoreToolRuntime>>
   - 如未找到 -> RespondToModel("不支持的函数")

5. 检查 Payload 兼容性:
   - tool.matches_kind(&invocation.payload)
   - 如不匹配 -> Fatal error

6. 通知工具启动:
   - notify_tool_start(&invocation)

7. 执行 PreToolUse Hooks:
   - run_pre_tool_use_hooks(session, turn, call_id, tool_name, tool_input)
   - 如果 Blocked -> RespondToModel(message)

8. 执行工具处理器:
   - tool.handle(invocation).await
   - 收集遥测: otel.tool_result_with_tags(...)

9. 执行 PostToolUse Hooks:
   - run_post_tool_use_hooks(session, turn, call_id, ...)

10. 检查终端结果:
    - 如果 terminal_outcome_reached 设置且为 false -> 返回结果
    - 否则 -> 检查工具是否产生终端结果，更新 flag

11. 返回 AnyToolResult
```

#### 6.6 上下文管理 (ContextManager) 详细机制

`ContextManager` (history.rs) 管理 Agent 的对话历史：

**核心方法**:
- `record_items(items, policy)` — 追加对话条目，应用截断策略
- `for_prompt(input_modalities)` — 返回可发送给模型的标准化历史
- `raw_items()` — 返回原始条目 (用于持久化)
- `history_version()` — 返回当前版本号 (每次变更递增)
- `estimate_token_count(turn_context)` — 粗略估计 token 使用量
- `compact()` (在 manager 文件中) — 压缩上下文

**自动压缩 (Compaction)**:
- 当 `model_auto_compact_token_limit` 触发时执行
- 保留最近的对话轮次，压缩较早的轮次
- 将压缩后的摘要作为 `RolloutItem::Compacted` 存储
- `window_generation` 跟踪压缩的次数

**Token 估算**:
- 使用字节级启发式算法 (`approx_token_count`, `approx_bytes_for_tokens`)
- 不是 tokenizer 精确计数，而是粗略下界估计
- 基础指令 token + 所有历史条目 token 的总和

**截断策略**:
- `truncate_function_output_items_with_policy` — 截断函数输出
- `TruncationPolicy` 控制模式 (bytes/tokens) 和限制
- 输出截断: `truncate_text` 使用 `truncate_middle_with_token_budget`

**参考上下文 (Reference Context)**:
- `reference_context_item: Option<TurnContextItem>` — 用于 diff 的基线
- 设置 diff 允许仅发送变更的设置，而不是每次都全量重新注入
- Rollback 可能清除此基线

---

### 七、CLI 与 Agent 的交互协议

#### 7.1 客户端-服务端架构

CLI 通过两种模式与 Agent 通信：

1. **直接模式 (本地进程)**:
   - CLI (`codex-rs/cli/`) 直接调用 `codex-rs/core/` 的 API
   - 使用 ThreadManager 管理会话生命周期
   - TUI (`codex-rs/tui/`) 提供交互式界面

2. **App-Server 模式 (远程进程)**:
   - App Server (`codex-rs/app-server/`) 作为中间层
   - CLI 通过 Unix Domain Socket (UDS) 与 App Server 通信
   - App Server 管理线程池，路由请求到正确的会话
   - 协议: `codex-rs/app-server-protocol/`

#### 7.2 会话来源 (SessionSource)

所有会话都需要一个 `SessionSource` 来标识其来源：

```rust
pub enum SessionSource {
    Cli,                                        // 命令行交互
    Exec,                                       // 代码执行
    McpServer { server_name: String },          // MCP 服务器发起的
    SubAgent(SubAgentSource),                   // 子 Agent
    ServerExec,                                 // 服务端执行
    ExecPolicy,                                 // 执行策略
    // ...
}

pub enum SubAgentSource {
    ThreadSpawn {
        parent_thread_id: ThreadId,
        depth: i32,
        agent_path: Option<AgentPath>,
        agent_nickname: Option<String>,
        agent_role: Option<String>,
    },
    // ...
}
```

`SessionSource` 决定了：
- 记忆是否可以生成 (`is_non_root_agent()` 检查)
- 线程持久化策略
- 配置文件加载范围 (restriction_product)

#### 7.3 事件流 (Event Queue)

`EventMsg` 枚举定义了服务端到客户端的所有事件类型。包括但不限于：

- `SessionConfigured` — 会话初始化完成
- `TurnStarted` / `TurnComplete` / `TurnAborted` — Turn 生命周期
- `AgentMessage` — 模型生成的文本消息
- `AgentReasoning` — 模型的推理过程
- `AgentReasoningSummary` — 推理摘要
- `FunctionCall` / `FunctionCallResult` — 工具调用
- `ExecApprovalRequest` / `ExecApprovalUpdate` — 执行审批
- `ExecCommandOutput` / `ExecCommandComplete` — 命令输出
- `ApplyPatchProposal` — 补丁提议
- `GuardianAssessment` — Guardian 安全评估
- `ElicitationRequest` — MCP 引导请求
- `RequestUserInput` — 请求用户输入
- `Error` — 错误事件
- `Warning` — 警告事件
- `DeprecationNotice` — 弃用通知
- `Compaction` / `ContextCompaction` — 上下文压缩
- `ShutdownComplete` — 关闭完成
- `RealtimeConversation*` — 实时会话事件
- `GoalCreated` / `GoalUpdated` — 目标管理事件
- `PlanUpdated` — 计划更新事件

---

### 八、模型路由和选择机制

#### 8.1 ModelProvider trait

文件: `/tmp/agent-research/codex-main/codex-rs/model-provider/src/provider.rs`

```rust
#[async_trait]
pub trait ModelProvider: fmt::Debug + Send + Sync {
    fn info(&self) -> &ModelProviderInfo;
    fn capabilities(&self) -> ProviderCapabilities;
    fn approval_review_preferred_model(&self) -> &'static str;
    fn supports_attestation(&self) -> bool;
    fn auth_manager(&self) -> Option<Arc<AuthManager>>;
    async fn auth(&self) -> Option<CodexAuth>;
    fn account_state(&self) -> ProviderAccountResult;
    async fn api_provider(&self) -> Result<Provider>;
    async fn runtime_base_url(&self) -> Result<Option<String>>;
    async fn api_auth(&self) -> Result<SharedAuthProvider>;
    fn models_manager(&self, codex_home: PathBuf, config_model_catalog: Option<ModelsResponse>) -> SharedModelsManager;
}

pub type SharedModelProvider = Arc<dyn ModelProvider>;
```

**两个实现**:
1. `ConfiguredModelProvider` — 标准 OpenAI-compatible 提供商（通过 `ModelProviderInfo` 配置）
2. `AmazonBedrockModelProvider` — AWS Bedrock 提供商

#### 8.2 ProviderCapabilities

每个提供商声明其能力上限：

```rust
pub struct ProviderCapabilities {
    pub namespace_tools: bool,   // 命名空间工具 (如 collab/spawn_agent)
    pub image_generation: bool,  // 图片生成
    pub web_search: bool,        // 网页搜索
}
```

默认全部为 `true`。

#### 8.3 模型管理器 (SharedModelsManager)

模型管理器负责模型目录的加载、缓存和刷新：

```rust
pub type SharedModelsManager = Arc<dyn ModelsManager>;

pub enum RefreshStrategy {
    Online,   // 从远端获取
    Cached,   // 仅使用缓存
}
```

两种实现:
1. **OpenAiModelsManager** — 动态从 `/models` 端点获取模型目录
2. **StaticModelsManager** — 使用静态配置的模型目录

**模型目录还可从配置中覆盖**: `Config::model_catalog: Option<ModelsResponse>`

#### 8.4 模型切换

`TurnContext::with_model` 方法 (turn_context.rs 第 165-200 行) 支持在运行时切换到不同模型：

```rust
pub(crate) async fn with_model(&self, model: String, models_manager: &SharedModelsManager) -> Self {
    // 1. 克隆配置
    // 2. 设置新模型
    // 3. 从 models_manager 获取新模型信息 (ModelInfo)
    // 4. 更新截断策略
    // 5. 智能选择推理强度 (取支持的中间值)
    // 6. 更新 collaboration_mode
    // 7. 返回新的 TurnContext
}
```

**推理强度智能选择**: 如果当前推理强度不被新模型支持，则取支持列表的中间值（或默认值）。

#### 8.5 认证链

认证链支持多种方法：

```rust
pub enum CodexAuth {
    ApiKey(String),                    // API Key
    Chatgpt(ChatgptAuth),             // ChatGPT 登录
    ChatgptAuthTokens(ChatgptAuthTokens), // ChatGPT Token
    AgentIdentity(AgentIdentityAuth),  // Agent 身份
}
```

- `AuthManager` 管理认证生命周期：登录、刷新、缓存
- `auth_manager_for_provider` 决定提供商特定认证
- 认证信息通过 `api_auth()` -> `SharedAuthProvider` 传递到 HTTP 客户端

---

### 九、配置系统深度分析

#### 9.1 分层配置架构 (ConfigLayerStack)

Codex 使用 **分层配置** 架构，`ConfigLayerStack` 管理多层配置的叠加：

1. **内置默认值** — 硬编码在代码中
2. **全局配置** — `~/.codex/config.toml`
3. **项目配置** — `<project>/.codex/config.toml`
4. **配置文件 (Profiles)** — `~/.codex/<name>.config.toml`
5. **需求配置 (Requirements)** — 项目目录下的 `.codex/requirements.toml`
6. **环境变量覆盖** — `CODEX_*` 环境变量
7. **CLI 参数覆盖** — `--model`, `--approval-policy` 等
8. **会话临时覆盖** — 通过 `SessionSettingsUpdate` 动态更新
9. **角色层 (Agent Role)** — 在生成时注入的角色特定配置

每个层有明确的优先级。后加载的层覆盖前一层。

#### 9.2 Config 的核心字段 (部分列表)

文件: `/tmp/agent-research/codex-main/codex-rs/core/src/config/mod.rs` 第 545 行起

```rust
pub struct Config {
    pub config_layer_stack: ConfigLayerStack,   // 配置层栈
    pub startup_warnings: Vec<String>,          // 启动警告
    pub model: Option<String>,                  // 模型选择覆盖
    pub model_provider_id: String,              // 提供商 ID (如 "openai")
    pub model_provider: ModelProviderInfo,      // 提供商完整信息
    pub personality: Option<Personality>,       // 人格选择
    pub permissions: Permissions,               // 权限配置
    pub model_context_window: Option<i64>,      // 上下文窗口大小
    pub model_auto_compact_token_limit: Option<i64>, // 自动压缩触发阈值
    pub model_auto_compact_token_limit_scope: AutoCompactTokenLimitScope, // 压缩范围
    pub hide_agent_reasoning: bool,             // 隐藏推理过程
    pub show_raw_agent_reasoning: bool,         // 显示原始推理
    pub user_instructions: Option<String>,      // AGENTS.md 的指令
    pub base_instructions: Option<String>,      // 基础指令覆盖
    pub developer_instructions: Option<String>,  // 开发者指令覆盖
    pub compact_prompt: Option<String>,         // 压缩提示词覆盖
    pub cwd: AbsolutePathBuf,                   // 工作目录
    pub workspace_roots: Vec<AbsolutePathBuf>,  // 工作区根目录
    pub agent_max_threads: Option<usize>,       // 最大 Agent 线程数
    pub agent_max_depth: i32,                   // 最大 Agent 嵌套深度
    pub agent_job_max_runtime_seconds: Option<u64>, // Agent 作业最大运行时间
    pub agent_roles: BTreeMap<String, AgentRoleConfig>, // Agent 角色定义
    pub memories: MemoriesConfig,               // 记忆子系统配置
    pub codex_home: AbsolutePathBuf,            // Codex 主目录
    pub sqlite_home: PathBuf,                   // SQLite 状态数据库目录
    pub ephemeral: bool,                        // 是否临时的 (不持久化)
    pub mcp_servers: Constrained<HashMap<String, McpServerConfig>>, // MCP 服务器
    pub model_providers: HashMap<String, ModelProviderInfo>, // 提供商映射
    pub web_search_mode: Constrained<WebSearchMode>, // 网页搜索模式
    pub model_reasoning_effort: Option<ReasoningEffort>, // 推理强度
    pub model_reasoning_summary: Option<ReasoningSummary>, // 推理摘要模式
    pub model_verbosity: Option<Verbosity>,     // 模型输出详细程度
    pub model_catalog: Option<ModelsResponse>,  // 完整模型目录覆盖
    pub chatgpt_base_url: String,               // ChatGPT 基本 URL
    pub realtime: RealtimeConfig,               // 实时配置
    pub experimental_thread_store: ThreadStoreConfig, // 线程存储后端
    pub cli_auth_credentials_store_mode: AuthCredentialsStoreMode, // 凭证存储模式
    pub mcp_oauth_credentials_store_mode: OAuthCredentialsStoreMode, // OAuth 存储模式
    pub tui_keymap: TuiKeymap,                  // TUI 按键绑定
    pub tui_theme: Option<String>,              // TUI 主题
    pub tui_pet: Option<String>,                // TUI 宠物
    pub animations: bool,                       // 动画开关
    pub history: History,                       // 历史记录配置
    pub notify: Option<Vec<String>>,            // 外部通知命令
    pub codex_self_exe: Option<PathBuf>,        // 自身可执行路径
    pub codex_linux_sandbox_exe: Option<PathBuf>, // Linux 沙箱路径
    pub main_execve_wrapper_exe: Option<PathBuf>, // execve 包装器路径
    pub zsh_path: Option<PathBuf>,              // zsh 路径
    pub guardian_policy_config: Option<String>, // Guardian 策略配置
    pub include_permissions_instructions: bool, // 是否注入权限指令
    pub include_apps_instructions: bool,        // 是否注入应用指令
    pub include_collaboration_mode_instructions: bool, // 是否注入协作模式指令
    pub include_skill_instructions: bool,       // 是否注入技能指令
    pub include_environment_context: bool,      // 是否注入环境上下文
    pub approvals_reviewer: ApprovalsReviewer,  // 审批审核者
    pub enforce_residency: Constrained<Option<ResidencyRequirement>>, // 驻留要求
    // ... 更多字段
}
```

#### 9.3 ConfigToml 结构

文件: `/tmp/agent-research/codex-main/codex-rs/config/src/config_toml.rs`

这是 `config.toml` 的 Rust 表示，由 `serde` 反序列化：

```rust
pub struct ConfigToml {
    pub model: Option<String>,
    pub model_provider: Option<String>,
    pub service_tier: Option<String>,
    pub personality: Option<Personality>,
    pub sandbox_mode: Option<SandboxMode>,
    pub approval_policy: Option<AskForApproval>,
    pub permissions: Option<PermissionsToml>,
    pub model_providers: Option<HashMap<String, ModelProviderToml>>,
    pub mcp_servers: Option<HashMap<String, McpServerConfig>>,
    pub agent_max_threads: Option<usize>,
    pub agent_max_depth: Option<i32>,
    pub agent_roles: Option<BTreeMap<String, AgentRoleConfig>>,
    pub memories: Option<MemoriesConfigToml>,
    pub ephemeral: Option<bool>,
    pub tui: Option<TuiConfigToml>,
    pub hooks: Option<HooksToml>,
    pub history: Option<HistoryToml>,
    pub features: Option<HashMap<String, TomlValue>>,
    pub notify: Option<Vec<String>>,
    pub web_search_mode: Option<WebSearchMode>,
    pub project_doc_max_bytes: Option<usize>,
    pub project_doc_fallback_filenames: Option<Vec<String>>,
    // ... etc
}
```

#### 9.4 配置加载流程

```
config.toml 文件
  -> ConfigToml::deserialize
  -> ConfigLayerStack::new
    -> 遍历配置层: 内置默认 -> 全局 -> 项目 -> Profile -> 需求 -> CLI 覆盖
    -> 每层通过 merge 合并
  -> ConfigRequirements 约束验证
    -> 检查必选字段 (required 标记)
    -> 约束检查 (Constrained<T>)
  -> Config::new
    -> 解析 model_provider
    -> 构建 ModelProviderInfo
    -> 检查 startup_warnings
    -> 特征验证
```

#### 9.5 配置特征开关 (ManagedFeatures)

文件: `/tmp/agent-research/codex-main/codex-rs/features/`

特征开关系统使用位掩码管理，定义在 `Feature` 枚举中。关键特征包括：

| 特征 | 说明 |
|------|------|
| `MemoryTool` | 启用记忆写入和检索 |
| `Collab` | 启用 V1 多 Agent 协作工具 |
| `MultiAgentV2` | 启用 V2 多 Agent 系统 |
| `ShellZshFork` | 使用 fork 的 zsh 执行 shell 命令 |
| `ShellSnapshot` | 启用 Shell 环境快照 |
| `Goals` | 启用目标管理工具 |
| `SpawnCsv` | 启用子 Agent 生成 |
| `AuthElicitation` | 启用认证引导 |
| `RuntimeMetrics` | 启用运行时度量 |
| `EnableRequestCompression` | 启用请求压缩 |

---

### 十、错误处理和重试机制

#### 10.1 CodexErr 枚举的完整分类

`CodexErr` 有 **25+ 个变体** (error.rs 第 67-164 行)，可分为以下类别：

**可重试错误** (`is_retryable() == true`):
- `Stream(String, Option<Duration>)` — SSE 流断开（自动重试 turn）
- `Timeout` — 命令超时
- `RequestTimeout` — 请求超时
- `UnexpectedStatus(UnexpectedResponseError)` — 非预期 HTTP 状态
- `ResponseStreamFailed(ResponseStreamFailed)` — 响应流失败
- `ConnectionFailed(ConnectionFailedError)` — 连接失败
- `InternalServerError` — 服务器内部错误
- `InternalAgentDied` — Agent 循环意外终止
- `Io(io::Error)` / `Json(serde_json::Error)` — I/O 或 JSON 错误
- `TokioJoin(JoinError)` — Tokio 任务 join 错误

**不可重试错误** (`is_retryable() == false`):
- `TurnAborted` — Turn 已中止
- `Interrupted` — 用户中断 (Ctrl-C)
- `Fatal(String)` — 致命错误
- `ContextWindowExceeded` — 上下文窗口超出
- `ThreadNotFound(ThreadId)` — 线程未找到
- `AgentLimitReached { max_threads }` — Agent 数量限制
- `UsageLimitReached(UsageLimitReachedError)` — 使用限制
- `QuotaExceeded` — 配额超出
- `UsageNotIncluded` — 使用不包含在计划内
- `ServerOverloaded` — 服务器过载
- `CyberPolicy { message }` — 安全策略拦截
- `RetryLimit(RetryLimitReachedError)` — 重试次数超限
- `InvalidRequest(String)` — 无效请求
- `InvalidImageRequest()` — 图片毒化
- `UnsupportedOperation(String)` — 不支持的操作
- `Sandbox(SandboxErr)` — 沙箱错误
- `RefreshTokenFailed(RefreshTokenFailedError)` — Token 刷新失败

**错误协议转换** (`to_codex_protocol_error`):
- 将内部错误映射到客户端可理解的 `CodexErrorInfo` 枚举
- 客户端友好的错误类型: `Unauthorized`, `BadRequest`, `UsageLimitExceeded`, `ServerOverloaded`, `InternalServerError`, `ContextWindowExceeded`, `SandboxError`

#### 10.2 自动重试逻辑

SSE 流断开的自动重试 (在 `Session` 的 turn 处理循环中):
```rust
// CodexErr::Stream 被 Session loop 视为瞬态错误
// 会自动使用提供的 delay 重试整个 turn
// 重试次数受 request_max_retries 和 stream_max_retries 配置控制
```

**重试配置**:
- `request_max_retries: Option<u32>` (ModelProviderInfo 中)
- `stream_max_retries: Option<u32>` (ModelProviderInfo 中)
- `stream_idle_timeout_ms: Option<u64>` (默认 5000ms)

#### 10.3 重试限制

```rust
pub struct RetryLimitReachedError {
    pub status: StatusCode,      // HTTP 状态码
    pub retries: usize,          // 已尝试次数
    pub request_id: Option<String>,
}
```

当达到 `RetryLimit` 时，`is_retryable()` 返回 `false`，终止 retry 循环。

#### 10.4 SandboxErr 分类

```rust
pub enum SandboxErr {
    Denied { output: Box<ExecToolCallOutput>, network_policy_decision: Option<...> },
    SeccompInstall(seccompiler::Error),   // Linux seccomp 安装错误
    SeccompBackend(seccompiler::BackendError), // Linux seccomp 后端错误
    Timeout { output: Box<ExecToolCallOutput> }, // 命令超时
    Signal(i32),                           // 信号终止
    LandlockRestrict,                      // Landlock 限制错误
}
```

#### 10.5 错误恢复策略总结

| 场景 | 策略 |
|------|------|
| SSE 流断开 | 自动重试 turn (带 delay) |
| HTTP 连接失败 | 重试 (受 request_max_retries 控制) |
| 命令超时 | 通知模型失败 |
| 上下文窗口超出 | 触发压缩或建议新会话 |
| Agent 死亡 | 从注册表清理，通知父 Agent |
| Token 刷新失败 | 不重试，向用户报告 |
| 用户中断 | 发送 TurnAborted 事件，停止处理 |
| 速率限制 | 等待后重试 (带退避) |
| 致命错误 | 终止会话 |

---

### 十一、Agent 角色系统

#### 11.1 角色定义

`AgentRoleConfig` 结构：

```rust
pub struct AgentRoleConfig {
    pub description: String,
    pub config_file: Option<PathBuf>,         // TOML 配置文件路径
    pub nickname_candidates: Option<Vec<String>>, // 昵称候选列表
}
```

**内置角色** (hardcoded in role.rs):

| 角色名 | 配置文件 | 描述 |
|--------|---------|------|
| `default` | 无 | "Default agent." 使用父线程的所有配置 |
| `explorer` | `explorer.toml` | 用于特定代码库问题，快速且有权威性，可并行生成多个 |
| `worker` | 无 | 用于执行和生产工作，明确划分所有权 |

#### 11.2 角色配置层应用 (apply_role_to_config)

角色的 TOML 配置作为配置层插入到 Agent 的配置栈中：
- 插入位置: SessionFlags 优先级
- 保留当前 `model_provider` 和 `service_tier` (除非角色层显式设置)
- 角色配置可以覆盖: 模型、推理强度、协作模式、人格等

#### 11.3 角色文件加载

1. 内置角色: 从编译时嵌入的 TOML 文件加载 (`include_str!`)
2. 用户自定义角色: 从 `config.toml` 的 `[agent_roles.<name>]` 段定义，指定 `config_file` 路径
3. 使用 `parse_agent_role_file_contents` 解析
4. 通过 `resolve_relative_paths_in_config_toml` 解析相对路径
5. 通过 `reload::build_next_config` 构建新配置

---

### 十二、AgentPath 和命名系统

#### 12.1 AgentPath 结构

`AgentPath` (agent_path.rs) 是一个类型安全的路径字符串：

```
/root                    — 根 Agent
/root/researcher         — 直接子 Agent
/root/researcher/worker  — 孙 Agent
/morpheus                — 系统 Agent (如记忆整合)
```

**命名规则** (严格验证):
- 必须以 `/root` 开头（或为 `/morpheus`）
- 名称段: 仅小写字母、数字、下划线
- 不允许 `.` 或 `..`
- 不允许以 `/` 结尾

**方法**:
- `root()` — 创建 `/root`
- `morpheus()` — 创建 `/morpheus`
- `join(agent_name)` — 创建子路径 `/root/researcher`
- `resolve(reference)` — 解析相对或绝对引用
- `name()` — 返回最后一个段名
- `is_root()` — 检查是否为根

#### 12.2 昵称列表

`agent_names.txt` 包含预定义的 Agent 昵称列表。如果所有名称都被占用：
1. 清空已使用昵称集合
2. 递增 `nickname_reset_count`
3. 添加后缀 "the 2nd", "the 3rd", "the 4th" 等等
4. 发射度量 `codex.multi_agent.nickname_pool_reset`

---

### 十三、SessionServices 完整组件列表

`SessionServices` 是每个 Session 持有的服务集合，包含所有运行时依赖：

```rust
struct SessionServices {
    mcp_connection_manager: Arc<RwLock<McpConnectionManager>>,     // MCP 连接管理
    mcp_startup_cancellation_token: Mutex<CancellationToken>,      // MCP 启动取消令牌
    unified_exec_manager: UnifiedExecProcessManager,               // 统一执行进程管理
    shell_zsh_path: Option<PathBuf>,                               // zsh 路径
    main_execve_wrapper_exe: Option<PathBuf>,                      // execve 包装器路径
    analytics_events_client: AnalyticsEventsClient,                // 分析事件客户端
    hooks: ArcSwap<Hooks>,                                         // Hook 集合
    rollout_thread_trace: ThreadTraceContext,                      // Rollout 线程跟踪
    user_shell: Arc<Shell>,                                        // 用户 Shell
    shell_snapshot_tx: watch::Sender<Option<Arc<ShellSnapshot>>>,  // Shell 快照发送者
    show_raw_agent_reasoning: bool,                                // 是否显示原始推理
    exec_policy: Arc<ExecPolicyManager>,                           // 执行策略管理
    auth_manager: Arc<AuthManager>,                                // 认证管理
    session_telemetry: SessionTelemetry,                           // 会话遥测
    models_manager: SharedModelsManager,                           // 模型管理
    tool_approvals: Mutex<ApprovalStore>,                          // 工具审批存储
    guardian_rejections: Mutex<HashMap<String, ...>>,              // Guardian 拒绝记录
    guardian_rejection_circuit_breaker: Mutex<CircuitBreaker>,     // Guardian 拒绝熔断器
    runtime_handle: tokio::runtime::Handle,                        // 运行时句柄
    skills_manager: Arc<SkillsManager>,                            // 技能管理
    plugins_manager: Arc<PluginsManager>,                          // 插件管理
    mcp_manager: Arc<McpManager>,                                  // MCP 管理
    extensions: Arc<ExtensionRegistry<Config>>,                    // 扩展注册表
    session_extension_data: Arc<ExtensionData>,                    // 会话扩展数据
    thread_extension_data: Arc<ExtensionData>,                     // 线程扩展数据
    agent_control: AgentControl,                                   // Agent 控制面
    network_proxy: ArcSwapOption<NetworkProxy>,                   // 网络代理
    network_proxy_audit_metadata: NetworkProxyAuditMetadata,      // 网络代理审计元数据
    managed_network_requirements_configured: bool,                 // 受管网络需求配置标志
    network_approval: Arc<NetworkApprovalService>,                 // 网络审批服务
    state_db: Option<StateDbHandle>,                              // 状态数据库句柄
    live_thread: Option<LiveThread>,                               // 活跃线程句柄
    thread_store: Arc<dyn ThreadStore>,                            // 线程存储
    attestation_provider: Option<Arc<dyn AttestationProvider>>,    // 认证提供者
    model_client: ModelClient,                                     // 模型 HTTP 客户端
    code_mode_service: CodeModeService,                            // 代码模式服务
    environment_manager: Arc<EnvironmentManager>,                  // 环境管理
}
```

---

### 十四、Guardian 安全审查系统

Codex 有一个内置的安全审查系统 Guardian，在代码执行前进行风险评估：

- **文件**: `/tmp/agent-research/codex-main/codex-rs/core/src/guardian/`
- **扩展**: `/tmp/agent-research/codex-main/codex-rs/ext/guardian/`
- **工作原理**:
  1. 在 Shell 命令/补丁执行前，调用 Guardian LLM 进行评估
  2. 输出风险评估: `GuardianRiskLevel` (Safe / Low / Medium / High / Critical)
  3. 评估结果通过 `GuardianAssessmentEvent` 事件发送给客户端
  4. 用户可以通过 `Op::ApproveGuardianDeniedAction` 覆盖拒绝

- **熔断器**: `guardian_rejection_circuit_breaker` 防止连续的 Guardian 绕过

- **Guardian 策略配置**: 可通过 `Config::guardian_policy_config` 自定义策略，插入到 Guardian 提示词模板的 `# Policy Configuration` 段

---

### 十五、关键代码路径汇总

| 功能 | 文件路径 |
|------|---------|
| 记忆启动入口 | `memories/write/src/start.rs` |
| Phase 1 提取 | `memories/write/src/phase1.rs` |
| Phase 2 整合 | `memories/write/src/phase2.rs` |
| 记忆检索提示词 | `memories/read/src/prompts.rs` |
| 记忆 MCP 后端 | `memories/mcp/src/backend.rs` |
| Agent 生成 | `core/src/agent/control.rs` |
| Agent 注册表 | `core/src/agent/registry.rs` |
| Agent 状态 | `core/src/agent/status.rs` |
| Agent 角色 | `core/src/agent/role.rs` |
| Session 初始化 | `core/src/session/session.rs` |
| Session 配置 | `core/src/session/session.rs` (SessionConfiguration) |
| Turn 上下文 | `core/src/session/turn_context.rs` |
| 线程管理 | `core/src/thread_manager.rs` |
| CodexThread | `core/src/codex_thread.rs` |
| 上下文管理 | `core/src/context_manager/history.rs` |
| 工具注册表 | `core/src/tools/registry.rs` |
| 多Agent 工具 V1 | `core/src/tools/handlers/multi_agents/` |
| 多Agent 工具 V2 | `core/src/tools/handlers/multi_agents_v2/` |
| CLI 入口 | `cli/src/main.rs` |
| 协议定义 (Op/Event/AgentStatus) | `protocol/src/protocol.rs` |
| 错误类型 | `protocol/src/error.rs` |
| AgentPath | `protocol/src/agent_path.rs` |
| 配置类型 | `protocol/src/config_types.rs` |
| 模型提供商 | `model-provider/src/provider.rs` |
| Config 结构 | `core/src/config/mod.rs` (pub struct Config) |
| ConfigToml | `config/src/config_toml.rs` |
| 配置层栈 | `config/src/state.rs` (ConfigLayerStack) |
| Guardian | `core/src/guardian/` |
| Session Prefix 格式化 | `core/src/session/turn_context.rs` |
| 上下文化消息 | `core/src/context/` |
| Review 功能 | `core/src/session/review.rs` |
| 记忆数据模型 (SQLite) | `state/src/model/memories.rs` |
| 记忆状态运行时 | `state/src/runtime/memories.rs` |

---

### 十六、架构模式总结

1. **SQ/EQ 模式**: Submission Queue (用户->Agent) + Event Queue (Agent->用户) 实现了完全异步的双向通信

2. **分层配置**: ConfigLayerStack 提供多层配置叠加，每一层有自己的优先级和来源

3. **Weak 引用循环避免**: AgentControl 持有 `Weak<ThreadManagerState>` 而不是 `Arc`，防止循环引用导致内存泄漏。模式为 `ThreadManagerState -> CodexThread -> Session -> SessionServices -> AgentControl -> Weak<ThreadManagerState>`

4. **RAII 守卫模式**: SpawnReservation 使用 Drop trait 确保即使出错也能释放资源

5. **租赁锁模式**: Phase 1 和 Phase 2 都使用基于时间的租赁锁 (lease lock)，防止多进程/多实例同时处理同一批数据

6. **心跳续租**: Phase 2 使用周期性心跳 (`JOB_HEARTBEAT_SECONDS = 90s`) 来续租锁，防止长时间运行的整合任务锁过期

7. **渐进式披露**: 记忆检索使用 4 层渐进式结构：memory_summary -> MEMORY.md -> rollout_summaries -> skills

8. **双存储**: 结构化数据 (SQLite) + 可读文件 (Markdown)，两者互补

9. **模型提供商抽象**: `ModelProvider` trait 隔离了不同后端 (OpenAI, Amazon Bedrock, 自定义) 的差异

10. **特征开关 (Feature Flags)**: 使用 `ManagedFeatures` 进行编译时和运行时的功能精细控制
