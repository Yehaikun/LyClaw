# OpenHive 项目深度分析报告

> 分析日期: 2026-05-23
> 项目: OpenHive (aden-hive/hive) — 多智能体生产级执行框架
> 源码路径: `/tmp/agent-research/hive-main/`

---

## 目录

1. [记忆系统分析](#1-记忆系统分析)
2. [Agent编排分析](#2-agent编排分析)
3. [Agent实现分析](#3-agent实现分析)

---

# 1. 记忆系统分析

## 1.1 总体架构

Hive 的记忆系统采用**基于文件的分层存储架构**，没有向量数据库，没有语义搜索索引。记忆系统由以下几层组成：

### 第一层：文件记忆系统 (Markdown + YAML Frontmatter)

**核心文件**：`core/framework/agents/queen/queen_memory_v2.py`

记忆目录结构（从代码注释中明确）：

```
~/.hive/memories/
    global/              # 跨所有 Queen 和 Colony 共享
    colonies/{name}/     # Colony 级别的记忆
    agents/queens/{name}/# Queen 特定的记忆
    agents/{name}/       # 每个 Worker Agent 的记忆
```

**关键常量** (`queen_memory_v2.py`):

```python
GLOBAL_MEMORY_CATEGORIES: tuple[str, ...] = ("profile", "preference", "environment", "feedback")
MAX_FILES: int = 200           # 每次扫描最多 200 个文件
MAX_FILE_SIZE_BYTES: int = 4096 # 每文件最大 4KB
_HEADER_LINE_LIMIT: int = 30    # 读取前 30 行用于头信息扫描
```

**目录访问函数**：

```python
def global_memory_dir() -> Path:
    return MEMORIES_DIR / "global"                           # ~/.hive/memories/global

def colony_memory_dir(colony_name: str) -> Path:
    return MEMORIES_DIR / "colonies" / colony_name           # ~/.hive/memories/colonies/{name}

def queen_memory_dir(queen_name: str = "default") -> Path:
    return MEMORIES_DIR / "agents" / "queens" / queen_name   # ~/.hive/memories/agents/queens/{name}

def agent_memory_dir(agent_name: str) -> Path:
    return MEMORIES_DIR / "agents" / agent_name              # ~/.hive/memories/agents/{name}
```

### 第二层：会话存储 (Session State)

**核心文件**：`core/framework/schemas/session_state.py`, `core/framework/storage/session_store.py`

会话目录结构：

```
~/.hive/agents/{agent_name}/sessions/session_YYYYMMDD_HHMMSS_{uuid}/
    state.json            # 单一真相来源
    conversations/        # EventLoop 状态 (每个 part 携带 phase_id)
        parts/
            0000000000.json
            0000000001.json
            ...
    artifacts/            # 溢出数据
    logs/                 # L1/L2/L3 可观测性
        summary.json
        details.jsonl
        tool_logs.jsonl
```

### 第三层：对话持久化

**核心文件**：`core/framework/storage/conversation_store.py`, `core/framework/agent_loop/conversation.py`

对话存储布局：

```
{base_path}/conversations/
    meta.json             # 当前节点配置 (节点转换时覆盖)
    cursor.json           # 迭代计数器, 累积输出, 停滞状态
    parts/
        0000000000.json   # (phase_id=node_a)
        0000000001.json   # (phase_id=node_a)
        0000000002.json   # (转换标记)
        0000000003.json   # (phase_id=node_b)
    partials/             # 飞行中的 assistant 轮次部分检查点
```

### 第四层：检查点系统

**核心文件**：`core/framework/schemas/checkpoint.py`, `core/framework/storage/checkpoint_store.py`

检查点目录结构：

```
{存储路径}/checkpoints/
    index.json              # 检查点清单
    cp_{type}_{node}_{timestamp}.json  # 单个检查点
```

### 第五层：任务队列 (SQLite)

**核心文件**：`core/framework/host/progress_db.py`

每个 Colony 都有一个 `progress.db` (SQLite)，存储在 `~/.hive/colonies/{name}/data/`。

### 第六层：会话摘要缓存

**核心文件**：`core/framework/storage/session_summary.py`

每个 Queen 会话目录中有 `summary.json`，缓存会话列表需要的数据。

---

## 1.2 记忆数据模型

### MemoryFile (文件记忆)

**文件**：`core/framework/agents/queen/queen_memory_v2.py`

```python
@dataclass
class MemoryFile:
    """磁盘上单个记忆文件的解析表示"""
    filename: str                          # 文件名
    path: Path                             # 文件路径
    name: str | None = None                # Frontmatter: name
    type: str | None = None                # Frontmatter: type (profile/preference/environment/feedback)
    description: str | None = None         # Frontmatter: description
    header_lines: list[str] = field(default_factory=list)  # 前 N 行
    mtime: float = 0.0                     # 修改时间 (Unix 时间戳)
```

**Frontmatter 格式**（YAML-like）：

```yaml
---
name: 记忆名称
description: 记忆描述
type: profile  # profile | preference | environment | feedback
---
记忆正文内容...
```

**Frontmatter 解析函数**：`parse_frontmatter(text: str) -> dict[str, str]`

使用正则表达式提取 YAML frontmatter `^---\s*\n(.*?)\n---\s*\n?`，然后按行解析 `key: value` 格式。**不支持嵌套结构**，只支持扁平的 key-value。

### Message (对话消息)

**文件**：`core/framework/agent_loop/conversation.py`

```python
@dataclass
class Message:
    seq: int                                               # 单调序列号
    role: Literal["user", "assistant", "tool"]            # 角色
    content: str                                           # 消息文本
    tool_use_id: str | None = None                         # 工具调用ID
    tool_calls: list[dict[str, Any]] | None = None         # OpenAI格式工具调用列表
    is_error: bool = False                                 # 是否为错误
    phase_id: str | None = None                            # 阶段标识 (连续模式)
    is_transition_marker: bool = False                     # 是否为转换标记
    is_client_input: bool = False                          # 是否为真实用户输入
    image_content: list[dict[str, Any]] | None = None      # 图片内容块
    is_skill_content: bool = False                         # 是否包含技能内容
    run_id: str | None = None                              # 逻辑运行ID
    is_system_nudge: bool = False                          # 是否为框架注入的续写提示
    truncated: bool = False                                # 是否被截断
    inherited_from: str | None = None                      # 继承自哪个父会话
    is_trigger: bool = False                               # 是否由触发器生成
```

### SessionState (会话状态)

**文件**：`core/framework/schemas/session_state.py`

```python
class SessionState(BaseModel):
    schema_version: str = "1.1"
    session_id: str                                        # 格式: session_YYYYMMDD_HHMMSS_{uuid_8char}
    stream_id: str = ""
    correlation_id: str = ""
    status: SessionStatus = SessionStatus.ACTIVE          # active|paused|completed|failed|cancelled
    goal_id: str
    agent_id: str = ""
    entry_point: str = "start"
    timestamps: SessionTimestamps                          # started_at, updated_at, completed_at, paused_at
    progress: SessionProgress                              # current_node, paused_at, steps_executed, total_tokens... 
    result: SessionResult                                  # success, error, output
    data_buffer: dict[str, Any] = {}                       # 数据缓冲区 (别名为 memory)
    metrics: SessionMetrics                                # decision_count, problem_count, tokens...
    problems: list[dict[str, Any]] = []
    decisions: list[dict[str, Any]] = []
    input_data: dict[str, Any] = {}
    current_run_id: str | None = None
    pid: int | None = None                                 # 拥有进程的PID
    isolation_level: str = "shared"
    checkpoint_enabled: bool = False
    latest_checkpoint_id: str | None = None
    active_triggers: list[str] = []
    trigger_tasks: dict[str, str] = {}
    worker_configured: bool = False
    task_list_id: str | None = None
    picked_up_from: list[Any] | None = None
```

**特别说明**：`data_buffer` 字段有一个别名 `memory`，用于向后兼容：

```python
data_buffer: dict[str, Any] = Field(
    default_factory=dict,
    validation_alias=AliasChoices("data_buffer", "memory"),
)

@property
def memory(self) -> dict[str, Any]:
    """Backward-compatible alias for legacy callers."""
    return self.data_buffer
```

### Checkpoint (检查点)

**文件**：`core/framework/schemas/checkpoint.py`

```python
class Checkpoint(BaseModel):
    checkpoint_id: str                                     # 格式: cp_{type}_{node_id}_{timestamp}
    checkpoint_type: str                                   # "node_start" | "node_complete" | "loop_iteration"
    session_id: str
    run_id: str | None = None
    created_at: str                                        # ISO 8601
    current_node: str | None = None
    next_node: str | None = None
    execution_path: list[str] = []
    data_buffer: dict[str, Any] = {}                       # 完整 DataBuffer._data 快照
    accumulated_outputs: dict[str, Any] = {}
    metrics_snapshot: dict[str, Any] = {}
    is_clean: bool = True
    description: str = ""
```

### 任务表 (SQLite)

**文件**：`core/framework/host/progress_db.py`

```sql
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY, seq INTEGER, priority INTEGER DEFAULT 0,
    goal TEXT NOT NULL, payload TEXT,
    status TEXT DEFAULT 'pending', worker_id TEXT, claim_token TEXT,
    claimed_at TEXT, started_at TEXT, completed_at TEXT,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
    retry_count INTEGER DEFAULT 0, max_retries INTEGER DEFAULT 3,
    last_error TEXT, parent_task_id TEXT REFERENCES tasks(id),
    source TEXT
);

CREATE TABLE IF NOT EXISTS steps (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id),
    seq INTEGER NOT NULL, title TEXT NOT NULL, detail TEXT,
    status TEXT DEFAULT 'pending', evidence TEXT, worker_id TEXT,
    started_at TEXT, completed_at TEXT,
    UNIQUE (task_id, seq)
);

CREATE TABLE IF NOT EXISTS sop_checklist (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id),
    key TEXT NOT NULL, description TEXT NOT NULL, required INTEGER DEFAULT 1,
    done_at TEXT, done_by TEXT, note TEXT,
    UNIQUE (task_id, key)
);

CREATE TABLE IF NOT EXISTS colony_meta (
    key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL
);
```

---

## 1.3 记忆的生命周期

### 写入时机

| 操作 | 触发时机 | 持久化到 |
|------|---------|---------|
| **文件级记忆** | 手动创建 .md 文件 或 Agent 通过工具写入 | `~/.hive/memories/` |
| **会话状态** | 每次 `write_state()` 调用 | `state.json` |
| **对话消息** | 每个 LLM 轮次结束时 `write_part()` | `parts/` 目录 |
| **光标状态** | 每次迭代后 `write_cursor()` | `cursor.json` |
| **检查点** | 节点完成时 (`checkpoint_on_node_complete=True`) | `checkpoints/` 目录 |
| **进度** | 节点转换时 `_write_progress()` | `state.json` (patch) |
| **飞行中的部分** | 流事件每次触发 `write_partial()` | `partials/` 目录 |
| **任务创建** | Agent 调用 `task_create` 工具 | SQLite `progress.db` |

### 检索时机

| 操作 | 触发时机 | 方法 |
|------|---------|------|
| **记忆召回** | 每次用户对话轮次前 | `recall_selector.select_memories()` — LLM 选择最多5个相关文件 |
| **会话恢复** | 从检查点恢复时 | `restore()` — 读取 cursor.json + parts/ |
| **检查点恢复** | 崩溃后重新执行 | `CheckpointStore.load_checkpoint()` |
| **对话历史** | 节点初始化 | `NodeConversation.restore()` — 读取 parts/ |
| **会话列表** | UI 展示 | `list_sessions()` / `list_cold_sessions()` |
| **部分检查点** | 崩溃后重新执行 | `read_all_partials()` — 检测最后未完成轮次 |

### 清理时机

| 操作 | 触发时机 | 实现 |
|------|---------|------|
| **会话删除** | 显式调用 | `SessionStore.delete_session()` — `shutil.rmtree` |
| **检查点清理** | 超过7天自动 | `CheckpointStore.prune_checkpoints(max_age_days=7)` |
| **旧消息删除** | 显式调用 | `FileConversationStore.delete_parts_before(seq)` |
| **清除对话** | 新执行开始 | `FileConversationStore.clear()` — 清除 parts/ + cursor.json |
| **销毁对话** | 显式调用 | `FileConversationStore.destroy()` — 删除整个目录 |
| **对话压缩** | Token 超出限制时 | 多级压缩管道 (见下文) |

**注意**：文件级记忆没有自动清理机制。`queen_memory_v2.py` 中有 `MAX_FILES=200` 限制扫描，但没有清理过期文件的逻辑。

---

## 1.4 记忆检索策略

### 1.4.1 召回选择器 (Recall Selector)

**文件**：`core/framework/agents/queen/recall_selector.py`

**检索流程**：

1. **扫描**：`scan_memory_files()` 扫描记忆目录中所有 `.md` 文件（最多200个），按 mtime 排序（最新优先）
2. **生成清单**：`format_memory_manifest()` 将文件列表格式化为 `[type] filename: description` 文本
3. **LLM 选择**：使用 LLM 调用，传入用户查询和可用记忆清单，返回 JSON `{"selected_memories": [文件列表]}`
4. **读取内容**：只读取被选中的文件内容（最多5个），注入系统提示

**系统提示词核心逻辑**：

```
你正在选择记忆，这些记忆将对 Queen Agent 处理用户查询有用。

只返回一个 JSON 对象：{"selected_memories": [文件名列表]}

- 只选择你确定会有帮助的记忆 (最多 5 个)
- 如果不确定，就不要包含
- 如果没有明显有用的，返回空列表
```

**关键特征**：
- **不是语义搜索**！没有 embedding，没有向量索引
- **不是关键词搜索**！没有 TF-IDF，没有 BM25
- **完全依赖 LLM 判断**：基于文件名和描述字符串做相关性判断
- **基于文件扫描**：线性扫描目录，读文件头（前30行）
- **没有时间衰减**：权重仅由 LLM 隐式决定
- **没有加权评分**：LLM 只返回选中的文件名列表

### 1.4.2 对话恢复

`cursor_persistence.py` 中的 `restore()` 函数：

- 从 `conversation_store` 恢复对话历史
- 恢复累加器状态 (`OutputAccumulator.restore()`)
- 恢复迭代计数器、停滞检测状态
- 对于连续模式 (`continuous_mode=True`)：加载所有 parts
- 对于隔离模式：按 `phase_id` 过滤只加载当前节点的消息

### 1.4.3 Queen 的上下文注入

`AgentContext` 中包含 `memory_prompt` 字段，由 `dynamic_memory_provider` 动态提供：

```python
memory_prompt: str = ""

# AgentContext 中:
dynamic_memory_provider: Any = None   # 可选的回调，返回当前记忆块
```

---

## 1.5 Agent 之间的记忆共享与隔离

### 隔离策略

**文件记忆层面**（`queen_memory_v2.py`）：

| 范围 | 目录 | 可见性 |
|------|------|--------|
| `global` | `~/.hive/memories/global/` | 所有 Queen 和 Colony 共享 |
| `colonies/{name}` | `~/.hive/memories/colonies/{name}/` | 仅该 Colony 可见 |
| `agents/queens/{name}` | `~/.hive/memories/agents/queens/{name}/` | 仅该 Queen 可见 |
| `agents/{name}` | `~/.hive/memories/agents/{name}/` | 仅该 Worker 可见 |

**会话状态层面**：

- 每个会话有自己的 `state.json`
- 不同 Worker 的会话完全隔离
- `data_buffer` (memory) 是会话级别的

**对话层面**：

- 连续模式 (`conversation_mode="continuous"`)：所有 event_loop 节点共享同一个对话线程
- 隔离模式 (`conversation_mode="isolated"`)：每个节点有独立的对话

**检查点层面**：

- 检查点按 session_id 组织
- 每个会话的检查点互相隔离

### 共享机制

1. **Global 记忆**：`~/.hive/memories/global/` 目录下的 `.md` 文件对所有 Agent 可见
2. **连续对话模式**：多个节点共享同一个对话历史
3. **Colony 内 Worker**：Worker 是 Queen AgentLoop 的精确副本，共享相同的工具、提示词、LLM
4. **触发继承**：`Message.inherited_from` 标识从父会话继承的消息

### 核心结论

**Hive 的记忆隔离主要通过目录结构和会话ID实现，没有跨 Agent 的共享内存池或黑板系统。** `SharedBufferManager` 类已被标记为 stub（"Shared buffer was removed in colony refactor"）。

---

## 1.6 持久化方案

### 存储技术栈

| 存储类型 | 技术 | 位置 |
|---------|------|------|
| 文件记忆 | 纯文本 .md 文件 (YAML frontmatter) | `~/.hive/memories/` |
| 会话状态 | JSON 文件 (Pydantic model) | `~/.hive/agents/{name}/sessions/` |
| 对话消息 | 每个消息一个 JSON 文件 | `sessions/{id}/conversations/parts/` |
| 检查点 | JSON 文件 + 索引 | `sessions/{id}/checkpoints/` |
| 任务队列/进度 | SQLite (WAL 模式) | `~/.hive/colonies/{name}/data/progress.db` |
| 配置 | JSON 文件 | `~/.hive/configuration.json` |
| Queen 档案 | YAML 文件 | `~/.hive/agents/queens/{id}/profile.yaml` |
| 凭证 | 加密文件存储 | `~/.hive/credentials` |
| 会话摘要 | JSON 缓存 | `sessions/{id}/summary.json` |
| Agent 配置 | JSON 文件 (`agent.json`) | Agent 目录 |
| 运行时日志 | JSONL 文件 | `sessions/{id}/logs/` |
| MCP 服务器配置 | JSON 文件 (`mcp_servers.json`) | Agent 目录 |

### 原子写入

所有 JSON 写入都使用 `atomic_write()` 函数（`core/framework/utils/io.py`）：

```python
# 使用 temp file + rename 的原子写入模式
with atomic_write(path) as f:
    json.dump(data, f)
```

### 并发控制

- `CheckpointStore` 使用 `asyncio.Lock` 保护索引更新
- SQLite 使用 WAL 模式支持并发读取
- `FileConversationStore` 使用 `asyncio.to_thread` 进行非阻塞 I/O

### 结论

**Hive 没有使用向量数据库、没有使用专用内存数据库。持久化方案完全是文件系统 + SQLite 的朴素组合。** 文件记忆系统本质上是一个"Markdown 笔记"系统，通过 LLM 做相关性选择来检索。

---

# 2. Agent 编排分析

## 2.1 Agent 定义方式

Hive 支持两种 Agent 定义方式：

### 方式一：声明式 JSON 配置 (agent.json)

**核心文件**：`core/framework/schemas/agent_config.py`

通过 `agent.json` 文件定义 Agent，使用 Pydantic `AgentConfig` 模型：

```python
class AgentConfig(BaseModel):
    name: str                                              # Agent 名称
    version: str = "1.0.0"
    description: str | None = None
    metadata: MetadataConfig                               # intro_message 等
    variables: dict[str, str] = {}                         # 模板变量 {{var_name}}
    goal: GoalConfig                                       # 简化的目标定义
    nodes: list[NodeConfig]                                # 节点列表
    edges: list[EdgeConfig]                                # 边列表
    entry_node: str                                        # 入口节点ID
    terminal_nodes: list[str] = []                         # 终止节点ID
    pause_nodes: list[str] = []                            # 暂停节点ID
    entry_points: list[EntryPointConfig] = []              # 多入口点
    tools: ToolAccessConfig                                 # Agent 级工具访问
    mcp_servers: list[MCPServerRef] = []                   # MCP 服务器引用
    model: str | None = None                               # LLM 模型
    max_tokens: int = 4096
    conversation_mode: str = "continuous"
    identity_prompt: str = ""
    loop_config: dict = {...}                              # 循环配置
    pipeline: dict = {}                                    # Pipeline 覆盖
    max_cost_per_run: float | None = None
```

**工具访问配置**：

```python
class ToolAccessConfig(BaseModel):
    policy: str = "explicit"                               # "explicit" | "none" (禁止 "all")
    allowed: list[str] = []                                # 明确列出允许的工具名
    denied: list[str] = []                                 # 禁止的工具名 (在 allowed 之后应用)
```

**节点配置**：

```python
class NodeConfig(BaseModel):
    id: str
    name: str | None = None
    description: str | None = None
    node_type: str = "event_loop"                          # 目前只支持 event_loop
    system_prompt: str | None = None
    tools: ToolAccessConfig = ToolAccessConfig()
    model: str | None = None
    input_keys: list[str] = []
    output_keys: list[str] = []
    nullable_output_keys: list[str] = []
    max_iterations: int = 30
    max_node_visits: int = 1
    client_facing: bool = False
    success_criteria: str | None = None                     # 自然语言完成标准
    failure_criteria: str | None = None
    skip_judge: bool = False
    max_retries: int | None = None
```

**边配置**：

```python
class EdgeConfig(BaseModel):
    from_node: str                                         # 源节点ID
    to_node: str                                           # 目标节点ID
    condition: str = "on_success"                          # always|on_success|on_failure|conditional|llm_decide
    condition_expr: str | None = None
    input_mapping: dict[str, str] = {}
    priority: int = 1
```

### 方式二：Python 模块

通过 Python 代码直接构建 `GraphSpec`, `NodeSpec`, `EdgeSpec` 对象。例如 Queen Agent 的定义（`core/framework/agents/queen/agent.py`）：

```python
queen_goal = Goal(
    id="queen-manager",
    name="Queen Manager",
    description="Manage the worker agent lifecycle...",
    success_criteria=[],
    constraints=[],
)

queen_loop_config = {
    "max_iterations": 999_999,
    "max_tool_calls_per_turn": 30,
    "max_context_tokens": 180_000,
}
```

### 示例 Agent 模板

项目提供了多个示例模板（`examples/templates/`）：

| 模板 | 目录 |
|------|------|
| Deep Research Agent | `examples/templates/deep_research_agent/` |
| Email Reply Agent | `examples/templates/email_reply_agent/` |
| Email Inbox Management | `examples/templates/email_inbox_management/` |
| Job Hunter | `examples/templates/job_hunter/` |
| Competitive Intel Agent | `examples/templates/competitive_intel_agent/` |
| SDR Agent | `examples/templates/sdr_agent/` |
| Tech News Reporter | `examples/templates/tech_news_reporter/` |
| Twitter News Agent | `examples/templates/twitter_news_agent/` |
| Vulnerability Assessment | `examples/templates/vulnerability_assessment/` |
| Meeting Scheduler | `examples/templates/meeting_scheduler/` |
| Local Business Extractor | `examples/templates/local_business_extractor/` |

每个模板目录包含：`agent.json`（或 `agent.py`）、`flowchart.json`、`mcp_servers.json`、`config.py` 等。

---

## 2.2 Agent 注册表与发现机制

**Hive 没有中心化的 Agent 注册表/注册中心。** 采用**基于文件系统的目录扫描**方式：

### 发现机制

**文件**：`core/framework/agents/discovery.py`

```python
def discover_agents() -> dict[str, list[AgentEntry]]:
    """从所有已知来源发现 Agent，按类别分组"""
    sources = [
        ("Your Agents", COLONIES_DIR),                      # ~/.hive/colonies/
    ]
    # 扫描每个目录，寻找含有 worker 配置 JSON 文件的子目录
```

`AgentEntry` 数据结构：

```python
@dataclass
class AgentEntry:
    path: Path                    # Agent 目录路径
    name: str                     # Agent 名称
    description: str              # 描述
    category: str                 # 分类 ("Your Agents")
    session_count: int = 0        # 会话数
    run_count: int = 0            # 运行次数
    node_count: int = 0           # 节点数
    tool_count: int = 0           # 工具数
    tags: list[str] = []          # 标签
    last_active: str | None = None # 最后活跃时间
    created_at: str | None = None  # 创建时间
    icon: str | None = None       # 图标
    workers: list[WorkerEntry] = [] # 包含的 Worker
```

### 判断是否是 Colony 目录

```python
def _is_colony_dir(path: Path) -> bool:
    """检查目录是否是一个含有 Worker 配置文件的 Colony"""
    if not path.is_dir():
        return False
    return any(
        f.suffix == ".json" 
        and f.stem not in _EXCLUDED_JSON_STEMS      # 排除 agent, flowchart, triggers, configuration, metadata, tasks
        for f in path.iterdir() if f.is_file()
    )
```

### Worker 发现

```python
def _find_worker_configs(colony_dir: Path) -> list[Path]:
    """找到一个 Colony 目录中所有 Worker 配置 JSON 文件"""
    return sorted(
        p for p in colony_dir.iterdir() 
        if p.is_file() and p.suffix == ".json" 
        and p.stem not in _EXCLUDED_JSON_STEMS
    )
```

---

## 2.3 Agent 间通信/委派/协作

### Colony 模型 (Queen + Worker 模式)

**核心文件**：`core/framework/host/colony_runtime.py`

Hive 的 Agent 协作模型是 **Queen Overseer + Worker 克隆**：

```python
"""ColonyRuntime — 编排一组并行 Worker 克隆

每个 Worker 是 Queen 的 AgentLoop 的精确副本 — 相同的工具、
相同的提示词、相同的 LLM。Worker 独立运行，通过 EventBus 
将结果报告给 Queen。

ColonyRuntime 替代了 AgentHost 和 ExecutionManager。
没有图、没有边、没有节点、没有数据缓冲区。
只有：生成 N 个独立克隆，让它们运行，收集结果。
"""
```

### 通信机制：EventBus

**文件**：`core/framework/host/event_bus.py`

Agent 之间通过 `EventBus` 发布/订阅事件进行通信：

关键事件类型（从代码中推断）：
- `SUBAGENT_REPORT` — Worker 完成任务后向 Queen 报告
- `CLIENT_OUTPUT_DELTA` — Agent 输出文本流
- `CLIENT_INPUT_REQUESTED` — Agent 请求用户输入 (HITL)
- `LLM_TEXT_DELTA` — LLM 流式文本增量
- `TOOL_STARTED` / `TOOL_COMPLETED` — 工具执行状态
- `LOOP_STARTED` / `LOOP_COMPLETED` — 循环生命周期
- `STALLED` — Agent 停滞检测
- `CHECKPOINT_CREATED` — 检查点创建
- `TASK_CLAIMED` / `TASK_COMPLETED` — 任务队列状态

### Worker 生命周期

**文件**：`core/framework/host/worker.py`

Worker 有两种模式：

1. **短暂模式 (Ephemeral, 默认)**：
   - `PENDING -> RUNNING -> COMPLETED/FAILED/STOPPED`
   - 执行单个 AgentLoop，完成后发射 `SUBAGENT_REPORT` 事件，然后终止

2. **持久模式 (Persistent, Overseer 使用)**：
   - `PENDING -> RUNNING` (永不自动退出)
   - 通过 `inject(message)` 接收用户聊天
   - 每个注入消息触发 AgentLoop 的又一轮

### 委派机制

- Queen 通过 `spawn_worker()` 生成 Worker
- Worker 继承 Queen 的对话上下文（通过 `fork_session_into_colony`）
- Worker 可以通过 `report_to_parent` 工具向 Queen 发送结构化报告
- Worker 可以通过 `escalate` 工具升级问题

### 消息注入

```python
async def _persistent_input_loop(self) -> None:
    """将注入的消息泵入正在运行的 AgentLoop 中"""
    while True:
        msg = await self._input_queue.get()
        if msg is None:         # 哨兵：关闭
            return
        await self._agent_loop.inject_event(msg, is_client_input=True)
```

### 其他通信路径

- **Graph 内节点间通信**：通过共享的 `DataBuffer` 和边 `input_mapping`
- **连续对话模式**：所有 event_loop 节点共享一个对话线程
- **触发器 (Triggers)**：定时器/Webhook 事件触发 Agent 执行

---

## 2.4 动态 vs 静态 Agent 创建

### 静态定义

- Agent 通过 `agent.json` 文件声明式静态定义
- Queen 通过 YAML profile 文件静态定义（`~/.hive/agents/queens/{id}/profile.yaml`）
- **所有 9 个默认 Queen** 配置在代码中硬编码（`queen_profiles.py` 的 `DEFAULT_QUEENS` 字典）

### 动态生成

**Hive 的核心创新之一是 Coding Agent 动态生成 Agent 图：**

1. 用户用自然语言描述目标
2. **Coding Agent** (如 Claude Code 或 Cursor) 生成 Agent 图、节点、边和配置
3. 生成的 Agent 保存为文件（`agent.json`, Worker JSON 配置等）
4. 然后作为标准 Agent 加载和运行

**演化机制**：系统可以演化 Agent 图（README 中的流程图）：

```
Define Goal -> Auto-Generate Graph -> Execute Agents -> Monitor -> 
    Pass? -> Deliver Result
    No?   -> Evolve Graph -> Execute Agents
```

### 运行时行为

- Agent 数量由图定义中的节点数决定
- Colony 运行时可以动态生成 N 个 Worker 并行执行
- Worker 数量由配置控制（`HIVE_MAX_CONCURRENT_WORKERS`，默认 4）

---

## 2.5 计划-执行-评估 (Harness) 系统

**Hive 实现了完整的计划-执行-评估系统，但不同于传统的 Planner-Executor 模式。**

### 计划层面

**用户目标是起点**：

```python
class Goal(BaseModel):
    id: str
    name: str
    description: str
    status: GoalStatus                    # draft|ready|active|completed|failed|suspended
    success_criteria: list[SuccessCriterion]  # 成功标准
    constraints: list[Constraint]           # 约束
    context: dict[str, Any] = {}
    required_capabilities: list[str] = []
    input_schema: dict[str, Any] = {}
    output_schema: dict[str, Any] = {}
    version: str = "1.0.0"
    parent_version: str | None = None       # 演化版本
    evolution_reason: str | None = None     # 演化原因
```

**计划由 Coding Agent 生成**：从自然语言目标生成包含节点、边、条件的 GraphSpec。

### 执行层面

**Orchestrator** (`core/framework/orchestrator/orchestrator.py`) 执行 DAG：

1. 接收 `GraphSpec` + `Goal`
2. 初始化 `DataBuffer`
3. 从 `entry_node` 开始，按边遍历执行节点
4. 支持并行 Fan-out (多个 ON_SUCCESS 边)
5. 支持 Fan-in (多个前置节点的汇聚)
6. 记录所有决策到 `DecisionTracker`
7. 返回 `ExecutionResult`

**执行结果**：

```python
@dataclass
class ExecutionResult:
    success: bool
    output: dict[str, Any] = {}
    error: str | None = None
    steps_executed: int = 0
    total_tokens: int = 0
    total_latency_ms: int = 0
    path: list[str] = []                  # 已遍历的节点
    paused_at: str | None = None          # HITL 暂停点
    session_state: dict[str, Any] = {}    # 恢复状态
    total_retries: int = 0
    nodes_with_failures: list[str] = []
    had_partial_failures: bool = False
    execution_quality: str = "clean"      # "clean" | "degraded" | "failed"
    node_visit_counts: dict[str, int] = {}
```

### 评估层面

**多层次 Judge 系统** (`core/framework/agent_loop/internals/judge_pipeline.py`)：

| 级别 | 名称 | 行为 |
|------|------|------|
| Level 0 | 短路评估 | mark_complete_flag → ACCEPT; skip_judge → 跳过; 工具调用继续 → RETRY |
| Level 1 | 自定义 Judge | `JudgeProtocol.evaluate()` — 完全控制权 |
| Level 2 | 隐式 Judge | 输出键检查 + 对话感知质量门 (`success_criteria`) |

**Level 2 对话感知质量评估** (`core/framework/orchestrator/conversation_judge.py`)：

```python
async def evaluate_phase_completion(
    llm, conversation, phase_name, phase_description,
    success_criteria, accumulator_state, max_context_tokens
) -> PhaseVerdict:
    """Level 2 judge: 阅读对话并评估质量
    
    只在 Level 0 通过后调用 (所有输出键已设置)
    使用快速 LLM 调用来检查工作是否真正完成
    """
```

**目标级评估**：

```python
def is_success(self) -> bool:
    """检查所有加权成功标准是否满足"""
    total_weight = sum(c.weight for c in self.success_criteria)
    met_weight = sum(c.weight for c in self.success_criteria if c.met)
    return met_weight >= total_weight * 0.9  # 90% 阈值
```

---

## 2.6 工具绑定机制

### 绑定流程

1. **声明 (agent.json)**：Node 通过 `ToolAccessConfig` 声明需要的工具名列表
2. **发现**：`ToolRegistry` (`core/framework/loader/tool_registry.py`) 注册可用工具
3. **MCP 加载**：`McpRegistryStage` 加载 MCP 服务器并发现其工具
4. **技能工具**：`SkillRegistryStage` 注入技能相关的工具
5. **解析**：`build_node_context()` → `_resolve_available_tools()` 根据策略选择工具

### 工具访问策略

**文件**：`core/framework/orchestrator/context.py`

```python
def _resolve_available_tools(*, node_spec, tools, override_tools=None) -> list:
    """选择当前节点可用的工具"""
    
    # 始终包含框架默认工具
    _ALWAYS_AVAILABLE_TOOLS = frozenset({
        "read_file", "write_file", "edit_file", 
        "search_files", "set_output", "escalate",
    })
    
    always_tools = [t for t in tools if t.name in _ALWAYS_AVAILABLE_TOOLS]
    
    if policy == "none":
        return always_tools           # 只有框架默认工具
    
    # "explicit": 声明的工具 + 框架默认工具
    declared_tools = [t for t in tools if t.name in declared]
    return always_tools + declared_tools
```

### 合成工具 (Synthetic Tools)

**文件**：`core/framework/agent_loop/internals/synthetic_tools.py`

Agent 自动获得三个合成工具：

| 工具名 | 用途 |
|--------|------|
| `ask_user` | 显式请求用户输入 (HITL 阻塞) |
| `escalate` | 将问题升级给 Queen |
| `report_to_parent` | Worker 向 Queen 发送结构化报告 |

### MCP 工具集成

**文件**：`core/framework/loader/mcp_registry.py`, `core/framework/loader/mcp_client.py`

- 支持 STDIO 和 HTTP 传输的 MCP 服务器
- `mcp_servers.json` 配置每个 Agent 的 MCP 服务器
- 启动时自动发现 MCP 服务器的工具
- 工具通过 `ToolRegistry` 统一注册

### 内置工具

项目包含大量第三方集成工具（`tools/src/aden_tools/tools/`），覆盖：
CRM (Salesforce, HubSpot, Pipedrive, Zoho)，邮件 (Gmail, Email)，数据库 (Postgres, BigQuery, Snowflake, Redshift, MongoDB, Azure SQL, MSSQL)，项目管理 (Jira, Asana, Linear, Notion, Trello)，通信 (Slack, Discord, Telegram, Teams)，文件 (Google Drive/Sheets/Docs, Excel, CSV, PDF)，Web (LinkedIn, Twitter, Reddit, YouTube)，搜索 (Web Search, Wikipedia, Exa, ArXiv)，云 (AWS S3, Vercel, Cloudflare, Databricks)，安全 (DNS Scanner, Subdomain Enum, Port Scanner, SSL/TLS Scanner)

总共 **102 个 MCP 工具**。

---

## 2.7 会话管理与 Agent 生命周期管理

### 会话管理

**文件**：`core/framework/storage/session_store.py`, `core/framework/server/session_manager.py`

| 操作 | API |
|------|-----|
| 创建会话 | `generate_session_id()` → `session_YYYYMMDD_HHMMSS_{uuid_8char}` |
| 写入状态 | `write_state(session_id, state)` — 原子写入 |
| 读取状态 | `read_state(session_id)` |
| 列出会话 | `list_sessions(status, goal_id, limit)` |
| 删除会话 | `delete_session(session_id)` — 递归删除 |
| 检查存在 | `session_exists(session_id)` |

### Agent 生命周期

**文件**：`core/framework/loader/agent_loader.py`, `core/framework/host/agent_host.py`

```
加载 -> 验证 -> 启动 -> 运行/触发 -> 检查点/暂停 -> 恢复/完成 -> 停止 -> 清理
```

详细流程：

1. **加载** (`AgentLoader.load()`)：
   - 读取 `agent.json` 或 Python 模块
   - 构建 `GraphSpec` + `Goal`
   - 运行预加载验证 (`run_preload_validation`)

2. **启动** (`AgentLoader.start()`)：
   - 创建 Pipeline (LLM → Credentials → MCP → Skills)
   - 创建 `AgentHost` (或 `ColonyRuntime`)
   - 注册入口点
   - 初始化检查点配置

3. **运行** (`AgentLoader.run()` — 一次性 Worker):
   - 验证凭证
   - `AgentHost.trigger_and_wait()`
   - 返回 `ExecutionResult`

4. **触发** (`AgentLoader.trigger()` — 持久化 Queen):
   - 非阻塞触发
   - 返回 Execution ID

5. **检查点**：
   - 每个节点完成时自动创建检查点
   - 异步检查点写入 (`async_checkpoint=True`)
   - 7 天后自动清理

6. **恢复**：
   - 从最新检查点恢复 (`load_checkpoint()`)
   - 恢复对话、累加器、迭代计数器
   - 恢复停滞检测状态

7. **停止** (`AgentLoader.stop()`)：
   - 停止 AgentHost
   - 清理 MCP 连接
   - 清理临时目录

### 入口点管理

**文件**：`core/framework/host/execution_manager.py`

```python
@dataclass
class EntryPointSpec:
    id: str = "default"
    name: str = "Default"
    entry_node: str | None = None
    trigger_type: str = "manual"       # manual | scheduled | timer
    trigger_config: dict = {}
    isolation_level: str = "shared"     # isolated | shared | synchronized
    max_concurrent: int | None = None
```

### 超时与限制

```python
class GraphSpec(BaseModel):
    max_steps: int = 100               # 最大节点执行数
    max_retries_per_node: int = 3      # 每节点最大重试
    max_tokens: int = 8192             # 最大 Token
    max_cost_per_run: float | None = None  # 单次最大费用

# Loop 配置
loop_config = {
    "max_iterations": 100,
    "max_tool_calls_per_turn": 30,
    "max_context_tokens": 32000,
}

# Parallel 配置
class ParallelExecutionConfig:
    branch_timeout_seconds: float = 300.0  # 每分支超时
```

---

# 3. Agent 实现分析

## 3.1 Agent 数据结构

### AgentLoop — 核心 Agent 实现

**文件**：`core/framework/agent_loop/agent_loop.py`

```python
class AgentLoop(AgentProtocol):
    """多轮 LLM 流式循环 with 工具执行和 Judge 评估"""
    
    def __init__(self, event_bus, judge, config, tool_executor, conversation_store):
        self._event_bus = event_bus              # EventBus 事件总线
        self._judge = judge                       # JudgeProtocol 判断器
        self._config = config or LoopConfig()     # 循环配置
        self._tool_executor = tool_executor       # 工具执行器
        self._conversation_store = conversation_store  # 对话持久化
        self._injection_queue: asyncio.Queue      # 注入队列 (用户输入)
        self._trigger_queue: asyncio.Queue        # 触发器队列
        self._input_ready = asyncio.Event()       # 输入就绪事件
        self._awaiting_input = False              # 等待输入标志
        self._shutdown = False                    # 关闭标志
        self._stream_task: asyncio.Task | None    # 流任务
        self._tool_task: asyncio.Task | None      # 工具任务
        self._bg_tasks: TaskRegistry              # 后台任务注册表
        self._spill_counter: int = 0              # 溢出计数器
        self._report_terminated: bool = False     # 报告终止
        self._owner_worker: Any = None            # 拥有者 Worker
        self._counters: dict[str, int] = {}       # 可靠性计数器
```

### AgentSpec — 声明式定义

**文件**：`core/framework/agent_loop/types.py`

```python
class AgentSpec(BaseModel):
    id: str                                          # 唯一标识
    name: str                                        # 名称
    description: str                                 # 描述
    agent_type: str = "event_loop"                   # agent类型
    input_keys: list[str] = []                       # 输入键
    output_keys: list[str] = []                      # 输出键
    nullable_output_keys: list[str] = []             # 可为空的输出键
    input_schema: dict[str, dict] = {}               # 输入模式
    output_schema: dict[str, dict] = {}              # 输出模式
    system_prompt: str | None = None                 # 系统提示词
    tools: list[str] = []                            # 工具名列表
    tool_access_policy: str = "explicit"             # 工具访问策略
    model: str | None = None                         # 模型覆盖
    function: str | None = None                      # 函数名或路径
    routes: dict[str, str] = {}                      # 条件路由
    max_retries: int = 3
    retry_on: list[str] = []
    max_visits: int = 0                              # 最大访问次数, 0=无限制
    output_model: type[BaseModel] | None = None      # Pydantic 输出验证
    max_validation_retries: int = 2                  # 验证重试
    client_facing: bool = False                      # 已弃用
    success_criteria: str | None = None              # 成功标准
    skip_judge: bool = False                         # 跳过 Judge
```

### AgentContext — 运行时上下文

**文件**：`core/framework/agent_loop/types.py`

```python
@dataclass
class AgentContext:
    runtime: DecisionTracker                          # 决策跟踪器
    agent_id: str
    agent_spec: AgentSpec
    input_data: dict[str, Any] = {}                  # 输入数据
    llm: LLMProvider | None = None                   # LLM 提供者
    available_tools: list[Tool] = []                 # 可用工具
    goal_context: str = ""                           # 目标上下文
    goal: Any = None                                 # Goal 对象
    max_tokens: int = 4096
    attempt: int = 1
    max_attempts: int = 3
    runtime_logger: Any = None
    pause_event: Any = None                          # 暂停事件
    accounts_prompt: str = ""                        # 账户提示
    identity_prompt: str = ""                        # 身份提示
    narrative: str = ""                              # 叙事提示
    memory_prompt: str = ""                          # 记忆提示
    event_triggered: bool = False
    execution_id: str = ""
    run_id: str = ""
    stream_id: str = ""
    # 任务系统字段
    task_list_id: str | None = None
    colony_id: str | None = None
    picked_up_from: tuple[str, int] | None = None
    # 动态提供者
    dynamic_tools_provider: Any = None
    dynamic_prompt_provider: Any = None
    dynamic_prompt_suffix_provider: Any = None
    dynamic_memory_provider: Any = None
    dynamic_skills_catalog_provider: Any = None
    # 技能相关
    skills_catalog_prompt: str = ""
    protocols_prompt: str = ""
    skill_dirs: list[str] = []
    default_skill_batch_nudge: str | None = None
    default_skill_warn_ratio: float | None = None
    iteration_metadata_provider: Any = None
```

### AgentResult — 执行输出

**文件**：`core/framework/agent_loop/types.py`

```python
@dataclass
class AgentResult:
    success: bool
    output: dict[str, Any] = {}
    error: str | None = None
    next_agent: str | None = None
    route_reason: str | None = None
    tokens_used: int = 0
    latency_ms: int = 0
    validation_errors: list[str] = []
    conversation: Any = None
    exit_reason: str = "?"
    reliability_stats: dict[str, int] = {}          # 可靠性统计
```

---

## 3.2 Agent 执行循环

Hive 的 AgentLoop 实现的是一个 **流式 ReAct 变体**（Streaming ReAct-like Loop），不是经典的 Plan-Execute。具体流程：

### 生命周期

**文件**：`core/framework/agent_loop/agent_loop.py` — `execute()` 方法

```
1. 尝试从持久状态恢复 (崩溃恢复)
   └─ restore() → RestoredState (对话, 累加器, 迭代计数)
2. 如果没有先前状态，从 AgentSpec.system_prompt + input_keys 初始化
3. 发布 LOOP_STARTED 事件
4. 主循环:
   ├── drain_injection_queue()     — 处理注入的用户输入
   ├── drain_trigger_queue()       — 处理触发器事件
   ├── check_pause()               — 检查是否暂停
   ├── perform_iteration():
   │   ├── 构建 LLM 消息 (from conversation)
   │   ├── 调用 stream()           — 流式 LLM 调用
   │   ├── 处理 TextDeltaEvent     — 发布文本增量
   │   ├── 处理 ToolCallEvent:
   │   │   ├── coerce_tool_input()  — 强制转换工具输入
   │   │   ├── execute_tool()       — 执行工具
   │   │   └── truncate_tool_result() — 截断结果
   │   ├── 处理 FinishEvent         — 完成事件
   │   └── 写入 partial             — 检查点部分
   ├── judge_turn()                 — 评估当前轮次
   │   ├── Level 0: 短路 (mark_complete/skip_judge)
   │   ├── Level 1: 自定义 Judge
   │   └── Level 2: 隐式 Judge (输出键+质量)
   ├── write_cursor()               — 持久化光标
   ├── compaction()                 — 如果上下文太大，触发压缩
   └── 判断:
       ├── ACCEPT  → 跳出循环
       ├── RETRY   → 继续循环 (注入反馈)
       └── REJECT  → 失败
5. 构建输出
6. 发布 LOOP_COMPLETED 事件
7. 返回 AgentResult
```

### Queen 交互阻塞

当 Queen Agent 在执行时，有一个特殊的**文本轮次阻塞**机制：

```python
# agent_loop.py 注释:
"""
Queen interaction blocking:

- Text-only turns (没有真正的工具调用)
  自动阻塞等待用户输入。如果 LLM 在和用户对话（不是调工具），
  它应该在 Judge 运行前等待用户响应。

- Work turns (有工具调用)
  不阻塞地流过 — LLM 在进展中，不是问用户。

- ask_user 合成工具
  为显式阻塞注入，当 LLM 想主动请求输入时使用。
"""
```

---

## 3.3 Agent 能调用的工具

### 工具类型分类

**1. 框架默认工具 (始终可用)**：

```python
_ALWAYS_AVAILABLE_TOOLS = frozenset({
    "read_file",      # 读取文件
    "write_file",     # 写入文件
    "edit_file",      # 编辑文件
    "search_files",   # 搜索文件
    "set_output",     # 设置输出
    "escalate",       # 升级到 Queen
})
```

**2. 合成工具 (框架注入)**：

| 工具名 | 文件 | 用途 |
|--------|------|------|
| `ask_user` | `synthetic_tools.py::build_ask_user_tool()` | 暂停执行，等待用户输入 |
| `escalate` | `synthetic_tools.py::build_escalate_tool()` | 将问题升级给 Queen |
| `report_to_parent` | `synthetic_tools.py::build_report_to_parent_tool()` | Worker 向 Queen 发送报告 |

**3. 声明式工具 (per-node 配置)**：

每个节点在 `agent.json` 的 `tools.allowed` 列表中声明，或继承 Agent 级别的 `tools` 配置。

**4. MCP 工具**：

通过 `mcp_servers.json` 配置，运行时从 MCP 服务器自动发现和注册。项目内置了 **102 个 MCP 工具** (覆盖数据库、CRM、邮件、文件、搜索、安全等)。

**5. 任务系统工具** (`core/framework/tasks/tools/`)：

- Colony 级工具：管理 Colony 内任务
- Session 级工具：管理会话任务

**6. 技能工具** (`core/framework/skills/`)：

通过技能注册表动态注入的工具，与特定 Skill 绑定。

### 工具执行流程

```python
# agent_loop.py 中的工具执行
async def execute_tool(tool_use, tool_executor, ...):
    # 1. 发布 TOOL_STARTED 事件
    # 2. coerce_tool_input() — 强制转换输入类型
    # 3. 调用 tool_executor(tool_use)
    # 4. truncate_tool_result() — 截断过长结果
    # 5. 如果是图片结果且模型不支持 → vision_fallback 链
    # 6. 发布 TOOL_COMPLETED 事件
    # 7. 返回 ToolResult
```

---

## 3.4 Agent 的反思/自我评估

### Judge 管道

Hive 实现了多层次的自评估机制：

**Level 0 — 短路评估**：

```python
# 条件:
if mark_complete_flag:   return ACCEPT     # 显式完成标记
if ctx.skip_judge:       return RETRY      # 跳过评估
if 有工具调用:           return RETRY      # 继续执行工具
```

**Level 1 — 自定义 Judge**：

```python
class JudgeProtocol:
    async def evaluate(self, context: dict) -> JudgeVerdict:
        """自定义评估逻辑
        context: {
            "assistant_text": str,
            "tool_calls": list,
            "output_accumulator": dict,
            "iteration": int,
            "conversation_summary": str,
            "output_keys": list[str],
            "missing_keys": list[str],
        }
        """
```

`SubagentJudge`：检查缺失的输出键，根据剩余迭代次数调整紧迫性。

**Level 2 — 对话感知质量评估**：

```python
async def evaluate_phase_completion(llm, conversation, phase_name, 
    phase_description, success_criteria, accumulator_state, max_context_tokens
) -> PhaseVerdict:
    """使用 LLM 评估工作质量
    
    只在 Level 0 通过后才调用
    返回: {action: ACCEPT|RETRY, confidence: 0.X, feedback: str}
    """
```

### 反思 Agent

**文件**：`core/framework/agents/queen/reflection_agent.py`（注：文件名拼写为 refletion，不是 reflection）

代码中还有一个 `incubating_evaluator.py` 用于评估 Agent 性能。

### 演化机制

系统可以在失败后自动演化 Agent 图：

```
Monitor → Check Result → 
    Pass? → Deliver
    No? → Evolve Graph → Re-execute
```

演化理由记录在 `Goal.parent_version` 和 `Goal.evolution_reason` 中。

---

## 3.5 Agent 的上下文管理

### Token 预算与压缩

**多重保护机制**：

| 机制 | 实现文件 | 行为 |
|------|---------|------|
| `max_context_tokens` | `agent_loop.py` | 上下文窗口的硬限制 (默认 32000, Queen 使用 180000) |
| `max_tokens` | `GraphSpec` | LLM 调用限制 (默认 8192) |
| 上下文过大错误检测 | `agent_loop.py::_is_context_too_large_error()` | 正则匹配各 LLM 提供商的错误模式 |
| 上下文使用发布 | `event_publishing.py::publish_context_usage()` | 监控 token 使用率 |

### 多级压缩管道

**文件**：`core/framework/agent_loop/internals/compaction.py`

压缩策略（从轻到重）：

```
Level 0: 微观压缩 (Microcompaction)
  - 基于计数的 old tool result 清理
  - 保留最近 8 个可压缩工具结果
  - 无 LLM 调用，最低成本

Level 1: 令牌预算清理 (Token-budget based)
  - 剪除旧的 tool result
  - 基于当前对话和目标的令牌预算

Level 2: 结构保持压缩 (Spillover)
  - 保持消息结构
  - 将过长的内容溢出到文件

Level 3: LLM 摘要压缩
  - 使用 LLM 生成对话摘要
  - 支持递归拆分处理超长对话
  - 字符限制: 240_000
  - 最大深度: 10

Level 4: 紧急确定性摘要 (Emergency)
  - 不使用 LLM
  - 纯确定性的摘要策略
  - 作为最后的手段
```

**可压缩工具列表**（白名单）：

```python
COMPACTABLE_TOOLS = frozenset({
    "read_file", "search_files", "write_file", "edit_file", "pdf_read",
    "terminal_exec", "terminal_rg", "terminal_find",
    "terminal_output_get", "terminal_job_logs",
    "web_scrape", "search_papers", "download_paper", "search_wikipedia",
    "browser_screenshot", "browser_snapshot", "browser_html", "browser_get_text",
})
```

**电路断路器**：

```python
MAX_CONSECUTIVE_FAILURES: int = 3  # 连续压缩失败后停止自动压缩
```

### 溢出机制

`OutputAccumulator` 支持 `spillover_dir` 和 `max_value_chars`，当输出值过大时自动溢出到磁盘文件。

### Vision 回退链

**文件**：`core/framework/agent_loop/internals/vision_fallback.py`

当模型的工具结果包含图片但模型不支持视觉输入时：

```
1. 配置的 vision_fallback 模型 → 重试 → 
2. 相同模型重试 → 
3. gemini/gemini-3-flash-preview (覆盖)
```

---

## 3.6 Agent 的身份/人格/系统提示

### Onion Model (洋葱模型)

**文件**：`core/framework/orchestrator/node.py` 和 `GraphSpec`

系统提示词采用分层组合模型：

```
Layer 1: identity_prompt (Agent 级，静态)
    └─ 在连续模式下贯穿所有节点转换不变
    └─ 在隔离模式下被忽略

Layer 2: system_prompt (Node 级，per-phase)
    └─ 每个事件循环节点的系统提示

Layer 3: skills + memory + protocols
    └─ 动态注入的技能目录、记忆块、协议提示

Layer 4: accounts_prompt
    └─ 连接的账户信息
```

### Queen 身份/人格系统

**文件**：`core/framework/agents/queen/queen_profiles.py`

Queen 拥有精心设计的**五柱角色构造系统**：

| 柱 | 内容 | 是否对用户可见 |
|----|------|---------------|
| Pillar 1: Core Identity | 姓名、头衔、核心特质 | 是 |
| Pillar 2: Hidden Background | 过往创伤、深层动机、行为映射 | **否** (仅内部) |
| Pillar 3: Psychological Profile | 社交面具、反刻板印象规则 | 部分 |
| Pillar 4: Behavior Rules | 交互触发器 | 否 |
| Pillar 5: Negative Constraints | 禁止事项 | 是 |

**9 个默认 Queen 角色**：

| ID | 姓名 | 头衔 | 领域 |
|----|------|------|------|
| `queen_technology` | Alexandra | Head of Technology | 技术架构、工程 |
| `queen_growth` | Victoria | Head of Growth | 增长、分析 |
| `queen_product_strategy` | Isabella | Head of Product Strategy | 产品策略、用户体验研究 |
| `queen_finance_fundraising` | Charlotte | Head of Finance | 融资、财务建模 |
| `queen_legal` | Eleanor | Head of Legal | 法务、合同 |
| `queen_brand_design` | Sophia | Head of Brand & Design | 品牌设计 |
| `queen_marketing` | Catherine | Head of Marketing | 市场、需求生成 |
| `queen_talent` | Amelia | Head of Talent | 人才招聘 |
| `queen_operations` | Rachel | Head of Operations | 运营、流程优化 |

**Queen 选择机制**：

```python
async def select_queen_with_reason(user_message, llm) -> QueenSelection:
    """使用轻量级 LLM 分类器选择最佳匹配的 Queen
    
    系统提示：假装自己是 CEO，选择最适合处理请求的 Queen
    返回：{queen_id, reason}
    """
```

**角色提示词格式化** (`format_queen_identity_prompt()`):

整个 profile 被转换为 XML 标签块注入到系统提示词中，包含：
- `<core_identity>` — 姓名和头衔
- `<hidden_background>` — 创伤、动机、行为映射
- `<psychological_profile>` — 社交面具和反刻板印象
- `<behavior_rules>` — 内部评估流程 + 触发器
- `<negative_constraints>` — 禁止事项
- `<world_lore>` — 环境和词汇
- `<core_skills>` — 技能列表
- `<roleplay_examples>` — 少量示例对话（展示完整的内部推理过程）

### 内部推理标签

Queen 的响应中可能包含 **5 柱角色评估标签**，流式传输时被剥离：

```python
_INTERNAL_TAGS = frozenset({
    "relationship",    # 与对方的关系
    "context",         # 当前上下文
    "sentiment",       # 情感状态
    "physical_state",  # 身体状态
    "tone",            # 语气
})
```

### Worker 身份

Worker 的身份提示词通过 `identity_prompt` 字段设置，可选。Worker 不继承 Queen 的角色人格（除非在配置中明确设置且 Worker 是 Queen 的克隆副本）。

---

---

# 4. 深度执行细节分析

## 4.1 AgentLoop 完整执行流程图解

基于 `agent_loop.py::_execute_impl()` 的源代码分析（第 473-799+ 行），完整执行流程如下：

```
AgentResult execute(ctx)
  │
  ├── 1. 验证: LLM provider 必须存在 (否则返回 guard_failure)
  │
  ├── 2. 恢复或创建新对话:
  │     ├── restore() → RestoredState
  │     │   ├── 从 conversation_store 恢复对话 (按 phase_id 过滤)
  │     │   ├── 恢复 OutputAccumulator (包括溢出目录 + 最大字符限制)
  │     │   ├── 读取 cursor.json: 迭代计数 + 停滞检测状态
  │     │   ├── 恢复 run_id 过滤 (run_id 边界确保不同运行隔离)
  │     │   └── 刷新 system_prompt (提示可能已更改)
  │     │
  │     └── 新建对话:
  │         ├── 清除旧的 conversation_store
  │         ├── build_system_prompt_for_context(ctx)
  │         │   └── 组合: identity_prompt + system_prompt + skills + memory
  │         ├── 创建 NodeConversation (含 system_prompt + token 限制)
  │         ├── 创建 OutputAccumulator
  │         └── _build_initial_message(ctx) 添加首条用户消息
  │
  ├── 3. 构建工具列表:
  │     ├── 基础: ctx.available_tools (按 policy 解析)
  │     ├── + ask_user (queen 交互模式)
  │     ├── + escalate (worker 模式)
  │     ├── + report_to_parent (并行 worker 模式)
  │     └── - 对文本模型隐藏图片工具 (除非有 vision_fallback)
  │
  ├── 4. 发布 LOOP_STARTED 事件
  │
  ├── 5. 主循环 (for iteration in range(start_iteration, max_iterations)):
  │     │
  │     ├── 0. 早期退出检查:
  │     │   ├── report_terminated? → 返回成功 (Worker 已完成报告)
  │     │   └── check_pause()? → 返回 paused 状态 (HITL)
  │     │
  │     ├── 1. 排出队列:
  │     │   ├── drain_injection_queue()
  │     │   │   └── 用户输入 (来自 /chat 或 ask_user 恢复)
  │     │   └── drain_trigger_queue()
  │     │       └── 框架事件 (定时器、Webhook)
  │     │
  │     ├── 1b. 恢复用户输入等待 (pending_input_state):
  │     │   ├── 有新注入 → 清除等待状态, 继续循环
  │     │   └── 无新注入 → _await_user_input() 阻塞等待
  │     │
  │     ├── 2. 刷新动态内容:
  │     │   ├── 动态工具提供者 → 替换 ctx.available_tools
  │     │   ├── 动态提示词提供者 → 替换 system_prompt
  │     │   └── 动态记忆提供者 → 替换 memory_prompt
  │     │
  │     ├── 3. 流式 LLM 调用:
  │     │   ├── llm.stream(messages, system=system_prompt, tools=tools, max_tokens=...)
  │     │   ├── 异步迭代流事件:
  │     │   │   ├── TextDeltaEvent → publish_text_delta() + 累积
  │     │   │   ├── ToolCallEvent → 收集 tool 调用
  │     │   │   ├── FinishEvent → 记录 stop_reason + 使用统计
  │     │   │   └── StreamErrorEvent → 错误处理
  │     │   └── 处理完成后: 添加 assistant 消息 + tool 结果消息到对话
  │     │
  │     ├── 4. 工具执行:
  │     │   ├── coerce_tool_input() → 类型强制转换
  │     │   ├── execute_tool(tool_use, ctx):
  │     │   │   ├── 发布 TOOL_STARTED 事件
  │     │   │   ├── 执行工具函数
  │     │   │   ├── truncate_tool_result() → 截断过长结果
  │     │   │   ├── 如果图片结果且模型不支持:
  │     │   │   │   └── _captioning_chain() (3 次尝试回退链)
  │     │   │   └── 发布 TOOL_COMPLETED 事件
  │     │   └── 特殊处理: ask_user → 阻塞等待用户输入
  │     │
  │     ├── 5. 停滞检测:
  │     │   ├── fingerprint_tool_calls() → 工具调用指纹
  │     │   ├── is_stalled(fingerprint, recent_responses) → 重复检测
  │     │   └── is_tool_doom_loop() → doom loop 检测
  │     │       └── 注入 nudge 消息提示 agent 改变策略
  │     │
  │     ├── 6. Judge 评估:
  │     │   ├── Level 0: mark_complete → ACCEPT
  │     │   ├── Level 0: skip_judge → 继续 (不记录反馈)
  │     │   ├── Level 0: 有工具调用 → RETRY (继续)
  │     │   ├── Level 1: 自定义 judge.evaluate() → verdict
  │     │   ├── Level 2: 输出键检查 → 缺失则 RETRY + 紧迫性
  │     │   └── Level 2: success_criteria → evaluate_phase_completion()
  │     │       └── LLM 调用评估对话质量
  │     │
  │     ├── 7. Queen 自动阻塞:
  │     │   ├── 文本轮次连续计数 (无工具调用、无 set_output)
  │     │   ├── 达到阈值 → _await_user_input() 阻塞
  │     │   └── 不匹配 → 重置计数, 继续
  │     │
  │     ├── 8. Worker 自动升级:
  │     │   ├── 文本轮次连续计数
  │     │   └── 达到阈值 → 自动调用 escalate 工具
  │     │
  │     ├── 9. 上下文压缩 (如果需要):
  │     │   ├── microcompact() → 清除旧工具结果
  │     │   ├── compact() → 令牌预算剪枝
  │     │   ├── llm_compact() → LLM 摘要压缩
  │     │   └── 发布 CONTEXT_USAGE 事件
  │     │
  │     └── 10. 持久化:
  │         ├── write_cursor() → 迭代计数 + 停滞状态 + 累加器
  │         ├── 更新对话消息 (已通过 write_part 持久化)
  │         └── 发布 ITERATION_COMPLETED 事件
  │
  ├── 6. 构建输出:
  │     └── 从 OutputAccumulator.to_dict() 提取输出
  │
  ├── 7. 发布 LOOP_COMPLETED 事件
  │
  └── 8. 返回 AgentResult (success, output, tokens_used, ...)
```

## 4.2 对话压缩管道详细分析

**文件**: `core/framework/agent_loop/internals/compaction.py`

压缩是在 LLM 上下文接近限制时触发的保护机制。管道包含5个级别（0-4），从最轻量到最重量级：

### Level 0: 微观压缩 (microcompact)

```python
def microcompact(conversation, *, keep_recent=8) -> int:
    """基于计数的旧工具结果清理
    
    算法:
    1. 从最新消息向前扫描
    2. 收集 COMPACTABLE_TOOLS 白名单中的工具结果 (最多保留 8 个最近的)
    3. 将超过保留数的旧工具结果内容替换为占位符 "(result truncated)"
    4. 不改变消息结构，只修改内容
    
    成本: 零 LLM 调用, 纯确定性的字符串替换
    触发: 每次迭代结束时检查
    """
```

`COMPACTABLE_TOOLS` 白名单包含 18 种工具，它们的结果可以安全清除，因为 Agent 可以按需重新获取（读取文件、搜索、截图等）。

### Level 1: 令牌预算剪枝

```python
# 基于 compact() 调用，按令牌预算剪除旧工具结果
# 保留关键消息（用户消息、非工具结果、最近输出）
# 只在微观压缩不够时触发
```

### Level 2: 结构保持压缩 (溢出)

内容过大的消息被截断并溢出到磁盘文件 (`spillover_dir`)，在对话中保留文件引用。

### Level 3: LLM 摘要压缩

```python
LLM_COMPACT_CHAR_LIMIT: int = 240_000    # 超过此字符数触发主动拆分
LLM_COMPACT_MAX_DEPTH: int = 10          # 最大递归深度

def llm_compact(conversation, config) -> str:
    """使用 LLM 生成对话摘要
    
    1. 格式化消息为文本 (每条消息截断至合理长度)
    2. 如果超过 LLM_COMPACT_CHAR_LIMIT:
       → 递归二分拆分 (对半分割消息列表，分别摘要，合并)
    3. 调用 LLM: "你需要保留用户规则、约束、偏好;
       保留关键决策和结果; 保留下一阶段需要的上下文"
    4. 附加工具调用历史
    """
```

### Level 4: 紧急确定性摘要

```python
def build_emergency_summary(messages) -> str:
    """不使用 LLM 的纯确定性策略
    
    提取:
    - 所有用户消息 (直接引用)
    - 工具调用名称和时间线
    - set_output 调用
    - 错误消息
    
    这是最后的手段，在所有 LLM 摘要尝试失败后使用
    """
```

### 压缩电路断路器

```python
MAX_CONSECUTIVE_FAILURES: int = 3
# 跟踪 _failure_counts[conversation_id]
# 连续压缩失败 3 次后停止自动压缩
# 避免在 LLM 错误情况下反复调用压缩造成级联失败
```

## 4.3 Colony Worker 生成与管理

**文件**: `core/framework/host/colony_runtime.py` (第 155+ 行)

### ColonyRuntime 初始化流程

```
ColonyRuntime.__init__():
  1. 创建 PipelineRunner (LLM + Credentials + MCP + Skills)
  2. 创建 SkillsManager (加载技能目录)
  3. 确保 Colony 任务模板存在于任务存储
  4. 创建 ConcurrentStorage (缓存 + 批处理)
  5. 创建 SessionStore
  6. 创建 EventBus (或使用传入的)
  7. 包装为 StreamEventBus (自动打 colony_id 标签)
  8. 初始化 MCP 工具白名单过滤
```

### Worker 生成机制

```python
# ColonyRuntime.spawn()
# 1. 解析 AgentSpec (queen 的副本)
# 2. 创建 Worker 实例:
#    - 分配 worker_id (格式: w_{uuid})
#    - 绑定 AgentLoop (clone)
#    - 绑定 AgentContext
#    - 设置 profile_name (从 colony 配置)
#    - 设置 integrations (账户覆盖)
#    - 设置 storage_path (per-worker 目录)
# 3. 注册到活跃 workers 字典
# 4. asyncio.create_task(worker.run())
# 5. 返回 worker_id
```

### Worker 并发控制

```python
_DEFAULT_MAX_CONCURRENT_WORKERS = env_int("HIVE_MAX_CONCURRENT_WORKERS", 4)
# 默认4个并发 - 笔记本电脑安全值
# 通过信号量控制
```

### Worker 报告到 Queen 的通信路径

```
Worker AgentLoop
  └─ LLM 调用 report_to_parent({status, summary, data})
      └─ synthetic_tools.handle_report_to_parent()
          └─ agent_loop._owner_worker.record_explicit_report()
          └─ 发射 SUBAGENT_REPORT 事件到 EventBus
              └─ Queen (或 Overseer) 订阅并接收
```

## 4.4 事件系统详解

### EventBus 架构

```
EventBus
  ├── _subscriptions: dict[str, Subscription]     # 按 subscription_id 索引
  ├── _event_history: deque[AgentEvent]            # 最近事件历史
  ├── _semaphore: asyncio.Semaphore               # 并发控制
  └── publish(event: AgentEvent):
      ├── 添加到 _event_history
      ├── 遍历 _subscriptions
      │   ├── 按 event_types 过滤
      │   ├── 按 colony_id 过滤 (如果有 scoped bus)
      │   └── 调用 handler(event)
      └── 通知等待者 (wait_for)
```

### 作用域事件总线

两种包装器确保事件正确路由：

1. **GraphScopedEventBus** (`execution_manager.py` 第 54 行):
   - 自动在事件上打 `graph_id` 标签
   - 用于 Orchestrator 和 EventLoopNode

2. **StreamEventBus** (`colony_runtime.py` 第 126 行):
   - 自动在事件上打 `colony_id` 标签
   - 用于 ColonyRuntime

## 4.5 错误恢复与韧性机制

### 崩溃恢复路径

```
进程崩溃
  ↓
新进程启动, 加载同一个 session
  ↓
SessionStore.read_state(session_id)
  ├── 检查 checkpoint_enabled + latest_checkpoint_id
  ├── 检查 pid (跨进程陈旧会话检测)
  └── 如果可恢复:
      ↓
CheckpointStore.load_checkpoint(latest_checkpoint_id)
  ├── 恢复 data_buffer (所有键值对)
  ├── 恢复 execution_path (已执行的节点列表)
  ├── 恢复 accumulated_outputs
  ├── 恢复 metrics_snapshot
  └── 确定 current_node (从检查点或路径)
      ↓
Orchestrator.execute(graph, goal, session_state=...)
  ├── 从恢复的节点继续执行
  └── 不去重执行已完成的节点
```

### 部分消息恢复

`conversation_store.read_all_partials()` 返回飞行中的消息 — 被中断的 LLM 流的部分输出。恢复时作为 `truncated=True` 消息添加，Agent 可以看到不完整的上一轮并决定是否重做。

### 迭代级韧性

- `max_retries`: 每个节点可配置的重试次数
- `retry_on`: 可配置的重试错误类型
- 节点内部重试 (Judge RETRY) vs 外部重试 (Orchestrator)
- WP-7 强制: EventLoopNode 返回 `retryable=False`，内部处理重试

### 工具级韧性

```python
def _build_tool_error_result(tc, exc) -> ToolResult:
    """将工具异常转换为模型可读的 ToolResult
    
    特殊处理 CredentialExpiredError:
    - 结构化 payload: {error: "credential_expired", credential_id, provider, reauth_url}
    - Agent 的 behavior block 识别并提示用户重新授权
    """
```

### 停滞检测与自动恢复

```python
# stall_detector.py
def is_stalled(recent_responses, recent_tool_fingerprints) -> bool:
    """检测 Agent 是否陷入停滞
    
    算法:
    1. 连续 N 轮文本响应完全相同 → 停滞
    2. 工具调用指纹重复出现 → doom loop
    3. n-gram 相似度 > 阈值 → 警告
    """

def is_tool_doom_loop(fingerprints) -> bool:
    """检测工具 doom loop
    
    相同的工具序列连续重复出现 → Agent 陷入无进展循环
    自动注入 nudge 消息建议改变策略
    """
```

## 4.6 技能系统集成

**文件**: `core/framework/skills/`

### 技能架构

```
SkillsManager
  ├── 加载来源:
  │   ├── _default_skills/     # 框架内置技能 (6个)
  │   │   ├── colony-progress-tracker
  │   │   ├── context-preservation
  │   │   ├── error-recovery
  │   │   ├── note-taking
  │   │   ├── quality-monitor
  │   │   └── writing-hive-skills
  │   ├── _preset_skills/      # 预置技能 (6个)
  │   │   ├── browser-automation
  │   │   ├── chart-creation-foundations
  │   │   ├── linkedin-automation
  │   │   ├── terminal-tools-foundations
  │   │   ├── terminal-tools-fs-search
  │   │   └── terminal-tools-job-control
  │   ├── project skills/      # 项目级 .hive/skills/
  │   ├── colony_ui skills/    # Colony 的 flat skills/ 目录
  │   └── community registry/  # GitHub 远程注册表
  │
  └── 输出:
      ├── skills_catalog_prompt   # 注入到系统提示词
      ├── protocols_prompt        # 默认行为协议
      ├── context_warn_ratio      # Token 使用警告比例
      └── batch_init_nudge        # 批处理自动检测提示
```

### 技能社区注册表

`registry.py` 实现了从 GitHub (hive-skill-registry) 获取技能索引的客户端，缓存 1 小时 (TTL: 3600s)，支持强制刷新。

---

## 4.7 批处理场景自动检测 (DS-12)

Hive 能够自动检测批处理场景并注入专门的提示词 (nudge)：

```python
# agent_loop.py 中的逻辑
if ctx.default_skill_batch_nudge:
    _input_text = (ctx.goal_context or "") + " " \
        + " ".join(str(v) for v in ctx.input_data.values() if v)
    if is_batch(_input_text):
        system_prompt = f"{system_prompt}\n\n{ctx.default_skill_batch_nudge}"
        logger.info("[%s] DS-12: batch scenario detected, nudge injected", node_id)
```

---

# 5. 架构总结与设计洞察

## 5.1 核心设计理念

| 理念 | 实现方式 |
|------|---------|
| **目标驱动 (Goal-Driven)** | 从自然语言目标生成执行图, 非手工编排 |
| **模型无关 (Model-Agnostic)** | 支持 Anthropic, OpenAI, Gemini, LiteLLM 兼容的任意模型 |
| **持续模式 (Continuous Conversation)** | 多个节点共享一个对话线程, 工具累积 |
| **崩溃恢复 (Crash Recovery)** | 检查点 + 部分消息 + 光标持久化确保无缝恢复 |
| **人机协同 (HITL)** | ask_user, escalate, 暂停/恢复, approval_callback |
| **自我演化 (Self-Evolution)** | 失败后自动演化图结构 |
| **生产级韧性** | 电路断路器, 停滞检测, doom loop 防护, 多级压缩 |

## 5.2 层次架构

```
┌──────────────────────────────────────────┐
│            前端 (Frontend)                │
│  React SPA + WebSocket + REST API        │
├──────────────────────────────────────────┤
│            服务器层 (Server)              │
│  路由(Colonies/Workers/Credentials/...)   │
│  SessionManager + SSE streaming           │
├──────────────────────────────────────────┤
│           运行时层 (Runtime)              │
│  AgentHost / ColonyRuntime                │
│  Pipeline (LLM→Cred→MCP→Skills→User)     │
├──────────────────────────────────────────┤
│           编排层 (Orchestration)          │
│  Orchestrator (DAG执行器)                 │
│  NodeSpec + EdgeSpec + GraphSpec          │
├──────────────────────────────────────────┤
│           执行层 (Execution)              │
│  AgentLoop (流式ReAct循环)                │
│  Judge (3级评估管道)                      │
│  Compaction (5级压缩管道)                 │
│  Worker (短暂/持久模式)                    │
├──────────────────────────────────────────┤
│           持久化层 (Persistence)          │
│  SessionStore (state.json)                │
│  ConversationStore (JSON parts)           │
│  CheckpointStore (JSON checkpoints)       │
│  Memory (Markdown files)                  │
│  Task Queue (SQLite WAL)                  │
├──────────────────────────────────────────┤
│           基础设施层 (Infrastructure)      │
│  LLM Providers (Anthropic/OpenAI/LiteLLM) │
│  MCP Servers (STDIO/HTTP)                │
│  Credential Store (加密)                  │
│  EventBus (Pub/Sub)                       │
└──────────────────────────────────────────┘
```

## 5.3 关键数据流

```
用户输入
  ↓
Queen (AgentLoop 流式循环)
  ├── 记忆召回 → LLM 选择相关 .md 文件
  ├── 人格注入 → 角色档案 (5柱系统)
  ├── 技能注入 → 技能目录 + 协议
  ├── 工具调用 → MCP/本地工具执行
  ├── Judge 评估 → 3级质量门
  ├── 对话压缩 → 5级压缩管道
  └── 光标持久化 → cursor.json
  ↓
Colony Runtime
  ├── spawn workers → N 个并行 AgentLoop 克隆
  ├── 每个 Worker: 独立执行, EventBus 报告
  └── Overseer: 汇总结果, 返回给 Queen
  ↓
会话状态持久化
  ├── SessionStore.write_state() → state.json
  ├── CheckpointStore.save_checkpoint() → 检查点
  └── 崩溃恢复: restore() → 无缝继续
```

## 5.4 与传统 Agent 框架对比

| 维度 | Hive | LangChain/LangGraph | AutoGen | CrewAI |
|------|------|---------------------|---------|--------|
| 定义方式 | agent.json + 自动生成 | Python 代码编排 | Python 代码 | YAML + Python |
| 执行模型 | DAG + Colony Worker 集群 | 图 + 链 | 对话 Agent | 顺序任务 |
| 记忆系统 | 文件 Markdown + LLM 选择 | 向量数据库 | 无内置 | 短期记忆 |
| Agent 通信 | EventBus (Pub/Sub) | 函数调用 | 消息传递 | 委派 |
| Agent 人格 | 5柱角色构造 | 无 | 无 | 基础角色 |
| 崩溃恢复 | 检查点 + 部分消息 | 有限 | 无 | 无 |
| 生产特性 | 电路断路器, 停滞检测 | 基础 | 基础 | 基础 |
| 工具集成 | 102 MCP 工具 | 社区工具 | 社区工具 | 少量内置 |

## 5.5 Hive 的设计优势

1. **一键生成**: 自然语言目标 → Coding Agent → 自动生成完整 Agent 图
2. **Queen 人格化**: 精心设计的角色档案系统, 不仅是 System Prompt
3. **多层韧性**: 压缩管道、停滞检测、doom loop 防护、电路断路器
4. **连续对话模式**: 多节点共享对话线程, 保留完整上下文
5. **检查点恢复**: 完整的崩溃恢复方案, 生产级可靠性
6. **Colony 并行模型**: 简单的 Queen-Worker 模式, 易于理解和扩展
7. **MCP 深度集成**: 102 个预构建工具, 覆盖几乎所有企业系统

## 5.6 Hive 的设计局限

1. **无向量记忆**: LLM 线性扫描文件选择记忆; 大规模记忆时不可扩展
2. **无自动记忆学习**: 需要手动或 Agent 显式写入 .md 文件
3. **无跨 Agent 共享内存**: 没有黑板/共享工作区 (被显式移除)
4. **无 Agent 发现协议**: 纯文件系统扫描, 不支持分布式 Agent
5. **会话隔离**: 每个会话独立, 无跨会话学习
6. **无工作流引擎**: DAG 执行不持久化为工作流定义
7. **Colony 模型简单**: 无层级 Agent、无 Swarm、无选举
8. **技能热加载有限**: 技能通过文件系统扫描, 无实时推送

---

## 附录 A: 未实现的能力清单

下表列出了常见的 Agent 框架能力在 Hive 中的实现状态：

| 能力 | 状态 |
|------|------|
| **向量数据库存储** | 未实现 — 没有使用任何向量数据库 |
| **Embedding 语义搜索** | 未实现 — 记忆搜索完全依赖 LLM 文本匹配 |
| **关键词搜索 (BM25/TF-IDF)** | 未实现 |
| **时间衰减加权检索** | 未实现 — 仅按 mtime 排序文件，无加权 |
| **分层记忆 (工作/短期/长期)** | 部分实现 — 按目录分层 (global/colony/agent)，但无自动转移机制 |
| **自动记忆写入** | 未实现 — 需要手动创建 .md 文件 |
| **记忆重要性评分** | 未实现 |
| **记忆总结与合并** | 未实现 — 但对话层面有 LLM 摘要压缩 |
| **跨 Agent 共享内存池 (Blackboard)** | 未实现 — SharedBufferManager 已标记为 stub |
| **中心化 Agent 注册表** | 未实现 — 基于目录扫描 |
| **Agent 间直接通信 (Message Passing)** | 部分实现 — 通过 EventBus 间接通信 |
| **动态 Agent 创建 (运行时自动)** | 部分实现 — Coding Agent 生成文件，但不支持运行时动态创建 |
| **Agent 发现协议** | 未实现 — 仅文件系统扫描 |
| **SOP 检查清单验证** | 已实现 — SQLite sop_checklist 表 + Worker 协议 |
| **对话连贯性 (Coherence) 跟踪** | 未实现 |
| **主动记忆 (Proactive Memory)** | 未实现 |
| **GraphRAG** | 未实现 |
| **记忆图谱 (Memory Graph)** | 未实现 |
| **用户画像自动更新** | 未实现 |
| **经验学习 (Experience Replay)** | 未实现 — 但有演化机制 (Evolve Graph) |
| **记忆清理/过期策略** | 部分实现 — 检查点7天后清理，但文件记忆无清理 |
| **Swarm 架构** | 部分实现 — Colony 模型类似但更简单 |

---

## 附录 B: 关键代码文件索引

| 文件路径 | 核心内容 |
|---------|---------|
| `core/framework/agent_loop/agent_loop.py` | AgentLoop 主循环实现 (stream LLM + tools + judge) |
| `core/framework/agent_loop/types.py` | AgentSpec, AgentContext, AgentResult, AgentProtocol |
| `core/framework/agent_loop/conversation.py` | NodeConversation, Message 数据结构 |
| `core/framework/agent_loop/internals/compaction.py` | 多级对话压缩管道 |
| `core/framework/agent_loop/internals/judge_pipeline.py` | Judge 评估管道 (3 级) |
| `core/framework/agent_loop/internals/cursor_persistence.py` | 光标持久化和状态恢复 |
| `core/framework/agent_loop/internals/stall_detector.py` | 停滞检测 |
| `core/framework/agent_loop/internals/synthetic_tools.py` | ask_user, escalate, report_to_parent |
| `core/framework/agents/queen/agent.py` | Queen Agent 定义 (Goal + LoopConfig) |
| `core/framework/agents/queen/queen_memory_v2.py` | 文件记忆系统 |
| `core/framework/agents/queen/queen_profiles.py` | 9 个 Queen 角色档案 |
| `core/framework/agents/queen/recall_selector.py` | LLM 记忆召回选择器 |
| `core/framework/agents/discovery.py` | Agent 发现和目录扫描 |
| `core/framework/orchestrator/orchestrator.py` | Graph 执行引擎 (DAG) |
| `core/framework/orchestrator/node.py` | NodeSpec, NodeProtocol, NodeContext |
| `core/framework/orchestrator/edge.py` | EdgeSpec, EdgeCondition, GraphSpec |
| `core/framework/orchestrator/goal.py` | Goal, SuccessCriterion, Constraint |
| `core/framework/orchestrator/context.py` | 节点上下文构建，工具解析 |
| `core/framework/orchestrator/conversation_judge.py` | Level 2 对话质量评估 |
| `core/framework/schemas/agent_config.py` | AgentConfig, NodeConfig, EdgeConfig (agent.json) |
| `core/framework/schemas/session_state.py` | SessionState, SessionProgress, SessionTimestamps |
| `core/framework/schemas/checkpoint.py` | Checkpoint, CheckpointIndex, CheckpointSummary |
| `core/framework/host/agent_host.py` | AgentHost 顶层运行时 |
| `core/framework/host/colony_runtime.py` | ColonyRuntime (Queen + Worker 集群) |
| `core/framework/host/worker.py` | Worker 生命周期管理 |
| `core/framework/host/execution_manager.py` | ExecutionManager, 并行执行流 |
| `core/framework/host/progress_db.py` | SQLite 任务队列 + 进度账本 |
| `core/framework/host/shared_state.py` | SharedBufferManager (stub) |
| `core/framework/host/event_bus.py` | EventBus 事件总线 |
| `core/framework/loader/agent_loader.py` | AgentLoader — Agent 加载器 |
| `core/framework/storage/conversation_store.py` | FileConversationStore (JSON parts) |
| `core/framework/storage/session_store.py` | SessionStore (state.json) |
| `core/framework/storage/checkpoint_store.py` | CheckpointStore |
| `core/framework/storage/session_summary.py` | 会话摘要缓存 |
| `core/framework/config.py` | 全局配置 (~/.hive/configuration.json) |
| `core/framework/skills/registry.py` | 技能注册表客户端 |
| `core/framework/pipeline/stages/` | Pipeline 阶段 (LLM, Credentials, MCP, Skills) |
| `core/framework/tasks/store.py` | 任务存储 |
| `core/framework/tracker/decision_tracker.py` | 决策跟踪器 |
| `examples/templates/` | 11 个示例 Agent 模板 |
| `tools/src/aden_tools/tools/` | 102 个 MCP 工具实现 |

---

---

# 补充详细分析

> 本章节在初版报告基础上，对记忆系统、Agent编排、Agent实现、CLI入口、配置系统、工具系统和日志监控进行了更深层次的源代码级分析。

---

## 6. 记忆系统深度补充分析

### 6.1 文件记忆系统的完整生命周期

#### 6.1.1 记忆写入流程

记忆写入不是自动的，由 Agent 在对话中显式调用工具完成。写入流程如下：

```
Agent 决定记录记忆
  → 调用 write_file 工具 (MCP 工具之一)
    → 目标路径: ~/.hive/memories/{scope}/{filename}.md
    → 写入 build_memory_document() 格式的文件
      → YAML frontmatter: name, description, type
      → Markdown body
```

`build_memory_document()` 在 `queen_memory_v2.py` 中的实现：

```python
def build_memory_document(*, name: str, description: str, mem_type: str, body: str) -> str:
    return (
        f"---\n"
        f"name: {name.strip()}\n"
        f"description: {description.strip()}\n"
        f"type: {mem_type.strip()}\n"       # 必须是 profile/preference/environment/feedback 之一
        f"---\n\n"
        f"{body.strip()}\n"
    )
```

#### 6.1.2 记忆召回选择算法

`recall_selector.py` 中的 `select_memories()` 是完整的 LLM 选择管道：

```
select_memories(query, llm, memory_dir, max_results=5)
  │
  ├── 1. scan_memory_files(memory_dir)
  │     ├── 扫描 *.md 文件 (最多 MAX_FILES=200)
  │     ├── 跳过 dotfiles 和子目录
  │     ├── 按 mtime 降序排列 (最新的在前)
  │     └── 对每个文件调用 MemoryFile.from_path()
  │         ├── 读取文本 (最大 MAX_FILE_SIZE_BYTES=4096)
  │         ├── 解析 YAML frontmatter
  │         ├── 提取 name, type, description
  │         ├── 读取前 _HEADER_LINE_LIMIT=30 行作为 header_lines
  │         └── 获取 st_mtime 时间戳
  │
  ├── 2. format_memory_manifest(files)
  │     └── 格式化为 "[type] filename: description" 每文件一行
  │
  ├── 3. LLM 选择调用
  │     ├── system: SELECT_MEMORIES_SYSTEM_PROMPT (指示 LLM 做选择)
  │     ├── user: "## User query\n\n{query}\n\n## Available memories\n\n{manifest}"
  │     ├── max_tokens=1024
  │     ├── response_format={"type": "json_object"}  (强制 JSON 输出)
  │     └── 期望输出: {"selected_memories": ["file1.md", "file2.md"]}
  │
  ├── 4. 结果清理和验证
  │     ├── 提取 JSON (支持 markdown 包裹和前缀文本)
  │     ├── 过滤: 只保留 valid_names (实际存在的文件名)
  │     ├── 限制: 最多 max_results=5 个
  │     └── 错误时返回 [] (永不让记忆召回阻塞主对话)
  │
  └── 5. format_recall_injection(filenames, memory_dir)
        ├── 读取每个选中文件的完整内容
        ├── 添加年龄标记: "### filename.md (3 days old)"
        └── 格式化为提示词注入块
```

#### 6.1.3 双重作用域记忆召回

`build_scoped_recall_blocks()` 支持两个独立的作用域同时召回：

```python
async def build_scoped_recall_blocks(query, llm, *, global_memory_dir, queen_memory_dir, ...):
    # 第一次 LLM 调用：全局记忆
    global_selected = await select_memories(query, llm, memory_dir=global_dir, max_results=3)
    global_block = format_recall_injection(global_selected, label="Global Memories")

    # 第二次 LLM 调用：Queen 私有记忆
    queen_selected = await select_memories(query, llm, memory_dir=queen_dir, max_results=3)
    queen_block = format_recall_injection(queen_selected, label="Queen Memories: {queen_id}")

    return global_block, queen_block  # 分别注入到提示词不同位置
```

#### 6.1.4 记忆文件命名和碎片化策略

`allocate_memory_filename()` 实现了一个简单的冲突避免算法：

```python
def allocate_memory_filename(memory_dir, name, *, suffix=".md") -> str:
    base = slugify_memory_name(name)  # → "my-important-memory"
    candidate = f"{base}{suffix}"     # → "my-important-memory.md"
    counter = 2
    while (memory_dir / candidate).exists():
        candidate = f"{base}-{counter}{suffix}"  # → "my-important-memory-2.md"
        counter += 1
    return candidate
```

### 6.2 三层运行时日志系统 (L1/L2/L3)

`runtime_logger.py` 和 `runtime_log_schemas.py` 定义了一个三层可观测性系统，存储在会话目录的 `logs/` 子目录下：

#### Level 3 (Tool Logs) -- 最细粒度

每步每个节点内包含完整工具调用详情和 LLM 文本，以 JSONL 格式追加写入。

```python
class ToolCallLog(BaseModel):
    tool_use_id: str
    tool_name: str
    tool_input: dict[str, Any]
    result: str
    is_error: bool
    start_timestamp: str     # ISO 8601
    duration_s: float        # 实际执行时间

class NodeStepLog(BaseModel):
    node_id: str
    node_type: str           # "event_loop" (唯一有效类型)
    step_index: int          # event_loop 的迭代号
    llm_text: str            # LLM 生成的文本
    tool_calls: list[ToolCallLog]
    input_tokens: int
    output_tokens: int
    latency_ms: int
    verdict: str             # "ACCEPT"|"RETRY"|"ESCALATE"|"CONTINUE"
    verdict_feedback: str
    error: str
    stacktrace: str
    is_partial: bool         # 未正常完成的步骤
    # OpenTelemetry 对齐字段:
    trace_id: str
    span_id: str
    parent_span_id: str
    execution_id: str
```

#### Level 2 (Details) -- 每节点完成

```python
class NodeDetail(BaseModel):
    node_id: str
    node_name: str
    node_type: str
    success: bool
    error: str | None
    stacktrace: str
    total_steps: int
    tokens_used: int
    input_tokens: int
    output_tokens: int
    latency_ms: int
    attempt: int
    exit_status: str         # "success"|"failure"|"stalled"|"escalated"|"paused"|"guard_failure"
    accept_count: int
    retry_count: int
    escalate_count: int
    continue_count: int
    needs_attention: bool
    attention_reasons: list[str]
    trace_id: str
    span_id: str
```

#### Level 1 (Summary) -- 每次图执行

```python
class RunSummaryLog(BaseModel):
    run_id: str
    agent_id: str
    goal_id: str
    status: str              # "success"|"failure"|"degraded"
    total_nodes_executed: int
    node_path: list[str]
    total_input_tokens: int
    total_output_tokens: int
    needs_attention: bool
    attention_reasons: list[str]
    started_at: str
    duration_ms: int
    execution_quality: str   # "clean"|"degraded"|"failed"
    trace_id: str
    execution_id: str
```

#### 存储结构

L1/L2/L3 日志存储在每个会话的 `logs/` 目录下：

```
{storage_path}/logs/
    summary.json       # L1: RunSummaryLog (单文件, 在 end_run() 时写入)
    details.jsonl      # L2: NodeDetail (每节点完成时追加)
    tool_logs.jsonl    # L3: NodeStepLog (每步时追加)
```

### 6.3 SQLite 任务队列中的跨运行记忆

`progress_db.py` 提供了 Colony 级别的跨运行持久记忆，通过 SQLite WAL 模式实现：

**Schema 设计**（4 张表）：

```sql
-- 任务表
CREATE TABLE tasks (
    id TEXT PRIMARY KEY, seq INTEGER, priority INTEGER,
    goal TEXT NOT NULL, payload TEXT,
    status TEXT NOT NULL DEFAULT 'pending',  -- pending|claimed|in_progress|done|failed
    worker_id TEXT, claim_token TEXT,
    claimed_at TEXT, started_at TEXT, completed_at TEXT,
    created_at TEXT, updated_at TEXT,
    retry_count INTEGER DEFAULT 0, max_retries INTEGER DEFAULT 3,
    last_error TEXT, parent_task_id TEXT, source TEXT
);

-- 步骤表 (每个任务可拆分多个步骤)
CREATE TABLE steps (
    id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id) ON DELETE CASCADE,
    seq INTEGER, title TEXT, detail TEXT,
    status TEXT DEFAULT 'pending',  -- pending|in_progress|done|failed|skipped
    evidence TEXT, worker_id TEXT, started_at TEXT, completed_at TEXT,
    UNIQUE(task_id, seq)
);

-- SOP 检查清单表 (强制质量门)
CREATE TABLE sop_checklist (
    id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id) ON DELETE CASCADE,
    key TEXT, description TEXT, required INTEGER DEFAULT 1,
    done_at TEXT, done_by TEXT, note TEXT,
    UNIQUE(task_id, key)
);

-- Colony 元数据 (键值存储)
CREATE TABLE colony_meta (
    key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL
);
```

**关键特性**：

- **原子认领 (Atomic Claim)**：`BEGIN IMMEDIATE; UPDATE tasks SET status='claimed' WHERE id=(SELECT ... LIMIT 1)`，确保 100 个并发 Worker 不会重复认领同一任务
- **WAL 模式**：默认开启，支持高并发读写，Worker 无需持有长连接
- **过期认领回收**：启动时自动回收超过 15 分钟未完成的认领，更新 retry_count，超过 max_retries 则标记为 failed
- **批量播种**：`seed_tasks()` 在单个 `BEGIN IMMEDIATE` 事务中插入 10k+ 任务，一次磁盘刷新完成

**Agent 访问方式**：Agent 通过 MCP 工具 `terminal_exec` 调用 `sqlite3` CLI 访问此数据库，不通过 Python ORM。这确保 Worker 在每次 LLM 调用之间释放数据库锁。

### 6.4 对话持久化与光标恢复系统

`cursor_persistence.py` 实现了完整的对话状态持久化和恢复：

```
cursor.json 存储内容:
{
    "iteration": 42,              # 当前迭代计数
    "output_accumulator": {...},  # 累积输出状态
    "stall_state": {              # 停滞检测状态
        "recent_responses": [...],
        "recent_tool_fingerprints": [...],
        "consecutive_stalled": 0
    },
    "pending_input_state": {...}, # HITL 等待状态 (ask_user)
    "run_id": "run_abc123",       # 用于隔离的运行 ID
    "spill_counter": 17           # 溢出文件编号 (确保跨恢复唯一性)
}
```

恢复流程：

```
restore(conversation_store, node_spec, max_context_tokens) → RestoredState
  │
  ├── 1. 恢复对话消息:
  │     ├── read_parts() → 所有部分的 JSON
  │     ├── 按 phase_id 过滤 (只恢复属于当前节点的消息)
  │     ├── 重建 NodeConversation (包含 system_prompt)
  │     └── 恢复 run_id 边界 (跳过不属于当前 run_id 的消息)
  │
  ├── 2. 恢复光标:
  │     ├── read_cursor() → 迭代计数、停滞状态、溢出计数
  │     └── 如果 cursor.json 不存在 → 从头开始 (iteration=0)
  │
  ├── 3. 重建 OutputAccumulator:
  │     ├── 从光标中提取输出值 (set_output 累积)
  │     ├── 恢复溢出目录路径
  │     └── 恢复最大工具结果字符限制
  │
  ├── 4. 刷新系统提示词:
  │     └── 重新调用 build_system_prompt_for_context() (提示词可能在运行时更新)
  │
  └── 5. 返回 RestoredState:
        ├── conversation: NodeConversation
        ├── conversation_store: FileConversationStore
        ├── cursor: cursor 数据
        ├── accumulator: OutputAccumulator
        ├── start_iteration: int (从光标中恢复的迭代号 + 1)
        └── pending_input_state: dict | None (如果上次在等待用户输入)
```

### 6.5 会话摘要缓存

`session_summary.py` 提供了会话摘要的内存内缓存 (TTL: 60 秒)，避免重复扫描 `state.json` 文件。缓存键为 `(agent_name, status_filter, goal_filter)` 三元组。

---

## 7. Agent 编排深度补充分析

### 7.1 图执行引擎内部状态机

`orchestrator.py` 中的 `Orchestrator` 类采用**基于 Worker 的事件驱动模型**，取代了早期的命令式 while 循环：

```
execute() 入口
  │
  ├── Phase 0: 初始化
  │     ├── 图结构验证 (GraphSpec.validate())
  │     ├── 工具可用性验证 (只检查可达节点，不阻塞无关节点)
  │     ├── 初始化 DataBuffer
  │     ├── 恢复会话状态 (data_buffer, node_visit_counts, execution_path)
  │     ├── 检查是否有检查点可恢复 (CheckpointStore.load_checkpoint)
  │     ├── 设置 ToolRegistry 执行上下文 (data_dir, agent_id)
  │     └── 确定 entry_point (可能是恢复节点)
  │
  ├── Phase 1: Worker 创建
  │     └── _execute_with_workers() 
  │         ├── 为图中每个 NodeSpec 创建一个 NodeWorker
  │         ├── 每个 Worker 绑定 GraphContext (共享buffer, runtime, tools)
  │         ├── 识别入口 Worker 和终止 Worker
  │         └── 订阅 WORKER_COMPLETED / WORKER_FAILED 事件
  │
  ├── Phase 2: 激活传播
  │     ├── 激活入口 Worker → 它们开始执行
  │     ├── 完成时发布 WORKER_COMPLETED 事件 (携带 outgoing activations)
  │     ├── 事件处理函数 _on_worker_completed():
  │     │   ├── 反序列化 Activation 列表
  │     │   ├── 为每个 Activation 调用 _route_activation():
  │     │   │   ├── 查找目标 Worker
  │     │   │   ├── 检查目标 Worker 是否已完成 (反馈循环 → 重置)
  │     │   │   ├── 将 Activation 推送到目标 Worker
  │     │   │   └── 检查目标 Worker 就绪状态 (fan-out 收敛检查)
  │     │   └── 递进检查: 如果所有终端 Worker 完成 → 设置 completion_event
  │     └── 失败处理: _on_worker_failed() → 检查 ON_FAILURE 边 → 路由到恢复节点
  │
  └── Phase 3: 结果汇总
        ├── 从终端 Worker 收集输出
        ├── 计算执行质量 (clean/degraded/failed)
        ├── 保存会话状态到 state.json
        └── 返回 ExecutionResult
```

### 7.2 NodeWorker 生命周期状态机

`node_worker.py` 中定义的完整生命周期：

```
                 ┌─────────┐
      构造 →     │ PENDING │
                 └────┬────┘
                      │ receive_activation() + check_readiness()
                      ▼
                 ┌─────────┐
                 │ RUNNING │ ←── reset_for_revisit() (反馈循环)
                 └────┬────┘
          ┌───────────┼───────────┐
          ▼           ▼           ▼
    ┌──────────┐ ┌──────────┐ ┌────────┐
    │COMPLETED │ │  FAILED  │ │(retry) │ → RUNNING
    └──────────┘ └──────────┘ └────────┘
```

Worker 的就绪条件检查：

```python
def check_readiness(self) -> bool:
    # 1. 必须至少收到一个 Activation
    if not self._has_been_activated:
        return False
    # 2. 所有 fan-out 组必须收敛
    for tracker in self._active_fan_outs.values():
        if not tracker.is_complete:
            return False
    # 3. 必须是 PENDING 状态 (不是已运行完)
    if self.lifecycle != WorkerLifecycle.PENDING:
        return False
    return True
```

### 7.3 Fan-out / Fan-in 并行模式

```
         ┌───────┐
         │ NodeA │ (fan-out 源)
         └───┬───┘
        ┌────┴────┐
        ▼         ▼
   ┌─────────┐ ┌─────────┐
   │ Branch1 │ │ Branch2 │ (fan-out 分支)
   └────┬────┘ └────┬────┘
        └─────┬─────┘
              ▼
         ┌─────────┐
         │  Merger │ (fan-in 目标)
         └─────────┘
```

实现机制：

```python
# FanOutTag 在 Activation 中传播
@dataclass
class FanOutTag:
    fan_out_id: str          # 此 fan-out 事件的唯一 ID
    fan_out_source: str      # 执行 fan-out 的节点
    branches: frozenset[str] # 此 fan-out 中的所有目标节点 ID
    via_branch: str          # 此 Activation 通过哪个分支

# Merger Worker 跟踪 FanOutTracker
@dataclass
class FanOutTracker:
    fan_out_id: str
    branches: frozenset[str]
    reached: set[str]        # 已到达的分支

    @property
    def is_complete(self) -> bool:
        return self.reached == self.branches  # 所有分支都到达后才激活
```

### 7.4 并行执行的三种冲突策略

当多个并行分支同时写入同一个 DataBuffer key 时：

| 策略 | 值 | 行为 |
|------|-----|------|
| `last_wins` (默认) | `"last_wins"` | 最后写入的值覆盖，前值丢失（记录 DEBUG 日志） |
| `first_wins` | `"first_wins"` | 保留第一个写入的值，后续写入被忽略 |
| `error` | `"error"` | 抛出 RuntimeError，整个并行执行失败 |

### 7.5 分支失败处理策略

在 `ParallelExecutionConfig.on_branch_failure` 中配置：

| 策略 | 行为 |
|------|------|
| `fail_all` (默认) | 任何分支失败时立即取消所有分支 |
| `continue_others` | 成功的分支继续执行，失败的分支记录警告 |
| `wait_all` | 等待所有分支完成，报告所有失败 |

### 7.6 Worker 的两种工作模式

**Ephemeral 模式 (默认)**：

```
PENDING → RUNNING → AgentLoop.execute() → emit SUBAGENT_REPORT → COMPLETED/FAILED
```

每个 Worker 执行单个任务，完成后终止。用于 Colony 中的并行任务处理。

**Persistent 模式**：

```
PENDING → RUNNING → AgentLoop.execute() → 进入 _persistent_input_loop()
                                                 │
                                          ┌──────┘
                                          ▼
                                    _input_queue.get()
                                          │
                                    inject_event(msg)
                                          │
                                    AgentLoop 处理新消息
                                          │
                                    循环 (永远运行)
```

用于 Colony 的长期运行监督者 (Overseer)，持续接收和响应用户消息。

### 7.7 跨 Queen-Worker 升级路径

Worker 需要人工干预时的升级机制：

```
Worker AgentLoop 检测到需要升级
  │
  ├── 自动触发 (连续纯文本轮次达到阈值)
  │     └── 调用 escalate({reason, context})
  │
  └── LLM 显式调用 escalate 工具
        └── synthetic_tools.build_escalate_tool()
              │
              ├── 发布 ESCALATION_REQUESTED 事件到 EventBus
              │     ├── colony_id: worker 所属 colony
              │     ├── request_id: 唯一请求 ID
              │     ├── reason: 升级原因
              │     └── context: 上下文 (最多 4000 字符)
              │
              ├── Queen orchestrator 接收:
              │     ├── _on_worker_escalation()
              │     ├── 检查 inbox 容量 (MAX_PENDING_ESCALATIONS=32)
              │     ├── 若满 → 自动回复 queue_full
              │     ├── 记录到 session.pending_escalations[request_id]
              │     └── 注入 [WORKER_ESCALATION] 消息到 Queen 对话
              │
              └── Queen 调用 reply_to_worker({request_id, reply})
                    └── ColonyRuntime.inject_input(worker_id, reply)
                          └── Worker AgentLoop 收到 QUEEN_REPLY 注入
```

### 7.8 Worker Profile 和账户绑定

`worker.py` 支持在多账户 Colony 中为每个 Worker 绑定特定的集成账户：

```python
# Worker 构造时:
Worker(
    profile_name="slack-work",   # 人性化标签
    integrations={               # 工具 → 账户别名
        "slack_api": "slack-prod",
        "gmail_api": "gmail-ops",
        "salesforce_api": "sf-corp"
    }
)

# 执行时通过 contextvar 传播到所有 MCP 工具调用:
with account_overrides(self._integrations):
    result = await self._agent_loop.execute(self._context)
```

### 7.9 图规范 (GraphSpec) 结构细节

`edge.py` 中定义的图规范包含这些关键字段：

```python
class GraphSpec(BaseModel):
    id: str                    # 图/Agent 唯一标识
    name: str                  # 人类可读名称
    description: str           # 用途描述
    nodes: list[NodeSpec]      # 所有节点
    edges: list[EdgeSpec]      # 所有边
    entry_node: str            # 入口节点 ID
    terminal_nodes: list[str]  # 终止节点 ID 列表
    max_steps: int = 50        # 最多执行步数 (断路器)
    max_tokens: int = 8192     # 每个节点的最大 token
    conversation_mode: str     # "isolated" | "continuous"
    identity_prompt: str       # 图级身份提示词
    cleanup_llm_model: str     # 清理节点使用的 LLM 模型
```

### 7.10 边条件类型

```python
class EdgeCondition(StrEnum):
    ON_SUCCESS = "on_success"      # 源节点成功时遍历
    ON_FAILURE = "on_failure"      # 源节点失败时遍历
    CONDITIONAL = "conditional"    # 通过可选的 LLM 调用评估条件
    ALWAYS = "always"              # 始终遍历 (常用在反馈循环)
```

每条边还带有：
- `priority`: 整数值，在多个 CONDITIONAL 边同时匹配时决定优先级
- `map_inputs`: 输入映射字典，将源节点输出映射到目标节点输入
- `condition_code`: 当条件为 CONDITIONAL 时评估的表达式

---

## 8. Agent 实现深度补充分析

### 8.1 Agent 启动的完整加载链

从零启动一个 Hive Agent 经历了完整的加载链：

```
CLI: hive serve
  → SessionManager.__init__()
    → 加载凭证存储 (CredentialStore)
    → 加载 Queen 工具注册表 (ToolRegistry)
    → v2 目录结构迁移
  → cmd_serve() → create_app()
    → 初始化 aiohttp Application
    → 注册路由: colonies, workers, credentials, sessions, skills, mcp, ...
  → 客户端调用 POST /api/colonies/{name}/load
    → SessionManager.load_colony()
      → 1. 从 ~/.hive/colonies/{name}/ 读取 worker configs
      → 2. 初始化 Pipeline (LLM → Credentials → MCP → Skills)
      → 3. 创建 ColonyRuntime
      → 4. _start_queen() (创建 Queen Overseer)
        → 构建 System Prompt (人格 + 技能 + 记忆 + 协议)
        → 创建 AgentLoop (AgentSpec → AgentContext)
        → 创建 Worker 实例 (persistent=True)
        → worker.start_background()
        → 设置迁移回调 (内存反思)
      → 5. 加载触发器 (triggers.json → timer/webhook)
```

### 8.2 AgentSpec 数据结构

`agent_loop/types.py` 中定义的 Agent 规范：

```python
@dataclass
class AgentSpec:
    """完整的 Agent 规范，包含 AgentLoop 启动所需的一切"""
    name: str                          # Agent 名称
    goal: str                          # 目标描述
    system_prompt: str                 # 基础系统提示词
    identity_prompt: str               # 身份/人格提示词
    max_iterations: int                # 最大迭代数 (Queen=999999, Worker=100)
    max_tool_calls_per_turn: int       # 每次 LLM 调用的最大工具调用数 (30)
    max_context_tokens: int            # 最大上下文窗口 (Queen=180000, Worker=32000)
    tools: list[str]                   # 需要的工具名称列表
    input_data: dict[str, Any]         # 初始输入数据
    model: str                         # LLM 模型标识符
    temperature: float                 # LLM 温度 (0.7)
    skill_dirs: list[str]              # 技能目录路径
    profile: str                       # 浏览器 profile 名称 (用于标签分组)
    storage_path: str                  # 持久化存储路径
```

### 8.3 AgentContext 注入

`agent_loop/types.py` 中 AgentContext 携带运行时上下文：

```python
@dataclass
class AgentContext:
    """传递给 AgentLoop.execute() 的运行时上下文"""
    agent_id: str                      # Agent 标识符
    stream_id: str                     # 事件流 ID
    execution_id: str                  # 执行 ID
    run_id: str                        # 运行 ID
    goal_context: str                  # 目标上下文文本
    input_data: dict[str, Any]         # 输入数据
    available_tools: list[Tool]        # 可用工具列表
    system_prompt: str                 # 系统提示词 (可能是动态的)
    dynamic_tools_provider: Callable   # 动态工具提供者
    dynamic_prompt_provider: Callable  # 动态提示词提供者
    dynamic_memory_provider: Callable  # 动态记忆提供者
    iteration_metadata_provider: Callable  # 迭代元数据提供者
    skills_catalog_prompt: str         # 技能目录 (注入到提示词)
    protocols_prompt: str              # 默认协议 (注入到提示词)
    skill_dirs: list[str]              # 技能目录
    pause_event: asyncio.Event         # 暂停信号
    accounts_prompt: str               # 已连接账户信息
    accounts_data: list[dict]          # 账户原始数据
    tool_provider_map: dict[str, str]  # 工具 → 提供者映射
    identity_prompt: str               # 身份提示词
    narrative: str                     # 叙述文本
    context_warn_ratio: float          # 上下文使用警告阈值
    batch_init_nudge: str              # 批处理自动检测提示
    task_list_id: str                  # 任务列表 ID
    colony_id: str                     # Colony ID
    picked_up_from: list               # 此会话从哪个任务列表拾取的任务
```

### 8.4 工具注册和发现的完整链路

`tool_registry.py` 中的 `ToolRegistry` 实现了多源工具发现：

```
ToolRegistry.__init__()
  │
  ├── 工具来源 1: 内置工具
  │     └── queen_lifecycle_tools: colony_create, colony_delete, ...
  │     └── worker_monitoring_tools: worker_status, worker_list, ...
  │
  ├── 工具来源 2: MCP 服务器
  │     └── ToolRegistry.load_mcp_tools_from_config()
  │         ├── 读取 mcp_servers.json
  │         ├── 为每个 MCP 服务器创建 MCPClient
  │         ├── MCPClient.connect() (STDIO/HTTP/Unix/SSE)
  │         ├── 获取服务器工具列表
  │         ├── 为每个工具创建 Tool + executor
  │         └── 注册到 _tools 字典
  │
  ├── 工具来源 3: tasks/tools/ (任务系统工具)
  │     └── colony_tools.py: create_colony, delete_colony, ...
  │     └── session_tools.py: session_list, session_stop, ...
  │
  └── 并发安全工具白名单:
        └── ToolRegistry.CONCURRENCY_SAFE_TOOLS = frozenset({
              # 文件读取: read_file, search_files, pdf_read
              # 终端读取: terminal_rg, terminal_find, terminal_output_get
              # 网络读取: web_scrape, search_papers, search_wikipedia
              # 浏览器只读: browser_screenshot, browser_snapshot, ...
            })
```

#### MCP 工具白名单过滤

`mcp_client.py` 通过正则表达式模式自动检测生成图片的工具：

```python
_IMAGE_TOOL_NAME_RE = re.compile(
    r"(screenshot|screen_capture|capture_image|render_image|get_image|snapshot_image)",
    re.IGNORECASE,
)
```

匹配此模式的工具被标记为 `produces_image=True`，自动从文本模型过滤掉。

#### 工具输入强制转换器

`tool_input_coercer.py` 处理 LLM 生成的工具参数与实际工具签名之间的类型不匹配：

```python
# 自动处理常见情况:
# "3" → 3 (字符串转整数)
# "true" → True (字符串转布尔)
# 缺失参数 → None (如果参数类型为 Optional)
# 多余参数 → 移除 (LLM 幻觉的参数)
```

### 8.5 合成工具 (Synthetic Tools)

`agent_loop/internals/synthetic_tools.py` 定义了三种在运行时动态生成的工具：

#### 8.5.1 ask_user (HITL 交互)

```python
def build_ask_user_tool(event_bus, node_id, agent_id):
    """创建 ask_user 工具
    
    当 Agent 需要人工输入时调用。
    工具执行会:
    1. 发布 CLIENT_OUTPUT_DELTA 事件 (用户看到 agent 的消息)
    2. 阻塞 AgentLoop 等待用户响应
    3. 用户通过 /chat API 回复 → 注入到对话中
    4. 工具结果返回用户的响应文本
    """
```

#### 8.5.2 escalate (Worker 升级)

```python
def build_escalate_tool(event_bus, node_id, agent_id, colony_id, request_id):
    """创建 escalate 工具
    
    Worker 向 Queen 请求干预时调用。
    1. 发布 ESCALATION_REQUESTED 事件
    2. Worker AgentLoop 暂停等待 QUEEN_REPLY
    3. Queen 通过 reply_to_worker 回复
    4. 工具结果返回 Queen 的回复
    """
```

#### 8.5.3 report_to_parent (Worker 报告)

```python
def build_report_to_parent_tool(event_bus, node_id, agent_id, colony_id):
    """创建 report_to_parent 工具
    
    Worker 向 Overseer 报告任务完成时调用。
    1. 标记 Worker 的 _explicit_report
    2. 发布 SUBAGENT_REPORT 事件
    3. Overseer 接收并汇总
    """
```

### 8.6 提示词组装过程

`prompt_composer.py` 和 `agent_loop/prompting.py` 负责构建完整的系统提示词：

```python
def build_system_prompt_for_context(ctx) -> str:
    """组装系统提示词的完整流程"""
    parts = []
    
    # 1. 身份/人格提示词 (最顶层，最重要)
    if ctx.identity_prompt:
        parts.append(ctx.identity_prompt)
    
    # 2. 基础系统提示词
    if ctx.system_prompt:
        parts.append(ctx.system_prompt)
    
    # 3. 技能目录
    if ctx.skills_catalog_prompt:
        parts.append(ctx.skills_catalog_prompt)
    
    # 4. 默认协议
    if ctx.protocols_prompt:
        parts.append(ctx.protocols_prompt)
    
    # 5. 记忆召回注入 (来自 select_memories 的结果)
    if memory_block:
        parts.append(memory_block)
    
    # 6. 账户信息
    if ctx.accounts_prompt:
        parts.append(ctx.accounts_prompt)
    
    # 7. 动态提示词 (运行时可变，如 phase switching)
    if dynamic_prompt:
        parts.append(dynamic_prompt)
    
    return "\n\n".join(parts)
```

### 8.7 动态提供者机制

Orchestrator 支持五个动态提供者，在每次迭代时调用：

```python
# 在每个 AgentLoop 迭代开始时调用:
dynamic_tools_provider() → list[Tool] | None
    # 返回新的工具列表替换 ctx.available_tools
    # 用于模式切换: 不同阶段需要不同工具集

dynamic_prompt_provider() → str | None
    # 返回新的系统提示词替换
    # 用于阶段切换: 不同阶段需要不同行为指令

dynamic_memory_provider() → str | None
    # 返回新的记忆块注入
    # 用于上下文更新: 根据当前任务进展加载不同记忆

iteration_metadata_provider() → dict | None
    # 返回每轮的额外元数据
    # 用于向 LLM 传递当前进度信息
```

---

## 9. CLI 入口和命令结构

### 9.1 完整命令树

`cli.py` 和 `loader/cli.py` 定义了完整的命令结构：

```
hive
├── serve [--host HOST] [--port PORT] [--colony PATH] [--open] [--verbose] [--debug]
│     └── 启动 aiohttp HTTP API 服务器 (端口默认 8787)
│
├── open [--host HOST] [--port PORT] [--colony PATH]
│     └── 启动服务器 + 自动打开浏览器仪表板
│
├── queen
│   ├── list                                    # 列出所有 Queen 档案
│   ├── show <queen_id>                        # 检查 Queen 档案详情
│   └── sessions <queen_id>                    # 列出 Queen 的会话
│
├── colony
│   ├── list                                    # 列出所有 Colony (目录扫描)
│   ├── info <name>                            # 检查 Colony 详情 (metadata.json)
│   └── delete <name>                          # 删除 Colony (整个目录)
│
├── session
│   ├── list [--cold]                          # 列出活跃会话 (--cold 列出磁盘上的)
│   └── stop <session_id>                      # 停止活跃会话
│
├── chat <session_id> "message"                 # 向活跃 Queen 发送消息 (HTTP POST)
│
├── skill                                       # 技能管理
│   ├── list                                    # 列出已安装技能
│   ├── search <query>                         # 搜索社区注册表
│   ├── install <name>                         # 从注册表安装技能
│   ├── uninstall <name>                       # 卸载技能
│   └── info <name>                            # 技能详情
│
├── mcp                                         # MCP 服务器管理
│   ├── list                                    # 列出注册的 MCP 服务器
│   ├── add <name> <transport> [--command ...] # 注册 MCP 服务器
│   ├── remove <name>                          # 移除 MCP 服务器
│   └── test <name>                            # 测试 MCP 服务器连接
│
└── debugger                                    # LLM 调试日志查看器
      └── 交互式终端 UI 查看 LLM 调用日志
```

### 9.2 服务器启动的完整流程

```
cmd_serve(args)
  │
  ├── 1. _build_frontend() — 如果不存在则构建 React 前端
  │
  ├── 2. configure_logging(level=INFO/DEBUG)
  │
  ├── 3. 注册 atexit 处理器: MCPConnectionManager.cleanup_all()
  │     └── 确保 MCP 子进程不会在服务器崩溃后继续运行
  │
  ├── 4. create_app(model=model)
  │     ├── 创建 SessionManager (凭证存储 + 工具注册表)
  │     ├── 创建 aiohttp Application
  │     ├── 配置 CORS (localhost:5173 用于 React 开发)
  │     ├── 注册 REST API 路由 (26 个端点)
  │     └── 注册 SSE 端点
  │
  ├── 5. run_server()
  │     ├── 注册 SIGINT/SIGTERM handlers (优雅关闭)
  │     ├── 加载预加载的 colony (--colony 参数)
  │     ├── 启动 aiohttp web.run_app() (端口 8787)
  │     └── 关闭时: manager.shutdown_all() → MCP 断开连接
  │
  └── 6. 第二次 Ctrl+C: os._exit(130) (强制退出，先用 MCP 清理)
```

### 9.3 前端自动构建

服务器启动时自动检测前端构建产物：

```
_build_frontend()
  ├── 检查 core/frontend/dist/ 是否存在
  ├── 如果不存在 → npm install + npm run build
  │     └── 构建 React SPA (Vite)
  └── 如果存在 → 跳过构建 (开发时可以手动构建)
```

---

## 10. 配置系统深度分析

### 10.1 configuration.json 完整结构

`~/.hive/configuration.json` 的所有可配置字段：

```json
{
  "llm": {
    "provider": "anthropic|openai|gemini|openrouter|groq|deepseek|ollama|...",
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 8192,
    "max_context_tokens": 32000,
    "api_key_env_var": "ANTHROPIC_API_KEY",
    "api_keys": ["key1", "key2"],
    "api_base": "https://custom-endpoint.com/v1",
    "use_claude_code_subscription": false,
    "use_codex_subscription": false,
    "use_kimi_code_subscription": false,
    "use_antigravity_subscription": false,
    "antigravity_client_id": "...",
    "antigravity_client_secret": "...",
    "num_ctx": 16384
  },
  "worker_llm": {
    "provider": "anthropic|...",
    "model": "claude-haiku-4-5-20251001",
    "max_tokens": 8192,
    "max_context_tokens": 32000,
    "api_key_env_var": "ANTHROPIC_API_KEY",
    "api_base": null,
    "use_claude_code_subscription": false,
    "use_codex_subscription": false,
    "use_kimi_code_subscription": false,
    "use_antigravity_subscription": false,
    "num_ctx": 16384
  },
  "vision_fallback": {
    "provider": "gemini",
    "model": "gemini-3-flash-preview",
    "api_key_env_var": "GEMINI_API_KEY",
    "api_base": null
  },
  "gcu_enabled": true,
  "gcu_viewport_scale": 0.8
}
```

### 10.2 API Key 解析优先级

`get_api_key()` 的完整优先级链（最高优先到最低）：

```
1. llm.api_keys[0]                           (显式密钥池第一个密钥)
2. llm.use_claude_code_subscription           (从 ~/.claude/.credentials.json 或 Keychain)
   └── 支持 OAuth 令牌刷新 (refresh_token)
3. llm.use_codex_subscription                (从 ~/.codex/auth.json 或 Keychain)
4. llm.use_kimi_code_subscription            (从 ~/.kimi/config.toml)
5. llm.use_antigravity_subscription          (OAuth 令牌, 自动获取 GitHub 上的客户端凭据)
6. llm.api_key_env_var                       (从环境变量读取)
7. 加密凭证存储 (BYOK)                        (如果 HIVE_CREDENTIAL_KEY 已设置)
```

### 10.3 Worker 的独立 LLM 配置

`Worker LLM` 支持与 Queen 使用不同的模型和认证方式：

```python
# config.py 中的 Worker 配置解析
get_worker_api_key()       # 独立于 get_api_key() 的 Worker API Key
get_worker_api_base()      # Worker 专用 API 基础 URL
get_worker_max_tokens()    # Worker 专用 Max Tokens
get_worker_max_context_tokens()  # Worker 专用上下文窗口
get_worker_llm_extra_kwargs()    # Worker 专用额外参数
```

Worker 默认回退到 Queen 的配置（当未单独配置时）。

### 10.4 Hive Home 目录完整结构

```
~/.hive/
├── configuration.json                 # 全局配置
├── credentials/
│   ├── credentials/                   # 加密凭证文件 (*.enc)
│   └── store.json                     # 凭证索引
├── agents/
│   ├── queens/
│   │   └── {queen_id}/
│   │       ├── profile.yaml           # Queen 人格档案
│   │       ├── tools.json             # 工具白名单
│   │       └── sessions/
│   │           └── session_YYYYMMDD_HHMMSS_{uuid}/
│   │               ├── state.json     # 会话状态
│   │               ├── conversations/ # 对话存储
│   │               ├── artifacts/     # 溢出数据
│   │               └── logs/          # L1/L2/L3 日志
│   └── {agent_name}/                  # Worker Agent 目录
│       └── sessions/
├── colonies/
│   └── {colony_name}/
│       ├── metadata.json              # Colony 元数据
│       ├── triggers.json              # 触发器定义
│       ├── {worker_config}.json       # Worker 配置 (多个)
│       └── data/
│           ├── progress.db            # SQLite 任务队列
│           └── {worker_id}/           # 每个 Worker 的工作目录
├── memories/
│   ├── global/                        # 全局共享记忆
│   │   └── *.md
│   ├── colonies/{name}/               # Colony 记忆
│   ├── agents/queens/{name}/          # Queen 私有记忆
│   └── agents/{name}/                 # Worker 记忆
├── skills/                            # 已安装的技能
├── registry_cache/                    # 社区注册表缓存
│   ├── skill_index.json
│   └── metadata.json
└── event_logs/                        # 事件日志 (HIVE_DEBUG_EVENTS=1 时)
    └── YYYYMMDD_HHMMSS.jsonl
```

---

## 11. 工具系统完整清单

### 11.1 工具分类概览

Hive 的工具系统分为以下几层：

| 层级 | 来源 | 数量 | 描述 |
|------|------|------|------|
| **内置生命周期工具** | `tools/queen_lifecycle_tools.py` | ~10 | colony_create, colony_delete, fork_colony, run_parallel_workers |
| **内置监控工具** | `tools/worker_monitoring_tools.py` | ~5 | worker_status, worker_list, worker_stop |
| **内置会话工具** | `tasks/tools/session_tools.py` | ~3 | session_list, session_stop, session_resume |
| **内置 Colony 工具** | `tasks/tools/colony_tools.py` | ~5 | create_colony, delete_colony, colony_info |
| **内置数据工具** | `tasks/tools/` | ~3 | read_file, write_file, search_files |
| **合成工具** | `synthetic_tools.py` | 3 | ask_user, escalate, report_to_parent |
| **MCP 浏览器工具** | `tools/src/aden_tools/tools/` | ~15 | browser_navigate, browser_click, browser_type, browser_screenshot, ... |
| **MCP 终端工具** | `tools/src/aden_tools/tools/` | ~8 | terminal_exec, terminal_rg, terminal_find, ... |
| **MCP 文件工具** | `tools/src/aden_tools/tools/` | ~5 | file_read, file_write, file_search, ... |
| **MCP 集成工具** | `tools/src/aden_tools/tools/` | ~30 | gmail_*, slack_*, salesforce_*, github_*, ... |
| **MCP Web 工具** | `tools/src/aden_tools/tools/` | ~5 | web_scrape, search_papers, search_wikipedia, ... |
| **MCP 图表工具** | `chart_tools_server.py` | ~5 | create_chart, export_chart, ... |
| **总计** | | **~102** | |

### 11.2 MCP 工具注册配置

`mcp_servers.json` 的完整格式：

```json
[
  {
    "name": "terminal-tools",
    "transport": "stdio",
    "command": "uv",
    "args": ["run", "--directory", "tools/", "terminal_tools_server.py"],
    "env": {
      "SHELL": "/bin/bash",
      "HOME": "/home/user"
    },
    "cwd": "/home/user/projects",
    "description": "Terminal command execution and file system tools"
  },
  {
    "name": "browser-tools",
    "transport": "http",
    "url": "http://localhost:9222",
    "headers": {},
    "description": "Chrome DevTools Protocol browser automation"
  },
  {
    "name": "chart-tools",
    "transport": "sse",
    "url": "http://localhost:8080/sse",
    "description": "Chart creation and rendering tools"
  }
]
```

### 11.3 MCP 传输类型对比

| 传输 | 协议 | 连接方式 | 特点 |
|------|------|---------|------|
| `stdio` | 标准 I/O | 子进程 stdin/stdout | 最常用，独立进程，自动生命周期管理 |
| `http` | HTTP POST | REST API | 适合远程或已有 HTTP 服务 |
| `sse` | Server-Sent Events | 长连接 HTTP | 实时双向通信，支持流式 |
| `unix` | Unix Domain Socket | 本地 socket | 最低延迟，仅本地 |

### 11.4 工具上下文参数自动注入

`ToolRegistry.CONTEXT_PARAMS` 定义了三类自动注入的上下文参数：

```python
CONTEXT_PARAMS = frozenset({"agent_id", "data_dir", "profile"})

# 在工具调用时:
# 1. 从 LLM 面向的 schema 中剥离这些字段 (LLM 不知道它们)
# 2. 从执行上下文中读取实际值
# 3. 自动注入到工具调用的参数中
# 这确保了工具调用始终携带正确的 agent_id (用于日志关联)
# 和 data_dir (用于文件隔离)
```

### 11.5 Concurrency-Safe 工具分类

工具分为两类执行模式：

| 类别 | 默认行为 | 示例 |
|------|---------|------|
| **Safe (并发安全)** | 可并发执行 | read_file, web_scrape, browser_screenshot, terminal_rg |
| **Unsafe (默认)** | 串行化执行 | write_file, terminal_exec, browser_click, browser_type |

未在 `CONCURRENCY_SAFE_TOOLS` 白名单中的工具默认被视为不安全，同一轮中不可并发执行。

---

## 12. 日志和监控机制

### 12.1 事件类型完整列表

`event_bus.py` 中 `EventType` 枚举定义了所有事件类型：

```python
class EventType(StrEnum):
    # 执行生命周期
    EXECUTION_STARTED = "execution_started"
    EXECUTION_COMPLETED = "execution_completed"
    EXECUTION_FAILED = "execution_failed"
    EXECUTION_PAUSED = "execution_paused"
    EXECUTION_RESUMED = "execution_resumed"

    # 状态变更
    STATE_CHANGED = "state_changed"
    STATE_CONFLICT = "state_conflict"

    # 目标跟踪
    GOAL_PROGRESS = "goal_progress"
    GOAL_ACHIEVED = "goal_achieved"
    CONSTRAINT_VIOLATION = "constraint_violation"

    # 流生命周期
    STREAM_STARTED = "stream_started"
    STREAM_STOPPED = "stream_stopped"

    # 节点 EventLoop 生命周期
    NODE_LOOP_STARTED = "node_loop_started"
    NODE_LOOP_ITERATION = "node_loop_iteration"
    NODE_LOOP_COMPLETED = "node_loop_completed"
    NODE_ACTION_PLAN = "node_action_plan"

    # LLM 流式可观测性
    LLM_TEXT_DELTA = "llm_text_delta"
    LLM_REASONING_DELTA = "llm_reasoning_delta"
    LLM_TURN_COMPLETE = "llm_turn_complete"

    # 工具生命周期
    TOOL_CALL_STARTED = "tool_call_started"
    TOOL_CALL_COMPLETED = "tool_call_completed"

    # Queen/用户交互事件
    CLIENT_OUTPUT_DELTA = "client_output_delta"

    # 暂停/升级/报告
    PAUSE_REQUESTED = "pause_requested"
    ESCALATION_REQUESTED = "escalation_requested"
    SUBAGENT_REPORT = "subagent_report"

    # Worker 生命周期 (Worker-based 图执行)
    WORKER_COMPLETED = "worker_completed"
    WORKER_FAILED = "worker_failed"

    # 任务系统事件
    TASK_CREATED = "task_created"
    TASK_COMPLETED = "task_completed"

    # 上下文使用
    CONTEXT_USAGE = "context_usage"
```

### 12.2 事件历史尾部查询

EventBus 保留最近事件的历史记录，支持按时间、类型和自定义过滤器查询：

```python
def get_history(
    self,
    since: float | None = None,          # 从此时间戳(含)开始的事件
    event_types: list[EventType] | None = None,  # 按事件类型过滤
    filter_fn: Callable[[AgentEvent], bool] | None = None  # 自定义过滤
) -> list[AgentEvent]
```

### 12.3 HIVE_DEBUG_EVENTS 环境变量

当设置 `HIVE_DEBUG_EVENTS=1` 时，每个发布的事件都会写入 JSONL 文件：

```
~/.hive/event_logs/20260523_143022.jsonl

每行格式:
{"type":"worker_completed","stream_id":"stream_abc","node_id":"node_x","data":{...}}
```

生产环境中禁用（默认），仅用于调试。

### 12.4 决策跟踪器

`decision_tracker.py` 实现了完整的决策记录系统：

```python
class DecisionTracker:
    """记录每次 LLM 调用的决策，用于审计和调试"""
    
    def start_run(goal_id, goal_description, input_data) -> str
        # 开始新的执行运行，返回 run_id
    
    def log_decision(
        node_id, node_name, decision_type,  # "llm_call"|"tool_call"|"judge_verdict"
        input_data, output_data, tokens_used, latency_ms, ...
    ):
        # 记录单个决策
    
    def end_run(success, narrative) -> None
        # 结束运行并标记状态
    
    # 决策历史存储在内存中，可通过 API 查询
    # 格式: list[{"timestamp": ..., "type": ..., "data": {...}}]
```

### 12.5 LLM 调试日志查看器

`debugger/cli.py` 提供了一个交互式终端 UI，用于查看 LLM 调用日志：

```
hive debugger

打开交互式界面:
┌──────────────────────────────────────────────┐
│ LLM Debug Log Viewer                         │
│                                              │
│ Run: run_20260523_143022_abc12345            │
│                                              │
│ 1. Node "research_phase"  (3 steps)          │
│    ├── Step 1: LLM Call (1234 tokens)         │
│    │   ├── System: "You are a research..."    │
│    │   ├── User: "Find information about..."  │
│    │   ├── Assistant: "I'll search for..."   │
│    │   └── Tool: search_wikipedia("AI")       │
│    ├── Step 2: LLM Call (567 tokens)          │
│    └── Step 3: Judge Verdict ACCEPT           │
│                                              │
│ 2. Node "report_phase" (1 step)              │
│    └── ...                                    │
│                                              │
│ [q]uit [j]down [k]up [enter]expand           │
└──────────────────────────────────────────────┘
```

### 12.6 OpenTelemetry 对齐

所有三个日志级别都包含 OTel 对齐字段：

| 字段 | 用途 |
|------|------|
| `trace_id` | 关联同一执行中所有日志的追踪 ID |
| `span_id` | 标识单个步骤/节点的跨度 ID |
| `parent_span_id` | 父子跨度关系 (L3 属于 L2) |
| `execution_id` | 跨运行/会话关联的执行 ID |

这些字段当前用于内部关联，未来可直接导出到 OpenTelemetry 后端。

---

## 13. 技能系统深度分析

### 13.1 SkillsManager 加载层次

```
SkillsManager.load_all()
  │
  ├── Tier 1: _default_skills/ (6 个)
  │     ├── colony-progress-tracker     # Colony 进度跟踪
  │     ├── context-preservation        # 上下文保护协议
  │     ├── error-recovery              # 错误恢复策略
  │     ├── note-taking                 # 笔记/记忆记录
  │     ├── quality-monitor             # 质量监控
  │     └── writing-hive-skills         # 编写新技能的指南
  │     └── (始终加载，不可禁用)
  │
  ├── Tier 2: _preset_skills/ (6 个)
  │     ├── browser-automation          # 浏览器自动化
  │     ├── chart-creation-foundations  # 图表创建
  │     ├── linkedin-automation         # LinkedIn 自动化
  │     ├── terminal-tools-foundations  # 终端工具基础
  │     ├── terminal-tools-fs-search    # 文件系统搜索
  │     ├── terminal-tools-job-control  # 终端任务控制
  │     └── (可选，通过 agent.json 配置)
  │
  ├── Tier 3: Agent 级技能 (每个 Agent 独有)
  │     ├── {agent_dir}/.hive/skills/   # Agent 本地技能
  │     └── 通过 skill_dirs 参数传入
  │
  ├── Tier 4: Colony 级技能
  │     └── {colony_dir}/skills/        # Colony 专属技能
  │
  └── Tier 5: 社区注册表
        └── GitHub: hive-skill-registry (远程)
```

### 13.2 技能格式 (SKILL.md)

每个技能目录直接包含一个 `SKILL.md` 文件和可选的 `references/` 子目录：

```
skills/terminal-tools-foundations/
├── SKILL.md                   # 主要技能文档 (Markdown)
└── references/
    └── exit_codes.md          # 参考文档 (Agent 可读取)
```

SKILL.md 的内容直接注入到系统提示词中，作为 Agent 的 "已知技能知识"。

### 13.3 技能信任和验证

`skills/trust.py` 实现了技能安装的安全性检查：

```python
# 安装时的安全检查:
# 1. 检查 source_url (来自注册表或用户提供)
# 2. 验证 git 仓库是公开的
# 3. 检查 SKILL.md 大小合理 (拒绝超大文件)
# 4. 验证目录结构不包含恶意文件
# 5. 解析 SKILL.md 的 frontmatter (YAML)
# 6. 检查是否与已有技能名称冲突
```

---

## 14. 编码生成 Agent (Coding Agent) 集成

### 14.1 Agent 自动生成流程

Hive 的一个关键特性是通过自然语言目标自动生成完整 Agent。此过程由 Coding Agent (Claude Code / Codex) 驱动：

```
用户描述: "创建一个每天检查邮箱并总结重要邮件的 Agent"
  ↓
Queen 调用 Coding Agent
  ├── 传递: Queen 的框架引用文档 (framework_guide.md, gcu_guide.md, ...)
  ├── 传递: 模板目录路径 (examples/templates/)
  ├── 传递: file_templates.md (声明式编写指南)
  └── 传递: anti_patterns.md (避免的坏模式)
  ↓
Coding Agent 生成:
  ├── agent.json        # Agent 元数据 + 目标定义
  ├── flowchart.json    # NodeSpec + EdgeSpec + GraphSpec
  ├── config.py         # RuntimeConfig(从 ~/.hive/configuration.json)
  ├── agent.py          # Python 文件 (如果自定义逻辑)
  ├── __main__.py       # 入口点
  ├── __init__.py       # 包标记
  ├── mcp_servers.json  # MCP 服务器配置
  ├── triggers.json     # 触发器定义
  └── README.md         # 文档
  ↓
输出到: exports/ 或 ~/.hive/colonies/{name}/
  ↓
CLI: hive colony load {name} → 启动
```

### 14.2 参考文档文件

`core/framework/agents/queen/reference/` 下的参考文档在 Coding Agent 生成代码时使用：

| 文件 | 内容 |
|------|------|
| `framework_guide.md` | 框架 API 和约定指南 (NodeSpec, EdgeSpec, GraphSpec, Goal, AgentLoop 生命周期) |
| `gcu_guide.md` | 浏览器自动化指南 (Graphical Computer Use) |
| `file_templates.md` | 文件模板和声明式 Agent 定义格式 |
| `file_templates_declarative.md` | 声明式编写的详细指南 (仅 JSON，无需 Python) |
| `anti_patterns.md` | 常见的坏模式和避免策略 |

---

## 15. 结论与架构总结

### 15.1 补充关键发现

通过对源代码的深入分析，以下是一些初版报告未充分覆盖的关键发现：

1. **无状态设计 vs 状态恢复**：Hive 在 Agent 级别采用无状态设计 (每个 AgentLoop 执行是原子的)，但在会话级别通过 state.json + cursor.json + 检查点实现了完整的崩溃恢复。

2. **Worker 隔离机制**：每个 Worker 运行在独立的 asyncio.Task 中，拥有独立的 ToolRegistry 执行上下文 (profile, agent_id)，通过 contextvars 隔离，不需要独立进程。

3. **双向通信代理模式**：Queen 和 Worker 之间的通信不是直接的函数调用，而是通过 EventBus 的发布/订阅模式 + 合成工具 (escalate/reply_to_worker) 实现。

4. **SQLite 作为唯一数据库**：Hive 不依赖任何外部数据库，所有持久化状态 (任务队列、进度账本、SOP 检查清单) 都存储在 SQLite 中，通过 WAL 模式支持高并发。

5. **提示词工程是基础设施**：Hive 将大量工程投入在提示词组装上，包括多级人格系统、记忆召回注入、技能目录注入、动态提供者模式，这些构成了 Agent 能力的核心而非后加功能。

6. **OAuth 令牌自动管理**：Hive 支持通过 Claude Code、Codex、Kimi Code 和 Antigravity 的订阅令牌自动认证，包括刷新令牌旋转和 Keychain 集成。

### 15.2 与 LyClaw 系统设计的对比分析

基于对 Hive 的全面分析，与当前正在设计的 LyClaw 记忆系统相比：

| 特性 | Hive 做法 | LyClaw 可借鉴的方向 |
|------|----------|-------------------|
| 记忆存储 | 纯文件 Markdown，无向量数据库 | 可考虑文件+向量混合方案 |
| 记忆召回 | LLM 文本选择 (每轮 1 次 LLM 调用) | 语义搜索可提升大规模记忆的效率 |
| 记忆写入 | Agent 手动调用 write_file | 自动记忆提取和写入 |
| 任务队列 | SQLite WAL 模式，原子认领 | 轻量级的 SQLite 方案适合单机部署 |
| Agent 通信 | EventBus 发布/订阅 | 适合松耦合的 Agent 通信 |
| 崩溃恢复 | 多层检查点系统 | 可借鉴 cursor.json + state.json 双文件模式 |
| 日志系统 | L1/L2/L3 三层 + OTel 对齐 | 开箱即用的可观测性 |
| 工具系统 | MCP 协议 (STDIO/HTTP/Unix/SSE) | MCP 是值得考虑的工具集成标准 |
| 身份/人格 | 5 柱角色构造 + 完整档案 | 人格注入对于对话型 Agent 很重要 |
| 配置管理 | 单 JSON 文件 + 目录约定 | 简单直接，容易理解和调试 |

### 15.3 LyClaw 架构建议

基于 Hive 的分析，对 LyClaw 记忆系统的建议：

1. **采用混合记忆存储**：文件记忆 (轻量、易编辑) + 向量索引 (高效检索) + SQLite (任务队列)，各取所长
2. **实现自动记忆提取**：通过 LLM 后处理从对话中自动提取关键信息写入记忆
3. **借鉴 cursor.json 模式**：每次迭代持久化状态，实现无缝崩溃恢复
4. **采用 MCP 协议**：标准化工具接口，复用社区工具生态
5. **分层日志系统**：实现 L1/L2/L3 三层日志，确保生产可观测性

---

*补充分析完成日期: 2026-05-23*
*分析基于: `hive-main` 源代码仓库, `core/framework/` 下全部源文件*
