# LyClaw 多Agent编排系统 — 全链路分析与实现优先级论证

> 创建日期: 2026-05-22
> 状态: 重新论证（前提变更：记忆系统将推倒重新设计）
> 参考: OpenClaw multi-agent orchestration, 06-renovation-phase2, 07-renovation-phase3, 08-renovation-phase4

---

## 变更记录

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v1.0 | 2026-05-22 | 初版：基于"实现现有25个记忆接口"的前提，结论为记忆系统#1 |
| v2.0 | 2026-05-22 | **重新论证**：记忆系统将参照OpenClaw重新设计+加入RAG知识库，现有25个接口文件大概率删除。前提变更导致依赖链完全重构 |

---

## 目录

1. [前提变更：为什么需要重新论证](#1-前提变更为什么需要重新论证)
2. [全景概览：四大子系统现状（重评估）](#2-全景概览四大子系统现状重评估)
3. [Agent CRUD 能力差距分析](#3-agent-crud-能力差距分析)
4. [子Agent编排就绪度评估](#4-子agent编排就绪度评估)
5. [记忆系统+知识库：从"实现"变为"重新设计"](#5-记忆系统知识库从实现变为重新设计)
6. [Phase 3 / Phase 4 与新记忆系统的关系](#6-phase-3--phase-4-与新记忆系统的关系)
7. [依赖链重分析：旧链断裂，新链形成](#7-依赖链重分析旧链断裂新链形成)
8. [重新论证的优先级排序](#8-重新论证的优先级排序)
9. [未来场景矩阵：六种场景的综合分析](#9-未来场景矩阵六种场景的综合分析)
10. [风险分析与替代方案](#10-风险分析与替代方案)
11. [数据库迁移方案](#11-数据库迁移方案)
12. [与OpenClaw的对比参考](#12-与openclaw的对比参考)
13. [总结与建议](#13-总结与建议)

---

## 1. 前提变更：为什么需要重新论证

### 1.1 旧前提 vs 新前提

```
┌─────────────────────────────────────────────────────────────────┐
│                     前提条件对比                                  │
├──────────────────────┬──────────────────────────────────────────┤
│  旧前提 (v1.0)       │  新前提 (v2.0)                            │
├──────────────────────┼──────────────────────────────────────────┤
│ 记忆系统：25个接口    │ 记忆系统：将删除现有代码，重新设计         │
│ 已设计完成，只需实现  │ 参照OpenClaw + 其他应用的设计模式          │
│                      │ 加入知识库(RAG)能力                        │
│                      │ 不再是四层架构(SENSORY/SHORT/LONG/ENTITY) │
├──────────────────────┼──────────────────────────────────────────┤
│ 工期：8天实现MVP      │ 工期：10~15天（设计+实现）                │
│ 确定性：高            │ 确定性：低（设计未开始）                   │
│ 阻塞面：Phase 3       │ 阻塞面：可能影响Phase 3（需重构）          │
├──────────────────────┼──────────────────────────────────────────┤
│ 优先级推理：          │ 优先级推理：                              │
│ 实现快 + 阻塞多 = #1  │ 设计慢 + 不确定 + 无实现 = 不应排第一      │
└──────────────────────┴──────────────────────────────────────────┘
```

### 1.2 前提变更的核心影响

**影响1：旧依赖链断裂**

v1.0中，整条依赖链的根节点是"实现MemorySystem"。但如果25个接口文件被删除，这个根节点就不存在了。Phase 3的CompactionEngine硬依赖的`MemorySystem`接口也将消失，Phase 3需要重构。

**影响2：记忆系统从"快赢"变为"深水区"**

v1.0中，记忆系统是"8天实现MVP"的快赢任务。现在变为"研究OpenClaw→设计新架构→实现RAG→集成知识库"的深水区任务，周期10~15天且充满不确定性。

**影响3：出现了"无记忆依赖"的操作窗口**

在记忆系统重新设计期间，Agent CRUD和编排雏形完全不依赖它。这意味着我们可以在这2~3周内交付可用的多Agent系统（手动委派），同时并行设计新记忆系统。

**影响4：Phase 3/4不再是"等记忆系统"的关系**

Phase 3的CompactionEngine需要重构以适配新记忆系统。BootstrapLoader和AgentRouter不依赖记忆系统，可以先做。

### 1.3 新前提下的核心问题

> 在记忆系统需要重新设计（周期长、不确定）的前提下，如何排序才能：
> 1. 尽快交付可见价值（多Agent可用）？
> 2. 不给未来记忆系统设计制造障碍？
> 3. 让Phase 3/4不阻塞在"等记忆系统"上？

---

## 2. 全景概览：四大子系统现状（重评估）

### 2.1 系统全景图（修订版）

```
┌─────────────────────────────────────────────────────────────────┐
│                        LyClaw Platform                          │
├───────────────┬───────────────┬───────────────┬─────────────────┤
│   记忆系统     │  Agent CRUD   │  子Agent编排   │  Phase 3/4     │
│  (待重设计)   │  (Persistence)│ (Orchestration)│ (Bootstrap/     │
│               │               │               │  Streaming)     │
├───────────────┼───────────────┼───────────────┼─────────────────┤
│ 25接口→将删除 │ agents表24列  │ 代码完成度95% │ CompactionEngine│
│ 四层架构→废弃 │ 缺11字段      │ 数据层阻塞    │ 依赖旧MemorySys │
│ 参照OpenClaw  │ 无CRUD API   │ 仅1个@Agent   │ 需重构适配     │
│ 加入RAG知识库 │ 无前端界面    │ ToolProvider✅ │ BootstrapLoader│
│ 需10~15天设计 │              │ SubagentSpawner│ 可独立先行     │
├───────────────┼───────────────┼───────────────┼─────────────────┤
│ 状态: 待重设计│ 状态: 半成品  │ 状态: 代码完成│ 状态: 设计完成  │
│ 就绪度: 0%   │ 就绪度: 67%  │ 就绪度: 95%   │ 就绪度: 5%     │
│ 确定性: 低   │ 确定性: 高   │ 确定性: 高    │ 确定性: 需重估  │
└───────────────┴───────────────┴───────────────┴─────────────────┘
```

### 2.2 各子系统就绪度矩阵（修订版）

| 子系统 | 接口/设计 | 实现 | 确定性 | 综合就绪度 | 备注 |
|--------|----------|------|--------|-----------|------|
| 记忆系统(旧) | 100% (25文件) | 0% | 将废弃 | **0%** | 代码将删除 |
| 记忆系统(新) | 0% | 0% | 低 | **0%** | 从零开始设计 |
| Agent CRUD | 67% (24/35字段) | 60% | 高 | **40%** | 需求明确 |
| 子Agent编排 | 100% | 95% | 高 | **70%** | 仅缺per-agent控制 |
| Phase 3 | 100% (设计) | 0% | 中 | **5%** | 需等新记忆或重构 |
| Phase 4 | 100% (设计) | 0% | 中 | **10%** | 依赖Phase 3 |

### 2.3 四条腿的桌子：新比喻

```
旧比喻（v1.0）:
  桌腿1 (记忆系统): 设计图纸有，实物没有 → 需要赶紧造
  桌腿2 (Agent CRUD): 太短 → 需要加长
  桌腿3 (编排引擎): 做好了没地方放 → 没有Agent可编排
  桌腿4 (Phase 3/4): 需要腿1和2 → 排在最后

新比喻（v2.0）:
  桌腿1 (记忆系统): 图纸要重画，材料要重选 → 急不来，先放一放
  桌腿2 (Agent CRUD): 短腿 → 先加长，这是最快的
  桌腿3 (编排引擎): 腿2加长后，立刻就能用上 → 紧接腿2之后
  桌腿4 (Phase 3/4): 桌腿1先要重画 → 等腿1新设计出来再适配

关键差异:
  旧结论: 先造腿1 (因为图纸现成，造得快)
  新结论: 先加长腿2，装腿3 (因为腿1要重新画图纸，急不来)
```

---

## 3. Agent CRUD 能力差距分析

### 3.1 为什么Agent CRUD现在是#1优先级

**论证1：唯一无依赖、确定性高的任务**

Agent CRUD不依赖记忆系统（无论新旧），不依赖Phase 3/4，不依赖编排引擎。它是整个系统中最独立、最确定的模块。在记忆系统充满不确定性的情况下，Agent CRUD是唯一可以"闭着眼睛做"的任务。

**论证2：最短路径到可见价值**

```
Agent CRUD完成后 → 用户可以：
  1. 在侧栏看到Agent列表（不只是chat一个）
  2. 创建代码审查Agent、文档写手Agent、测试生成器Agent...
  3. 为每个Agent配置不同的模型、系统提示词、工具集
  4. 在不同Agent之间切换对话

这直接让系统从"单一聊天机器人"升级为"多Agent平台"
```

**论证3：解锁编排引擎的前置数据**

编排引擎代码完成度95%，唯一缺的是"有多个Agent可以委派"。Agent CRUD提供这些Agent后，编排引擎立刻从"空转"变为"可用"。

**论证4：前端改动的连带价值**

Agent CRUD的前端部分（AgentSelector增强、AgentEditor表单、AgentDetail展示）同时为后续的记忆系统配置界面、知识库管理界面提供了UI框架和交互模式参考。

### 3.2 当前状态

**已有能力**：
- `ChatAgent.java` — 唯一的 `@Agent` 注解接口，硬编码定义
- `AgentProxyFactory` — JDK动态代理工厂，从注解创建Agent代理
- `AgentRegistry` — 内存中的Agent注册表
- `agents` SQLite表 — 24列，支持基本持久化

**缺失能力**：
1. **11个数据库字段缺失** — 最关键的 `delegation_mode`、`extensions`、`allow_agents` 不在表中
2. **无CRUD REST API** — 无法通过API创建/更新/删除Agent
3. **无前端界面** — 用户无法可视化管理Agent
4. **无动态注册机制** — Agent只能在Spring启动时通过注解注册

### 3.3 数据库字段差距详细对比

当前 `agents` 表结构（24列）：

```sql
CREATE TABLE IF NOT EXISTS agents (
    id TEXT PRIMARY KEY,              -- ✅
    name TEXT NOT NULL,               -- ✅
    description TEXT DEFAULT '',      -- ✅
    system_prompt TEXT,               -- ✅
    model TEXT DEFAULT 'gpt-4o',      -- ✅
    provider TEXT DEFAULT 'openai',   -- ✅
    temperature REAL DEFAULT 0.7,     -- ✅
    max_tokens INTEGER DEFAULT 4096,  -- ✅
    top_p REAL DEFAULT 1.0,           -- ✅
    frequency_penalty REAL DEFAULT 0, -- ✅
    presence_penalty REAL DEFAULT 0,  -- ✅
    stop_sequences TEXT,              -- ✅
    tools_enabled TEXT DEFAULT '[]',  -- ✅
    skills_enabled TEXT DEFAULT '[]', -- ✅
    thinking_enabled INTEGER DEFAULT 0,   -- ✅
    thinking_budget INTEGER DEFAULT 0,    -- ✅
    verbose_enabled INTEGER DEFAULT 0,    -- ✅
    reasoning_enabled INTEGER DEFAULT 0,  -- ✅
    fast_mode INTEGER DEFAULT 0,          -- ✅
    created_at TEXT DEFAULT (datetime('now')), -- ✅
    updated_at TEXT DEFAULT (datetime('now')), -- ✅
    is_active INTEGER DEFAULT 1,      -- ✅
    metadata TEXT DEFAULT '{}',       -- ✅
    icon TEXT DEFAULT 'bot',          -- ✅
    color TEXT DEFAULT '#6C63FF'      -- ✅
);
```

缺失的11个字段（与 `ResolvedAgentConfig` 和 `@Agent` 注解对照）：

| # | 缺失字段 | 对应注解属性 | 类型 | 重要性 | 原因 |
|---|---------|-------------|------|--------|------|
| 1 | `delegation_mode` | `@Agent.delegationMode()` | TEXT | **极高** | 控制子Agent委派行为：none/suggest/auto |
| 2 | `extensions` | `@Agent.extensions()` | TEXT(JSON) | **极高** | Agent扩展配置的核心载体 |
| 3 | `allow_agents` | `@Agent.allowAgents()` | TEXT(JSON) | **极高** | 允许委派的目标Agent白名单 |
| 4 | `version` | `@Agent.version()` | TEXT | 高 | Agent版本管理 |
| 5 | `default_agent` | `@Agent.defaultAgent()` | INTEGER | 中 | 标记默认Agent |
| 6 | `workspace` | `@Agent.workspace()` | TEXT | 中 | 工作目录隔离 |
| 7 | `agent_dir` | `@Agent.agentDir()` | TEXT | 中 | Agent配置目录 |
| 8 | `fallbacks` | `@Agent.fallbacks()` | TEXT(JSON) | 中 | 模型降级链 |
| 9 | `context_tokens` | `@Agent.contextTokens()` | INTEGER | 中 | 上下文窗口大小 |
| 10 | `context_injection` | `@Agent.contextInjection()` | TEXT | 低 | 上下文注入模板 |
| 11 | `bootstrap_max_chars` | `@Agent.bootstrapMaxChars()` | INTEGER | 低 | Bootstrap文件大小限制 |
| 12 | `bootstrap_total_max_chars` | `@Agent.bootstrapTotalMaxChars()` | INTEGER | 低 | Bootstrap总大小限制 |

**注意**：相比v1.0分析，新增了`allow_agents`字段（之前遗漏），实际缺失12个字段。

### 3.4 delegation_mode 的关键作用

```
delegation_mode 三态语义：

"none"     → 该Agent不具备委派能力，delegate_to_agent工具不可见
             适用于：简单任务Agent、安全敏感Agent、终端执行Agent

"suggest"  → 该Agent可以建议委派，但最终由用户/父Agent决定
             适用于：通用助手Agent、需要人工审核的Agent
             此为默认值

"auto"     → 该Agent可以自主决定委派，无需人工干预
             适用于：编排器Agent、自动工作流Agent
             需要配合 allowAgents 白名单使用
```

**当前代码中的断点**：`AgentInvocationHandler.invoke()` 第205-208行TODO：
```java
// TODO: Wire delegate_to_agent via DelegateToAgentToolProvider
// Should check resolvedConfig.getDelegationMode() != "none" 
// && !resolvedConfig.getAllowAgents().isEmpty()
```

目前 `delegate_to_agent` 工具使用全局 `isSubagentEnabled()` 开关控制，所有Agent要么全能看到委派工具，要么全都看不到 — 粒度太粗。

### 3.5 extensions 的关键作用

`extensions` 是 `Map<String, String>` 类型，是Agent元数据的核心载体：

```
extensions 典型使用场景：

1. Agent类型标记:
   extensions: { "agent_type": "code_reviewer", "language": "java" }

2. 子Agent生命周期:
   extensions: { "lifecycle": "temporary", "ttl_seconds": "300" }

3. 子Agent资源限制:
   extensions: { "max_tokens": "16384", "max_tool_calls": "20" }

4. 子Agent沙箱配置:
   extensions: { "sandbox": "docker", "image": "java:21" }

5. 未来：记忆策略（等新记忆系统设计完成后启用）:
   extensions: { "memory_mode": "inherit", "knowledge_base": "project-docs" }
```

### 3.6 Agent CRUD 实现工作量估算

```
任务分解:
├─ 数据库迁移 (agents表新增12列)         ─ 0.5天
├─ AgentRepository (完整CRUD)            ─ 1天
├─ AgentService (业务逻辑层)              ─ 1天
├─ AgentController (REST API)            ─ 0.5天
│  ├─ POST   /api/agents                 ─ 创建Agent
│  ├─ GET    /api/agents                 ─ 获取Agent列表
│  ├─ GET    /api/agents/{id}            ─ 获取Agent详情
│  ├─ PUT    /api/agents/{id}            ─ 更新Agent
│  ├─ DELETE /api/agents/{id}            ─ 删除Agent
│  └─ PATCH  /api/agents/{id}/activate   ─ 激活/停用Agent
├─ 动态Agent注册机制                      ─ 1天
│  └─ DynamicAgentRegistry: 启动时从DB加载→通过AgentProxyFactory创建代理→注册
├─ 前端Agent管理界面                      ─ 2天
│  ├─ AgentSelector增强 (侧栏Agent列表+切换)
│  ├─ AgentEditor (创建/编辑表单，含全部字段)
│  ├─ AgentDetail (详情展示)
│  └─ AgentDeleteConfirm (删除确认)
├─ 前后端联调                             ─ 0.5天
└─ 测试                                   ─ 0.5天

总计: 约 7 个工作日
```

---

## 4. 子Agent编排就绪度评估

### 4.1 代码层面：95%完成

当前编排系统的代码基础设施：

```
DelegateToAgentToolProvider (400行)         — 整体完成度: 90%
├─ provideTools()          ✅ LLM可见的tool定义（JSON Schema完整）
├─ execute()               ✅ 解析参数→委派→阻塞等待→返回结果
├─ getToolSchema()         ✅ JSON Schema定义
└─ per-agent可见性控制     ❌ TODO未实现（当前使用全局开关）

SubagentSpawner                            — 整体完成度: 95%
├─ spawnSubagent()         ✅ 核心委派逻辑：验证→构建上下文→创建会话→执行→收集结果
├─ buildChildContext()     ✅ 子Agent上下文构建（隔离的AgentContext）
├─ resolveSubagentConfig() ✅ 从extensions解析子Agent配置
├─ validateSubagent()      ✅ 参数验证（agentId存在性、深度限制）
└─ 委派链监控              ⚠️ maxSpawnDepth/maxChildrenPerAgent已定义但未严格执行

AgentInvocationHandler                     — 整体完成度: 90%
├─ invoke()                ✅ JDK动态代理方法拦截
├─ buildChatRequest()      ✅ 使用resolvedConfig构建ChatRequest
├─ 5 Stage管线             ✅ 完整的ReactivePipelineStage链
└─ per-agent delegation    ❌ TODO未实现（第205-208行）
```

### 4.2 数据层面：Agent CRUD完成后即可用

```
Agent CRUD完成前（当前状态）:
  ┌──────┬──────────────────────────────────┬──────────┐
  │ ID   │ 名称                             │ 可委派   │
  ├──────┼──────────────────────────────────┼──────────┤
  │ chat │ 通用聊天助手，具备工具调用能力    │ ❓       │
  └──────┴──────────────────────────────────┴──────────┘
  问题: chat委派给chat = 自己委派自己 = 递归风险

Agent CRUD完成后（预期状态）:
  ┌──────────────────┬──────────────────────┬──────────┬──────────────┐
  │ ID               │ 名称                  │ 可委派   │ 委派模式      │
  ├──────────────────┼──────────────────────┼──────────┼──────────────┤
  │ chat             │ 通用聊天助手          │ ✅       │ suggest      │
  │ code_reviewer    │ Java代码审查员        │ ✅       │ auto         │
  │ test_writer      │ 单元测试生成器        │ ✅       │ auto         │
  │ web_researcher   │ 网络搜索研究员        │ ✅       │ auto         │
  │ doc_writer       │ 技术文档写手          │ ✅       │ auto         │
  │ orchestrator     │ 任务编排器            │ ✅       │ auto         │
  └──────────────────┴──────────────────────┴──────────┴──────────────┘
  
  委派场景:
    orchestrator: "分析这个PR并生成测试"
      → delegate_to_agent(target="code_reviewer", task="审查代码变更")
      → delegate_to_agent(target="test_writer", task="生成单元测试")
      → delegate_to_agent(target="doc_writer", task="更新API文档")
      → 合成最终报告
```

### 4.3 编排雏形的实现内容

编排雏形（Agent CRUD完成后执行）分两个层次：

**层次1：手动委派可用（最小可行）— 2天**

```
任务:
├─ 修复 AgentInvocationHandler TODO       ─ 1天
│  └─ 读取resolvedConfig.getDelegationMode()和getAllowAgents()
│     → 决定delegate_to_agent工具是否对该Agent可见
│
├─ DelegateToAgentToolProvider增强        ─ 0.5天
│  └─ 从数据库动态获取可委派Agent列表，替换硬编码的SubagentConfig.defaults()
│
└─ 委派链循环检测                          ─ 0.5天
   └─ spawnSubagent时检查 targetAgentId != parentAgentId
      检查委派深度不超过maxSpawnDepth
```

**层次2：自动委派+Agent生成（进阶）— 4天**

```
任务:
├─ AgentRouter雏形                         ─ 1天
│  └─ 基于extensions的Agent类型匹配
│     输入: taskDescription
│     输出: 最匹配的Agent ID (或null表示无匹配)
│     匹配逻辑: extensions.agent_type + description关键词
│
├─ TemplateAgentFactory                    ─ 1.5天
│  └─ 根据任务类型生成Agent配置模板
│     模板来源: 预定义模板 + 从已有Agent学习
│     输出: 可直接用于Agent CRUD的Agent配置
│
├─ TemporaryAgentLifecycle                 ─ 1天
│  └─ 创建→执行→提取结果→销毁
│     销毁前将执行结果摘要存入会话上下文
│     （不依赖旧记忆系统，直接存入当前会话的metadata）
│
└─ 委派链监控完善                           ─ 0.5天
   └─ 严格执行maxSpawnDepth和maxChildrenPerAgent
      添加委派超时和中断机制
```

**编排雏形总工期：约6个工作日**（可在Agent CRUD完成后立即开始）

### 4.4 编排雏形与记忆系统的关系（关键洞察）

```
编排雏形不依赖记忆系统的原因：

1. 上下文传递不依赖记忆系统：
   当前 SubagentSpawner.buildChildContext() 通过以下方式传递上下文：
   - 父Agent的task描述 → 子Agent的userMessage
   - 父Agent的sessionId → 子Agent的parentSessionKey
   - 父Agent的resolvedConfig → 子Agent的配置参考
   
   这些都不需要记忆系统参与。

2. 委派结果不依赖记忆系统持久化：
   子Agent执行结果直接返回给父Agent，父Agent将其作为工具调用结果
   整合到当前会话中。会话本身已有JSONL持久化（SessionPersistenceHook）。

3. 临时Agent的"知识抢救"可以用简化方案：
   在记忆系统未完成前，临时Agent的执行摘要存入：
   - 当前会话的metadata（JSONL中）
   - Agent的extensions.metadata字段（SQLite中）
   这些不依赖记忆系统接口。

4. 记忆系统完成后，编排雏形可以无缝升级：
   - 上下文传递 → 加入MemoryRetriever检索相关记忆
   - 委派结果 → 通过MemorySystem.commitLongTerm持久化
   - 临时Agent知识抢救 → 正式走记忆系统流程
```

---

## 5. 记忆系统+知识库：从"实现"变为"重新设计"

### 5.1 旧记忆系统的问题

当前25个接口文件定义的四层架构（SENSORY→SHORT_TERM→LONG_TERM→ENTITY）存在以下问题：

```
问题1：过度设计
  四层记忆对于当前阶段过于复杂。在只有1个Agent、没有RAG的情况下，
  SENSORY层（感知队列）和ENTITY层（知识图谱）几乎没有使用场景。

问题2：与OpenClaw的实际做法偏离
  OpenClaw的"记忆"本质是：
  - CLAUDE.md 文件注入（静态系统提示词）→ 相当于"长期指令"
  - 文件树上下文（项目感知）→ 相当于"环境感知"
  - 会话JSONL持久化（对话历史）→ 相当于"短期记忆"
  - 子Agent的CLAUDE.md独立配置 → 相当于"Agent个性记忆"
  
  OpenClaw没有SENSORY队列、没有consolidation流程、没有MemoryJanitor。
  它用的是更简单、更工程化的方式。

问题3：缺乏知识库(RAG)能力
  旧设计有EmbeddingModel接口但没有任何RAG相关设计：
  - 没有文档摄入管道（document ingestion pipeline）
  - 没有分块策略（chunking strategy）
  - 没有向量数据库集成（只有SQLite BLOB存embedding）
  - 没有检索增强生成的具体流程

问题4：与Agent系统的耦合方式不清晰
  MemorySystem作为CompactionEngine的构造函数参数是硬编码的。
  但记忆系统应该是可插拔的——不同Agent可能需要不同的记忆策略。
```

### 5.2 新记忆系统的设计方向（参照OpenClaw）

```
新记忆系统的核心思想：分层可插拔，从简单到复杂逐步演进

第一层：会话记忆（Session Memory）— 已有
  ├─ 职责: 当前会话的对话历史
  ├─ 存储: JSONL文件（SessionPersistenceHook已实现）
  ├─ 检索: 时间顺序读取（已有）
  └─ 状态: ✅ 已完成

第二层：指令记忆（Instruction Memory）— 类似CLAUDE.md
  ├─ 职责: Agent的持久化系统提示词、行为准则、领域知识
  ├─ 存储: agents表的system_prompt字段 + 独立instruction_files表
  ├─ 注入: 每次对话开始时注入System Prompt
  └─ 状态: ⚠️ 部分完成（system_prompt已有，但无文件级指令管理）

第三层：知识库（Knowledge Base / RAG）— 新增
  ├─ 职责: 文档集合的语义检索
  ├─ 存储: 向量数据库（初期可用SQLite+本地embedding，后期可换ChromaDB/Milvus）
  ├─ 流程: 文档摄入→分块→嵌入→索引→检索→重排序→注入上下文
  └─ 状态: ❌ 全新设计

第四层：Agent个性记忆（Agent Persona Memory）— 类似子Agent的CLAUDE.md
  ├─ 职责: 每个Agent的独立长期记忆空间
  ├─ 存储: agent_memories表（agent_id + key + value + embedding）
  ├─ 检索: 按agent_id过滤 + 语义相似度排序
  └─ 状态: ❌ 全新设计
```

### 5.3 新记忆系统与Agent CRUD、编排雏形的关系

```
关键洞察：前三层可以独立演进，不互相阻塞

会话记忆 ────────────────────────────── ✅ 已完成，无需改动
    │
指令记忆 ─── 依赖Agent CRUD ──────────  Agent CRUD完成后自然获得
    │         (CRUD提供了agent配置管理)   (system_prompt字段已在agents表中)
    │
知识库 ──── 独立子系统 ──────────────── 可与Agent CRUD并行设计
    │         (文档摄入/向量检索)         不阻塞编排雏形
    │
个性记忆 ── 依赖编排雏形+知识库 ─────── 最后实现
              (需要多Agent运行后才知道    (需要RAG基础设施)
               每个Agent需要记什么)
```

### 5.4 新记忆系统设计工作量估算

```
任务分解:
├─ 阶段1: 研究+设计 (3~4天)
│  ├─ OpenClaw memory机制深入研究        ─ 1天
│  ├─ RAG系统设计（分块/嵌入/检索/重排序）─ 1天
│  ├─ 接口定义（新MemorySystem接口）     ─ 1天
│  └─ 数据库设计（知识库表+Agent记忆表）  ─ 1天
│
├─ 阶段2: 知识库核心实现 (4~5天)
│  ├─ 文档摄入管道（DocumentIngestionPipeline）─ 1天
│  ├─ 分块策略（ChunkingStrategy）              ─ 0.5天
│  ├─ 嵌入生成（EmbeddingService）              ─ 1天
│  ├─ 向量存储（VectorStore — SQLite初期方案） ─ 1天
│  ├─ 检索+重排序（RetrievalService）           ─ 1天
│  └─ 上下文注入（注入到System Prompt）         ─ 0.5天
│
├─ 阶段3: Agent个性记忆 (2~3天)
│  ├─ agent_memories表设计与迁移         ─ 0.5天
│  ├─ AgentMemoryService（CRUD+检索）    ─ 1天
│  ├─ 与编排雏形集成（子Agent继承父记忆）─ 1天
│  └─ Spring自动配置                     ─ 0.5天
│
├─ 阶段4: 清理旧代码 (1天)
│  ├─ 删除25个旧memory接口文件           ─ 0.5天
│  └─ 清理相关import和配置引用           ─ 0.5天
│
└─ 阶段5: 测试+文档 (2天)
   ├─ 知识库检索准确性测试               ─ 1天
   └─ Agent记忆集成测试                  ─ 1天

总计: 约 12~15 个工作日（新记忆系统完整版）
其中核心知识库MVP（阶段1+2）约 7~9 天
```

### 5.5 为什么新记忆系统不能排第一

```
论证1：设计不确定性高
  旧记忆系统：接口已定义，只需写实现 → 确定性高，适合排第一快速完成
  新记忆系统：需要研究OpenClaw + RAG模式 → 不确定性高，排第一会阻塞一切

论证2：2~3周内无可见交付
  如果先做新记忆系统，前2~3周用户看到的是：
  - Week 1-2: 代码被删了（旧25个文件删除）
  - Week 2-3: 新代码还没法用（RAG管道刚调通）
  → 用户体验：系统退步了
  
  如果先做Agent CRUD：
  - Week 1: 用户可以在前端创建Agent了
  - Week 2: 多Agent委派可用了
  → 用户体验：系统进步了

论证3：Agent CRUD + 编排雏形 完成后，对记忆系统的需求更明确
  有了多个Agent实际运行后，才能知道：
  - 哪些知识需要RAG检索？（项目文档？API文档？编码规范？）
  - Agent之间需要共享什么记忆？（编码偏好？常用模式？）
  - 每个Agent需要什么粒度的个性记忆？（全局？按项目？按用户？）
  
  这些问题的答案直接指导新记忆系统的设计。先做CRUD+编排 = 先获取需求信息。

论证4：不阻塞Phase 3（可通过重构解决）
  Phase 3的CompactionEngine硬依赖旧MemorySystem接口。
  既然旧接口要被删除，Phase 3无论如何都需要重构。
  那么是否先做记忆系统对Phase 3的影响是一样的——都需要重构。
  
  反而，有了Agent CRUD + 编排雏形后，Phase 3重构时能更好地理解上下文注入的需求。
```

---

## 6. Phase 3 / Phase 4 与新记忆系统的关系

### 6.1 Phase 3 各组件依赖分析

```
Phase 3 组件清单                 依赖旧MemorySystem?      新方案
─────────────────────────────────────────────────────────────────
CompactionEngine                 ✅ 硬依赖(构造函数)      需重构适配新记忆
BootstrapLoader                  ❌ 不依赖               可独立先行
AgentRouter                      ❌ 不依赖               可独立先行
IdentityConfig                   ❌ 不依赖               可独立先行
ContextInjector                  ❌ 不依赖               可独立先行
CompactionStrategy               ❌ 不依赖               可独立先行
BootstrapConfig                  ❌ 不依赖               可独立先行
```

**关键发现**：Phase 3的7个核心组件中，只有CompactionEngine依赖MemorySystem。其余6个组件不依赖。

### 6.2 Phase 3 拆分策略

```
Phase 3 可以拆分为两个子阶段：

Phase 3a: 无记忆依赖组件（可在Agent CRUD之后立即开始）
  ├─ BootstrapLoader          — 项目文件上下文加载
  ├─ AgentRouter              — Agent类型路由（基于extensions）
  ├─ IdentityConfig           — Agent身份配置
  ├─ ContextInjector          — 上下文注入管道
  ├─ BootstrapConfig          — Bootstrap配置模型
  └─ CompactionStrategy       — 压缩策略接口（先做简单截断策略）
  工期: ~4天
  
  不依赖记忆系统。上下文注入使用：
  - 静态Bootstrap文件（类似CLAUDE.md）
  - Agent的system_prompt
  - 当前会话的对话历史（JSONL）

Phase 3b: CompactionEngine（在新记忆系统完成后）
  ├─ CompactionEngine         — 上下文压缩（使用新记忆系统持久化关键事实）
  └─ 与知识库集成              — 压缩时提取的事实自动进入RAG知识库
  工期: ~3天
  
  依赖新记忆系统的MemorySystem接口（新设计版本）
```

### 6.3 Phase 4 依赖分析

```
Phase 4 组件                     依赖关系                  可开始时机
─────────────────────────────────────────────────────────────────
BlockStreamingController         依赖Phase 3的ContextInjector   Phase 3a后
SandboxExecutionService          独立（沙箱不依赖记忆）         随时可做
HeartbeatScheduler               独立                           随时可做
RunRetryManager                  独立                           随时可做
StreamingSessionManager          依赖Phase 3的ContextInjector   Phase 3a后
SandboxProvider                  独立                           随时可做
```

### 6.4 Phase 3/4 实现工作量估算（修订版）

```
Phase 3a: 无记忆依赖组件                    ─ 4天
├─ BootstrapLoader                          ─ 1天
├─ AgentRouter完整版                        ─ 1天
├─ IdentityConfig                           ─ 0.5天
├─ ContextInjector                          ─ 0.5天
├─ CompactionStrategy (简单截断策略)         ─ 0.5天
└─ 自动配置                                 ─ 0.5天

Phase 3b: CompactionEngine (新记忆完成后)   ─ 3天
├─ CompactionEngine (使用新MemorySystem)    ─ 1.5天
├─ 知识库集成（compaction→RAG ingestion）   ─ 1天
└─ 测试                                     ─ 0.5天

Phase 4: Streaming Gateway                  ─ 7天
├─ BlockStreamingController                 ─ 1天
├─ SandboxExecutionService                  ─ 1.5天
├─ HeartbeatScheduler                       ─ 0.5天
├─ RunRetryManager                          ─ 1天
├─ StreamingSessionManager                  ─ 1天
├─ SandboxProvider                          ─ 1天
└─ 测试                                     ─ 1天
```

---

## 7. 依赖链重分析：旧链断裂，新链形成

### 7.1 旧依赖链（v1.0）— 已失效

```
旧链（假设: 实现现有MemorySystem接口）:
  MemorySystem实现 → CompactionEngine → Phase 3 → Phase 4
                   → Agent CRUD → 编排雏形 → Agent自动生成

旧链失效原因:
  MemorySystem将删除 → 旧接口不存在 → CompactionEngine无法编译
  → 整条链断裂 → 需要重构
```

### 7.2 新依赖链（v2.0）

```
新链（前提: 记忆系统重新设计，旧接口删除）:

  ┌─────────────────────────────────────────────────────────────┐
  │                      独立分支（可并行）                       │
  │                                                             │
  │  Agent CRUD (7天)                                           │
  │      │                                                      │
  │      └─→ 编排雏形 (6天)                                     │
  │              │                                              │
  │              └─→ Phase 3a (4天) ──→ Phase 4 (7天)          │
  │                      │                                      │
  │                      │ (Phase 3a不依赖记忆系统)              │
  │                      │                                      │
  │  新记忆系统+知识库 (12~15天) ──→ Phase 3b (3天)             │
  │      │                              │                       │
  │      └──────────────────────────────┘                       │
  │          (Phase 3b需要新记忆系统)                            │
  └─────────────────────────────────────────────────────────────┘

关键变化:
  1. Agent CRUD不再是"与记忆系统并行"的分支，而是整条链的新根节点
  2. 编排雏形紧随CRUD之后，两者形成"快速交付线"
  3. 新记忆系统独立成线，与"快速交付线"并行
  4. Phase 3拆分为3a（无记忆依赖）+ 3b（有新记忆后），3a可提前
```

### 7.3 硬依赖 vs 软依赖（修订版）

```
硬依赖（编译/运行时强制）:
  Agent CRUD ──→ 无硬依赖（完全独立）
  编排雏形   ──→ 硬依赖Agent CRUD（需要数据库中的Agent列表）
  Phase 3a  ──→ 软依赖编排雏形（有AgentRouter雏形后更好，但不强制）
  Phase 3b  ──→ 硬依赖新记忆系统（CompactionEngine需要新MemorySystem接口）
  Phase 4    ──→ 硬依赖Phase 3a（需要ContextInjector）
  新记忆系统 ──→ 无硬依赖（独立设计）

软依赖（功能完整性）:
  编排雏形   → 新记忆系统（委派上下文更丰富）
  Agent自动生成 → 新记忆系统（知识持久化）
  Phase 3a   → Agent CRUD（Bootstrap文件按Agent类型选择）
  Phase 4    → Agent CRUD（流式会话需要Agent配置）
```

### 7.4 依赖链总结

```
新的实现顺序:

  Agent CRUD (新根节点)
      │
      ├─→ 编排雏形 (紧接其后，快速交付)
      │       │
      │       ├─→ Phase 3a (Bootstrap/AgentRouter/ContextInjector)
      │       │       │
      │       │       └─→ Phase 4 (Streaming/Sandbox)
      │       │
      │       └─→ Agent自动生成 (编排进阶)
      │
      └─→ [并行] 新记忆系统+知识库 设计+实现
              │
              └─→ Phase 3b (CompactionEngine，使用新记忆系统)
```

---

## 8. 重新论证的优先级排序

### 8.1 最终优先级排序

```
┌──────────────────────────────────────────────────────────────────┐
│              最终优先级排序（5个Phase，修订版v2.0）                │
├──────┬─────────────────────┬────────┬────────────────────────────┤
│ 优先级│ Phase               │ 工期    │ 核心交付物                  │
├──────┼─────────────────────┼────────┼────────────────────────────┤
│  #1  │ B: Agent CRUD       │ 7天    │ agents表扩展（+12列）       │
│      │                     │        │ CRUD REST API               │
│      │                     │        │ 前端Agent管理界面            │
│      │                     │        │ 动态Agent注册                │
│      │                     │        │ delegation_mode/extensions  │
│      │                     │        │ 持久化支持                  │
├──────┼─────────────────────┼────────┼────────────────────────────┤
│  #2  │ C: 子Agent编排雏形   │ 6天    │ per-agent delegation控制    │
│      │                     │        │ AgentRouter雏形             │
│      │                     │        │ TemplateAgentFactory        │
│      │                     │        │ TemporaryAgentLifecycle     │
│      │                     │        │ 委派链循环检测+深度限制       │
├──────┼─────────────────────┼────────┼────────────────────────────┤
│  #3  │ D1: Phase 3a        │ 4天    │ BootstrapLoader             │
│      │ (无记忆依赖组件)     │        │ AgentRouter完整版           │
│      │                     │        │ IdentityConfig              │
│      │                     │        │ ContextInjector             │
│      │                     │        │ CompactionStrategy(截断)     │
├──────┼─────────────────────┼────────┼────────────────────────────┤
│  #4  │ A: 新记忆系统+知识库  │ 12~15天│ 指令记忆（文件管理）         │
│      │                     │        │ 知识库RAG（摄入/嵌入/检索）   │
│      │                     │        │ Agent个性记忆               │
│      │                     │        │ 新MemorySystem接口           │
│      │                     │        │ 删除旧25个接口文件           │
├──────┼─────────────────────┼────────┼────────────────────────────┤
│  #5  │ D2: Phase 3b        │ 3天    │ CompactionEngine（新接口）   │
│      │ (CompactionEngine)  │        │ 压缩→知识库集成              │
├──────┼─────────────────────┼────────┼────────────────────────────┤
│  #6  │ E: Phase 4          │ 7天    │ BlockStreamingController    │
│      │ Streaming           │        │ SandboxExecutionService     │
│      │                     │        │ HeartbeatScheduler          │
│      │                     │        │ RunRetryManager             │
└──────┴─────────────────────┴────────┴────────────────────────────┘
```

### 8.2 为什么Agent CRUD现在是#1

**论证1：唯一无依赖的确定性任务**

在记忆系统推倒重来的前提下，Agent CRUD是整个系统中唯一"需求明确、无外部依赖、可立即开始"的任务。它不是"最重要"的，但是"最能确定交付"的。

**论证2：最短路径到可见价值**

```
从用户视角看：
  Day 0:  系统只有1个Agent (chat)，无法委派，无法切换
  Day 7:  系统有多个Agent，用户可以创建、编辑、切换Agent
          → 系统从"聊天工具"升级为"Agent平台"
  
从开发者视角看：
  Day 0:  编排引擎空转（无Agent可编排）
  Day 7:  编排引擎有数据了（多个Agent在数据库中）
          → 编排雏形可以立即开始
```

**论证3：为后续所有Phase提供基础数据**

Agent CRUD完成后提供的不仅是CRUD能力，更是整个系统的"Agent数据层"：
- 编排雏形需要Agent列表（从DB获取）
- BootstrapLoader需要知道每个Agent的Bootstrap配置（从agents表读取）
- 新记忆系统需要知道每个Agent的记忆配置（从agents.extensions读取）
- 前端所有组件需要Agent数据（AgentSelector, SessionView, Settings...）

**论证4：前端联动效应**

Agent CRUD是前后端联动的任务。完成后：
- AppSidebar新增Agent管理入口
- AgentSelector从"单选项"变为"多选项+管理"
- 设置页面新增Agent管理Tab
- 这些前端改动同时为后续的记忆系统+知识库管理界面提供了UI框架

### 8.3 为什么编排雏形是#2

**论证1：Agent CRUD的自然延续**

Agent CRUD提供了"有哪些Agent"，编排雏形提供"Agent之间如何协作"。两者紧密衔接：
```
Agent CRUD: 创建 code_reviewer, test_writer, doc_writer
编排雏形:   orchestrator 可以 delegate_to_agent 给它们
```

**论证2：修复TODO + 解锁Phase 2所有投入**

Phase 2投入了大量精力建设编排基础设施（DelegateToAgentToolProvider 400行 + SubagentSpawner + 5 Stage管线），但目前因为"只有1个Agent"而无法使用。Agent CRUD + 编排雏形 = Phase 2投入的价值终于兑现。

**论证3：为记忆系统设计提供需求信息**

有了实际运行的委派场景后，才能回答：
- 子Agent最需要从父Agent继承什么信息？
- 哪些委派结果值得持久化？
- Agent之间共享知识的频率和模式是什么？

这些答案直接指导新记忆系统的接口设计。

**论证4：不依赖记忆系统即可工作**

如4.4节分析，编排雏形通过"task描述→userMessage"传递上下文，通过"会话JSONL"持久化结果，完全不依赖记忆系统接口。记忆系统完成后可以无缝升级。

### 8.4 为什么Phase 3a是#3

**论证1：BootstrapLoader提升所有Agent的基础能力**

BootstrapLoader让每个Agent都能加载项目级上下文（类似OpenClaw的文件树注入）。这在多Agent场景下特别有用——不同Agent可以加载不同的Bootstrap文件。

**论证2：AgentRouter让委派更智能**

编排雏形的手动委派需要用户指定target_agent。AgentRouter让Agent自动选择合适的子Agent，从"手动委派"升级到"半自动委派"。

**论证3：ContextInjector为Phase 4做准备**

流式响应需要ContextInjector来管理上下文的注入时机和方式。Phase 3a完成ContextInjector后，Phase 4可以直接使用。

**论证4：不依赖记忆系统，可以提前做**

如6.1节分析，Phase 3a的6个组件都不依赖MemorySystem。不需要等记忆系统设计完成。

### 8.5 为什么新记忆系统是#4

**论证1：给设计留足时间**

新记忆系统需要研究OpenClaw + RAG模式，这个研究过程不应被"赶工"心态驱动。排在#4意味着有3~4周的时间（Agent CRUD + 编排雏形 + Phase 3a期间）可以同步思考和设计。

**论证2：前面的Phase提供了需求输入**

Agent CRUD完成后 → 知道Agent配置的完整数据结构 → 知道Agent记忆需要存储什么
编排雏形完成后 → 知道Agent之间的信息传递模式 → 知道记忆系统需要检索什么
Phase 3a完成后 → BootstrapLoader在运行 → 知道上下文注入的实际需求 → 知道记忆如何与上下文融合

**论证3：删除旧代码的时机更合适**

旧25个接口文件在以下时刻删除最合适：
- Agent CRUD已稳定 → agents表结构确定
- 编排雏形已运行 → 知道委派场景需要什么记忆
- 新记忆系统设计完成 → 删除旧代码的同时引入新接口

如果一上来就删旧代码，会有一段"青黄不接"的时期（旧代码没了，新代码还没出来）。

### 8.6 为什么Phase 3b和Phase 4排在最后

**Phase 3b (CompactionEngine)**: 硬依赖新MemorySystem接口，只能等#4完成。

**Phase 4 (Streaming)**: 需要Phase 3a的ContextInjector + Phase 3b的CompactionEngine。但Streaming的核心价值（流式响应）当前已有基本实现（SSE）。Phase 4要做的是增强版（沙箱、心跳、重试），优先级低于前面几个Phase。

### 8.7 时间线预估（修订版）

```
时间线（单人开发，按工作日计算）:

Week 1-2:    #1 Agent CRUD                         (7天)
Week 2-3:    #2 子Agent编排雏形                     (6天)
              ─── 里程碑1: 多Agent手动委派可用 ───
Week 3-4:    #3 Phase 3a (无记忆依赖)              (4天)
              ─── 里程碑2: Bootstrap+上下文注入可用 ───
Week 4-7:    #4 新记忆系统+知识库                   (12~15天)
              ─── 里程碑3: RAG知识库+Agent记忆可用 ───
Week 7-8:    #5 Phase 3b (CompactionEngine)        (3天)
Week 8-10:   #6 Phase 4 Streaming                  (7天)
              ─── 里程碑4: 全功能多Agent平台 ───
────────────────────────────────────────────────────────
总计: 约 39~42 个工作日 (8~9周)

并行优化（如有2人）:
  开发者A: #1→#2→#3 (主线，17天)
  开发者B: #4 新记忆系统设计 (同时进行，12~15天)
  汇合后: A+B一起做 #5+#6 (10天)
  ────────────────────────────────────────────
  并行后总计: 约 6~7 周

与v1.0时间线对比:
  v1.0: 35天，记忆系统最先完成
  v2.0: 39~42天，记忆系统在中间完成，但第3周就可见多Agent委派
  差异: v2.0多4~7天，但提前4周交付可见价值
```

---

## 9. 未来场景矩阵：六种场景的综合分析

### 9.1 场景总览（修订版：标注新记忆系统依赖）

```
┌──────────────────────────────────────────────────────────────────┐
│                     六种未来场景（修订版）                         │
├─────┬────────────────────┬──────┬──────┬──────┬──────┬──────┬────┤
│ 场景 │ 描述                │ CRUD │ 编排 │ Ph3a │新记忆│ Ph4 │依赖│
├─────┼────────────────────┼──────┼──────┼──────┼──────┼──────┼────┤
│  1  │ 静态多Agent手动切换  │  ●   │  ○   │  ○   │  ○   │  ○  │ #1 │
│  2  │ 用户手动委派子Agent  │  ●   │  ●   │  ○   │  ○   │  ○  │#1+#2│
│  3  │ Agent自动委派子Agent │  ●   │  ●   │  ●   │  ○   │  ○  │1+2+3│
│  4  │ Agent生成临时Agent   │  ●   │  ●   │  ●   │  ○   │  ○  │1+2+3│
│  5  │ Agent生成持久Agent   │  ●   │  ●   │  ●   │  ●   │  ○  │1-4 │
│  6  │ Agent社会+知识网络    │  ●   │  ●   │  ●   │  ●   │  ●  │全部│
└─────┴────────────────────┴──────┴──────┴──────┴──────┴──────┴────┘

● = 必须  ○ = 非必须但有益

与v1.0的关键差异:
  - 场景1不再需要记忆系统（会话JSONL已足够）
  - 场景2不再需要记忆系统（上下文通过task描述传递）
  - 场景3-4仅需Phase 3a（不依赖记忆系统）
  - 场景5才开始需要新记忆系统
  - 场景6需要全部
```

### 9.2 场景1：静态多Agent手动切换

**场景描述**：用户在前端创建了多个Agent，手动在AgentSelector中切换对话。

**最低实现要求**：#1 Agent CRUD（7天）

**记忆需求**：无。会话JSONL已持久化每个Agent的对话历史，切换Agent时恢复对应会话即可。

```
当前已有支持的组件:
  ✅ SessionPersistenceHook  — 会话JSONL持久化
  ✅ SessionStore             — 前端会话管理
  ✅ SessionSelector          — 前端会话切换
  ✅ ChatView                 — 按session加载消息

Agent CRUD新增:
  ✅ 多个Agent（不仅是chat）
  ✅ AgentSelector支持多Agent切换
  ✅ 每个Agent独立的session空间
```

### 9.3 场景2：用户手动委派子Agent

**场景描述**：用户在对话中显式调用`delegate_to_agent`，将子任务委派给另一个Agent。

**最低实现要求**：#1 Agent CRUD + #2 编排雏形（13天）

**记忆需求**：无。子Agent通过task描述获取上下文，结果直接返回父Agent的会话中。

```
交互流程:
  用户: "帮我校验这段代码，然后写单元测试"
  父Agent (orchestrator):
    1. 分析任务 → 需要code_reviewer和test_writer
    2. delegate_to_agent(target="code_reviewer", task="校验以下代码: ...")
    3. 收到code_reviewer的审查结果（JSON格式）
    4. delegate_to_agent(target="test_writer", task="基于以下审查结果生成测试: ...")
    5. 收到test_writer的测试代码
    6. 合成最终回答给用户

无记忆系统时如何传递上下文:
  - 父Agent将相关代码片段放入task参数
  - 父Agent将审查结果放入第二个委派的task参数
  - 子Agent的userMessage = task描述 + 必要的代码上下文
  - 整个委派链的输入输出都在当前会话的JSONL中
  
记忆系统完成后的增强:
  - code_reviewer自动检索项目编码规范（RAG知识库）
  - test_writer自动检索已有测试模式（Agent个性记忆）
  - 委派结果自动存入知识库（避免重复审查相同代码）
```

### 9.4 场景3：Agent自动委派子Agent

**场景描述**：Agent根据任务复杂度自动判断是否需要委派，无需用户手动触发。

**最低实现要求**：#1 + #2 + #3 Phase 3a（17天）

**关键组件**：AgentRouter（Phase 3a）根据任务类型自动匹配子Agent。

```
自动委派决策流程:
  用户: "帮我全面分析这个项目的代码质量"
  
  父Agent (orchestrator, delegation_mode="auto"):
    1. 分析任务 → 识别子任务:
       - 代码风格检查 → agent_type="code_reviewer"
       - 测试覆盖率分析 → agent_type="test_analyzer"
       - 依赖安全检查 → agent_type="security_auditor"
       - 文档完整性检查 → agent_type="doc_reviewer"
    
    2. AgentRouter.match(taskType, extensions):
       遍历数据库中的Agent，匹配extensions.agent_type
       返回匹配的Agent列表，按匹配度排序
    
    3. 对每个子任务:
       如果有匹配的Agent → delegate_to_agent
       如果无匹配 → 告知用户缺少某种类型的Agent
    
    4. 收集所有子Agent结果 → 合成综合报告
```

### 9.5 场景4：Agent自动生成临时Agent

**场景描述**：当没有合适的子Agent时，Agent自动创建一个临时Agent来完成任务。

**最低实现要求**：#1 + #2 + #3（17天）

**不依赖新记忆系统**。临时Agent的"知识抢救"使用简化方案（存入会话metadata）。

```
临时Agent生命周期:
  创建:
    TemplateAgentFactory.generateConfig(taskType, taskDescription)
    → 基于预定义模板生成Agent配置
    → 注册到DynamicAgentRegistry（标记temporary=true, ttl=会话结束）
    → 不写入agents表（纯内存+仅当前会话）
  
  执行:
    → AgentProxyFactory.create(temporaryAgentInterface)
    → 完整的5 Stage管线
    → 结果返回父Agent
  
  知识抢救（简化方案，不依赖记忆系统）:
    → 提取执行摘要（关键发现、使用的工具、最终结果）
    → 存入当前会话metadata:
      session.metadata.temporaryAgents[agentId] = {
        taskType, taskDescription, resultSummary, 
        usefulPatterns, mistakes, createdAt
      }
    → 下次用户问"之前那个临时Agent做了什么"时可以检索
    → 下次类似任务时，父Agent可以查看之前临时Agent的配置和结果
  
  销毁:
    → 从DynamicAgentRegistry注销
    → 会话metadata保留（JSONL持久化）
    → 内存清理

记忆系统完成后的增强:
  → 临时Agent的关键发现自动进入RAG知识库
  → 成功的临时Agent配置自动提升为持久Agent模板
  → Agent个性记忆记录"这个任务类型之前用过什么配置"
```

### 9.6 场景5：Agent自动生成持久Agent

**场景描述**：Agent发现某类任务反复出现，决定创建持久化Agent。

**最低实现要求**：#1 + #2 + #3 + #4 新记忆系统（约34天）

**开始依赖新记忆系统**：需要记忆系统来判断"是否值得持久化"。

```
持久化决策逻辑:
  触发条件:
    同类型临时Agent被创建超过阈值（如3次）
    AND 临时Agent的平均任务成功率 > 阈值（如70%）
    AND 用户未明确拒绝过此类Agent的创建
  
  决策数据来源（需要记忆系统）:
    - 临时Agent创建历史 → Agent个性记忆
    - 任务成功率统计 → Agent个性记忆
    - 用户反馈信号 → 会话记忆分析
  
  执行:
    1. 基于表现最好的临时Agent配置创建持久Agent
    2. 通过Agent CRUD API写入agents表
    3. 通知用户（前端弹窗/消息）
    4. 用户可编辑/确认/删除
    5. 新Agent的元信息存入EntityMemory
```

### 9.7 场景6：多Agent协作+知识网络

**场景描述**：多个Agent形成协作网络，共享知识库，互相委派，形成"Agent社会"。

**最低实现要求**：全部 #1~#6

```
知识共享架构（新记忆系统支持）:

                    ┌──────────────────────┐
                    │   知识库 (RAG)        │ ← 共享文档知识
                    │   - 项目文档          │
                    │   - API规范           │
                    │   - 编码规范          │
                    │   - 历史决策记录      │
                    └──────────┬───────────┘
                               │
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
   ┌────────────────┐ ┌────────────────┐ ┌────────────────┐
   │ orchestrator   │ │ code_reviewer  │ │ test_writer    │
   │ 个性记忆:      │ │ 个性记忆:      │ │ 个性记忆:      │
   │ - 委派策略偏好  │ │ - 常见代码模式  │ │ - 测试模板     │
   │ - 任务分解模式  │ │ - Bug模式库    │ │ - 覆盖率偏好   │
   │ - Agent能力图谱 │ │ - 审查历史     │ │ - Mock策略     │
   └────────┬───────┘ └────────┬───────┘ └────────┬───────┘
            │                  │                  │
            └──────────────────┼──────────────────┘
                               │
                    ┌──────────▼───────────┐
                    │   会话记忆 (JSONL)    │ ← 对话历史
                    │   - 每次委派的输入输出 │
                    │   - 用户反馈          │
                    │   - 临时Agent记录     │
                    └──────────────────────┘

知识流转:
  RAG知识库 ←→ Agent个性记忆: Agent学到的通用知识提升到知识库
  Agent个性记忆 ←→ 会话记忆: 会话中的关键发现沉淀为Agent经验
  知识库 → 上下文注入: 相关文档片段注入到每个Agent的System Prompt
```

---

## 10. 风险分析与替代方案

### 10.1 主要风险

#### 风险1：新记忆系统设计周期超出预期

**风险等级**：高
**描述**：RAG+知识库的设计如果追求完美，可能从预期的12~15天延长到4~6周。
**影响**：Phase 3b (CompactionEngine) 被阻塞，Phase 4的完整版延迟。
**缓解措施**：
- 新记忆系统分两期：MVP（7~9天，只做知识库核心）→ 完整版（12~15天）
- Phase 3b可以在记忆系统MVP完成后就开始，不必等完整版
- CompactionEngine初期可以只用简单截断策略（CompactionStrategy），不依赖记忆系统

#### 风险2：Agent CRUD完成后编排雏形遇到未预期的集成问题

**风险等级**：中
**描述**：DynamicAgentRegistry与现有AgentProxyFactory的集成可能存在未知冲突。
**影响**：编排雏形延迟2~3天。
**缓解措施**：
- Agent CRUD的DynamicAgentRegistry先做单元测试
- 编排雏形先用手动构造的Agent数据测试，验证核心委派链路
- 渐进集成：先注册1个额外Agent → 测试委派 → 再注册多个

#### 风险3：旧记忆接口删除的连锁反应

**风险等级**：中
**描述**：25个接口文件被删除后，可能有其他文件引用了这些接口（import、类型声明等）。
**影响**：编译失败，需要额外0.5~1天清理引用。
**缓解措施**：
- 删除前先全局搜索引用（grep import.*memory）
- 分步删除：先标记@Deprecated → 确认无引用 → 再删除
- 保留MemorySystem接口的最简版本（只定义方法签名，无实现），作为过渡

#### 风险4：前端开发节奏滞后于后端

**风险等级**：中
**描述**：后端API快速迭代，前端UI开发可能跟不上。
**影响**：后端API完成了但前端无法使用，集成测试延迟。
**缓解措施**：
- 每个Phase先定义API契约（TypeScript接口 + OpenAPI文档）
- 前端先Mock数据开发UI框架
- Phase结束前集中联调

#### 风险5：Phase 3的CompactionEngine重构方向不明确

**风险等级**：中
**描述**：在新记忆系统接口未确定前，CompactionEngine无法重构。
**影响**：Phase 3b可能面临二次重构。
**缓解措施**：
- 新记忆系统设计时，把CompactionEngine的需求作为接口设计的输入
- CompactionEngine的"记忆写入"部分抽象为独立接口（MemoryFlushHook），可插拔
- 初期CompactionEngine只做截断，不写记忆；记忆系统完成后插入MemoryFlushHook

### 10.2 替代方案分析

#### 替代方案A：先做新记忆系统（v1.0方案）

```
方案描述:
  按v1.0的思路，先花12~15天设计并实现新记忆系统+知识库
  然后Agent CRUD → 编排雏形 → Phase 3 → Phase 4

优势:
  - CompactionEngine可以直接使用新接口，不需要重构
  - 知识库从一开始就可用

劣势:
  - 前3~4周无可见交付（用户看不到新功能）
  - 设计和实现并行推进，容易返工
  - 没有多Agent运行数据，设计可能偏离实际需求
  - Agent CRUD被推迟，编排引擎继续空转

结论: 不推荐。在记忆系统设计充满不确定性的情况下，
      让其他确定性高的任务等它，是不合理的。
```

#### 替代方案B：跳过编排雏形，CRUD后直接新记忆系统

```
方案描述:
  Agent CRUD → 新记忆系统+知识库 → Phase 3 → 编排雏形 → Phase 4

优势:
  - 知识库先做好，编排雏形从一开始就有RAG支持

劣势:
  - "多Agent委派"推迟到第5~6周（v2.0方案第3周即可用）
  - Phase 2的编排基础设施继续空转4~5周
  - 编排雏形的反馈无法指导记忆系统设计

结论: 不推荐。编排雏形投入小(6天)价值大(解锁多Agent协作)，
      不值得为了"更好的记忆支持"推迟它。
```

#### 替代方案C：CRUD + 编排 + 新记忆系统 完全并行

```
方案描述（需2~3人团队）:
  开发者A: Agent CRUD (7天)
  开发者B: 新记忆系统设计+实现 (12~15天)
  开发者C: 编排雏形 (等A完成后开始，6天)
  汇合后: 一起做Phase 3/4

优势:
  - 总周期最短（约5~6周全部完成）
  - 各子系统独立推进

劣势:
  - 需要多人协作
  - 接口定义需要提前约定（避免集成时冲突）

结论: 条件允许时的最优方案。前提是提前定义好接口契约。
```

#### 替代方案D：极简记忆 + 快速CRUD + 最小编排（3周快速验证）

```
方案描述:
  Week 1: Agent CRUD核心API（不做前端）
  Week 2: 编排雏形（只做per-agent delegation + 手动委派）
  Week 3: 极简知识库（单文件文档摄入 + SQLite关键词检索，不做向量嵌入）
  Week 4: 前端补齐 + 联调

优势:
  - 4周内可见"多Agent委派+简单知识库"的全链路
  - 快速验证架构可行性
  - 为后续完整实现提供反馈

劣势:
  - 每个组件都是半成品，后续需要大量补充
  - 极简知识库可能后续需要大幅重构

结论: 适合"先看效果再决定投入"的策略。
      如果对架构方向不确定，可以先做这个快速验证。
```

### 10.3 推荐策略

```
首选方案（单人开发）:
  v2.0五阶段顺序: Agent CRUD → 编排雏形 → Phase 3a → 新记忆系统 → Phase 3b → Phase 4
  特点: 确定性优先，第3周可见多Agent委派

备选方案（多人开发）:
  替代方案C: CRUD + 编排 + 记忆系统 并行
  特点: 速度优先，需2~3人

快速验证方案（架构不确定时）:
  替代方案D: 极简记忆 + 快速CRUD + 最小编排
  特点: 验证优先，4周出全链路原型

不推荐:
  替代方案A（先做记忆系统）: 确定性低的任务不应阻塞确定性高的任务
  替代方案B（跳过编排雏形）: 编排雏形投入产出比最高，不应推迟
```

---

## 11. 数据库迁移方案

### 11.1 agents 表扩展（ALTER TABLE — 12个新字段）

```sql
-- 委派相关（最关键，编排雏形直接依赖）
ALTER TABLE agents ADD COLUMN delegation_mode TEXT DEFAULT 'suggest';
-- 值: 'none' | 'suggest' | 'auto'

ALTER TABLE agents ADD COLUMN extensions TEXT DEFAULT '{}';
-- JSON Map<String, String>，Agent类型标记/资源配置/生命周期等

ALTER TABLE agents ADD COLUMN allow_agents TEXT DEFAULT '[]';
-- JSON Array<String>，允许委派的目标Agent ID白名单

-- 版本与默认
ALTER TABLE agents ADD COLUMN version TEXT DEFAULT '1.0.0';
ALTER TABLE agents ADD COLUMN default_agent INTEGER DEFAULT 0;

-- 工作目录
ALTER TABLE agents ADD COLUMN workspace TEXT DEFAULT '';
ALTER TABLE agents ADD COLUMN agent_dir TEXT DEFAULT '';

-- 模型降级
ALTER TABLE agents ADD COLUMN fallbacks TEXT DEFAULT '[]';
-- JSON Array<String>，如 ["claude-sonnet-4-6", "gpt-4o"]

-- 上下文相关（Phase 3a BootstrapLoader使用）
ALTER TABLE agents ADD COLUMN context_tokens INTEGER DEFAULT 65536;
ALTER TABLE agents ADD COLUMN context_injection TEXT DEFAULT '';

-- Bootstrap相关（Phase 3a BootstrapLoader使用）
ALTER TABLE agents ADD COLUMN bootstrap_max_chars INTEGER DEFAULT 50000;
ALTER TABLE agents ADD COLUMN bootstrap_total_max_chars INTEGER DEFAULT 200000;
```

### 11.2 knowledge_documents 表（新记忆系统 — 知识库）

```sql
-- 知识库文档表：存储摄入的文档及其分块
CREATE TABLE IF NOT EXISTS knowledge_documents (
    doc_id TEXT PRIMARY KEY,                        -- UUID
    agent_id TEXT,                                  -- 关联Agent（NULL=全局知识库）
    title TEXT NOT NULL,                            -- 文档标题
    source_path TEXT,                               -- 源文件路径
    source_type TEXT DEFAULT 'file',                -- file/url/text/manual
    content_type TEXT DEFAULT 'text',               -- text/markdown/code/pdf
    total_chunks INTEGER DEFAULT 0,                 -- 总分块数
    metadata TEXT DEFAULT '{}',                     -- JSON扩展元数据
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_kd_agent ON knowledge_documents(agent_id);
CREATE INDEX IF NOT EXISTS idx_kd_title ON knowledge_documents(title);
```

### 11.3 knowledge_chunks 表（新记忆系统 — 文档分块+向量）

```sql
-- 知识库分块表：存储文档分块及其向量嵌入
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    chunk_id TEXT PRIMARY KEY,                      -- UUID
    doc_id TEXT NOT NULL,                           -- 所属文档
    chunk_index INTEGER NOT NULL,                   -- 分块序号
    content TEXT NOT NULL,                          -- 分块文本内容
    content_hash TEXT,                              -- 内容哈希（去重用）
    embedding TEXT,                                 -- 向量嵌入（JSON数组，初期方案）
    token_count INTEGER DEFAULT 0,                  -- Token数估算
    metadata TEXT DEFAULT '{}',                     -- JSON扩展元数据
    created_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (doc_id) REFERENCES knowledge_documents(doc_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_kc_doc ON knowledge_chunks(doc_id);
CREATE INDEX IF NOT EXISTS idx_kc_hash ON knowledge_chunks(content_hash);
```

**注意**：`embedding`字段使用TEXT存JSON数组是初期方案。当文档量超过1000个分块后，建议迁移到专业向量数据库（ChromaDB/Milvus/Qdrant）。

### 11.4 agent_memories 表（新记忆系统 — Agent个性记忆）

```sql
-- Agent个性记忆表：每个Agent的独立长期记忆空间
CREATE TABLE IF NOT EXISTS agent_memories (
    memory_id TEXT PRIMARY KEY,                     -- UUID
    agent_id TEXT NOT NULL,                         -- 所属Agent
    key TEXT NOT NULL,                              -- 记忆键（如 "coding_style_preference"）
    value TEXT NOT NULL,                            -- 记忆值（JSON或文本）
    category TEXT DEFAULT 'general',                -- 分类
    importance REAL DEFAULT 0.5,                    -- 重要性评分 0.0-1.0
    access_count INTEGER DEFAULT 0,                 -- 访问次数
    embedding TEXT,                                 -- 值的向量嵌入（用于语义检索）
    metadata TEXT DEFAULT '{}',                     -- JSON扩展元数据
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    expires_at TEXT,                                -- 过期时间（NULL=永不过期）
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_am_agent ON agent_memories(agent_id);
CREATE INDEX IF NOT EXISTS idx_am_key ON agent_memories(agent_id, key);
CREATE INDEX IF NOT EXISTS idx_am_category ON agent_memories(agent_id, category);
```

### 11.5 instruction_files 表（新记忆系统 — 指令文件，类似CLAUDE.md）

```sql
-- 指令文件表：持久化的Agent指令文件（类似OpenClaw的CLAUDE.md）
CREATE TABLE IF NOT EXISTS instruction_files (
    file_id TEXT PRIMARY KEY,                       -- UUID
    agent_id TEXT,                                  -- 关联Agent（NULL=全局指令）
    filename TEXT NOT NULL,                         -- 文件名（如 "CLAUDE.md", "RULES.md"）
    content TEXT NOT NULL,                          -- 文件内容
    priority INTEGER DEFAULT 0,                    -- 注入优先级（数字越大越靠前）
    enabled INTEGER DEFAULT 1,                      -- 是否启用
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_if_agent ON instruction_files(agent_id);
```

### 11.6 迁移执行计划

```sql
-- SqliteMigrationService 中新增的迁移步骤

-- v2 → v3: Agent扩展字段
-- 12个ALTER TABLE（SQLite不支持一次多列）
-- 在单个事务中执行

BEGIN TRANSACTION;

-- Agent扩展列
ALTER TABLE agents ADD COLUMN delegation_mode TEXT DEFAULT 'suggest';
ALTER TABLE agents ADD COLUMN extensions TEXT DEFAULT '{}';
ALTER TABLE agents ADD COLUMN allow_agents TEXT DEFAULT '[]';
ALTER TABLE agents ADD COLUMN version TEXT DEFAULT '1.0.0';
ALTER TABLE agents ADD COLUMN default_agent INTEGER DEFAULT 0;
ALTER TABLE agents ADD COLUMN workspace TEXT DEFAULT '';
ALTER TABLE agents ADD COLUMN agent_dir TEXT DEFAULT '';
ALTER TABLE agents ADD COLUMN fallbacks TEXT DEFAULT '[]';
ALTER TABLE agents ADD COLUMN context_tokens INTEGER DEFAULT 65536;
ALTER TABLE agents ADD COLUMN context_injection TEXT DEFAULT '';
ALTER TABLE agents ADD COLUMN bootstrap_max_chars INTEGER DEFAULT 50000;
ALTER TABLE agents ADD COLUMN bootstrap_total_max_chars INTEGER DEFAULT 200000;

COMMIT;

-- v3 → v4: 新记忆系统表（在新记忆系统实现时执行）
-- 包含 knowledge_documents, knowledge_chunks, agent_memories, instruction_files
-- 具体SQL见11.2~11.5节
```

---

## 12. 与OpenClaw的对比参考

### 12.1 架构对比（修订版：加入新记忆系统设计方向）

```
                    OpenClaw                          LyClaw (当前)              LyClaw (新设计方向)
─────────────────────────────────────────────────────────────────────────────────────────────
Agent定义      CLAUDE.md + config.yaml        @Agent注解 + YAML配置         @Agent注解 + DB持久化 + 前端CRUD
Agent注册      文件系统扫描                     Spring Bean + 注解扫描        DynamicAgentRegistry + DB加载
子Agent委派    delegate_to_agent工具           delegate_to_agent工具         delegate_to_agent + AgentRouter
工具系统       MCP协议                         ToolProvider SPI              ToolProvider SPI (可对齐MCP)
记忆系统       CLAUDE.md持久化指令              四层记忆架构(25接口)          指令文件 + RAG知识库 + Agent个性记忆
              文件树注入(Bootstrap)              (将删除)                     (参照OpenClaw，增强RAG)
上下文引导     文件树注入                       BootstrapLoader (Phase 3a)   BootstrapLoader + 知识库检索注入
流式响应       SSE + 实时事件                   SSE (已有)                   SSE + Phase 4增强 (沙箱/心跳/重试)
沙箱执行       Docker/Bash                     无                           SandboxProvider (Phase 4)
Agent路由      MCP tool description匹配         无                           extensions匹配 + AgentRouter
Agent生成      手动创建CLAUDE.md                手动CRUD                     手动CRUD + 自动模板生成 + 临时Agent
知识库         无                               无                           RAG文档摄入/分块/嵌入/检索 (新)
多Agent协作    树形委派                          树形委派(空转)               树形委派 + 知识共享网络
```

### 12.2 新记忆系统 vs OpenClaw 记忆机制

```
OpenClaw的记忆机制:
  1. CLAUDE.md (静态指令)
     - 位置: 项目根目录或Agent目录
     - 内容: 系统提示词、行为准则、领域知识
     - 注入方式: 对话开始时附加到System Prompt
     - 作用范围: 全局 或 单个Agent (子Agent可有独立CLAUDE.md)
  
  2. 文件树上下文 (环境感知)
     - 位置: 工作目录下的文件
     - 内容: 项目结构、源代码、配置文件
     - 注入方式: Bootstrap时读取文件列表和内容
     - 作用范围: 当前会话
  
  3. 会话持久化 (对话历史)
     - 位置: JSONL文件
     - 内容: 用户消息、Agent响应、工具调用
     - 注入方式: 按需加载历史消息
     - 作用范围: 当前会话

LyClaw新记忆系统的对应+增强:
  1. 指令文件 (对应CLAUDE.md)
     - instruction_files 表存储
     - 支持多文件、优先级排序
     - 前端可编辑（不需要手动编辑文件）
     - 每个Agent可有独立指令文件集
  
  2. 项目上下文 (对应文件树)
     - BootstrapLoader (Phase 3a) 加载
     - 支持按Agent类型选择不同的Bootstrap策略
     - 可以注入的不只是文件，还有数据库中的知识库条目
  
  3. 会话持久化 (对应JSONL) — 已有
     - SessionPersistenceHook 已实现
     - JSONL格式存储，支持懒加载
  
  4. 知识库 (LyClaw独有 — 超越OpenClaw)
     - 文档摄入 → 分块 → 嵌入 → 语义检索
     - 支持按Agent过滤的知识库范围
     - 检索结果注入到System Prompt
     - OpenClaw没有这个能力！
  
  5. Agent个性记忆 (LyClaw独有 — 超越OpenClaw)
     - 每个Agent的独立记忆空间
     - 跨会话持久化
     - 支持语义检索
     - OpenClaw的CLAUDE.md是静态的，Agent个性记忆是动态的
```

### 12.3 核心设计理念差异

```
OpenClaw的设计理念:
  "文件即配置，文件即记忆"
  优势: 简单、透明、可版本控制(Git)
  劣势: 静态、无语义检索、无知识积累

LyClaw新设计理念:
  "数据库+文件双轨，从静态到动态演进"
  
  Phase 1-2 (当前):    静态为主（注解+配置文件）
  Phase 3a (Bootstrap): 动静结合（文件+BootstrapLoader）
  Phase 4+ (记忆系统): 动态为主（知识库语义检索+Agent个性记忆）
  
  优势: 从简单到智能逐步演进，每个阶段都有可见价值
  劣势: 比纯文件方案复杂，需要维护数据库
```

### 12.4 值得从OpenClaw借鉴的设计

```
1. CLAUDE.md 的文件格式和约定:
   - 采用类似的Markdown格式
   - 支持frontmatter元数据
   - 指令文件的优先级和合并规则参照OpenClaw

2. MCP协议的工具描述格式:
   - ToolProvider的getToolSchema()可以输出MCP兼容格式
   - 方便未来与MCP生态集成

3. 子Agent的CLAUDE.md独立配置:
   - 每个Agent可以有自己的指令文件集
   - instruction_files表已支持（agent_id字段）

4. 文件树注入的简洁性:
   - BootstrapLoader不应过度设计
   - 初期只需要"读取文件列表→注入文件树→注入关键文件内容"
```

---

## 13. 总结与建议

### 13.1 最终优先级排序（一句话版本）

| 优先级 | Phase | 一句话理由 |
|--------|-------|-----------|
| #1 | Agent CRUD | 唯一无依赖、确定性高、最短路径到可见价值 |
| #2 | 编排雏形 | CRUD的自然延续，解锁Phase 2全部投入，不依赖记忆系统 |
| #3 | Phase 3a | Bootstrap提升Agent能力，不依赖记忆系统，可提前做 |
| #4 | 新记忆系统+知识库 | 需要充分设计，前面的Phase提供需求输入 |
| #5 | Phase 3b | 硬依赖新记忆接口，只能等#4完成 |
| #6 | Phase 4 | 依赖链最长，已有基础SSE可用，增强版放最后 |

### 13.2 与v1.0的核心差异

```
┌─────────────────────────────────────────────────────────────────┐
│                   v1.0 vs v2.0 核心差异                          │
├────────────────────────────┬────────────────────────────────────┤
│  v1.0 (旧前提)             │  v2.0 (新前提)                      │
├────────────────────────────┼────────────────────────────────────┤
│ 记忆系统: 实现既有接口     │ 记忆系统: 删除旧代码，重新设计       │
│ 确定性: 高 (8天)           │ 确定性: 低 (12~15天)                │
│ 排第一: 做得快+阻塞多     │ 排第四: 做不快，不应阻塞其他        │
├────────────────────────────┼────────────────────────────────────┤
│ Agent CRUD排第二           │ Agent CRUD排第一                    │
│ 理由是"可与记忆并行"       │ 理由是"唯一无依赖+最短价值路径"     │
├────────────────────────────┼────────────────────────────────────┤
│ Phase 3整体排第四          │ Phase 3拆分为3a(#3) + 3b(#5)       │
│ 全部等记忆系统             │ 3a不依赖记忆，可提前; 3b等新记忆    │
├────────────────────────────┼────────────────────────────────────┤
│ 总工期: 约35天             │ 总工期: 约39~42天                   │
│ 第8天可见: 记忆系统MVP     │ 第7天可见: 多Agent平台              │
│ 第15天可见: Agent CRUD     │ 第13天可见: 多Agent手动委派          │
│ 第35天可见: 全部完成       │ 第17天可见: Bootstrap+自动委派       │
│                            │ 第42天可见: 全部完成                 │
└────────────────────────────┴────────────────────────────────────┘

关键差异总结:
  v1.0: 记忆优先，基础先行。首8天投入在"看不见"的基础设施上。
  v2.0: 价值优先，快速交付。首7天就交付用户可感知的多Agent平台。
  
  v2.0虽然总工期多4~7天，但:
  - 提前1周看到多Agent切换
  - 提前2周看到Agent委派
  - 记忆系统在第4~7周完成时，已有充分的需求输入和多Agent运行数据
```

### 13.3 新旧记忆系统的衔接策略

```
过渡期方案（Phase #1~#3期间，记忆系统尚未完成）:

1. 旧25个接口文件 → 保留但标记@Deprecated
   - 不删除（避免编译错误）
   - 不实现（避免浪费精力）
   - 添加JavaDoc: "@deprecated 将在新记忆系统完成后删除，请勿依赖此接口"

2. Phase 3a的CompactionStrategy → 只做简单截断
   - 不写入记忆（无记忆系统可写）
   - 策略: 保留最近N条消息 + System Prompt + 关键工具调用结果
   - 接口保留扩展点: onCompact(CompactionResult) 钩子，新记忆完成后插入

3. 编排雏形的上下文传递 → 使用当前会话数据
   - 父Agent → 子Agent: task描述 + 必要代码片段 (通过userMessage)
   - 子Agent → 父Agent: 执行结果 (通过ToolCallResult)
   - 持久化: JSONL (已有)

4. 新记忆系统完成后:
   - 删除旧25个文件
   - CompactionEngine使用新MemorySystem接口
   - 编排雏形升级: 子Agent可检索知识库和相关记忆
   - Phase 4使用完整的上下文注入管道
```

### 13.4 立即可执行的下一步

```
Step 1 (本周):  Agent CRUD — 数据库迁移 + 后端CRUD
  - SqliteMigrationService: 添加agents表12列
  - AgentRepository: 完整CRUD实现
  - AgentService: 业务逻辑层
  - AgentController: REST API

Step 2 (下周):  Agent CRUD — 前端 + 动态注册
  - AgentSelector增强
  - AgentEditor (创建/编辑表单)
  - DynamicAgentRegistry (从DB加载→创建代理)
  - 前后端联调

Step 3 (第3周): 编排雏形
  - 修复 AgentInvocationHandler TODO
  - DelegateToAgentToolProvider 增强 (动态Agent列表)
  - AgentRouter雏形 (extensions匹配)
  - 委派链循环检测+深度限制

Step 4 (第4周): Phase 3a + 新记忆系统设计启动
  - BootstrapLoader实现
  - 同步启动新记忆系统的研究和设计工作
```

### 13.5 关键决策记录

| # | 决策 | 选择 | 理由 |
|---|------|------|------|
| 1 | 旧记忆系统代码 | 保留+标记@Deprecated | 避免编译错误，新系统完成后统一删除 |
| 2 | Agent CRUD vs 新记忆系统 | CRUD先做 | CRUD确定性高+无依赖+快速可见，记忆系统需充分设计 |
| 3 | 编排雏形 vs Phase 3a | 编排雏形先做 | 编排解锁多Agent协作（核心价值），Phase 3a提升体验 |
| 4 | Phase 3拆分 | 拆为3a(无记忆)+3b(有记忆) | 3a不阻塞，3b等新记忆接口 |
| 5 | 新记忆系统架构 | 参照OpenClaw + RAG | 指令文件+知识库+Agent个性记忆，非旧四层架构 |
| 6 | 向量存储初期方案 | SQLite TEXT存JSON数组 | 简单起步，文档>1000分块后迁移专业向量DB |
| 7 | 单人 vs 多人 | 按单人规划 | 保守估计，可并行加速 |
| 8 | 知识库RAG范围 | 全局+按Agent过滤 | 支持共享知识库和Agent专属知识库 |

---

## 附录A：关键技术设计摘要

### A.1 DynamicAgentRegistry 设计

```
DynamicAgentRegistry 是Agent CRUD与AgentProxyFactory之间的桥梁。

职责:
  1. 系统启动时从数据库加载所有active Agent
  2. 通过AgentProxyFactory为每个Agent创建JDK动态代理
  3. 注册到Spring容器（作为@Agent注解接口的Bean）
  4. 运行时动态注册/注销Agent（CRUD操作触发）

核心流程:
  @PostConstruct init():
    1. agentRepository.findAllActive()
    2. for each agent:
       a. 将数据库记录转为 @Agent 注解的代理实例
          - 使用 Javaassist/ByteBuddy 动态创建接口?
          - 不，更简单的方案：创建一个通用AgentInterface
       b. agentProxyFactory.create(dynamicInterface, agentConfig)
       c. 注册到 internalRegistry (ConcurrentHashMap<String, Object>)
    3. 发布 AgentRegistryRefreshedEvent

  registerAgent(AgentConfig config):
    1. agentRepository.insert(config)  → 持久化
    2. 创建代理 → 注册到 internalRegistry
    3. 发布 AgentRegisteredEvent

  unregisterAgent(String agentId):
    1. 从 internalRegistry 移除
    2. agentRepository.delete(agentId) 或 markInactive
    3. 发布 AgentUnregisteredEvent

关键设计决策:
  - Agent接口动态生成: 不要求用户编写Java接口
    方案: 定义一个通用 AgentInterface { String chat(String message); Flux<SSE> chatStream(String); }
    所有动态Agent共用此接口，差异体现在配置（systemPrompt/model/tools等）
    
  - 与现有ChatAgent共存:
    ChatAgent (注解定义) 作为bootstrap Agent
    动态Agent (DB定义) 在ChatAgent之后注册
    两者在AgentRegistry中平等存在

  - 配置优先级:
    DB中的Agent配置 > application.yml的agent.defaults > AgentSystemDefaults
    (与现有3层合并逻辑一致，DB层插入在@Agent注解层和YAML层之间)
```

### A.2 AgentRouter 匹配算法设计

```
AgentRouter 负责根据任务描述找到最合适的子Agent。

输入:
  - taskDescription: String (父Agent对子任务的自然语言描述)
  - availableAgents: List<AgentConfig> (从DB获取所有delegation_mode != "none"的Agent)

输出:
  - Optional<AgentConfig> (最匹配的Agent，或空表示无匹配)

匹配算法 (多层过滤+排序):

  第1层: 白名单过滤
    如果父Agent有allowAgents白名单:
      availableAgents = availableAgents.filter(a -> allowAgents.contains(a.id))
    如果 allowAgents=["*"]: 跳过过滤

  第2层: 关键词匹配 (快速筛选)
    从taskDescription提取关键词:
      - 预定义关键词表:
          "审查|review|reviewer|code review" → agent_type="code_reviewer"
          "测试|test|unit test|测试用例" → agent_type="test_writer"
          "文档|doc|documentation|文档生成" → agent_type="doc_writer"
          "搜索|search|查找|research" → agent_type="web_researcher"
          "安全|security|漏洞|vulnerability" → agent_type="security_auditor"
      - 匹配Agent的extensions.agent_type
      - 匹配Agent的name和description字段
    
    得分: keywordScore = 匹配到的关键词数 / 总关键词数

  第3层: 语义匹配 (LLM辅助，可选)
    如果关键词匹配得分 < 阈值 (如0.3):
      调用LLM (轻量模型，单轮):
        prompt: "以下是任务描述和可用Agent列表，请选择最合适的Agent。
                只返回Agent ID，不要解释。"
      解析LLM返回的Agent ID
    得分: semanticScore = 1.0 (LLM直接选择)

  第4层: 排序
    综合得分 = keywordScore * 0.7 + semanticScore * 0.3
    如果综合得分 > 阈值 (0.5): 返回最高分Agent
    否则: 返回 Optional.empty() (无合适Agent)

  第5层: 无匹配时的行为
    如果 AgentRouter 返回空:
      - delegation_mode="suggest" → 告知用户无合适Agent，询问是否创建
      - delegation_mode="auto" → 触发 TemplateAgentFactory (场景4)
      - delegation_mode="none" → 自己处理（不委派）
```

### A.3 TemplateAgentFactory 设计

```
TemplateAgentFactory 根据任务类型生成Agent配置模板。

预定义模板库:

  code_reviewer:
    name: "代码审查员"
    systemPrompt: "你是一位严格的代码审查员。审查代码时关注: 1)正确性 2)性能 3)安全性 4)可维护性。
                   对每个问题给出: 严重级别(高/中/低)、问题描述、修复建议。"
    model: 继承父Agent
    delegation_mode: "none"
    extensions: { agent_type: "code_reviewer", language: "{derived}" }

  test_writer:
    name: "测试工程师"
    systemPrompt: "你是一位测试工程师。为给定代码生成全面的单元测试。
                   覆盖: 1)正常路径 2)边界条件 3)异常情况 4)空值处理。"
    model: 继承父Agent
    delegation_mode: "none"
    extensions: { agent_type: "test_writer", framework: "{derived}" }

  doc_writer:
    name: "文档写手"
    systemPrompt: "你是一位技术文档撰写者。生成清晰、准确、结构良好的技术文档。"
    model: 继承父Agent
    delegation_mode: "none"
    extensions: { agent_type: "doc_writer", format: "markdown" }

  web_researcher:
    name: "网络研究员"
    systemPrompt: "你是一位信息检索专家。擅长搜索、整理和总结互联网信息。
                   始终标注信息来源，区分事实和观点。"
    model: 继承父Agent
    delegation_mode: "none"
    extensions: { agent_type: "web_researcher", tools: "web_search, web_fetch" }

模板派生逻辑:
  1. 从taskDescription提取变量:
     - language: "Java" / "Python" / "TypeScript" (从代码片段推断)
     - framework: "JUnit5" / "pytest" / "vitest" (从项目依赖推断)
  
  2. 填入模板:
     template.extensions.language = derived.language
     template.extensions.framework = derived.framework
  
  3. 个性化微调 (可选，记忆系统完成后):
     从Agent个性记忆中检索用户偏好:
       - "用户偏好简洁的审查意见" → 调整systemPrompt
       - "项目使用JUnit5+Mockito" → 设置extensions.framework

临时Agent vs 持久Agent:
  临时: 不写入agents表，仅在当前会话存活，标记TemporaryAgentLifecycle
  持久: 写入agents表 (is_active=1)，永久可用，需要用户确认
```

### A.4 新旧记忆系统迁移策略

```
过渡期 (Phase #1~#3期间):

  阶段0 (当前): 旧25个接口文件存在，无实现
    → 不删除，不实现，不依赖

  阶段1 (Agent CRUD开始时):
    → 在所有旧接口文件顶部添加 @Deprecated 注解
    → 添加 JavaDoc: "@deprecated 将在新记忆系统完成后删除"
    → 全局搜索 import lyjew.com.lyclaw.memory.*
    → 确认: Phase 3设计文档引用了这些接口，但代码中尚无引用

  阶段2 (编排雏形+Phase 3a):
    → CompactionEngine 不依赖 MemorySystem（使用简单截断策略）
    → 上下文传递不经过记忆系统（直接通过task描述+会话JSONL）
    → 旧接口继续保留但无人使用

  阶段3 (新记忆系统设计完成):
    → 创建新的 memory 包结构:
      lyclaw.com.lyclaw.memory/          ← 新接口
      lyclaw.com.lyclaw.memory.rag/      ← RAG相关
      lyclaw.com.lyclaw.memory.instruction/ ← 指令记忆
      lyclaw.com.lyclaw.memory.agent/    ← Agent个性记忆
    
    → 新接口定义完成且稳定后:
      1. 删除旧25个文件
      2. 搜索并清理所有 import lyjew.com.lyclaw.memory.* (此时应只有新引用)
      3. 更新 Phase 3设计文档 (CompactionEngine使用新接口)
      4. 实现 CompactionEngine (Phase 3b)
    
    → 如果存在未被发现的旧接口引用:
      编译会失败 → 逐个修复或添加适配层

回退预案:
  如果新记忆系统设计遇到重大困难:
    → 保留旧接口文件（不删除）
    → 实现最简版本的旧MemorySystem（只做会话级key-value存储，不做四层架构）
    → CompactionEngine使用此最简实现
    → 新记忆系统设计继续，但不阻塞Phase 3b
```

---

## 附录B：文件索引

### 旧记忆系统接口文件（25个，将删除或标记@Deprecated）

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/memory/
├── MemorySystem.java           — 顶层门面 (将重新设计)
├── MemoryEntry.java            — 记忆条目模型 (将废弃)
├── MemoryQuery.java            — 查询参数模型 (将废弃)
├── PerceptionData.java         — 感知输入模型 (将废弃)
├── EntityMemory.java           — 实体记忆模型 (将废弃)
├── TemporalProps.java          — 时间属性模型 (将废弃)
├── EmbeddingModel.java         — 嵌入模型接口 (保留概念，重新设计)
├── MemoryExtractor.java        — 记忆提取器 (将废弃)
├── MemoryConsolidator.java     — 记忆巩固器 (将废弃)
├── MemoryRetriever.java        — 记忆检索器 (保留概念，重新设计)
├── FusionRanker.java           — 融合排序器 (保留概念，重新设计)
├── MemoryJanitor.java          — 记忆清理器 (将废弃)
├── MemoryPersistence.java      — 持久化接口 (将废弃)
├── MemoryWriteState.java       — 写入状态 (将废弃)
└── (其余配置/枚举/异常类)       — (将废弃)
```

### 新记忆系统规划文件

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/memory/ (重新设计后)
├── MemorySystem.java              — 新顶层接口（简化，可插拔）
├── InstructionMemory.java         — 指令记忆（类似CLAUDE.md注入）
├── KnowledgeBase.java             — 知识库RAG接口
├── AgentMemory.java               — Agent个性记忆接口
├── DocumentIngestionPipeline.java — 文档摄入管道
├── ChunkingStrategy.java          — 分块策略
├── EmbeddingService.java          — 嵌入服务
├── VectorStore.java               — 向量存储接口
├── RetrievalService.java          — 检索+重排序服务
└── MemoryFlushHook.java           — CompactionEngine的记忆写入钩子
```

### Agent相关核心文件

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/
├── annotation/Agent.java               — @Agent注解定义（27字段）
├── config/ResolvedAgentConfig.java     — 三层合并配置
├── config/AgentConfigResolver.java     — 配置解析器
├── config/AgentSystemDefaults.java     — 系统默认值
├── react/AgentProxyFactory.java        — 动态代理工厂
├── react/AgentInvocationHandler.java   — 调用处理器（第205-208行TODO）
├── react/subagent/
│   ├── DelegateToAgentToolProvider.java — 委派工具提供者
│   ├── SubagentSpawner.java            — 子Agent生成器
│   └── SubagentConfig.java             — 子Agent配置
└── persistence/sqlite/
    └── SqliteMigrationService.java      — 数据库迁移（agents表24列→36列）

lyclaw-web/src/main/java/lyjew/com/lyclaw/web/agent/
└── ChatAgent.java                       — 唯一的@Agent接口（CRUD完成后不再唯一）

lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/
└── autoconfigure/AgentProxyAutoConfiguration.java  — Spring自动配置
```

### 设计文档

```
docs/agent-renovation/
├── 06-renovation-phase2-subagent-models.md    — Phase 2 (2814行)
├── 07-renovation-phase3-context-bootstrap.md  — Phase 3 (2814行)
└── 08-renovation-phase4-streaming-gateway.md  — Phase 4 (2677行)

design/
└── 多agent编排设计.md                          — 本文档 (v2.0)
```

---

> **文档状态**: 重新论证完成（v2.0），待讨论确认后进入实现阶段。
> **核心变更**: 记忆系统从"实现既有接口(#1)"变为"重新设计(#4)"；Agent CRUD从#2升为#1；编排雏形从#3升为#2；Phase 3拆分为3a(#3)+3b(#5)。
> **下一步**: 基于本文档讨论具体的实现细节和分工。优先启动Agent CRUD的数据库迁移和后端API实现。
> **维护者**: LyClaw开发团队
> **最后更新**: 2026-05-22
