# OpenClaw 记忆系统架构全解

## 先看全局：记忆系统在 OpenClaw 中的位置

OpenClaw 的记忆系统不是一个大一统模块，而是由三层组成：

```
Agent 工具层（Agent 调用的工具）
    ├── memory_search(query) → 检索记忆
    ├── memory_get(path, from, lines) → 按路径+行号精确读取原文
    └── write / edit → Agent 主动写入记忆文件

运行时引擎层（自动执行，无需 Agent 干预）
    ├── 会话记录追加（每条消息自动写入 JSONL）
    ├── Bootstrap 注入（会话启动时加载 MEMORY.md + memory/*.md 到 LLM 上下文）
    └── Pre-compaction Memory Flush（压缩前强制 Agent 记笔记）

索引 & 存储层（对 Agent 透明）
    ├── SQLite + sqlite-vec + FTS5（内建后端，默认）
    └── QMD 独立进程（可选后端，外部 CLI 管理索引）
```

**核心设计原则**：Markdown 文件是唯一事实来源（Source of Truth）。向量索引、FTS 索引都是可重建的派生数据，删了也没关系，sync 一下就能重建。

---

## 一、双后端架构：Builtin vs QMD

OpenClaw 有两个完全独立的记忆后端，通过 `memory.backend` 配置切换：

```
memory.backend = "builtin"（默认）
    → MemoryIndexManager 类
    → 内嵌 SQLite + sqlite-vec + FTS5
    → 索引和搜索都在 Node.js 进程内完成

memory.backend = "qmd"
    → QmdMemoryManager 类
    → 独立 qmd CLI 进程
    → 通过 spawn() 调用 qmd 命令
```

两者实现同一个接口 `MemorySearchManager`，所以对上层 Agent 工具来说完全透明：

```typescript
// types.d.ts 中定义的接口
interface MemorySearchManager {
    search(query, opts?): Promise<MemorySearchResult[]>;
    readFile(params): Promise<{ text, path }>;
    status(): MemoryProviderStatus;
    sync(params?): Promise<void>;
    probeEmbeddingAvailability(): Promise<MemoryEmbeddingProbeResult>;
    probeVectorAvailability(): Promise<boolean>;
    close?(): Promise<void>;
}
```

---

## 二、内建后端（Builtin）：完整代码级拆解

### 2.1 类继承体系

```
MemorySearchManager (interface, types.d.ts)
  ↑
MemoryManagerSyncOps (abstract, manager-sync-ops.d.ts)
  ↑  负责：sync() 调度、文件监听（chokidar）、Schema 管理、元数据读写
  ↑
MemoryManagerEmbeddingOps (abstract, manager-embedding-ops.d.ts)
  ↑  负责：indexFile()、批量 Embedding、Embedding Cache、Provider 管理
  ↑
MemoryIndexManager (concrete, manager.d.ts)
     负责：search()、readFile()、status()、对外接口
```

### 2.2 存储结构：三张核心表 + FTS5 虚表

数据库路径：`~/.openclaw/memory/{agentId}.sqlite`

```sql
-- 文件清单：跟踪哪些文件已被索引
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,      -- 相对于 workspace 的路径，如 "memory/2026-05-19.md"
    source TEXT NOT NULL DEFAULT 'memory',  -- "memory" 或 "sessions"
    hash TEXT NOT NULL,         -- 文件内容的 SHA-256，用于检测变更
    mtime INTEGER NOT NULL,     -- 文件修改时间
    size INTEGER NOT NULL       -- 文件字节数
);

-- 向量块：每个文件被切分为多个 chunk，各自有独立的 embedding
CREATE TABLE IF NOT EXISTS chunks (
    id TEXT PRIMARY KEY,        -- chunk 唯一 ID
    path TEXT NOT NULL,         -- 来源文件
    source TEXT NOT NULL DEFAULT 'memory',
    start_line INTEGER NOT NULL, -- 在源文件中的起始行号（1-indexed）
    end_line INTEGER NOT NULL,   -- 在源文件中的结束行号
    hash TEXT NOT NULL,          -- chunk 文本的 SHA-256
    model TEXT NOT NULL,         -- 生成 embedding 的模型名
    text TEXT NOT NULL,          -- chunk 原始文本（不是摘要！）
    embedding TEXT NOT NULL,     -- JSON 序列化的 float32 数组
    updated_at INTEGER NOT NULL
);

-- Embedding 缓存：避免对相同文本重复请求 embedding API
CREATE TABLE IF NOT EXISTS embedding_cache (
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    provider_key TEXT NOT NULL,
    hash TEXT NOT NULL,
    embedding TEXT NOT NULL,
    dims INTEGER,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (provider, model, provider_key, hash)
);

-- FTS5 全文索引虚表：BM25 关键词搜索
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
    text,                       -- 被索引的文本内容
    id UNINDEXED,               -- 关联 chunks.id
    path UNINDEXED,
    source UNINDEXED,
    model UNINDEXED,
    start_line UNINDEXED,
    end_line UNINDEXED
);
```

**关键细节**：
- `chunks.text` 存的是原始文本片段，不是 embedding 的摘要。这就是为什么 `memory_search` 能直接返回 snippet 而不需要再去读原文。
- FTS5 表中的 `id` 是 UNINDEXED 的，意味着不能用 `id` 做关键词搜索，它只用于 JOIN 回 `chunks` 表获取完整信息。
- `embedding_cache` 的 key 是 `(provider, model, provider_key, hash)`，意味着同一段文本在相同的 provider+model 组合下可以复用缓存。

### 2.3 配置参数与默认值

```javascript
// memory-search-C7gfehPk.js 中定义的默认值
DEFAULT_CHUNK_TOKENS = 400        // 每个 chunk 约 400 token
DEFAULT_CHUNK_OVERLAP = 80        // chunk 之间重叠 80 token
DEFAULT_WATCH_DEBOUNCE_MS = 1500  // 文件变更后防抖 1.5 秒
DEFAULT_MAX_RESULTS = 6           // 最多返回 6 条结果
DEFAULT_MIN_SCORE = 0.35          // 最低相关性分数阈值
DEFAULT_HYBRID_ENABLED = true     // 默认开启混合检索
DEFAULT_HYBRID_VECTOR_WEIGHT = 0.7  // 向量权重 70%
DEFAULT_HYBRID_TEXT_WEIGHT = 0.3    // 关键词权重 30%
DEFAULT_HYBRID_CANDIDATE_MULTIPLIER = 4  // 候选数量 = 最终数量 × 4
DEFAULT_MMR_ENABLED = false       // 默认关闭 MMR 多样性重排
DEFAULT_MMR_LAMBDA = 0.7          // MMR λ 参数（0=多样性优先, 1=相关性优先）
DEFAULT_TEMPORAL_DECAY_ENABLED = false     // 默认关闭时间衰减
DEFAULT_TEMPORAL_DECAY_HALF_LIFE_DAYS = 30 // 半衰期 30 天
DEFAULT_CACHE_ENABLED = true      // 默认开启 embedding 缓存
DEFAULT_SOURCES = ["memory"]      // 默认只检索记忆文件，不含会话记录
DEFAULT_SESSION_DELTA_BYTES = 100000   // 会话新增 100KB 触发索引
DEFAULT_SESSION_DELTA_MESSAGES = 50    // 会话新增 50 条消息触发索引
```

### 2.4 文件切块算法：chunkMarkdown()

实现在 `internal-Bnt1j4hp.js` 的 `chunkMarkdown()` 函数中（第282-359行）：

```
输入：Markdown 文本 + { tokens: 400, overlap: 80 }
输出：{ startLine, endLine, text, hash, embeddingInput }[]

算法：
1. 将内容按 \n 分割为行
2. maxChars = tokens × 4 = 1600 字符（粗略按 4 字符/Token 估算）
3. overlapChars = overlap × 4 = 320 字符
4. 逐行累积：
   - 如果当前行特别长（超过 maxChars）→ 按 token 粒度继续细分
   - 注意不切断 UTF-16 代理对（emoji 等 4 字节字符）
5. 当累积的字符数超过 maxChars → flush 一个 chunk
6. 保留末尾 overlapChars 的文本 → 作为下一个 chunk 的开头
7. 对每个 chunk 计算 SHA-256 hash，构建 { text } 格式的 EmbeddingInput
```

**行号映射（remapChunkLines）**：对于会话 JSONL 文件，先把消息展平为纯文本再切块，然后通过 `lineMap` 把 chunk 的行号映射回原始 JSONL 的行号。这确保了 `memory_search` 返回的行号准确对应到原始文件。

### 2.5 核心流程：search() 方法

`MemoryIndexManager.search(query, opts?)` 的完整执行路径：

```
search("台积电 CoWoS 封装产能")
    │
    ├─→ 1. 检查是否 dirty（文件变化但未同步）
    │      → 如果是，触发后台 sync()，等 sync 完成后再检索
    │
    ├─→ 2. hasIndexedContent()
    │      → 如果索引为空（首次使用）→ 直接返回 []
    │
    ├─→ 3. 并行执行两路检索：
    │
    │    ┌─ searchVector(query) ──────────────────────────┐
    │    │  a. embedQueryWithTimeout(query)                │
    │    │     → 调用 embedding API 将查询转为向量         │
    │    │  b. 在 sqlite-vec 虚表中做 ANN 近似最近邻搜索    │
    │    │  c. 对每个候选计算 cosineSimilarity(query, chunk)│
    │    │  d. 返回 HybridVectorResult[]                   │
    │    │     { id, path, startLine, endLine, source,     │
    │    │       snippet, vectorScore }                    │
    │    └────────────────────────────────────────────────┘
    │
    │    ┌─ searchKeyword(query) ─────────────────────────┐
    │    │  a. buildFtsQuery("台积电 CoWoS 封装产能")      │
    │    │     → 解析查询，生成 FTS5 查询语法              │
    │    │  b. 查询 chunks_fts 虚表                        │
    │    │  c. bm25RankToScore(rank)                       │
    │    │     → 将 BM25 排名转换为 [0,1] 分数             │
    │    │  d. 返回 HybridKeywordResult[]                  │
    │    │     { id, path, startLine, endLine, source,     │
    │    │       snippet, textScore }                      │
    │    └────────────────────────────────────────────────┘
    │
    ├─→ 4. mergeHybridResults({ vector, keyword, weights, mmr, temporalDecay })
    │      │
    │      ├─→ a. 合并两路结果（加权分数融合）
    │      │      score = vectorWeight × vectorScore + textWeight × textScore
    │      │
    │      ├─→ b. （可选）MMR 多样性重排
    │      │      if mmr.enabled:
    │      │        for each remaining slot:
    │      │            pick item that maximizes: λ × score - (1-λ) × max_similarity_to_selected
    │      │      使用 Jaccard 相似度（基于 token 集合）作为文本相似度度量
    │      │
    │      ├─→ c. （可选）时间衰减
    │      │      if temporalDecay.enabled:
    │      │        multiplier = 2^(-ageInDays / halfLifeDays)
    │      │        score = score × multiplier
    │      │
    │      └─→ d. 按 score 降序排序 → 截断到 maxResults 条 → 返回
    │
    └─→ 5. 返回 MemorySearchResult[]
           { path, startLine, endLine, score, snippet, source }
```

### 2.6 buildFtsQuery()：查询语法构建

查询 "台积电 CoWoS 封装产能" 经过 `buildFtsQuery()` 处理后：

```
原始查询:  "台积电 CoWoS 封装产能"
FTS5 查询: "台积电" OR "CoWoS" OR "封装" OR "产能"
```

如果是中文查询，还会限制关键词数量（最多 12 个），避免 FTS5 查询过宽。

### 2.7 MMR 多样性重排：算法细节

MMR（Maximal Marginal Relevance）来自 Carbonell & Goldstein 1998 年的论文，核心公式：

```
MMR = λ × rel(d_i) - (1-λ) × max_{d_j ∈ S} sim(d_i, d_j)
```

其中：
- `rel(d_i)` 是候选文档 d_i 的原始相关性分数
- `S` 是已经被选中的文档集合
- `sim(d_i, d_j)` 是 d_i 和 S 中最相似文档的相似度
- `λ` 控制权衡（0 = 完全多样性优先, 1 = 完全相关性优先）

实现细节：
- 文本相似度用 **Jaccard 相似度** 而非向量余弦相似度
- Token 化：提取英文单词（小写化）、CJK 单字、以及相邻 CJK 双字组合（bigram）
- 贪婪选择：第一轮选分数最高的，之后每轮用 MMR 公式选最优的

### 2.8 时间衰减：指数衰减模型

```
score_new = score_old × 2^(-ageInDays / halfLifeDays)
```

默认半衰期 30 天。意味着：
- 15 天前的记录：分数 × 0.707
- 30 天前的记录：分数 × 0.5
- 60 天前的记录：分数 × 0.25

文件年龄通过文件系统 mtime 获得。

### 2.9 索引同步：sync() 的五种触发时机

```javascript
// 配置项（默认值）
sync.onSessionStart = true    // 1. 会话启动时异步同步一次
sync.onSearch = true           // 2. 检索前如果 dirty → 触发同步
sync.watch = true              // 3. chokidar 监听文件变化，debounce 1.5s 后同步
sync.watchDebounceMs = 1500
sync.intervalMinutes = 0       // 4. 定时同步（0=禁用）
// 5. 手动: openclaw memory index CLI 命令
```

sync() 内部的执行流程（`runSync()` 方法）：

```
runSync({ reason, force, sessionFiles, progress })
    │
    ├─→ syncMemoryFiles()  ←── 扫描 MEMORY.md + memory/**/*.md
    │   │
    │   ├─→ listMemoryFiles(workspaceDir, extraPaths, multimodal)
    │   │     ├─→ 扫描 workspaceDir/MEMORY.md
    │   │     ├─→ 扫描 workspaceDir/memory/**/*.md（递归，跳过符号链接）
    │   │     └─→ 扫描 extraPaths（额外的自定义记忆路径）
    │   │     → 对重复路径做 realpath 去重
    │   │
    │   ├─→ 对每个文件：buildFileEntry(absPath, workspaceDir, multimodal)
    │   │     ├─→ 计算 SHA-256 hash
    │   │     ├─→ 如果 onnx 是 multimodal → buildMultimodalChunkForIndexing()
    │   │     └─→ 返回 { path, absPath, mtimeMs, size, hash, kind, contentText }
    │   │
    │   ├─→ 与 files 表对比 hash：
    │   │     ├─→ hash 不同 → indexFile(entry)  // 新增或修改
    │   │     └─→ hash 相同 → 跳过
    │   │
    │   └─→ 从 files 表删除磁盘上已不存在的文件记录
    │
    ├─→ syncSessionFiles()  ←── 扫描 sessions/*.jsonl
    │   │
    │   ├─→ listSessionFilesForAgent(agentId)
    │   │     → 扫描 ~/.openclaw/agents/{agentId}/sessions/*.jsonl
    │   │
    │   ├─→ buildSessionEntry(absPath)
    │   │     ├─→ 逐行解析 JSONL
    │   │     ├─→ 只提取 type="message" 且 role="user"/"assistant" 的行
    │   │     ├─→ 调用 redactSensitiveText() 脱敏
    │   │     ├─→ 构建 lineMap（展平文本行号 → 原始 JSONL 行号映射）
    │   │     └─→ 返回 { path, content, hash, lineMap }
    │   │
    │   └─→ 增量更新：
    │         ├─→ deltaBytes 达到 100KB → 重新索引
    │         └─→ deltaMessages 达到 50 条 → 重新索引
    │
    └─→ indexFile(entry, { source })
          │
          ├─→ chunkMarkdown(content, { tokens: 400, overlap: 80 })
          │     → 切分为多个 chunk
          │
          ├─→ 对 session 文件: remapChunkLines(chunks, lineMap)
          │     → 将行号映射回原始 JSONL 行号
          │
          ├─→ 插入/更新 files 表
          │
          ├─→ 删除旧的 chunks（DELETE FROM chunks WHERE path = ?）
          │
          ├─→ embedChunksInBatches(chunks)
          │     ├─→ 先查 embedding_cache（缓存命中直接复用）
          │     ├─→ 未命中的组成 batch（默认并发度 2）
          │     ├─→ 调用 embedding API（支持 batch API）
          │     ├─→ 写入 embedding_cache
          │     └─→ 支持 fallback provider（primary 挂了自动切）
          │
          └─→ 写入 chunks 表 + chunks_fts 表
               （每条 chunk: id, path, start_line, end_line, hash, model, text, embedding）
```

### 2.10 Embedding Provider 插件系统

Provider 通过全局单例注册（`memory-search-C7gfehPk.js`）：

```javascript
// 全局 Provider 注册表（存储在 globalThis 的 Symbol 键下）
getMemoryEmbeddingProviders()  →  Map<providerId, { adapter, ownerPluginId }>

// Provider 的 key 方法
registerMemoryEmbeddingProvider(adapter, options)
getMemoryEmbeddingProvider(id)        // 按 ID 获取
listMemoryEmbeddingProviders()        // 列出所有
```

支持的 Provider 类型：
- **auto**：自动检测（本地优先，如有远程配置则用远程）
- **OpenAI**：远程 API，支持 batch API
- **Voyage**：远程 API
- **Ollama**：本地服务
- **Gemini**：Google API
- **Mistral**：远程 API

Fallback 机制：
```
provider → 如果初始化失败 → fallback adapter → 如果也失败 → providerUnavailable
```

### 2.11 文件监听器（chokidar + debounce）

```javascript
// manager-sync-ops.d.ts 中的相关字段
watcher: FSWatcher | null        // chokidar 实例
watchTimer: NodeJS.Timeout | null // debounce timer
dirty: boolean                    // 脏标记（文件变了但未同步）
sessionsDirty: boolean            // 会话文件脏标记
```

工作流程：
1. chokidar 监听 `MEMORY.md` 和 `memory/**/*.md` 的 add/change/unlink 事件
2. 文件变化 → 标记 `dirty = true`
3. 启动 debounce timer（默认 1500ms）
4. timer 到期 → 执行 `scheduleWatchSync()` → 触发 `sync()`
5. 如果在 debounce 期间又来新的变化 → 重置 timer（防抖）

**不立刻同步的原因**：防止文件正在写入时被读取（文件写入不是原子操作）。

---

## 三、渐进式披露（Progressive Disclosure）

这是 OpenClaw 记忆系统最重要的设计模式。

### 3.1 三层架构

```
第一层 memory_search(query) → 返回片段列表
    每条结果: { path, startLine, endLine, score, snippet, source }
    snippet 最多 700 字符（DEFAULT_QMD_LIMITS.maxSnippetChars）
    总共注入到 LLM 上下文不超过 4000 字符（DEFAULT_QMD_LIMITS.maxInjectedChars）

第二层 memory_get(path, from, lines) → 按需回读原文
    Agent 根据第一层的行号范围，精确读取原文段落
    例如：memory_get("memory/2026-05-15.md", from=40, lines=30)

第三层 → 如果还不够，Agent 可以直接用 read 工具读完整文件
```

### 3.2 为什么这样设计？

将 100 个 .md 文件（总计 5MB ≈ 125万 Token）全塞进上下文是不可能的。

而渐进式披露只需要：
- `memory_search`：6 条结果 × 700 字符 ≈ 1050 Token
- `memory_get`：2 条 × 30 行 ≈ 750 Token
- 总计：约 1800 Token，是全文加载的 **0.14%**

### 3.3 在源码中的体现

```typescript
// memory-search-C7gfehPk.js
DEFAULT_MAX_RESULTS = 6          // 最多 6 条
DEFAULT_MIN_SCORE = 0.35         // 过滤低分结果

// backend-config-Bw8hjn_C.js (QMD 限制)
DEFAULT_QMD_LIMITS = {
    maxResults: 6,               // 最多 6 条
    maxSnippetChars: 700,        // 每条 snippet 最多 700 字符
    maxInjectedChars: 4000       // 总注入字符上限
}

// qmd-manager-Bdi-TUef.js
clampResultsByInjectedChars()    // 按字符预算截断
diversifyResultsBySource()       // 按来源交叉排列（memory + sessions 交替出现）
```

### 3.4 安全校验

`memory_get` 中的 `readFile()` 方法会做路径安全校验：

```typescript
// backend-config-Bw8hjn_C.js - readMemoryFile()
// 1. 解析路径（支持绝对路径和相对于 workspace 的路径）
// 2. 检查是否在 workspace 的 memory 目录下
// 3. 检查是否在 extraPaths 白名单内
// 4. 只允许 .md 文件
// 5. 确保没有路径穿越（.. 和绝对路径）
if (!allowedWorkspace && !allowedAdditional) throw new Error("path required");
if (!absPath.endsWith(".md")) throw new Error("path required");
```

---

## 四、记忆写入的三个路径

### 4.1 会话运行记录（自动写入，最高频）

**谁写**：运行时引擎，不是 Agent 工具调用
**写什么**：每条消息、每次工具调用、每次模型切换
**写到哪里**：`~/.openclaw/agents/{agentId}/sessions/{sessionId}.jsonl`
**格式**：JSONL（每行一个 JSON 对象）

```jsonl
{"type":"session","id":"0cc52c2b...","timestamp":"2026-05-19T09:15:35.152Z"}
{"type":"message","role":"user","content":"台积电CoWoS封装产能情况怎么样..."}
{"type":"message","role":"assistant","content":"台积电的CoWoS封装目前面临产能瓶颈..."}
```

写入频率：基本跟随对话过程持续发生。

### 4.2 日常记忆（Agent 主动写入）

**谁写**：Agent 通过 `write` / `edit` 工具调用
**什么时机**：
1. **Agent 自主判断**：当前信息需要暂存（临时决定、待办事项、重要上下文）
2. **Pre-compaction Memory Flush**：上下文接近窗口限制时，系统触发静默轮次
**写到哪里**：`memory/YYYY-MM-DD.md`
**写入方式**：追加（append），不是覆盖

### 4.3 长期记忆（Agent 主动提炼）

**谁写**：Agent 通过 `edit` 工具调用
**什么时机**：
1. 用户明确要求："记住，我以后..."
2. Agent 定期从日常日志中提炼
**写到哪里**：`MEMORY.md`
**写入方式**：编辑（edit），通常是结构性修改
**安全限制**：只在私聊（direct）中加载，群聊绝不加载

### 4.4 Pre-compaction Memory Flush 机制

当会话上下文接近 LLM 上下文窗口限制时：

```
1. OpenClaw 运行时检测到 token 数接近阈值
2. 发起一个静默的 Agentic 轮次（用户看不到）
3. 提示模型：
   "The conversation is about to be compacted.
    Please persist important notes to memory/2026-05-19.md
    before compaction begins."
4. Agent 提取当前会话中的重要信息
5. 调用 write 工具写入 memory/YYYY-MM-DD.md
6. 写入完成后，会话压缩才开始
```

判断条件（`memory-flush.d.ts`）：

```typescript
function shouldRunMemoryFlush(params: {
    entry?: { totalTokens, totalTokensFresh, compactionCount, memoryFlushCompactionCount }
    tokenCount?: number
    contextWindowTokens: number
    reserveTokensFloor: number
    softThresholdTokens: number
}): boolean

// 去重机制：通过 contextHash 避免重复 flush
function computeContextHash(messages): string
// 输入：messages.length + 最后 3 条 user/assistant 消息的内容
// 输出：SHA-256 截断到 16 hex 字符
// 如果 hash 和上次 flush 一样 → 跳过（上下文没变化）
```

`hasAlreadyFlushedForCurrentCompaction()` 确保同一个 compaction cycle 内不会重复 flush。

---

## 五、QMD 后端：独立进程架构

### 5.1 与 Builtin 的核心区别

| | Builtin 后端 | QMD 后端 |
|---|---|---|
| 进程模型 | 内嵌（Node.js 进程内） | 独立进程（spawn qmd CLI） |
| 索引存储 | SQLite（自定义 schema） | QMD 自己的索引格式 |
| 嵌入模型 | 通过 Provider 插件调用 API | QMD 内置的本地模型 |
| 混合检索 | BM25(FTS5) + 向量(sqlite-vec) | BM25(qmd search) + 向量(qmd vsearch) + 深度(qmd deep_search) |
| 搜索接口 | SQL 查询 | 子进程 stdout JSON 解析 |
| 会话索引 | 直接读 JSONL → chunk → embed | 导出 session 为 Markdown → qmd 管理 |

### 5.2 QMD 的配置结构

```javascript
// backend-config-Bw8hjn_C.js 中 resolveMemoryBackendConfig() 的输出
{
    backend: "qmd",
    citations: "auto" | "inline" | "none",
    qmd: {
        command: "qmd",                     // qmd CLI 路径
        mcporter: { enabled: false, ... },  // MCP 模式
        searchMode: "search" | "vsearch" | "query", // 检索模式
        collections: [                      // QMD collection 列表
            { name: "main-memory-root", path: "{workspace}", pattern: "MEMORY.md", kind: "memory" },
            { name: "main-memory-dir",  path: "{workspace}/memory", pattern: "**/*.md", kind: "memory" },
            { name: "main-custom-1",    path: "/extra/docs", pattern: "**/*.md", kind: "custom" },
        ],
        includeDefaultMemory: true,
        sessions: {
            enabled: false,                 // 是否索引会话
            exportDir: "...",              // 会话导出目录
            retentionDays: 30
        },
        update: {
            intervalMs: 300000,            // 每 5 分钟更新 BM25 索引
            debounceMs: 15000,             // 防抖 15 秒
            onBoot: true,                  // 启动时触发 update
            waitForBootSync: false,        // 是否等首次 update 完成
            embedIntervalMs: 3600000,      // 每 60 分钟重新生成向量
            commandTimeoutMs: 30000,       // 每个 qmd 命令 30 秒超时
            updateTimeoutMs: 120000,       // update 操作 2 分钟超时
            embedTimeoutMs: 120000         // embed 操作 2 分钟超时
        },
        limits: {
            maxResults: 6,
            maxSnippetChars: 700,
            maxInjectedChars: 4000,
            timeoutMs: 4000
        },
        scope: {                           // 安全范围控制
            default: "deny",               // 默认拒绝
            rules: [
                { action: "allow", match: { chatType: "direct" } }  // 只允许私聊
            ]
        }
    }
}
```

### 5.3 集合（Collection）管理

QMD 将不同的文件集组织为 collection：

```
默认 collections:
├── {agentId}-memory-root → workspace/MEMORY.md
├── {agentId}-memory-alt  → workspace/memory.md
└── {agentId}-memory-dir  → workspace/memory/**/*.md

自定义 collections:
└── {agentId}-custom-N → 用户配置的任意目录
```

集合命名规则：`{collectionBase}-{sanitizedName}`，sanitize 规则：只保留 `[a-z0-9-]`。

### 5.4 三阶段工作流

```
qmd update → qmd embed → qmd search/vsearch/deep_search

阶段 1: qmd update
  - 扫描配置的 collections 中所有 .md 文件
  - 更新 BM25 全文索引
  - 频率：每 5 分钟（默认）
  - 命令：qmd update --collection main-memory-root --collection main-memory-dir ...

阶段 2: qmd embed
  - 使用 QMD 内置的嵌入模型生成向量
  - 频率：每 60 分钟（默认，比 update 低很多）
  - 原因：向量嵌入比 BM25 索引费时得多
  - 命令：qmd embed --collection main-memory-root ...

阶段 3: qmd search/vsearch/deep_search
  - search → BM25 关键词检索
  - vsearch → 纯向量语义检索
  - deep_search → BM25 + 向量混合检索（取决于 searchMode 配置）
```

**查询命令路由**（qmd-manager-Bdi-TUef.js 第457行附近）：

```javascript
const qmdSearchCommand = this.qmd.searchMode;
const tool = qmdSearchCommand === "search" ? "search" :         // BM25 only
             qmdSearchCommand === "vsearch" ? "vector_search" :  // Vector only
             "deep_search";                                      // Hybrid
```

### 5.5 Mcporter：QMD 的 MCP 模式

Mcporter 将 QMD 包装为 MCP 服务：

```javascript
DEFAULT_QMD_MCPORTER = {
    enabled: false,           // 默认关闭
    serverName: "qmd",       // MCP server 名称
    startDaemon: true        // 自动启动守护进程
}
```

启用后，搜索不走 `qmd search` 子进程，而是通过 MCP 协议与 QMD 守护进程通信。

### 5.6 作用域控制（Scope）

QMD 后端有独立的 Scope 机制控制哪些聊天场景可以触发记忆检索：

```javascript
scope: {
    default: "deny",               // 默认拒绝所有
    rules: [
        { action: "allow", match: { chatType: "direct" } },          // 允许私聊
        { action: "allow", match: { channel: "general" } },           // 允许特定频道
        { action: "deny",  match: { keyPrefix: "agent:sub" } }       // 拒绝子 agent
    ]
}
```

Scope 解析发生在 `isQmdScopeAllowed()` 函数中（qmd-scope.ts），对每条检索请求的 sessionKey 做匹配。如果在 scope 中被拒绝，检索直接返回空结果（不报错），且会记录 "Scope denied" 日志。

### 5.7 会话导出到 QMD

QMD 后端可以选择将会话内容也纳入检索（`sessions.enabled = true`）：

```
会话 JSONL → buildSessionEntry() → Markdown 文本 → 写入 exportDir
    → qmd update → qmd embed → 可检索
```

这样 `memory_search` 就能同时检索到记忆文件和历史会话。

### 5.8 QMD 子进程管理

```typescript
// qmd-process.ts - runCliCommand()
function runCliCommand(params: {
    spawnInvocation: { command, argv, shell, windowsHide }
    env, cwd
    timeoutMs: number
    maxOutputChars: number
    commandSummary: string
}): Promise<{ stdout: string, stderr: string }>
```

关键特性：
- 通过 `spawn()` 启动子进程
- 持续收集 stdout/stderr（有字符上限保护，防止 OOM）
- 超时后 `SIGKILL` 强杀
- 返回前检查 exit code（非零 = 抛异常）

共享嵌入模型通过 **symlink** 实现：`symlinkSharedModels()` 把默认的 `~/.cache/qmd/models/` 软链接到 agent 专用的 `XDG_CACHE_HOME` 下，避免每个 agent 重复下载相同的嵌入模型。

### 5.9 QMD 检索结果的解析

```typescript
// qmd-query-parser.ts - parseQmdQueryJson()
function parseQmdQueryJson(stdout, stderr): QmdSearchResult[]
```

解析策略：
1. 检查是否为 "no results found"（支持多种格式）
2. 尝试直接 `JSON.parse(stdout)` → 期望获得数组
3. 如果 stdout 有噪音（前面有日志）→ `extractFirstJsonArray()` 手动解析括号匹配
4. 如果都失败 → 抛出详细错误（包含 stderr 摘要）

`extractFirstJsonArray()` 是一个手写的 JSON 数组提取器——从 `[` 开始，跟踪括号深度、字符串状态、转义字符，找到配对的 `]`。这样可以容忍 qmd 在 JSON 前面输出 warning 日志。

---

## 六、Bootstrap 注入：会话恢复时的上下文加载

每次会话启动时，运行时自动加载以下文件注入 LLM 上下文：

| 文件 | 路径 | 作用 | 加载条件 |
|------|------|------|---------|
| AGENTS.md | workspace/AGENTS.md | Agent 行为准则、工作流定义 | 所有会话 |
| SOUL.md | workspace/SOUL.md | Agent 的性格、伦理边界 | 所有会话 |
| USER.md | workspace/USER.md | 用户偏好、时区、上下文 | 所有会话 |
| IDENTITY.md | workspace/IDENTITY.md | Agent 的名字、emoji | 所有会话 |
| MEMORY.md | workspace/MEMORY.md | 长期关键事实 | **仅私聊** |
| memory/YYYY-MM-DD.md | workspace/memory/ | 今天的日常记录 | 所有会话 |
| memory/(昨天).md | workspace/memory/ | 昨天的日常记录 | 所有会话 |
| sessions/{id}.jsonl | ~/.openclaw/agents/{id}/sessions/ | 本次会话历史消息 | 每个会话独有的 |

**关键约束**：
- `MEMORY.md` 仅在私聊（direct message）加载，群聊中绝不注入（安全原因）
- 日常记忆文件只加载 "今天 + 昨天" 两天（再早的不注入，省 Token）
- Bootstrap 文件有 Token 预算截断，不是无限塞入

---

## 七、完整时序：一个问题从提出到回答的全链路

```
用户：在飞书/微信/Discord 上发了一条消息
    "上次分析的台积电 CoWoS 封装产能问题，结论是什么？"
        │
        ▼
┌────────────────────────────────────────────────────────────┐
│ 阶段 1: 会话恢复（运行时自动，无需 Agent 干预，< 100ms）      │
│                                                            │
│ a. 从 sessions/{id}.jsonl 恢复历史消息 → ChatMemory         │
│ b. 从 workspace 加载 SOUL.md + USER.md + IDENTITY.md         │
│ c. 如果是私聊 → 加载 MEMORY.md                               │
│ d. 加载 memory/05-19.md + memory/05-18.md                   │
│ e. 构建初始 LLM 上下文                                       │
│                                                            │
│ 此时 LLM 可看到的历史消息 + Bootstrap 文件 ≈ 10K-30K Token    │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ 阶段 2: Agent 推理决策                                       │
│                                                            │
│ LLM 分析用户问题：                                           │
│ - "台积电 CoWoS 封装产能" → 特定话题，不是闲聊                 │
│ - "上次分析" → 答案不在当前上下文中                           │
│ - bootstrap 文件中没有相关具体结论                            │
│                                                            │
│ → 决策：调用 memory_search("台积电 CoWoS 封装产能")           │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ 阶段 3: memory_search 执行（Builtin 后端）                   │
│                                                            │
│ a. MemoryIndexManager.search("台积电 CoWoS 封装产能")        │
│                                                            │
│ b. 检查索引是否为脏 → 如有必要触发后台 sync()                  │
│                                                            │
│ c. hasIndexedContent() → 确认有已索引内容                     │
│                                                            │
│ d. 并行两路检索：                                            │
│    ┌─ searchVector():                                      │
│    │  query → embedding API → [0.12, -0.34, ...]           │
│    │  → sqlite-vec ANN 搜索 → cosine 相似度排序              │
│    │  → 候选数量: maxResults × candidateMultiplier = 24 条  │
│    │                                                       │
│    └─ searchKeyword():                                     │
│       buildFtsQuery("台积电 CoWoS 封装产能")                  │
│       → chunks_fts MATCH '台积电 OR CoWoS OR 封装 OR 产能'   │
│       → BM25 排名 → textScore                               │
│       → 候选数量: 24 条                                      │
│                                                            │
│ e. mergeHybridResults():                                   │
│    - 加权融合: score = 0.7 × vectorScore + 0.3 × textScore  │
│    - 按 score 降序 → 取前 6 条                                │
│                                                            │
│ f. 返回 MemorySearchResult[]                                │
│    [{ path: "memory/2026-05-15.md",                         │
│       startLine: 42, endLine: 58,                           │
│       score: 0.94,                                          │
│       snippet: "台积电CoWoS封装产能在2026Q3...",              │
│       source: "memory" },                                   │
│     { path: "MEMORY.md",                                    │
│       startLine: 30, endLine: 35,                           │
│       score: 0.81,                                          │
│       snippet: "台积电先进封装仍是瓶颈...",                    │
│       source: "memory" }, ...]                              │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ 阶段 4: 渐进式披露（memory_get）                              │
│                                                            │
│ Agent 分析搜索结果:                                          │
│ - 第 1 条 (score 0.94) 的 snippet 只有 700 字符              │
│ - 片段中提到 "2026Q3 产能约 50K wpm" 但没有完整上下文          │
│                                                            │
│ → 调用 memory_get(                                          │
│     path: "memory/2026-05-15.md",                           │
│     from: 40,    // 从片段前面几行开始                         │
│     lines: 30    // 读 30 行                                 │
│   )                                                         │
│                                                            │
│ → readFile() → readMemoryFile()                             │
│   → 路径校验（在 workspace 或 extraPaths 白名单内）            │
│   → 只允许 .md 文件                                          │
│   → fileLines.slice(40-1, 40-1+30).join("\n")               │
│   → 返回完整段落文本                                         │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ 阶段 5: Agent 生成最终回答                                    │
│                                                            │
│ "上次我们在 5月15日 讨论了台积电 CoWoS 封装产能问题。            │
│  结论是 2026Q3 产能约 50K wpm（晶圆/月），但需求达 80K wpm，    │
│  缺口主要靠英伟达的长期协议锁定。H200 交付因此延期到..."         │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ 阶段 6: 写入（并行发生，用户无感知）                            │
│                                                            │
│ a. sessions/{id}.jsonl ← 运行时自动追加用户消息和 Agent 回复   │
│                                                            │
│ b. memory/05-19.md ← Agent 可能追加待办：                     │
│    "- 用户确认台积电 CoWoS 分析结论，下周需要向老板汇报"         │
│                                                            │
│ c. 文件变更 → 标记 dirty = true                              │
│    → debounce 1.5s → scheduleWatchSync()                   │
│    → syncMemoryFiles() → indexFile()                        │
│    → chunk + embed + store → 索引更新完成                    │
└────────────────────────────────────────────────────────────┘
```

---

## 八、会话记录到索引的完整链路

```
sessions/{id}.jsonl 在磁盘上
        │
        ▼
┌─ 运行时引擎 ─────────────────────────────────────────────┐
│ onSessionTranscriptUpdate 事件触发                        │
│ → updateSessionDelta(sessionId, newBytes, newMessages)   │
│ → 累计 deltaBytes / deltaMessages                         │
│ → 达到阈值 (100KB / 50条) → 标记 sessionsDirty = true     │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─ syncSessionFiles() ─────────────────────────────────────┐
│ buildSessionEntry(jsonlPath)                              │
│   ├─→ 逐行读取 JSONL                                      │
│   ├─→ 过滤: type === "message" && role in ["user","assistant"] │
│   ├─→ extractSessionText(message.content)                 │
│   │     ├─→ 纯文本: normalize → trim                      │
│   │     └─→ ContentBlock[]: 提取 type="text" 的部分        │
│   ├─→ redactSensitiveText() 对每条消息脱敏                 │
│   ├─→ 格式化为 "User: xxx\nAssistant: yyy"                 │
│   └─→ 构建 lineMap (展平文本行 → JSONL 行号)               │
│                                                            │
│ indexFile(sessionEntry, { source: "sessions" })            │
│   ├─→ chunkMarkdown(content, { tokens: 400, overlap: 80 }) │
│   ├─→ remapChunkLines(chunks, lineMap)                    │
│   └─→ embed + 写入 chunks 表 + chunks_fts 表               │
│   此时 source = "sessions" ← 区别于 "memory"              │
└──────────────────────────────────────────────────────────┘
```

**重要设计**：会话记录通过 `redactSensitiveText()` 脱敏后才索引。JSONL 原始文件保留完整信息，但检索索引中的文本是脱敏版本。

---

## 九、架构总结

OpenClaw 的记忆系统 = 五条设计原则：

1. **Markdown 文件是唯一 Source of Truth**
   - 所有记忆以 .md 文件存在磁盘上
   - 人类可读、可编辑、可版本控制（git）
   - SQLite 索引、sqlite-vec 向量、QMD 索引都是可重建的派生数据

2. **渐进式披露**
   - `memory_search` 返回片段 + 定位信息（按 Token 预算截断）
   - `memory_get` 按路径 + 行号精确回读原文
   - 不是把所有文件全文塞进上下文

3. **双轨写入机制**
   - 运行时自动追加：会话 JSONL（高频，无需 Agent 干预）
   - Agent 主动写入：memory/*.md + MEMORY.md（低频，语义驱动）

4. **Pre-compaction Memory Flush**
   - 上下文接近窗口限制时，强制 Agent 在压缩前把重要信息写入磁盘
   - 通过 contextHash 去重避免重复 flush
   - 同一个 compaction cycle 内只 flush 一次

5. **文件变更与索引解耦**
   - 写入文件 ≠ 立即可检索
   - 通过 chokidar 监听 + debounce(1.5s) + 脏标记 + 定时任务多机制保障新鲜度
   - 检索前检查 dirty → 必要时触发后台 sync → 等待完成后才返回结果

---

## 附：关键文件索引

| 源码文件 | 内容 |
|---------|------|
| `internal-Bnt1j4hp.js` | chunkMarkdown() 切块算法、buildFileEntry()、listMemoryFiles()、cosineSimilarity() |
| `memory-search-C7gfehPk.js` | 全部默认配置、mergeConfig()、resolveMemorySearchConfig() |
| `backend-config-Bw8hjn_C.js` | QMD 默认配置、readMemoryFile() 安全校验、resolveMemoryBackendConfig() |
| `memory-core-host-engine-storage-C3DUzgCl.js` | SQLite schema（files, chunks, embedding_cache, chunks_fts）、sqlite-vec 加载 |
| `memory-core-host-engine-qmd-C4i1wMqd.js` | buildSessionEntry()、parseQmdQueryJson()、runCliCommand() 子进程管理、QMD Scope 控制 |
| `qmd-manager-Bdi-TUef.js` | QmdMemoryManager 完整实现（search/sync/readFile/status/Collections/Embedding） |
| `memory-state-CKh9RZhV.js` | 全局 Plugin 状态：Memory Flush、Prompt Builder、Runtime |
| `manager.d.ts` | MemoryIndexManager 类的完整接口定义 |
| `manager-sync-ops.d.ts` | MemoryManagerSyncOps 抽象类：sync() 调度、文件监听、Schema 管理 |
| `manager-embedding-ops.d.ts` | MemoryManagerEmbeddingOps 抽象类：indexFile()、批量 Embedding、缓存管理 |
| `hybrid.d.ts` | mergeHybridResults() 接口：BM25+向量加权、MMR、时间衰减 |
| `mmr.d.ts` | MMR 算法：Jaccard 相似度、贪婪选择、λ 权衡参数 |
| `temporal-decay.d.ts` | 时间衰减：指数衰减模型、半衰期参数 |
| `types.d.ts` | MemorySearchManager 接口、MemorySearchResult 类型定义 |
| `memory-flush.d.ts` | Pre-compaction Memory Flush 条件判断、contextHash 去重 |
| `qmd-manager.d.ts` | QmdMemoryManager 类完整接口（150+ 方法） |
