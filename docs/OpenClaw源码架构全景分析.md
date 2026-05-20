# OpenClaw 源码架构全景分析

> 基于 2026.5.18 版本 dist 代码 + 12个Agent并行分析结果的综合报告

---

## 一、总体架构鸟瞰

OpenClaw 是一个纯 TypeScript/Node.js 的 AI Agent 网关平台。核心定位是：**多平台消息接入 → 统一 Agent 处理 → 多模型调用 → 工具执行 → 记忆持久化**。

```
┌──────────────────────────────────────────────────────────────────┐
│                        Gateway (HTTP服务)                        │
│                   port 18789, loopback only                      │
├──────────────────────────────────────────────────────────────────┤
│  Channel Layer (消息平台适配)                                      │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                  │
│  │Zalo  │ │Discord│ │Slack │ │WhatsApp│ │WebChat│  ...           │
│  └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘                  │
│     └────────┴───────┴───────┴───────┴──────┘                    │
│                       │                                          │
├──────────────────────────────────────────────────────────────────┤
│  Session Layer (会话管理)                                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐                     │
│  │ Session  │ │ Thread   │ │ SessionStore │                     │
│  │ 主会话    │ │ 子线程    │ │ SQLite持久化  │                     │
│  └──────────┘ └──────────┘ └──────────────┘                     │
├──────────────────────────────────────────────────────────────────┤
│  Agent Layer (Agent编排)                                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐                     │
│  │Agent定义  │ │Sub-Agent │ │ 角色/深度控制 │                     │
│  │model/tools│ │  生成管理  │ │ Orchestrator │                     │
│  └──────────┘ └──────────┘ └──────────────┘                     │
├──────────────────────────────────────────────────────────────────┤
│  Turn Pipeline (消息处理流水线)                                     │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐              │
│  │入站  │→│解析  │→│调度  │→│LLM  │→│工具  │→│出站  │              │
│  │适配  │ │上下文│ │路由  │ │调用  │ │执行  │ │格式化│              │
│  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘              │
├──────────────────────────────────────────────────────────────────┤
│  Tool System (27+ 原生工具)                                       │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐                     │
│  │exec│ │read│ │edit│ │mem │ │web │ │sub │  ...                 │
│  │    │ │    │ │    │ │srch│ │srch│ │spwn│                       │
│  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘                     │
├──────────────────────────────────────────────────────────────────┤
│  Memory System (记忆系统) ★核心★                                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                         │
│  │ Builtin  │ │   QMD    │ │ Session  │                         │
│  │ SQLite+  │ │ External │ │  Memory  │                         │
│  │ vec+BM25 │ │ CLI Proc │ │  Flush   │                         │
│  └──────────┘ └──────────┘ └──────────┘                         │
├──────────────────────────────────────────────────────────────────┤
│  Plugin & Skills (插件/技能)                                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                         │
│  │ Manifest │ │ 19 API  │ │ Skill   │                         │
│  │ Registry │ │ 注册方法  │ │ Discovery│                         │
│  └──────────┘ └──────────┘ └──────────┘                         │
├──────────────────────────────────────────────────────────────────┤
│  Provider System (40+模型提供商)                                   │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                  │
│  │OpenAI│ │Anthropic│ │Google│ │DeepSeek│ │AWS │  ...           │
│  │Chat  │ │Messages│ │GenAI │ │Chat   │ │Bedrk│                  │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘                  │
├──────────────────────────────────────────────────────────────────┤
│  Config System (配置系统)                                          │
│  ┌──────┐ ┌──────┐ ┌──────┐                                      │
│  │Zod v4│ │7-Step│ │Hot   │                                      │
│  │Schema│ │Load  │ │Reload│                                      │
│  └──────┘ └──────┘ └──────┘                                      │
└──────────────────────────────────────────────────────────────────┘
```

### 核心Bundle文件（按大小排列）

| 文件 | 大小 | 实际内容 |
|------|------|----------|
| `dist-R75ezP7-.js` | 943K | 主运行时(Reflect-Metadata polyfill + 3rd party libs) |
| `dist-Cpe9eI94.js` | 812K | Zalo消息平台集成 |
| `openclaw-tools-wLbjLILX.js` | 451K | 全部原生工具实现 |
| `schemas-Bmna8ihM.js` | 429K | Zod v4 运行时校验Schema |
| `plugin-5PeetjLI.js` | 384K | Twitch频道插件 |
| `provider-Db4lCuL9.js` | 302K | Discord Bot集成 |
| `runtime-schema-CBUTClgL.js` | 295K | 配置Schema元数据 |

---

## 二、一个Agent封装了什么

这是用户最关心的核心问题之一。在OpenClaw中，一个Agent = 以下能力的完整封装：

### 2.1 Agent核心定义

```typescript
// 逻辑模型 (来自 plugin-sdk src/agents/)
Agent {
  agentId: string           // 唯一标识
  model: { primary: string } // 主模型 (如 deepseek/deepseek-chat)
  systemPrompt: string       // 系统提示词
  tools: Tool[]              // 可用工具列表
  subagents: SubagentDef[]   // 可生成的子Agent定义
  mode: "local" | "remote"   // 运行模式
  
  // 子Agent控制
  maxSpawnDepth: number      // 最大生成深度 (默认3)
  maxChildrenPerAgent: number // 每个Agent最多子节点 (默认5)
  canSpawnChildren: boolean  // 是否可以生成子Agent
  canControlChildren: boolean // 是否可以控制子Agent
  role: "main" | "orchestrator" | "leaf" // 3层角色
}
```

### 2.2 Agent的工具能力清单 (27+原生工具)

#### 文件与代码操作
| 工具 | 功能 | 安全策略 |
|------|------|----------|
| `read` | 读取文件，支持PDF/图片 | 工作区限制 |
| `write` | 创建/覆盖文件 | 安全校验 |
| `edit` | 精确字符串替换 | 冲突检测 |
| `glob` | 文件模式匹配 | 路径过滤 |
| `grep` | 正则搜索文件内容 | 大小限制 |

#### 命令执行
| 工具 | 功能 |
|------|------|
| `exec` | 执行shell命令 |
| `process` | 管理后台进程(启动/停止/列表) |

#### 记忆系统
| 工具 | 功能 |
|------|------|
| `memory_search` | 混合搜索记忆(BM25+向量) |
| `memory_get` | 获取记忆文件具体内容 |
| `memory_list` | 列出已索引的记忆文件 |

#### 网络与搜索
| 工具 | 功能 |
|------|------|
| `web_search` | 网络搜索 |
| `web_fetch` | 获取URL内容 |

#### 子Agent编排
| 工具 | 功能 |
|------|------|
| `sessions_spawn` | 生成子Agent执行任务 |
| `sessions_list` | 列出所有会话 |
| `sessions_history` | 查看会话历史 |
| `sessions_send` | 向会话发送消息 |
| `sessions_yield` | 子Agent向父Agent汇报 |
| `subagents` | 管理子Agent(list/kill/steer) |
| `agents_list` | 列出可用Agent定义 |

#### 多媒体
| 工具 | 功能 |
|------|------|
| `image` | 图片理解 |
| `image_generate` | 图片生成 |
| `video_generate` | 视频生成 |
| `music_generate` | 音乐生成 |
| `pdf` | PDF处理 |
| `tts` | 文本转语音 |

#### 系统控制
| 工具 | 功能 |
|------|------|
| `message` | 发送消息到指定频道 |
| `cron` | 定时任务管理 |
| `gateway` | Gateway状态管理 |
| `session_status` | 会话状态查询 |
| `update_plan` | 更新执行计划 |
| `heartbeat_response` | 心跳响应 |

### 2.3 工具执行生命周期

```
构造 → 策略管道 → 标准化 → 运行时 → 结果
  │      │          │        │        │
  │      │          │        │    ┌───────┐
  │      │          │        │    │exec   │
  │      │          │        │    │read   │
create  policy   normalize  toTool │write  │  → ToolResult
Tool()  .wrap()  (handler)  Defs() │search │    { content,
  │      │          │        │    │spawn  │      metadata }
  │   security  coerce    generate  │...    │
  │   workspace params   JSONSchema └───────┘
  │   approval  types    for LLM
```

关键文件：
- 工具创建: `pi-tools.d.ts` (`createOpenClawCodingTools()`), `openclaw-tools.d.ts` (`createOpenClawTools()`)
- 工具实现: `openclaw-tools-wLbjLILX.js` (451K, 全部实现)
- `sessions_spawn`: 通过此工具由LLM function calling触发子Agent生成

---

## 三、记忆系统深度分析 ★★★

记忆系统是OpenClaw最复杂的子系统，采用**双后端架构**。

### 3.1 双后端架构

```
MemoryManagerSyncOps (基类 - 同步操作)
    │
    ├── MemoryManagerEmbeddingOps (嵌入操作层)
    │       │
    │       ├── MemoryIndexManager (Builtin后端)
    │       │   ├── SQLite数据库
    │       │   ├── FTS5全文索引
    │       │   ├── sqlite-vec向量索引
    │       │   └── 本地embedding模型
    │       │
    │       └── QmdMemoryManager (QMD后端)
    │           ├── 外部CLI进程 (mcporter)
    │           ├── Collections集合
    │           └── 3阶段工作流
    │
    └── MemoryPluginState (运行时状态管理)
```

### 3.2 Builtin后端 - SQLite Schema

```sql
-- 元数据表
CREATE TABLE meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- 文件索引表
CREATE TABLE files (
    path TEXT PRIMARY KEY,
    mtime_ms INTEGER NOT NULL,
    size_bytes INTEGER NOT NULL,
    hash TEXT NOT NULL,
    indexed_at_ms INTEGER NOT NULL
);

-- 文本块表
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path TEXT NOT NULL REFERENCES files(path) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL,
    line_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL
);

-- 嵌入缓存表
CREATE TABLE embedding_cache (
    content_hash TEXT PRIMARY KEY,
    embedding BLOB NOT NULL,
    model TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

-- FTS5全文索引 (虚拟表)
CREATE VIRTUAL TABLE chunks_fts USING fts5(
    content,
    content_rowid='id',
    tokenize='porter unicode61'
);

-- sqlite-vec向量索引 (虚拟表)
CREATE VIRTUAL TABLE chunks_vec USING vec0(
    id INTEGER PRIMARY KEY,
    embedding FLOAT[1536]
);
```

### 3.3 chunkMarkdown() - 分块算法

核心算法在 `internal-C8kPUK_1.js` 第267-344行：

```
输入: Markdown文件路径
  │
  ├── 1. 按行分割文件
  │
  ├── 2. 按标题(#)检测段落边界
  │     优先级: H2 > H3 > H1 > 空行
  │
  ├── 3. 逐行累积token直到达到预算(chunkTokens)
  │     在最近的段落边界处切割
  │
  └── 4. 相邻chunk之间重叠(overlapTokens)
        输出: chunks[] (每个包含content, line_start, line_end, token_count)
```

### 3.4 混合搜索算法 - 5阶段流水线

```
用户查询 "Q"
    │
    ├── 阶段1: 查询扩展 (query-expansion-BypeE2NS.js)
    │   生成同义/相关变体 Q1, Q2, Q3...
    │
    ├── 阶段2: 并行检索
    │   ├── searchVector(Q) → 向量搜索 (sqlite-vec ANN)
    │   │   权重: 0.7 (默认)
    │   │   候选数: maxResults × 4 = 24
    │   │
    │   └── searchKeyword(Q) → 关键词搜索 (FTS5 BM25)
    │       权重: 0.3 (默认)
    │       候选数: maxResults × 4 = 24
    │
    ├── 阶段3: 合并排序 (mergeHybridResults)
    │   combined_score = 
    │       vector_weight × vector_score 
    │     + text_weight × text_score
    │   (仅在两个结果集中都出现的chunk参与加权合并)
    │
    ├── 阶段4: MMR重排序 (Maximal Marginal Relevance)
    │   目标: 在相关性和多样性之间平衡
    │   MMR = λ × relevance_score - (1-λ) × max_similarity_to_selected
    │   λ = 0.7 (默认lambda)
    │
    └── 阶段5: 时间衰减
        decay = exp(-age_in_days / half_life_days)
        half_life_days = 30 (默认)
        最终分数 = MMR_score × decay
        │
        └── 返回 Top-K 结果 (snippet, 不超过700字符)
```

### 3.5 搜索结果的渐进式披露

```
memory_search("Q")
    │
    └── 返回: [ { file, chunk, snippet (≤700 chars), score } ]
         │
         └── Agent如需完整内容 → memory_get(file, lines)
              │
              └── 返回指定行范围的内容
                   │
                   └── 如需更多 → read(file) 读全文件
```

### 3.6 sync() - 5种触发方式

```
┌─────────────────────────────────────────────────────┐
│ 同步触发源                                           │
├────────────┬────────────────────────────────────────┤
│ 1. 会话启动 │ session start → syncMemoryFiles()     │
│ 2. 搜索前   │ search() 检测 dirty flag → sync()     │
│ 3. 文件监听 │ chokidar watch → debounce → sync()    │
│ 4. 定时器   │ setInterval → 检查变更 → sync()        │
│ 5. 手动触发 │ 用户命令 → sync()                      │
└────────────┴────────────────────────────────────────┘

syncMemoryFiles():
  1. 扫描工作区文件(glob patterns)
  2. 比较 mtime/hash 与 files 表
  3. 新增/修改 → indexFile()
  4. 删除 → 移除对应chunks
  5. 清除 dirty flag

indexFile():
  1. chunkMarkdown(file) → chunks[]
  2. 对每个chunk → embed(content) → embedding_cache
  3. INSERT INTO chunks + chunks_fts + chunks_vec
  4. UPDATE files SET indexed_at
```

### 3.7 Bootstrap注入与Pre-Compaction Memory Flush

```
Session Start
    │
    ├── Bootstrap注入
    │   将记忆摘要注入到System Prompt中
    │   格式: <memory>...摘要内容...</memory>
    │
    └── 上下文管理
        │
        ├── 正常对话进行...
        │
        ├── 当context接近限制 → Pre-Compaction Flush
        │   在上下文压缩前，静默执行一个agentic turn:
        │   1. Agent自动调用 memory_search 查找相关信息
        │   2. 提取关键记忆
        │   3. 将记忆摘要写入压缩后的context
        │
        └── 压缩后的context = 记忆摘要 + 最近对话
```

### 3.8 QMD后端

QMD (Question Mark Database) 是替代后端，通过外部CLI进程运行：

```
OpenClaw → spawn mcporter CLI → QMD Server
    │
    ├── Collection 概念 (类比SQLite表)
    │   每个collection存储一类记忆
    │
    ├── 3阶段工作流:
    │   1. Export - 从OpenClaw导出会话数据
    │   2. Transform - 转换为QMD格式
    │   3. Index - 向量化和索引
    │
    └── Scope机制:
        控制记忆的可见范围
        (session / agent / global)
```

### 3.9 记忆系统的5个设计原则

1. **混合检索优先**: 向量搜索(BM25) + 关键词搜索(FTS5)并行执行，结果加权合并
2. **渐进式披露**: snippet → memory_get → read 三级递进，节省token
3. **自动同步**: 5种触发器保证记忆索引与文件系统一致
4. **时间衰减**: 越旧的记忆权重越低，模拟人类记忆的自然遗忘
5. **双后端可切换**: Builtin(嵌入式) vs QMD(独立进程)，适应不同规模需求

---

## 四、核心模块详解

### 4.1 配置系统

```
7步加载流水线:
  JSON5.parse(file)           # 1. 解析(支持注释、尾随逗号)
    → $include 展开           # 2. 引用其他配置文件
    → ${ENV} 替换             # 3. 环境变量替换
    → 应用覆盖(overrides)     # 4. 命令行/API覆盖
    → 填充默认值(defaults)    # 5. Zod schema默认值
    → Zod v4 校验(validate)   # 6. 类型安全校验
    → 物化(materialize)       # 7. 转换为运行时对象

3个Branded状态:
  SourceConfig → ResolvedSourceConfig → RuntimeConfig

热重载: 写文件 → notify → refresh handler → 创建新快照
  (不使用 fs.watch, 而是主动通知机制)
```

### 4.2 Provider系统

```
40+ 提供商，按API类型分类:

  API类型                 提供商举例
  ─────────────────────────────────────
  openai-completions     OpenAI, DeepSeek, Groq, Fireworks...
  anthropic-messages     Anthropic Claude
  google-generative-ai   Google Gemini
  aws-bedrock            AWS Bedrock
  azure-openai           Azure OpenAI
  vertex-ai              Google Vertex AI
  together               Together AI
  mistral                Mistral AI
  cohere                 Cohere

特性:
  - 统一的 chat() 接口
  - 自动回退(fallback) + 冷却(cooldown)
  - 用量追踪: input/output/cacheRead/cacheWrite tokens + cost
  - streaming 支持
  - thinking/reasoning 模式支持
```

### 4.3 Plugin & Skills系统

```
Plugin架构:
  Manifest Registry
    ├── 声明式Manifest (package.json 中的 openclaw 字段)
    ├── 19种API注册方法:
    │   registerTool, registerChannel, registerProvider,
    │   registerSkill, registerHook, registerCommand,
    │   registerMemoryBackend, registerAuthProvider...
    └── 运行时注册与生命周期管理

Skills架构:
  SkillEntry {
    name: string              # 技能名称
    description: string       # 描述
    requires: string[]        # 依赖
    install: string           # 安装命令
    exposure: SkillExposure   # 暴露方式 (manual/auto)
    entry: string             # 入口文件
  }
  
  支持远程Skills (从URL加载)
  Skills注入到System Prompt中供LLM发现和调用
```

### 4.4 子Agent编排系统

```
生成方式: 主要通过 sessions_spawn 工具 (LLM function calling)
  备选: 直接API调用 spawnSubagentDirect()

6阶段生成流程:
  1. 验证 - 检查权限、深度、配额
  2. 会话创建 - 创建子Session
  3. 线程绑定 - 绑定到父Thread
  4. 上下文构建 - 系统提示词 + 附件
  5. 任务执行 - 运行Agent循环
  6. 注册完成 - 向父Agent推送结果

3层角色体系:
  Depth 0: main (主Agent)
  Depth 1~max-1: orchestrator (编排Agent, 可继续生成子Agent)
  Depth >= max: leaf (叶子Agent, 不可生成子Agent)

控制参数:
  maxSpawnDepth: 3 (默认)
  maxChildrenPerAgent: 5 (默认)
  canSpawnChildren: boolean
  canControlChildren: boolean

Push Announce机制:
  子Agent完成后主动推送结果到父Agent
  (非轮询, 通过Gateway事件系统)
```

### 4.5 Channel消息系统

```
8阶段Turn流水线:

  入站适配 (inbound adapter)
    │  平台特定消息 → 统一 Message 格式
    │
    ├── 上下文解析 (context resolution)
    │   加载会话历史、记忆、skills
    │
    ├── 调度路由 (dispatch)
    │   匹配Agent配置
    │
    ├── Agent循环 (agent loop)
    │   LLM调用 → 工具执行 → LLM调用 → ...
    │
    ├── 工具执行 (tool execution)
    │   安全策略检查 → 执行 → 结果序列化
    │
    ├── 记忆更新 (memory update)
    │   自动索引新信息
    │
    ├── 出站格式化 (outbound formatting)
    │   统一响应 → 平台特定格式
    │
    └── 消息发送 (message delivery)
        发送到目标平台

Gateway架构:
  - HTTP服务 (port 18789, loopback)
  - WebSocket用于实时通信
  - Token认证模式
  - Control UI (allowInsecureAuth for local dev)
```

### 4.6 会话管理

```
Session (主会话)
  ├── sessionId: 唯一标识
  ├── mode: "local" | "remote"
  ├── status: "idle" | "running" | "waiting"
  ├── agentId: 关联的Agent
  ├── model: 使用的模型
  │
  ├── Thread[] (子线程)
  │   ├── threadId: 线程标识
  │   ├── messages: 消息历史
  │   └── parentThreadId: 父线程(子Agent场景)
  │
  └── SessionStore (SQLite持久化)
      ├── 会话元数据
      ├── 消息历史
      └── 状态快照
```

---

## 五、关键设计模式

### 5.1 采用的设计模式

| 模式 | 应用场景 |
|------|----------|
| **工厂函数** | 所有工具通过 `createXxxTool()` 工厂创建 |
| **策略管道** | 工具执行的安全策略链式包装 |
| **双后端** | Builtin/QMD 记忆后端可切换 |
| **渐进式披露** | memory_search → memory_get → read 三级 |
| **观察者** | chokidar文件监听 + Gateway事件推送 |
| **Branded Types** | Config 3状态编译时区分 |
| **依赖注入** | Plugin通过Manifest + API注册注入能力 |
| **熔断器** | Provider fallback + cooldown |

### 5.2 安全架构

```
工具执行安全层:
  ┌──────────────────────┐
  │  工作区限制          │  所有文件操作限制在工作区内
  ├──────────────────────┤
  │  路径遍历检查        │  防止 ../ 逃逸
  ├──────────────────────┤
  │  命令白名单/黑名单   │  denyCommands 配置
  ├──────────────────────┤
  │  审批流程            │  高风险操作需用户确认
  ├──────────────────────┤
  │  输入净化            │  防注入
  ├──────────────────────┤
  │  速率限制            │  防滥用
  └──────────────────────┘

沙箱模式:
  - sessions_spawn 支持 sandbox 参数
  - 子Agent可在隔离环境中执行
  - 支持 Docker/VM 级别隔离
```

---

## 六、关键源文件索引

### 记忆系统相关
| 文件 | 内容 |
|------|------|
| `engine-storage-oithUJ84.js` | SQLite Schema定义 + 建表语句 |
| `manager-DH54qdpd.js` | MemoryIndexManager: sync/search/indexFile/hybrid merge/MMR/temporal decay |
| `manager-DUY6Gcg4.d.ts` | 类型定义: 类层次结构、接口声明 |
| `internal-C8kPUK_1.js` | chunkMarkdown() 分块算法实现 |
| `engine-qmd-CDxsfClU.js` | QMD后端引擎 |
| `qmd-manager-BeLu5_kc.js` | QMD管理器 |
| `memory-search-BWjOW7PF.js` | memory_search 工具封装 |
| `memory-state-CVl5QzvG.js` | 记忆运行时状态管理 |
| `read-file-Bb7AlNOZ.js` | memory_get + read 工具 |
| `query-expansion-BypeE2NS.js` | 查询扩展 |

### 核心运行时
| 文件 | 内容 |
|------|------|
| `dist-R75ezP7-.js` | 主运行时(入口、CLI、Gateway启动) |
| `schemas-Bmna8ihM.js` | Zod v4 所有配置Schema |
| `runtime-schema-CBUTClgL.js` | Schema元数据(帮助文本、标签) |

### 工具与Agent
| 文件 | 内容 |
|------|------|
| `openclaw-tools-wLbjLILX.js` | 全部原生工具实现(exec/read/write/edit/grep/glob/web/cron/subagent...) |
| `pi-tools.d.ts` | Coding工具创建函数声明 |
| `openclaw-tools.d.ts` | 通用工具创建函数声明 |
| `subagent-spawn-BEF7uA0s.js` | 子Agent生成核心逻辑(spawnSubagentDirect) |

### Plugin SDK (TypeScript定义)
| 路径 | 内容 |
|------|------|
| `src/agents/` | Agent定义类型 |
| `src/config/` | 配置类型 |
| `src/tools/` | 工具接口定义 |
| `src/plugins/` | 插件API接口 |
| `src/channels/` | Channel接口 |
| `extensions/memory-core/` | 记忆核心扩展 |
| `packages/memory-host-sdk/` | 记忆宿主SDK |

---

## 七、总结

OpenClaw 的架构可以用一句话概括：

> **以记忆为核心的、基于工具调用的、多平台多模型的Agent网关平台。**

关键特征：
1. **记忆优先**: 记忆系统是最大的子系统，包含双后端、混合搜索、渐进式披露、自动同步、时间衰减等复杂设计
2. **工具驱动**: Agent的能力通过27+工具表达，工具通过工厂+策略管道构建，通过JSON Schema序列化给LLM
3. **层级编排**: 3层角色(main/orchestrator/leaf)的树形子Agent体系，通过sessions_spawn工具由LLM自主调度
4. **平台无关**: Channel层隔离消息平台差异，Provider层隔离模型API差异
5. **安全内建**: 工作区限制、路径检查、命令黑名单、审批流程等安全机制深度集成在工具执行管道中
