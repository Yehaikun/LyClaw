# Agent编排协作调研报告：OpenClaw源码分析 + 业界模式 + Harness集成思路

> 创建日期: 2026-05-22
> 调研范围: OpenClaw npm源码、LangGraph/CrewAI/AutoGen框架对比、Harness AI Platform
> 目的: 为LyClaw多Agent编排（CRUD → 雏形 → 完整编排）提供设计参考

---

## 目录

1. [OpenClaw源码深度分析](#1-openclaw源码深度分析)
2. [业界多Agent编排框架对比](#2-业界多agent编排框架对比)
3. [Harness AI Platform与Agent编排](#3-harness-ai-platform与agent编排)
4. [对LyClaw编排设计的启示](#4-对lyclaw编排设计的启示)
5. [具体设计建议](#5-具体设计建议)

---

## 1. OpenClaw源码深度分析

### 1.1 源码结构概览

OpenClaw npm包位于 `/home/lyjew/.npm-global/lib/node_modules/openclaw`，源码完整checkout位于 `/home/lyjew/.claude/jobs/1f6253d5/openclaw` 是一个TypeScript大型项目（单体仓库）。

```
src/
├── agents/          ← 核心！Agent管理、子Agent生成、Bootstrap、配置
│   ├── subagent-spawn.ts           — 子Agent生成入口（800+行）
│   ├── subagent-registry.ts        — 子Agent注册表（状态管理、持久化、恢复）
│   ├── subagent-system-prompt.ts   — 子Agent系统提示词构建
│   ├── subagent-target-policy.ts   — 子Agent目标策略（允许列表）
│   ├── subagent-depth.ts           — 生成深度控制
│   ├── subagent-attachments.ts     — 附件传递
│   ├── subagent-control.ts         — 子Agent控制（steer/kill）
│   ├── subagent-announce.ts        — 子Agent结果回报机制
│   ├── compaction.ts               — 上下文压缩
│   ├── bootstrap-files.ts          — Bootstrap文件加载
│   ├── context.ts                  — 上下文管理
│   ├── harness/                    — Agent生命周期钩子系统
│   └── sandbox/                    — 沙箱执行
├── tasks/          ← 任务编排引擎
│   ├── task-flow-registry.ts       — 任务流注册表
│   ├── task-registry.ts            — 任务注册表
│   └── task-executor.ts            — 任务执行器
├── flows/          ← 工作流编排
│   ├── doctor-repair-flow.ts       — 自动修复工作流
│   └── provider-flow.ts            — 模型提供者切换流
├── tools/          ← 工具抽象层
│   ├── descriptors.ts              — 工具描述符
│   ├── planner.ts                  — 工具计划构建
│   └── availability.ts             — 工具可见性控制
├── context-engine/ ← 上下文引擎抽象
│   ├── types.ts                    — ContextEngine接口定义
│   ├── registry.ts                 — 引擎注册表
│   └── delegate.ts                 — 委托压缩到运行时
├── hooks/          ← 钩子系统（插件化扩展点）
├── sessions/       ← 会话管理
├── memory/         ← 记忆系统（轻量）
├── channels/       ← 多渠道路由
└── gateway/        ← API网关
```

### 1.2 核心编排模型：Hub-and-Spoke（中心辐射）

OpenClaw最核心的编排模式是**Hub-and-Spoke**（中心辐射），这是其多Agent架构的基石：

```
                    Telegram / Discord / Slack / Web
                              │
                              ▼
                    ┌─────────────────┐
                    │  Orchestrator   │  主Agent（唯一对外暴露）
                    │   (Primary)     │  有记忆、上下文、用户感知
                    └───────┬─────────┘
                            │ sessions_spawn
                ┌───────────┼───────────┐
                ▼           ▼           ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │  Coder   │ │Researcher│ │  Writer  │
        │ (Docker) │ │ (Docker) │ │ (Docker) │
        └──────────┘ └──────────┘ └──────────┘

关键特征:
  - Orchestrator是唯一对外暴露的Agent，接收所有用户消息
  - 子Agent在Docker沙箱中运行，无记忆、无用户上下文
  - 委托通过sessions_spawn工具完成
  - 采用push-based异步回报机制（子Agent完成后自动推送结果）
```

### 1.3 三层编排体系

OpenClaw的编排体系由三个独立但可组合的层次构成：

| 层次 | 机制 | 解决的问题 | 对应代码 |
|------|------|-----------|---------|
| **Bindings路由** | 按渠道/Peer分发消息给不同Agent | "谁对外接活" | `channels/`, `routing/` |
| **Sub-agents** | `sessions_spawn` 后台拉起子任务 | "谁在后台干活" | `agents/subagent-spawn.ts` |
| **Agent-to-Agent** | `sessions_send` 结构化消息 | "两个会话如何通信" | `sessions/` |

**Bindings路由** — 类似于LyClaw的AgentSelector，但更底层：
```
用户消息到达 → 根据渠道+Peer信息 → 路由到对应Agent会话
例如: Telegram群组A → Agent "assistant"；Telegram群组B → Agent "coder"
```

**Sub-agents** — 核心委派机制，类似于LyClaw的delegate_to_agent：
```typescript
// sessions_spawn工具参数（来自subagent-spawn.ts第127-150行）
type SpawnSubagentParams = {
  task: string;           // 任务描述（必填）
  label?: string;         // 显示标签
  agentId?: string;       // 指定目标Agent（可选，默认自动选择）
  model?: string;         // 模型覆盖
  thinking?: string;      // 思考模式
  mode?: "isolated" | "fork";  // 上下文模式
  cleanup?: "delete" | "keep"; // 完成后清理策略
  sandbox?: "off" | "docker";  // 沙箱模式
  attachments?: Array<{name, content, encoding, mimeType}>; // 附件
  runTimeoutSeconds?: number;    // 超时
};
```

**Agent-to-Agent** — 会话间通信：
- `sessions_send`: 向另一个会话发送结构化消息
- `sessions_yield`: 挂起当前Agent等待子Agent结果（避免轮询！）

### 1.4 子Agent生成流程（核心代码路径）

```
sessions_spawn工具调用
  │
  ▼
subagent-spawn.ts → spawnSubagent()
  │
  ├─ 1. 验证与策略检查
  │   ├─ resolveSubagentTargetPolicy() — 检查allowAgents白名单
  │   ├─ getSubagentDepthFromSessionStore() — 检查深度限制
  │   └─ countActiveRunsForSession() — 检查并发限制
  │
  ├─ 2. 准备会话上下文
  │   ├─ prepareSubagentSessionContext()
  │   │   ├─ mode="fork": 继承父Agent的transcript（forkSessionFromParent）
  │   │   └─ mode="isolated": 干净transcript，仅含task消息
  │   └─ materializeSubagentAttachments() — 处理附件
  │
  ├─ 3. 构建系统提示词
  │   └─ buildSubagentSystemPrompt() — 注入子Agent规则:
  │       - "你是子Agent，不是主Agent"
  │       - "完成指定任务，不要做其他事"
  │       - "不要主动发起心跳、cron等"
  │       - "你的结果会自动回报给父Agent"
  │
  ├─ 4. 创建子Agent会话
  │   ├─ forkSessionFromParent() — 创建子会话
  │   ├─ 设置 spawnDepth = parentDepth + 1
  │   ├─ 设置 subagentRole = childDepth < maxDepth ? "orchestrator" : "leaf"
  │   ├─ 设置 spawnedBy = parentSessionKey
  │   └─ 继承 inheritedToolAllow/Deny 列表
  │
  ├─ 5. 注册到Registry
  │   └─ registerSubagentRun() — 记录到subagentRuns (内存Map)
  │       └─ persistSubagentRunsToDisk() — 持久化到磁盘（崩溃恢复）
  │
  ├─ 6. 执行子Agent
  │   └─ callGateway("agent", {sessionKey: childKey}) — 通过Gateway调用
  │       └─ Gateway → PI Embedded Runner → LLM + Tools
  │
  └─ 7. 结果回报（push-based）
      └─ announce流程:
          ├─ captureSubagentCompletionReply() — 捕获子Agent最终回复
          └─ runSubagentAnnounceFlow() — 推送给父Agent会话
```

### 1.5 子Agent注册表（SubagentRegistry）设计

```typescript
// 来自 subagent-registry.ts 和 subagent-registry.types.ts

// 子Agent运行记录
type SubagentRunRecord = {
  runId: string;               // 唯一运行ID
  childSessionKey: string;     // 子会话Key
  requesterSessionKey: string; // 请求方会话Key
  requesterOrigin?: DeliveryContext; // 请求来源上下文
  status: "running" | "completed" | "error" | "killed";
  spawnDepth: number;          // 生成深度
  subagentRole: "leaf" | "orchestrator";
  startedAt: number;           // 启动时间戳
  endedAt?: number;            // 结束时间戳
  taskName?: string;           // 任务名称
  model?: string;              // 使用的模型
};

// 核心注册表操作
- subagentRuns: Map<string, SubagentRunRecord> — 内存存储
- persistSubagentRunsToDisk() — 持久化到JSON文件（崩溃恢复）
- restoreSubagentRunsFromDisk() — 启动时恢复
- countActiveRunsForSession() — 计数活跃子Agent
- countActiveDescendantRunsFromRuns() — 计数后代子Agent
- reconcileOrphanedRuns() — 恢复孤儿进程

// 关键设计: 内存Map + 磁盘持久化双写
// 重启时从磁盘恢复，reconcile孤儿进程（进程可能仍存活）
```

### 1.6 上下文引擎（ContextEngine）抽象

```typescript
// 来自 context-engine/types.ts

interface ContextEngine {
  // 组装上下文：将session历史+ bootstrap + 记忆 → LLM-ready消息列表
  assemble(params: AssembleParams): Promise<AssembleResult>;
  
  // 压缩上下文：超过token预算时自动压缩
  compact(params: CompactParams): Promise<CompactResult>;
  
  // 引导初始化：首次加载时导入历史消息
  bootstrap(params: BootstrapParams): Promise<BootstrapResult>;
  
  // 摄入消息：新消息到达时记录
  ingest(params: IngestParams): Promise<IngestResult>;
}

// 引擎是插件化的：
// - 内置引擎: 基于transcript文件的默认实现
// - 第三方引擎: 可通过Plugin SDK注册自定义引擎
// - 委托压缩: delegateCompactionToRuntime() 桥接到内置压缩
```

**对LyClaw的启示**：LyClaw已经有5 Stage Pipeline（PreTool → SystemMessage → Tool → PostTool → Response），但缺少统一的ContextEngine抽象。ContextEngine提供了一个"上下文即服务"的视角，值得引入。

### 1.7 工具系统设计

```typescript
// 来自 tools/types.ts — 工具描述符

type ToolDescriptor = {
  name: string;                  // 工具名称
  description: string;           // 描述（给LLM看）
  schema: JsonObject;            // JSON Schema参数定义
  executor: ToolExecutorRef;     // 执行器引用
  owner: ToolOwnerRef;           // 所有者（plugin/core/system）
  availability?: ToolAvailabilityExpression; // 可见性条件表达式
};

// 可见性控制（ToolAvailabilityExpression）:
// 支持表达式: "agent:subagent", "!agent:subagent", "plugin:foo"
// 示例: sessions_spawn仅在subagentRole="orchestrator"时可见
//      sessions_list仅在非子Agent时可见

// 工具策略管道（tool-policy-pipeline.ts）:
// 1. 全局策略 (tool-policy.ts)
// 2. Agent级allow/deny列表 (inherited-tool-deny.ts)
// 3. 插件级策略 (plugin-tool-delivery-defaults.ts)
// 4. 会话级覆盖 (session-tool-result-guard.ts)
```

**对LyClaw的启示**：当前LyClaw的ToolProvider只做了简单的全局开关（isSubagentEnabled）。OpenClaw的工具可见性表达式系统更灵活——可以基于Agent角色(主Agent vs 子Agent)、插件上下文、会话覆盖 逐层控制。

### 1.8 Harness钩子系统（生命周期扩展点）

OpenClaw的 `agents/harness/` 目录定义了一套完整的Agent生命周期钩子：

```typescript
// 来自 agents/harness/types.ts 和 lifecycle-hook-helpers.ts

// Agent生命周期钩子
- beforeAgentStart:    Agent启动前 → 初始化资源、注入配置
- afterAgentStart:     Agent启动后 → 注册监听器
- beforeAgentReply:    回复生成前 → 注入上下文、修改prompt
- afterAgentReply:     回复生成后 → 后处理、记录日志
- beforeToolCall:      工具调用前 → 权限检查、参数修改
- afterToolCall:       工具调用后 → 结果处理、审计
- onCompaction:        压缩时 → 提取关键信息到记忆
- onSessionEnd:        会话结束时 → 清理资源

// 钩子上下文携带:
- agentId, sessionKey, 当前消息, 工具调用信息
- 插件状态 (plugin state)
- 运行时配置
```

### 1.9 关键设计原则总结（来自OpenClaw源码）

从源码中提炼出以下设计原则：

```
原则1: Push-based结果回报，严禁轮询
  子Agent完成后自动推送结果给父Agent。
  代码证据: subagent-system-prompt.ts第79行明确告知子Agent:
  "Auto-announce is push-based. After spawning children, do NOT call 
   sessions_list, sessions_history, exec sleep, or any polling tool."
  替代方案: sessions_yield — 挂起等待结果到达

原则2: 子Agent无状态，临时性强
  子Agent没有MEMORY.md, HEARTBEAT.md, BOOTSTRAP.md
  子Agent不知道用户是谁
  子Agent只做被分配的任务，不做任何"主动"行为
  代码证据: subagent-system-prompt.ts第48-66行

原则3: 深度限制+角色分层
  spawnDepth=1 → leaf (不能再spawn)
  spawnDepth < maxSpawnDepth → orchestrator (可以再spawn)
  maxSpawnDepth默认1，调至2+启用嵌套编排
  代码证据: subagent-depth.ts

原则4: 上下文模式可配置
  "isolated" — 子Agent只有task消息，干净隔离
  "fork" — 子Agent继承父Agent的transcript（更多上下文但更多token消耗）
  代码证据: subagent-spawn.ts prepareSubagentSessionContext()

原则5: 工具可见性逐层收窄
  子Agent的工具集应比主Agent更窄
  默认屏蔽: sessions_list, sessions_history, sessions_spawn (非orchestrator)
  代码证据: tool-allowlist-guard.ts, inherited-tool-deny.ts

原则6: 优雅降级与崩溃恢复
  subagentRuns持久化到磁盘
  启动时恢复+reconcile孤儿进程
  代码证据: subagent-registry-state.ts, subagent-registry-helpers.ts
```

---

## 2. 业界多Agent编排框架对比

### 2.1 三大框架概览

| 维度 | LangGraph | CrewAI | AutoGen |
|------|-----------|--------|---------|
| **编排模型** | 图状态机（显式边） | 角色层级（Manager+Worker） | 对话驱动（GroupChat） |
| **委托方式** | Supervisor→Worker via Command/Send | Manager显式分配Task | 对话轮次隐式选择 |
| **状态管理** | Checkpoint持久化 | 短期/长期/实体记忆 | GroupChat transcript |
| **并行性** | 原生fan-out+fan-in | Crew内+跨Crew并行 | 顺序为主 |
| **人机协作** | interrupt()/resume | 审批门 | UserProxyAgent |
| **生产就绪度** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ |
| **学习曲线** | 陡峭（图论+状态） | 中等（角色定义） | 中高（对话设计） |

### 2.2 Orchestrator-Worker模式（行业共识）

2025年，三大框架的委托模式正在收敛到**Orchestrator-Worker**模式：

```
Orchestrator (编排器)
  │
  ├─ 分析任务 → 拆解为子任务
  ├─ 选择Worker → 根据能力匹配
  ├─ 分配子任务 → 传递上下文
  ├─ 收集结果 → Worker完成后回报
  └─ 合成最终回答

Worker (执行器)
  ├─ 接收明确的子任务
  ├─ 使用分配的工具集
  ├─ 完成后回报结果
  └─ 不做主动行为
```

三种框架的区别仅在于**实现方式**：

| 框架 | 如何实现Orchestrator-Worker |
|------|---------------------------|
| **LangGraph** | 显式图边：`add_conditional_edges("supervisor", route_fn, {"worker_a": ..., "worker_b": ...})` |
| **CrewAI** | 声明式角色：`Agent(role="Manager")` + `Crew(process=Process.hierarchical)` |
| **AutoGen** | 对话式：`GroupChat(agents=[manager, worker_a, worker_b], speaker_selection_method="auto")` |

### 2.3 委托模式对比

| 维度 | 显式委托（LangGraph） | 声明式委托（CrewAI） | 对话式委托（AutoGen） |
|------|---------------------|-------------------|---------------------|
| **控制粒度** | 细（每步可控） | 中（声明意图） | 粗（LLM决定） |
| **可预测性** | 高 | 高 | 低 |
| **灵活性** | 低（需预定义图） | 中 | 高 |
| **调试难度** | 中等 | 低 | 高 |
| **适用场景** | 确定性工作流 | 结构化任务 | 探索性任务 |

### 2.4 对LyClaw的建议

**选择显式委托模式（类似LangGraph + OpenClaw）**

理由：
1. LyClaw已有5 Stage Pipeline（相当于LangGraph的图节点）
2. LyClaw的`delegate_to_agent`已是显式工具调用（非对话隐式）
3. OpenClaw也是显式模式（sessions_spawn工具+SubagentSpawner）
4. Java后端更适合确定性工作流（类型安全 + 编译时检查）

**委托实现建议**：
```
Orchestrator Agent (delegation_mode="auto")
  │
  ├─ 分析任务 → AgentRouter.match(taskType, extensions)
  ├─ 匹配Worker → 从DB获取delegation_mode!="none"的Agent列表
  ├─ delegate_to_agent(target, task, context) → SubagentSpawner.spawnSubagent()
  ├─ 等待结果 → push-based announce (或blocking await for MVP)
  └─ 合成回答 → 整合所有子Agent结果
```

---

## 3. Harness AI Platform与Agent编排

### 3.1 Harness的核心架构理念

Harness在2025年从CI/CD平台演进为**Agent驱动的软件交付平台**，其架构理念与我们的多Agent编排高度相关：

```
Harness AI Platform 三层架构:

┌─────────────────────────────────────────────────────┐
│                  AI Agent Matrix                     │
│  (专业Agent: DevOps/SRE/Security/Testing/FinOps)     │
├─────────────────────────────────────────────────────┤
│           Workflow Orchestration Layer               │
│  (任务拆解 → 单步组件 → 分配Agent → 收集结果)        │
├─────────────────────────────────────────────────────┤
│               Knowledge Graph                        │
│  (基于DevSecOps SaaS管道数据的上下文感知知识图谱)     │
└─────────────────────────────────────────────────────┘
```

### 3.2 Harness Delegate模式（关键！可融入LyClaw编排）

Harness的**Delegate**是一种轻量级Worker进程模式，非常适合融入LyClaw的Agent编排：

```
Delegate核心特征:
  ├─ Outbound-only通信 — 只向外连接Harness Manager，不需要入站端口
  ├─ 部署在用户基础设施 — 在自己的K8s/VM中运行
  ├─ 执行所有重活 — 构建、部署、拉取、解密
  └─ 安全原则 — 密钥永不离开用户网络

对LyClaw的适配:
  LyClaw Delegate = 子Agent执行器
  ├─ 子Agent在隔离环境执行（Docker沙箱 → Phase 4）
  ├─ 子Agent通过outbound Gateway与父Agent通信
  ├─ 父Agent（Manager）分配任务，不直接访问子Agent环境
  └─ 密钥/敏感信息不离开子Agent的执行环境
```

### 3.3 Harness的递归Agent执行流

Harness在2025年提出的**递归Agent执行流（Recursive Agentic Execution Flow）**：

```
用户输入: "部署新版本到生产环境"
  │
  ▼
Orchestrator Agent:
  ├─ 步骤1: 安全性扫描 → delegate to Security Agent
  │   └─ Security Agent → 结果: "通过，无高危漏洞"
  ├─ 步骤2: 构建 → delegate to DevOps Agent  
  │   └─ DevOps Agent → 结果: "构建成功，镜像: app:v2.1.0"
  ├─ 步骤3: 部署 → delegate to DevOps Agent
  │   └─ DevOps Agent → 结果: "Canary部署完成，流量10%"
  ├─ 步骤4: 验证 → delegate to Testing Agent
  │   ├─ Testing Agent 发现异常 → 自动子委托 FinOps Agent
  │   └─ FinOps Agent → 结果: "成本增加3%，在预算内"
  └─ 步骤5: 全量发布 → delegate to DevOps Agent
      └─ DevOps Agent → 结果: "全量发布完成，100%流量"

关键特征:
  - 每步分解为单步组件
  - 每步委托给专门的Agent
  - Agent可以递归委托（Testing Agent → FinOps Agent）
  - 每步结果被Orchestrator收集和验证
```

### 3.4 对LyClaw编排的启示

```
启示1: Knowledge Graph → LyClaw的记忆系统+知识库
  Harness的Knowledge Graph基于管道数据。LyClaw的对应物:
  - 知识库(RAG): 项目文档、编码规范、历史决策
  - Agent个性记忆: 每个Agent的执行历史、成功模式
  - 会话记忆: 当前委派链的上下文

启示2: Delegate → LyClaw的子Agent隔离执行
  Harness的Delegate在用户基础设施上outbound-only通信。
  LyClaw的子Agent（Phase 4 Sandbox）可以类似设计:
  - 子Agent在独立进程/容器中执行
  - 通过Gateway与父Agent通信
  - 父Agent不直接访问子Agent的文件系统

启示3: 递归执行流 → LyClaw的委派链
  Harness的递归执行（Agent → Agent → Agent）与OpenClaw的
  maxSpawnDepth嵌套完全对应。LyClaw已有SubagentSpawner
  和maxSpawnDepth，需要的是:
  - 严格深度限制检查（已在设计中）
  - Push-based结果回报（替代当前blocking wait）
  - 委派链监控与可视化（前端展示委派树）

启示4: 评估Agent → LyClaw的质量保障
  Harness使用专门的评估Agent验证其他Agent的输出质量。
  LyClaw可以考虑:
  - 结果校验Agent: 验证子Agent的输出是否满足要求
  - 质量评分: 子Agent任务完成度的自动评分
  - 反馈循环: 低质量结果触发重新委派（不同Agent或调整参数）
```

---

## 4. 对LyClaw编排设计的启示

### 4.1 当前LyClaw编排 vs OpenClaw vs Harness 对比

```
┌─────────────────────────────────────────────────────────────────────┐
│              三系统编排能力对比                                       │
├──────────────┬─────────────────┬─────────────────┬──────────────────┤
│ 能力          │ LyClaw (当前)    │ OpenClaw         │ Harness AI       │
├──────────────┼─────────────────┼─────────────────┼──────────────────┤
│ Agent定义     │ @Agent注解+DB   │ YAML配置+文件    │ 平台内置+自定义   │
│ Agent注册     │ Spring Bean+    │ 文件系统扫描     │ 平台注册          │
│              │ DynamicRegistry │                 │                  │
│ 子Agent委派   │ delegate_to     │ sessions_spawn  │ Recursive        │
│              │ _agent (95%)    │ (push-based)    │ Agentic Flow     │
│ 上下文模式    │ 仅task描述      │ isolated/fork   │ Knowledge Graph  │
│ 深度限制      │ maxSpawnDepth   │ maxSpawnDepth   │ 递归深度控制      │
│              │ (已定义未强制)   │ (默认1, 最大5)  │                  │
│ 结果回报      │ Blocking wait   │ Push-based      │ 事件驱动          │
│              │ (当前实现)       │ announce        │                  │
│ 工具可见性    │ 全局开关        │ 表达式系统      │ 角色权限          │
│              │ (粒度粗)        │ (细粒度)        │                  │
│ 沙箱执行      │ 无              │ Docker隔离      │ Delegate隔离     │
│ 崩溃恢复      │ 无              │ 磁盘持久化+     │ 管道状态持久化    │
│              │                 │ reconcile       │                  │
│ 记忆系统      │ 待重新设计      │ MEMORY.md文件   │ Knowledge Graph  │
│ 任务编排      │ 无              │ TaskFlow        │ Pipeline引擎     │
│ 钩子系统      │ 无              │ Harness钩子     │ 管道钩子          │
└──────────────┴─────────────────┴─────────────────┴──────────────────┘
```

### 4.2 OpenClaw可借鉴的具体设计（按优先级排序）

**高优先级（应在编排雏形Phase中实现）：**

```
1. Push-based结果回报 → 替代当前Blocking Wait
   当前: SubagentSpawner.spawnSubagent() blocking等待子Agent完成
   改进: 子Agent完成后通过事件回调推送结果给父Agent
   好处: 
     - 父Agent不阻塞，可以继续处理其他任务
     - 支持超时和中断
     - 与Phase 4的Streaming自然集成
   实现: 
     - DelegateToAgentToolProvider.execute()返回Future/Promise
     - AgentInvocationHandler在子Agent完成事件中resume

2. 细粒度工具可见性 → 替代全局isSubagentEnabled
   当前: 所有Agent看到相同的delegate_to_agent（或都看不到）
   改进: 
     - 主Agent: 可见delegate_to_agent + sessions_list + sessions_history
     - Orchestrator子Agent (depth < max): 可见delegate_to_agent + subagents
     - Leaf子Agent: 不可见delegate_to_agent（不能委派）
   实现:
     - 扩展ToolProvider接口: getVisibility(agentContext) → boolean
     - AgentInvocationHandler根据角色过滤工具

3. 子Agent系统提示词增强
   当前: 子Agent的systemPrompt可能是通用的或空的
   改进: 参考OpenClaw的buildSubagentSystemPrompt()
     - "你是子Agent"的明确身份声明
     - "只做分配的任务"的行为约束
     - "结果会自动回报"的协议说明
     - "不要轮询等待"的反模式警告
```

**中优先级（Phase 3a完成后）：**

```
4. ContextEngine抽象 → 统一上下文管理
   当前: 5 Stage Pipeline各自处理上下文
   改进: ContextEngine接口:
     - assemble(): 组装LLM-ready上下文
     - compact(): 上下文压缩（Phase 3b）
     - bootstrap(): 项目初始化加载
     - ingest(): 消息摄入记录
   好处: 插件化，第三方可扩展

5. TaskFlow编排 → 复杂多步骤委派
   当前: 只有单层delegate_to_agent
   改进: 
     - TaskFlowRecord: { goal, steps, status, blockedBy, results }
     - TaskExecutor: 按DAG依赖顺序执行步骤
     - 步骤间数据传递
   场景: "分析PR → 审查代码 → 生成测试 → 写文档" 作为编排流
```

**低优先级（新记忆系统+Phase 4完成后）：**

```
6. 崩溃恢复 → 子Agent持久化
   当前: 子Agent状态纯内存
   改进: 
     - subagentRuns持久化到SQLite
     - 启动时restore+reconcile孤儿
     - 子Agent超时自动kill+清理

7. Agent间消息队列 → 异步通信
   参考OpenClaw的messages.queue.mode: "collect"
   当多个子Agent同时向父Agent发送结果时，排队合并
```

### 4.3 Harness Delegate模式如何融入LyClaw

```
LyClaw Delegate概念:

┌─────────────────────────────────────────────────────────────────┐
│                        LyClaw Manager                            │
│  (主Agent + AgentRouter + TaskFlow编排)                          │
│  职责: 接收用户输入、拆解任务、分配子Agent、收集结果、合成回答    │
└──────────────────────────┬──────────────────────────────────────┘
                           │ Gateway (HTTPS outbound-only)
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  Delegate A │  │  Delegate B │  │  Delegate C │
│ (CodeReview)│  │ (TestWriter)│  │ (DocWriter) │
│ Sandbox:    │  │ Sandbox:    │  │ Sandbox:    │
│ Docker      │  │ Docker      │  │ Docker      │
│ java:21     │  │ java:21     │  │ node:22     │
├─────────────┤  ├─────────────┤  ├─────────────┤
│ 工具集:     │  │ 工具集:     │  │ 工具集:     │
│ - read      │  │ - read      │  │ - read      │
│ - grep      │  │ - write     │  │ - write     │
│ - bash      │  │ - bash      │  │ - bash      │
│ (不能委派)  │  │ (不能委派)  │  │ (不能委派)  │
└─────────────┘  └─────────────┘  └─────────────┘

安全原则（参考Harness）:
  - Manager不直接访问Delegate的文件系统
  - Delegate只通过Gateway与Manager通信（outbound-only）
  - 密钥/API Key不离开Delegate环境
  - Delegate执行完毕后自动清理（或保留供检查）
```

---

## 5. 具体设计建议

### 5.1 编排雏形的增强设计（融入调研成果）

基于上述调研，LyClaw编排雏形应包含以下增强：

```
Phase C: 子Agent编排雏形（6天 → 建议扩展为8天）

C1: per-agent delegation控制（1天 — 保持不变）
  └─ 修复 AgentInvocationHandler TODO
     delegation_mode + allowAgents → 工具可见性

C2: Subagent系统提示词构建（1天 — 新增！参照OpenClaw）
  └─ SubagentPromptBuilder:
     - 注入子Agent角色声明
     - 注入行为约束（不主动、不越权、不轮询）
     - 注入结果回报协议
     - 根据depth决定是否允许再委派

C3: 委派结果回报机制（1天 — 增强）
  └─ 从blocking wait → CompletableFuture + 事件回调
     - SubagentCompletionEvent
     - 超时处理（runTimeoutSeconds）
     - 父Agent的onSubagentComplete钩子

C4: AgentRouter雏形（1天 — 保持不变）
  └─ extensions匹配 + 关键词匹配

C5: TemplateAgentFactory（1天 — 保持不变）
  └─ 模板库 + 派生逻辑

C6: 委派链监控与安全（1天 — 增强）
  └─ 参照OpenClaw增加:
     - 循环检测（targetAgentId != parentAgentId）
     - 继承工具Allow/Deny列表（inheritedToolAllow/Deny）
     - 子Agent工具默认屏蔽: sessions_list, sessions_spawn (leaf)
     - 并发限制（maxChildrenPerAgent严格检查）

C7: TemporaryAgentLifecycle（1天 — 保持不变）
  └─ 创建→执行→结果提取→销毁

C8: 委派链可视化（1天 — 新增）
  └─ 前端展示:
     - 当前会话的委派树（Agent A → Agent B → Agent C）
     - 每个子Agent的状态（running/completed/error）
     - 子Agent的执行时长和结果摘要
```

### 5.2 ContextEngine接口设计（供Phase 3a参考）

```java
// 参照OpenClaw的ContextEngine设计的LyClaw版本

public interface ContextEngine {
    
    /**
     * 组装LLM-ready上下文。
     * 将session历史 + bootstrap文件 + 知识库检索 + 记忆 → 消息列表
     */
    AssembleResult assemble(AssembleParams params);
    
    /**
     * 压缩上下文。超过token预算时触发。
     * @return CompactResult 包含压缩摘要和token前后对比
     */
    CompactResult compact(CompactParams params);
    
    /**
     * 引导初始化。首次加载时导入项目上下文。
     */
    BootstrapResult bootstrap(BootstrapParams params);
    
    /**
     * 摄入新消息。
     */
    IngestResult ingest(IngestParams params);
    
    // ---- 嵌套类型 ----
    
    record AssembleParams(
        String sessionId,
        String agentId,
        List<Message> sessionHistory,
        BootstrapContext bootstrapContext,  // 项目文件树+内容
        List<MemoryEntry> memoryEntries,     // 知识库检索结果
        int tokenBudget
    ) {}
    
    record AssembleResult(
        List<Message> messages,       // LLM就绪的消息列表
        int estimatedTokens,          // 估算token数
        String systemPromptAddition   // 引擎附加的系统提示词
    ) {}
    
    record CompactParams(
        String sessionId,
        String sessionFile,
        int tokenBudget,
        int currentTokenCount
    ) {}
    
    record CompactResult(
        boolean ok,
        boolean compacted,
        String summary,               // 压缩摘要
        int tokensBefore,
        int tokensAfter
    ) {}
}

// 引擎注册表（插件化）
public interface ContextEngineRegistry {
    void register(String engineId, ContextEngine engine);
    ContextEngine resolve(String agentId);  // 按Agent选择引擎
    ContextEngine getDefault();
}
```

### 5.3 工具可见性表达式设计

```java
// 参照OpenClaw的ToolAvailabilityExpression

public enum AgentRole {
    MAIN,           // 主Agent（直接面向用户）
    ORCHESTRATOR,   // 编排器子Agent（可以再委派）
    LEAF            // 叶子子Agent（不能委派）
}

public record ToolVisibilityContext(
    AgentRole agentRole,
    int spawnDepth,
    int maxSpawnDepth,
    String agentId,
    List<String> allowAgents
) {}

// 工具可见性策略
public interface ToolVisibilityPolicy {
    boolean isVisible(ToolDescriptor tool, ToolVisibilityContext ctx);
}

// 内置策略实现
public class SubagentToolVisibilityPolicy implements ToolVisibilityPolicy {
    
    private static final Set<String> MAIN_ONLY_TOOLS = Set.of(
        "sessions_list", "sessions_history"
    );
    
    private static final Set<String> ORCHESTRATOR_TOOLS = Set.of(
        "delegate_to_agent", "subagent_steer", "subagent_kill"
    );
    
    @Override
    public boolean isVisible(ToolDescriptor tool, ToolVisibilityContext ctx) {
        String toolName = tool.getName();
        
        // 主Agent可以看到所有工具
        if (ctx.agentRole() == AgentRole.MAIN) {
            return true;
        }
        
        // MAIN_ONLY工具对子Agent不可见
        if (MAIN_ONLY_TOOLS.contains(toolName)) {
            return false;
        }
        
        // ORCHESTRATOR工具仅对orchestrator角色可见
        if (ORCHESTRATOR_TOOLS.contains(toolName)) {
            return ctx.agentRole() == AgentRole.ORCHESTRATOR;
        }
        
        // 默认：所有Agent可见
        return true;
    }
}
```

### 5.4 子Agent系统提示词构建器

```java
// 参照OpenClaw buildSubagentSystemPrompt()

public class SubagentPromptBuilder {
    
    public String buildPrompt(SubagentPromptParams params) {
        int childDepth = params.childDepth();
        int maxDepth = params.maxSpawnDepth();
        boolean canSpawn = childDepth < maxDepth;
        String parentLabel = childDepth >= 2 ? "父编排器" : "主Agent";
        
        StringBuilder sb = new StringBuilder();
        
        // 角色声明
        sb.append("# 子Agent上下文\n\n");
        sb.append("你是由").append(parentLabel).append("生成的**子Agent**，负责处理特定任务。\n\n");
        
        // 核心规则
        sb.append("## 规则\n");
        sb.append("1. **保持专注** — 只做分配给你的任务，不要做其他事\n");
        sb.append("2. **完成任务** — 你的最终回复将自动回报给").append(parentLabel).append("\n");
        sb.append("3. **不要主动** — 不发送心跳、不主动操作、不做额外的事\n");
        sb.append("4. **你是临时的** — 任务完成后可能被终止，这是正常的\n");
        sb.append("5. **不要轮询** — 结果会自动推送给你，不要busy-polling\n\n");
        
        // 输出格式
        sb.append("## 输出格式\n");
        sb.append("完成后，你的最终回复应包含:\n");
        sb.append("- 你完成了什么或发现了什么\n");
        sb.append("- ").append(parentLabel).append("需要知道的相关细节\n");
        sb.append("- 保持简洁但信息完整\n\n");
        
        // 不能做的事
        sb.append("## 你不能做的事\n");
        sb.append("- 不要与用户对话（这是").append(parentLabel).append("的职责）\n");
        sb.append("- 不要发送外部消息\n");
        sb.append("- 不要创建cron任务或持久状态\n");
        sb.append("- 不要假装你是").append(parentLabel).append("\n\n");
        
        // 如果允许再委派
        if (canSpawn) {
            sb.append("## 子Agent生成\n");
            sb.append("你可以使用 `delegate_to_agent` 生成你自己的子Agent来处理并行或复杂工作。\n");
            sb.append("给每个子Agent明确的目标、预期输出和相关输入。\n");
            sb.append("你的子Agent会自动将结果回报给你（而不是主Agent）。\n");
            sb.append("生成子Agent后，不要轮询等待结果。使用 `sessions_yield` 挂起等待。\n");
        }
        
        return sb.toString();
    }
}
```

### 5.5 修订后的实现优先级建议

综合调研结果，建议在之前论证的优先级基础上微调：

```
#1 Agent CRUD (7天) — 不变
   但增加: agents表的工具可见性配置字段
   - tool_allow_list TEXT DEFAULT '[]'
   - tool_deny_list TEXT DEFAULT '[]'
   理由: 参照OpenClaw的inheritedToolAllow/Deny，Agent粒度工具控制

#2 编排雏形 (6天→8天) — 扩展
   增加:
   - C2: Subagent系统提示词构建（1天）
   - C8: 委派链可视化（1天）
   理由: 调研发现这是OpenClaw的核心竞争力——子Agent明确知道自己的角色

#3 Phase 3a (4天) — 不变
   增加: ContextEngine接口定义（0.5天）
   理由: 统一上下文管理是OpenClaw的重要抽象

#4 新记忆系统+知识库 (12~15天) — 不变
   参考: Harness Knowledge Graph + OpenClaw MEMORY.md

#5 Phase 3b CompactionEngine (3天) — 不变
   增强: 压缩结果同步到知识库（RAG ingestion）

#6 Phase 4 Streaming (7天) — 不变
   增加: Sandbox Delegate模式（Harness启发）
```

### 5.6 关键差异：LyClaw独特设计选择

```
OpenClaw的做法                 LyClaw应该做的              原因
─────────────────────────────────────────────────────────────────
子Agent无记忆                子Agent有选择性继承记忆       LyClaw的记忆系统更先进(RAG)
子Agent在Docker沙箱          子Agent可Sandbox可选          初期MVP不强制Docker
纯push-based结果回报         MVP用blocking wait            简单优先，后期升级push-based
                                            (Phase 4再升级)
文件系统Agent定义             DB持久化+前端CRUD            用户友好，可视化
CLAUDE.md静态指令           指令文件表+RAG动态检索         更灵活，可语义搜索
MCP协议工具                   ToolProvider SPI              Java原生，类型安全
task_name随机                taskName友好可读               前端展示和调试更友好
```

---

## 附录A：OpenClaw关键文件索引

```
src/agents/subagent-spawn.ts            — 子Agent生成主逻辑（800+行）
src/agents/subagent-registry.ts         — 子Agent注册表（持久化+恢复）
src/agents/subagent-system-prompt.ts    — 子Agent系统提示词构建
src/agents/subagent-target-policy.ts    — 目标Agent允许列表策略
src/agents/subagent-depth.ts            — 生成深度控制
src/agents/subagent-control.ts          — 子Agent控制（steer/kill）
src/agents/subagent-announce.ts         — 子Agent结果push-based回报
src/agents/compaction.ts                — 上下文压缩
src/agents/bootstrap-files.ts           — Bootstrap文件加载
src/agents/harness/                     — 生命周期钩子系统
src/agents/sandbox/                     — 沙箱执行

src/tasks/task-flow-registry.ts         — 任务流注册表
src/tasks/task-registry.ts              — 任务注册表
src/tasks/task-executor.ts              — 任务执行器

src/context-engine/types.ts             — ContextEngine接口定义
src/context-engine/registry.ts          — 引擎注册表
src/context-engine/delegate.ts          — 委托压缩到运行时

src/tools/types.ts                      — 工具描述符+可见性表达式
src/tools/descriptors.ts                — 工具描述符构建器
src/tools/planner.ts                    — 工具计划构建

src/flows/doctor-repair-flow.ts         — 自动修复工作流
src/flows/provider-flow.ts              — 模型提供者切换流

src/sessions/                           — 会话管理
src/hooks/                              — 钩子系统
src/memory/root-memory-files.ts         — 记忆文件
src/channels/                           — 多渠道路由
```

## 附录B：调研信息来源

### OpenClaw源码
- 本地安装路径: `/home/lyjew/.npm-global/lib/node_modules/openclaw`
- 源码checkout: `/home/lyjew/.claude/jobs/1f6253d5/openclaw`
- GitHub: https://github.com/openclaw/openclaw

### Web资源
- OpenClaw多Agent编排完全指南: https://www.cnblogs.com/qiniushanghai/p/20049535
- OpenClaw协作模式文档: https://yeasy.gitbook.io/openclaw_guide
- ACP & Sub-Agents DeepWiki: https://deepwiki.com/openclaw/openclaw/3.8-acp-and-sub-agents
- 阿里云OpenClaw部署指南: https://developer.aliyun.com/article/1720334
- Harness AI Platform: https://www.techtarget.com/searchitoperations/news/366631493
- LangGraph vs CrewAI vs AutoGen: https://arize.com/blog/orchestrator-worker-agents
- CrewAI vs AutoGen Deep Dive: https://sparkco.ai/blog/crewai-vs-autogen-a-deep-dive

---

## 附录C：关键设计决策建议

### C.1 Blocking Wait vs Push-Based 的取舍

```
                    Blocking Wait (当前LyClaw)    Push-Based (OpenClaw)
─────────────────────────────────────────────────────────────────────
实现复杂度            低（同步调用）                高（事件驱动）
资源占用              高（线程阻塞）                低（异步等待）
用户体验              Agent"卡住"直到子Agent完成    Agent可以继续处理其他事务
超时处理              简单（Future.get(timeout)）   需要心跳+超时检测
崩溃恢复              依赖调用方处理                子Agent注册表持久化
多子Agent并发          需要多线程                    天然支持
前端展示              等待所有结果后一起显示         流式逐个展示

建议: MVP阶段保留Blocking Wait（简单可靠）
      到Phase 4升级为Push-Based（与Streaming自然集成）
```

### C.2 子Agent上下文传递策略

```
三种策略对比:

1. "全量Fork" (OpenClaw fork模式):
   子Agent继承父Agent的完整transcript → 上下文丰富但token消耗大
   适用: 子Agent需要理解完整对话背景

2. "仅任务描述" (LyClaw当前模式):
   子Agent只收到task参数 → token节省但可能缺少关键背景
   适用: 子任务独立性强，不需要对话历史

3. "选择性注入" (推荐!):
   智能选择父Agent上下文中与子任务相关的部分:
   - task描述（必传）
   - 相关代码片段（从当前会话中提取）
   - 相关项目文件（从BootstrapLoader获取）
   - 相关知识库条目（从RAG检索）
   - 父Agent的关键决策（从当前会话中提取）
   
   实现: ContextInjector (Phase 3a) + 知识库检索 (Phase 4)
```

### C.3 委派错误处理策略

```
错误类型              处理方式
─────────────────────────────────────────────────
目标Agent不存在        AgentRouter返回空 → 触发TemplateAgentFactory (或提示用户创建)
目标Agent不可委派      delegation_mode="none" → 返回明确错误消息给父Agent
超时                   按runTimeoutSeconds终止 → 父Agent收到超时通知
                         → 父Agent可以: (a)重试 (b)换Agent (c)自己处理
子Agent执行失败        捕获错误 → 分类:
                         - 可重试: 自动重试(最多N次)
                         - 不可重试: 返回错误+建议给父Agent
循环委派检测            spawnSubagent时检查targetAgentId != parentAgentId
                         AND 祖先链中没有出现过该Agent
深度超限                spawnDepth >= maxSpawnDepth → 拒绝委派
并发超限                activeChildren >= maxChildrenPerAgent → 排队或拒绝
```

### C.4 前端委派树可视化设计建议

```
建议在ChatView中展示当前会话的委派树:

┌─────────────────────────────────────────────────┐
│  🔄 Agent委派状态                                │
├─────────────────────────────────────────────────┤
│  orchestrator (主Agent)                          │
│  ├─ ✅ code_reviewer — 完成 (2.3s)               │
│  │  结果: 发现3个问题(1高2中)                     │
│  ├─ 🔄 test_writer — 运行中 (5.1s)               │
│  └─ ❌ doc_writer — 超时 (30.0s)                 │
│     建议: 重试 or 换markdown_writer              │
└─────────────────────────────────────────────────┘

实现:
  - 使用SubagentRunRegistry中的记录
  - 轮询或SSE推送状态更新
  - 可展开查看子Agent的详细输出
  - 支持手动kill/retry
```

---

> **文档状态**: 调研完成
> **核心发现**: 
>   1. OpenClaw的Hub-and-Spoke+三层编排(Bindings/Sub-agents/Agent-to-Agent)是成熟的参考模型
>   2. 行业共识收敛于Orchestrator-Worker模式(LangGraph/CrewAI/AutoGen)
>   3. Harness的Delegate+递归执行流可融入LyClaw沙箱设计
>   4. LyClaw编排雏形建议从6天扩展至8天，增加子Agent系统提示词构建和push-based回报
>   5. 上下文传递策略建议采用"选择性注入"（介于全量Fork和纯任务描述之间）
> **下一步**: 基于调研结果，调整Agent CRUD和编排雏形的具体实现计划
> **最后更新**: 2026-05-22
