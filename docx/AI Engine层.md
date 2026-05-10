# LyClaw AI 引擎层 — 完整架构设计文档

**Metadata**
- Date: 2026-04-27
- Author: 海坤
- Module: lyclaw-engine（新建模块）
- Version: v1.0 → v2.0 (持久化决策层)
- Status: 设计完成，部分已实现（持久化决策层）

---

## 目录

1. [整体架构概览](#第一章整体架构概览)
2. [设计原则与哲学](#第二章设计原则与哲学)
3. [模块拆分与职责边界](#第三章模块拆分与职责边界)
4. [设计模式全景图](#第四章设计模式全景图)
5. [核心接口设计](#第五章核心接口设计)
6. [Engine 顶层抽象设计](#第六章engine-顶层抽象设计)
7. [Pipeline 可编排管道设计](#第七章pipeline-可编排管道设计)
8. [ContextBuilder 上下文构建设计](#第八章contextbuilder-上下文构建设计)
9. [Interceptor 拦截器链设计](#第九章interceptor-拦截器链设计)
10. [ToolExecutor 工具执行设计](#第十章toolexecutor-工具执行设计)
11. [MemoryManager 记忆管理设计](#第十一章memorymanager-记忆管理设计)
12. [EventBus 事件总线设计](#第十二章eventbus-事件总线设计)
13. [AgentCoordinator Agent协调设计](#第十三章agentcoordinator-agent协调设计)
14. [ErrorPolicy 错误处理设计](#第十四章errorpolicy-错误处理设计)
15. [模块依赖关系](#第十五章模块依赖关系)
16. [扩展性验证矩阵](#第十六章扩展性验证矩阵)
17. [未来演进路线](#第十七章未来演进路线)
18. [Persistence 持久化决策层](#519-persistence-持久化决策接口v20新增)
19. [Persistence 持久化决策层](#第十九章persistence-持久化决策层)

## 第六章子章节

### 6.4 流式工具调用状态机设计
├── 6.4.1 设计动机
├── 6.4.2 状态机设计原则
├── 6.4.3 核心组件
├── 6.4.4 状态转换图
├── 6.4.5 ToolDetectState 三路径检测
├── 6.4.6 ModelCallState 边收边发设计
├── 6.4.7 状态转换表
├── 6.4.8 ToolExecuteState 消息插入逻辑
├── 6.4.9 SSE 事件格式设计
├── 6.4.10 文件清单与包结构
└── 6.4.11 与已有设计的关系

## 第五章新增接口

### 5.19 State 接口（toolcall 包）
### 5.20 StateEngine 接口（toolcall 包）
### 5.21 Signal 枚举（toolcall 包）
### 5.22 StateResult 类（toolcall 包）
### 5.23 Sse 数据解析方法（ModelAdapter 接口）
### 5.24 ToolCallEventEmitter 类（toolcall 包）
---

## 第一章：整体架构概览

### 1.1 AI 引擎层的定位

AI 引擎层是 LyClaw 的核心业务逻辑层，位于模型抽象层之上、API 网关层之下。它负责：

1. 接收用户的对话请求
2. 根据会话历史构建上下文
3. 经过拦截器链进行预处理
4. 调用模型抽象层获取 AI 回复
5. 当模型返回工具调用请求时，调度工具执行模块执行工具
6. 将工具结果注入上下文，再次调用模型
7. 处理响应、发布事件、管理记忆
8. 协调主 Agent 与子 Agent 的生命周期

一句话定位：**AI 引擎层是整个应用的"大脑"，负责决策"什么时候调用模型、什么时候调用工具、什么时候记住信息、什么时候通知其他模块"。**

### 1.2 为什么需要 AI 引擎层

在没有引擎层的情况下，上层业务（如 Controller）需要直接处理：
- 构建模型输入（拼接消息历史、注入 system prompt、加载记忆）
- 调用模型适配器
- 判断是否需要调用工具
- 调用工具并获取结果
- 再次调用模型
- 处理错误和重试
- 更新会话和记忆

这不仅导致 Controller 代码臃肿，更致命的是：**所有这些逻辑都被写死在 Controller 中，未来增加任何新功能（如缓存、限流、多 Agent 并行）都需要修改 Controller 代码。**

引擎层的出现将这些逻辑从 Controller 中剥离出来，形成独立、可扩展的业务层。

### 1.3 核心架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              上层调用者                                       │
│                                                                              │
│  HTTP Controller / WebSocket Handler / CLI TUI / gRPC Service / ...         │
│                                                                              │
│  所有入口都通过 EngineSelector 选择合适的 Engine 执行                         │
└─────────────────────────────────────┬───────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          EngineSelector                                      │
│                                                                              │
│  根据 ChatRequest 的特征自动选择合适的 Engine：                               │
│  - 普通对话 → DefaultEngine                                                │
│  - 推理任务 → ReasoningEngine（未来）                                       │
│  - 规划任务 → PlanningEngine（未来）                                        │
│  - RAG 查询 → RagEngine（未来）                                            │
│  - 批处理   → BatchEngine（未来）                                          │
│                                                                              │
│  EngineSelector 遍历所有注册的 Engine，调用 supports() 方法                  │
│  返回第一个匹配的 Engine。新增 Engine 只需 @Component 自动注册。             │
└─────────────────────────────────────┬───────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Engine（顶层接口）                                   │
│                                                                              │
│  public interface Engine {                                                   │
│      String getName();                                                       │
│      boolean supports(ChatRequest request);                                  │
│      Flux<String> execute(ChatRequest request);                             │
│      EngineMetadata getMetadata();                                           │
│  }                                                                           │
│                                                                              │
│  每一个 Engine 都是一个独立的"引擎实现"，拥有完全不同的业务逻辑。              │
│  它们共享底层的 ToolRegistry、MemoryManager、EventBus 等组件。               │
└─────────────────────────────────────┬───────────────────────────────────────┘
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
┌───────────────┐         ┌───────────────┐         ┌───────────────┐
│ DefaultEngine │         │ReasoningEngine│         │(未来任意引擎) │
│               │         │               │         │               │
│ Pipeline 内部  │         │ 独立的推理流程 │         │ 实现 Engine   │
│ 使用 Pipeline │         │ ChainOfThought│         │ 接口即可      │
│ Builder 编排  │         │ → 模型调用    │         │               │
└───────────────┘         │ → 验证        │         └───────────────┘
        │                 │ → 输出        │
        │                 └───────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DefaultEngine 内部 — Pipeline 管道                        │
│                                                                              │
│  Pipeline = PipelineBuilder                                                  │
│      .addStage(ContextBuildStage)       ← 构建上下文（含记忆注入）            │
│      .addStage(InterceptorStage)        ← 拦截器链（限流/缓存/日志/审计）     │
│      .addStage(ToolCallLoop)            ← 模型调用 + 工具执行循环            │
│      .addStage(MetricsStage)           ← 指标采集                            │
│      .addStage(ResponseBuildStage)     ← 响应构建                            │
│      .build();                                                               │
│                                                                              │
│  每个 PipelineStage 都是一个可插拔的处理阶段。                                │
│  新增阶段只需新建类实现 PipelineStage，通过 addStage() 加入管道。             │
│  移除阶段只需从 Builder 中删除对应的 addStage() 调用。                        │
│  调整顺序只需改变 addStage() 的调用顺序。                                     │
└─────────────────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ToolCallLoop（独立循环组件）                           │
│                                                                              │
│  int rounds = 0;                                                             │
│  while (rounds < toolCallPolicy.getMaxRounds()) {                           │
│      ModelResponse resp = modelAdapter.chat(context);                       │
│      if (!resp.hasToolCalls()) {                                            │
│          return resp;             ← 无工具调用，正常返回                     │
│      }                                                                      │
│      for (ToolCall tc : resp.getToolCalls()) {                              │
│          ToolResult result = toolRegistry.execute(tc);                      │
│          context.addToolResult(tc.getId(), result);                         │
│      }                                                                      │
│      if (!toolCallPolicy.shouldContinue(context, rounds)) {                 │
│          break;                   ← 策略决定终止                             │
│      }                                                                      │
│      rounds++;                                                              │
│  }                                                                          │
│                                                                              │
│  循环终止条件由 ToolCallPolicy 接口控制，可替换策略：                          │
│  - DefaultToolCallPolicy：最多 10 轮                                         │
│  - BudgetAwarePolicy：根据 Token 预算动态决定                                │
│  - ModelDrivenPolicy：让模型自己决定是否继续                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.4 共享组件层

每个 Engine 实例都通过依赖注入共享以下组件：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           共享组件（Spring 单例）                             │
│                                                                              │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────────────────┐ │
│  │ ToolRegistry   │  │ MemoryManager  │  │ EventBus                       │ │
│  │                │  │                │  │                                │ │
│  │ • 工具注册/发现 │  │ • 记忆存储/读取 │  │ • 事件发布/订阅                 │ │
│  │ • 工具执行     │  │ • 上下文构建    │  │ • Token事件/工具事件/错误事件    │ │
│  │ • 超时控制     │  │ • 记忆策略     │  │ • 可切换实现(内存/Kafka/...)     │ │
│  └────────────────┘  └────────────────┘  └────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────────────────┐ │
│  │ ModelProvider  │  │ ErrorPolicy    │  │ AgentCoordinator               │ │
│  │                │  │                │  │                                │ │
│  │ • 适配器获取    │  │ • 错误处理策略  │  │ • Agent生命周期管理             │ │
│  │ • 厂商列表     │  │ • 重试/降级/熔断│  │ • 主从Agent通信                │ │
│  │ • 解耦adapter  │  │ • 超时处理     │  │ • 拓扑可切换(星型/树形/网状)     │ │
│  └────────────────┘  └────────────────┘  └────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.5 一次完整对话请求的流转

以下是一次用户对话请求经过引擎层的完整路径。流式和非流式统一走 Pipeline。
请求携带的 stream 标记决定 ToolCallLoopStage 内部的调用方式。

#### 前置准备（流式/非流式共用）

在 Pipeline.execute() 之前，DefaultEngine.execute() 执行公共前序：

```
DefaultEngine.execute(request)
  │
  ├── memoryManager.read()                     ← FileMemoryManager：加载长期记忆
  │
  ├── toolRegistry.getAllDefinitions()         ← ToolRegistry：获取所有工具定义
  │
  ├── loadOrCreateSession(request)             ← SessionStorage
  │   ├── sessionStorage.get(request.getSessionId())
  │   ├── 已有会话：追加本次请求消息到历史
  │   ├── 无会话：用本次请求消息新建
  │   └── session.setId(session.getSessionId()) ← 确保 BaseDTO.id == sessionId
  │
  └── build ChatContext(request, session, memory, tools, interceptorChain, modelProvider)
```

#### 方案一：非流式（同步）执行

```
PipelineBuilder
  .addStage(new ContextBuildStage(contextBuilder))
  .addStage(new InterceptorStage(interceptorChain))
  .addStage(new ToolCallLoopStage(modelProvider, toolRegistry, toolCallPolicy, errorPolicy))
  .addStage(new MetricsStage(eventBus))
  .addStage(new ResponseBuildStage(interceptorChain))
  .build();

Pipeline.execute(context)
  │
  ├── Stage 1: ContextBuildStage
  │   └── contextBuilder.buildContext(session, memory, toolDefinitions)
  │       ├── 系统提示（工具列表）
  │       ├── 记忆消息（role=user）
  │       └── session 历史消息 + 当前请求消息
  │   └── context.getRequest().setMessages(builtMessages)  ← 消息列表写回 request
  │   └── chain.next()
  │
  ├── Stage 2: InterceptorStage
  │   └── interceptorChain.preHandle(context)
  │       ├── RateLimitInterceptor.order=10   — 检查请求频率
  │       ├── SensitiveDataInterceptor.order=50 — 脱敏
  │       └── LoggingInterceptor.order=100    — 日志
  │   └── chain.next()
  │
  ├── Stage 3: ToolCallLoopStage（stream=false）
  │   └── executeSyncInternal(context, chain)
  │       └── 循环（由 ToolCallPolicy 控制）：
  │           ├── adapter.chat(request) → ModelResponse
  │           ├── !response.hasToolCalls() → break
  │           ├── 执行工具 → 工具结果写入消息列表
  │           └── ToolCallPolicy.shouldContinue() → 决定下一轮
  │   └── chain.next()
  │
  ├── Stage 4: MetricsStage
  │   └── 采集指标（token用量、耗时）
  │       ├── eventBus.publish(TokenConsumedEvent)
  │       └── log.info("对话完成: tokenUsage=..., durationMs=...ms")
  │   └── chain.next()
  │
  └── Stage 5: ResponseBuildStage
      └── 构建 ChatResult(content, finishReason, tokenUsage, durationMs)
      └── interceptorChain.postHandle(context, result)
      └── chain.next()

Pipeline 执行完毕

DefaultEngine 后处理：
  ├── session.getMessages().add(assistantMsg)        ← 追加 assistant 消息到 session
  ├── sessionPersistence.evaluate(session, turnCount, millisSinceLastWrite)
  │   └── 返回 PersistenceDecision（WRITE / DEFER / SKIP）
  ├── persistenceExecutor.executeSessionWrite(session, decision)
  │   └── if WRITE → sessionStorage.save(session)    ← 持久化会话到 sessions/{uuid}.json
  │
  ├── memoryManager.append(content)                   ← 追加到记忆（只在内存）
  ├── memoryWriteState = memoryWriteState.accumulate(content)
  ├── memoryPersistence.evaluate(memoryWriteState)
  │   └── 返回 PersistenceDecision（WRITE / DEFER / SKIP）
  └── persistenceExecutor.executeMemoryFlush(decision)
      └── if WRITE → memoryManager.flush()            ← FileMemoryManager 刷盘记忆

返回 Flux.just(content) 给 Controller
```

#### 方案二：流式执行（状态机方案）

流式执行使用**状态机**驱动"模型调用 → 检测工具 → 执行工具 → 继续调用"的多轮循环。每个阶段是一个独立状态，状态机引擎持有转换表控制流转。

**状态机设计原则**：
- 状态只返回自己的执行结果（`StateResult` + `Signal`），不知道下一个状态是谁
- 状态机引擎持有 `transitionTable` 驱动流转，轮次/超时可统一控制
- 新增状态只需实现接口，改 transitionTable 即可改变流程

**流程概述**：

```
ToolCallLoopStage.process(context)
  │
  ├── isStream()=true:
  │   └── StateMachine 驱动以下循环（每轮为一个 "模型输出 → 检测 → 可能执行工具" 单元）
  │
  │   ┌─────────────────────────────────────────────────────┐
  │   │  循环体（由 StateMachine 控制）：                      │
  │   │                                                      │
  │   │  ① StreamModelCallState                              │
  │   │     adapter.chatStream(request) → Flux<String>        │
  │   │     收集原始 SSE 到 collector                         │
  │   │     → Signal.STREAM_COMPLETED                        │
  │   │                                                      │
  │   │  ② ToolDetectState                                   │
  │   │     adapter.extractSseToolCalls(collector) → 解析工具   │
  │   │     → 有工具调用: Signal.TOOL_CALLS_FOUND             │
  │   │     → 无工具调用: Signal.NO_TOOL_CALLS（终止循环）     │
  │   │                                                      │
  │   │  ③ ToolExecuteState                                  │
  │   │     执行每个工具 → 结果注入消息列表                    │
  │   │     → Signal.TOOLS_EXECUTED → 回到 ①（下一轮）        │
  │   │                                                      │
  │   └─────────────────────────────────────────────────────┘
  │
  │   状态机引擎合并所有轮流式输出为单一 Flux：
  │     Flux.concat(round1_flux, toolEvent_flux, round2_flux, ...)
  │   存入 context.setAttribute("__stream_flux__", mergedFlux)
  │
  │   注：每轮之间插入 ToolCallEventEmitter 构建的工具调用状态事件
  │       格式：data:{"type":"tool_call","name":"current_time","status":"executing"}
  │            data:{"type":"tool_call","name":"current_time","status":"done","result":"..."}
  │
  └── isStream()=false:
      └── StateMachine 驱动同步循环：
          ① SyncModelCallState → adapter.chat() → ModelResponse
          ② ToolDetectState → 检查 hasToolCalls()
          ③ ToolExecuteState → 执行工具 → 注入结果
          → 最终 Flux.just(content) 存入 __stream_flux__

chain.next()

Pipeline 继续执行：MetricsStage → ResponseBuildStage

ResponseBuildStage 检测 __stream_flux__ 存在，在 Flux 上注册持久化回调（doOnComplete）。
由于 Flux.concat(flux1, flux2, ...) 只触发一次 doOnComplete（所有子 Flux 都完成后），
持久化逻辑在完整交互结束后才执行，不会在工具调用中途触发。

持久化流程（由持久化决策层控制写入时机）：
  1. 追加 assistant 消息到 session.getMessages()
  2. sessionPersistence.evaluate(session, ...) → PersistenceDecision
  3. persistenceExecutor.executeSessionWrite(session, decision)
     → WRITE 时: sessionStorage.save(session)
  4. memoryManager.append(content) ← 只改内存，不刷盘
  5. memoryWriteState.accumulate(content) → memoryPersistence.evaluate(writeState)
  6. persistenceExecutor.executeMemoryFlush(decision)
     → WRITE 时: memoryManager.flush()

Controller 订阅 __stream_flux__，区分两种 SSE 事件：
- event:message（delta.content 文本）→ 追加显示
- event:tool_call（执行状态 JSON）→ 显示工具调用进度
```
---

## 第二章：设计原则与哲学

### 2.1 六大设计原则

#### 原则一：单一职责原则

每一个类、每一个接口、每一个模块都只有一个引起它变化的原因。

- `ContextBuilder` 只负责构建上下文，不负责调用模型
- `ToolRegistry` 只负责工具注册和执行，不负责判断是否需要工具
- `MemoryManager` 只负责记忆的存储和读取，不负责构建对话上下文
- `EventBus` 只负责事件的发布和订阅，不关心事件的内容
- `Interceptor` 只负责单一横切关注点（限流、日志、审计各一个）

当类只做一件事时，修改它只会因为这一个原因，不会影响其他功能。新增功能时，只需新建类，不修改已有类。

#### 原则二：开闭原则

对扩展开放，对修改关闭。

- 新增工具：新建 `XxxTool implements Tool`，加 `@Component`，无需修改 `ToolRegistry`
- 新增拦截器：新建 `XxxInterceptor implements Interceptor`，加 `@Component`，无需修改 `InterceptorChain`
- 新增上下文策略：新建 `XxxContextBuilder implements ContextBuilder`，加 `@Component`，无需修改调用方
- 新增引擎：新建 `XxxEngine implements Engine`，加 `@Component`，无需修改 `EngineSelector`
- 新增事件监听器：新建监听器类，订阅事件类型，无需修改 `EventBus`

所有扩展都通过"新建类 + 实现接口"的方式完成，已有代码零修改。

#### 原则三：依赖倒置原则

上层模块不依赖下层模块的具体实现，只依赖抽象接口。

```
上层（Controller）        →  依赖 Engine 接口
Engine 实现               →  依赖 PipelineStage 接口
PipelineStage 实现         →  依赖 ContextBuilder/Interceptor/Tool 接口
ContextBuilder 实现        →  依赖 ChatContext（纯数据）

每一层都只依赖接口，不依赖具体类。
具体实现通过 Spring 依赖注入在运行时绑定。
```

当需要替换某个实现时（如从文件存储切换到 Redis 存储），只需：
1. 新建 `RedisMemoryManager implements MemoryManager`
2. 在 Spring 配置中将 `@Primary` 或 `@Qualifier` 指向新实现

所有依赖该接口的代码无需任何修改。

#### 原则四：接口隔离原则

接口小而精，不强迫使用者依赖它不需要的方法。

- `Engine` 接口只定义 4 个方法：`getName()`、`supports()`、`execute()`、`getMetadata()`
- `PipelineStage` 接口只定义 2 个方法：`supports()`、`execute()`
- `ContextBuilder` 接口只定义 2 个方法：`supports()`、`build()`
- `Tool` 接口只定义 2 个方法：`getDefinition()`、`execute()`
- `Interceptor` 接口只定义 2 个方法：`preHandle()`、`postHandle()`

每个接口都是最小化的。如果一个类不需要某个方法，就不应该因为实现某个接口而被迫提供空实现。

#### 原则五：里氏替换原则

任何子类或实现类都可以替换父类或接口使用，行为不发生变化。

- 任何 `Engine` 实现都可以被 `EngineSelector` 使用，行为取决于 `supports()` 的返回值
- 任何 `ContextBuilder` 实现都可以被 `ContextBuildStage` 使用，`supports()` 自动选择
- 任何 `Tool` 实现都可以被 `ToolRegistry` 管理，`getDefinition()` 返回工具定义
- 任何 `Interceptor` 实现都可以被 `InterceptorChain` 按序执行，`getOrder()` 决定顺序

替换实现时，调用方不需要知道具体是哪个实现类在工作。

#### 原则六：迪米特法则

一个对象应该对其他对象有最少的了解。

- `Engine` 不需要知道 `ToolRegistry` 的内部实现，只需要调用 `execute()`
- `PipelineStage` 不需要知道其他 Stage 的存在，只需要处理自己的逻辑
- `Interceptor` 不需要知道前后拦截器的逻辑，只需要处理自己的横切关注点
- `EventBus` 的发布者不需要知道有哪些订阅者，订阅者不需要知道事件是谁发布的

模块之间通过接口通信，通过事件总线解耦，降低耦合度。

### 2.2 核心设计哲学：一切皆可替换

本架构的核心设计哲学可以概括为一句话：**架构中没有任何一个组件是"写死"的。**

- 引擎逻辑可以替换：`Engine` 接口 + `EngineSelector` 自动选择
- 管道阶段可以替换：`PipelineStage` 接口 + `PipelineBuilder` 动态编排
- 上下文策略可以替换：`ContextBuilder` 接口 + `supports()` 自动遍历
- 工具执行可以替换：`Tool` 接口 + `ToolRegistry` 自动发现
- 记忆管理可以替换：`MemoryManager` 接口 + 依赖注入
- 事件总线可以替换：`EventBus` 接口 + 依赖注入
- 拦截器链可以替换：`Interceptor` 接口 + `@Order` 排序
- 错误处理可以替换：`ErrorPolicy` 接口 + 依赖注入
- Agent 通信可以替换：`AgentChannel` 接口 + 依赖注入
- HTTP 客户端可以替换：`ModelProvider` 内部实现（引擎不感知）

这种设计哲学的具体落地方式是：**任何一个组件，先定义接口，再提供默认实现。所有调用方只依赖接口，不依赖实现。**

### 2.3 扩展方式：只加不减

未来任何需求变更都遵循一个原则：**新建一个类，实现一个接口，加一个 `@Component` 注解。**

- 不需要修改已有类
- 不需要修改已有接口
- 不需要修改已有配置
- 不需要理解已有实现的细节

当需要替换某个实现时：
1. 新建一个实现类
2. 在 Spring 配置中切换注入（`@Primary` 或配置文件）
3. 旧实现可以保留，随时可以切回去

当需要移除某个功能时：
1. 移除对应的 `@Component` 注解或配置
2. 系统自动回退到默认行为

这种"只加不减"的方式保证了代码的稳定性和可追溯性。

---

## 第三章：模块拆分与职责边界

### 3.1 lyclaw-engine 模块包结构

```
lyclaw-engine/src/main/java/lyjew/com/lyclaw/
│
├── engine/                    ← Engine 顶层抽象
│   ├── Engine.java           ← 引擎接口
│   ├── EngineMetadata.java   ← 引擎元信息
│   ├── EngineSelector.java   ← 引擎选择器
│   └── impl/
│       └── DefaultEngine.java ← 默认引擎实现
│
├── pipeline/                  ← 管道模式
│   ├── Pipeline.java          ← 管道接口
│   ├── PipelineBuilder.java   ← 管道构建器（建造者模式）
│   ├── PipelineStage.java     ← 管道阶段接口
│   ├── Chain.java             ← 阶段链（责任链）
│   └── stages/
│       ├── ContextBuildStage.java      ← 上下文构建阶段
│       ├── InterceptorStage.java       ← 拦截器阶段
│       ├── ToolCallLoopStage.java      ← 工具调用循环阶段（入口调度）
│       ├── MetricsStage.java           ← 指标采集阶段
│       ├── ResponseBuildStage.java     ← 响应构建阶段
│       └── stream/                     ← 流式工具调用状态机
│           ├── StreamToolCallStateMachine.java  ← 状态机引擎
│           ├── StreamToolCallState.java         ← 状态接口
│           ├── StateResult.java                 ← 状态返回结果
│           ├── Signal.java                      ← 状态信号枚举
│           ├── ModelCallState.java              ← 调模型+收集SSE
│           ├── SyncModelCallState.java          ← 同步模式调模型
│           ├── ToolDetectState.java             ← 从SSE检测工具
│           ├── ToolExecuteState.java            ← 执行工具+注入结果
│           └── ToolCallEventEmitter.java        ← 工具调用状态事件Flux
│
├── context/                   ← 上下文构建
│   ├── ContextBuilder.java    ← 上下文构建策略接口
│   ├── ChatContext.java       ← 对话上下文数据对象
│   └── impl/
│       ├── FullWindowContextBuilder.java    ← 全量窗口策略
│       ├── SlidingWindowContextBuilder.java ← 滑动窗口策略
│       └── SummaryContextBuilder.java       ← 摘要压缩策略
│
├── interceptor/               ← 拦截器链
│   ├── Interceptor.java       ← 拦截器接口
│   ├── InterceptorChain.java  ← 拦截器链管理器
│   └── impl/
│       ├── RateLimitInterceptor.java      ← 限流拦截器
│       ├── LoggingInterceptor.java        ← 日志拦截器
│       └── SensitiveDataInterceptor.java  ← 脱敏拦截器
│
├── tool/                      ← 工具执行
│   ├── Tool.java              ← 工具接口
│   ├── ToolRegistry.java      ← 工具注册表
│   ├── ToolCallLoop.java      ← 工具调用循环
│   ├── ToolCallPolicy.java    ← 循环终止策略接口
│   ├── ToolResult.java        ← 工具执行结果
│   └── impl/
│       ├── WebSearchTool.java         ← 网络搜索工具
│       ├── CalculatorTool.java        ← 计算器工具
│       ├── CurrentTimeTool.java       ← 当前时间工具
│       ├── DefaultToolCallPolicy.java ← 默认循环策略
│       └── McpToolAdapter.java        ← MCP 工具适配器
│
├── persistence/               ← 持久化决策层（v2.0 新增）
│   ├── PersistenceSignal.java     ← 决策信号枚举
│   ├── PersistenceDecision.java   ← 决策结果值对象
│   │
│   ├── session/                   ← 会话持久化策略
│   │   ├── SessionPersistence.java     ← 会话持久化策略接口
│   │   └── impl/
│   │       └── EveryTurnSessionPersistence.java ← 每轮都存默认策略
│   │
│   ├── memory/                     ← 记忆持久化策略
│   │   ├── MemoryWriteState.java       ← 累积变更状态
│   │   ├── MemoryPersistence.java      ← 记忆持久化策略接口
│   │   └── impl/
│   │       └── ImmediateMemoryPersistence.java ← 每次追加即刷盘
│   │
│   └── executor/
│       └── PersistenceExecutor.java   ← 执行器：决策→存储映射
│
├── memory/                    ← 记忆管理
│   ├── MemoryManager.java     ← 记忆管理接口
│   ├── MemoryStrategy.java    ← 记忆提取策略接口
│   └── impl/
│       ├── ManualMemoryStrategy.java  ← 手动触发策略
│       └── FileMemoryManager.java     ← 文件存储实现
│
├── event/                     ← 事件总线
│   ├── Event.java             ← 事件基类
│   ├── EventBus.java          ← 事件总线接口
│   └── impl/
│       ├── InMemoryEventBus.java     ← 内存事件总线
│       ├── TokenConsumedEvent.java   ← Token消耗事件
│       ├── ToolCalledEvent.java      ← 工具调用事件
│       └── AgentStateChangedEvent.java ← Agent状态变更事件
│
├── agent/                     ← Agent 协调
│   ├── AgentCoordinator.java  ← Agent 协调器
│   ├── AgentChannel.java      ← Agent 通信拓扑接口
│   ├── AgentTask.java         ← Agent 任务
│   ├── AgentState.java        ← Agent 状态枚举
│   └── impl/
│       └── StarAgentChannel.java     ← 星型拓扑实现
│
├── error/                     ← 错误处理
│   ├── ErrorPolicy.java       ← 错误处理策略接口
│   └── impl/
│       └── DefaultErrorPolicy.java   ← 默认错误策略
│
├── config/                    ← 引擎配置
│   ├── EngineProperties.java  ← 配置属性类
│   └── EngineAutoConfiguration.java ← 自动配置
│
└── dto/                       ← 引擎内部 DTO
    ├── ChatResult.java        ← 对话结果
    └── AgentResult.java       ← Agent 执行结果
```

### 3.2 各包职责边界

| 包 | 职责 | 不负责 |
|----|------|--------|
| engine/ | 引擎顶层抽象、引擎选择 | 具体的对话流程 |
| pipeline/ | 管道编排、阶段管理 | 具体的业务逻辑 |
| context/ | 上下文构建策略 | 记忆存储、消息持久化 |
| interceptor/ | 横切关注点处理 | 核心对话流程 |
| tool/ | 工具注册、执行、循环控制 | 工具的具体业务实现 |
| memory/ | 记忆的增删改查（内存+持久化） | 持久化时机决策 |
| persistence/ | 持久化时机决策（何时存） | 如何存储、存储到哪里 |
| event/ | 事件的发布和订阅 | 事件的具体处理逻辑 |
| agent/ | Agent 生命周期管理、通信拓扑 | 具体的对话逻辑 |
| error/ | 错误处理、重试、降级 | 具体的错误恢复 |
| config/ | 配置管理、自动装配 | 业务逻辑 |

### 3.3 包之间的通信规则

1. **engine 包**：可以被所有包依赖，它只定义接口
2. **pipeline 包**：被 engine 包调用，依赖 context/interceptor/tool/memory/event/persistence 包
3. **context 包**：被 pipeline 包调用，不依赖其他业务包
4. **interceptor 包**：被 pipeline 包调用，可依赖 event 包发布事件
5. **tool 包**：被 pipeline 包调用，可依赖 event 包发布事件
6. **persistence 包**：被 pipeline 包调用，依赖 memory/ 包（调 MemoryManager.flush()）和 storage 模块（调 SessionStorage）
7. **storage 模块（lyclaw-storage）**：持久化决策层调用它执行实际的写入操作，但不了解决策逻辑
6. **memory 包**：被 engine 包调用（对话结束后），可依赖 event 包
7. **event 包**：被所有包调用，不依赖任何业务包
8. **agent 包**：被 engine 包调用，依赖 pipeline 包执行子任务

---

---

## 第四章：设计模式全景图

### 4.1 设计模式清单

本引擎层共应用 **13 种设计模式**，每种模式解决特定的设计问题：

### 4.2 策略模式
、SessionPersistence、MemoryPersistence
**应用位置**：`Engine`、`ContextBuilder`、`MemoryStrategy`、`ToolCallPolicy`、`ErrorPolicy`

**解决的问题**：同一种行为有多种实现方式，需要在运行时动态选择。

**设计说明**：
- `Engine` 接口定义了引擎的执行协议，`DefaultEngine`、`ReasoningEngine` 等都是不同的策略
- `ContextBuilder` 接口定义了上下文构建协议，`FullWindowContextBuilder`、`SlidingWindowContextBuilder` 是不同的策略
- 所有策略都通过 `supports()` 方法自描述适用场景，由选择器自动遍历匹配

**扩展方式**：新建一个类实现策略接口，实现 `supports()` 方法返回适用条件，加 `@Component` 注解自动注册。

### 4.3 模板方法模式

**应用位置**：`ToolCallLoop`、`AbstractPipelineStage`

**解决的问题**：一个操作的流程骨架是固定的，但某些步骤的具体实现因场景而异。

**设计说明**：
- `ToolCallLoop` 固化了工具调用循环的骨架：调用模型 → 检查工具调用 → 执行工具 → 注入结果 → 判断是否继续
- `ToolCallPolicy` 作为钩子方法让子类定制"是否继续循环"的判断逻辑
- `ErrorPolicy` 作为钩子方法让子类定制"出错时如何处理"

**扩展方式**：替换 `ToolCallPolicy` 或 `ErrorPolicy` 的实现即可改变循环行为，无需修改 `ToolCallLoop` 代码。

### 4.4 管道模式

**应用位置**：`Pipeline`、`PipelineStage`、`Chain`

**解决的问题**：一个请求需要经过多个阶段处理，每个阶段各司其职，阶段之间需要灵活编排。

**设计说明**：
- `Pipeline` 由一系列 `PipelineStage` 组成
- `PipelineBuilder` 负责编排阶段顺序
- `Chain` 负责在阶段之间传递上下文
- 每个阶段都可以决定是否调用 `chain.proceed()` 继续下一个阶段

**扩展方式**：新建 `PipelineStage` 实现，通过 `PipelineBuilder.addStage()` 加入管道。

### 4.5 责任链模式

**应用位置**：`Interceptor`、`InterceptorChain`

**解决的问题**：一个请求需要经过多个处理器的处理，每个处理器可以决定是否继续传递给下一个。

**设计说明**：
- `InterceptorChain` 管理所有 `Interceptor` 实例，按 `getOrder()` 排序
- 每个拦截器在 `preHandle()` 和 `postHandle()` 中执行自己的逻辑
- 拦截器可以调用 `chain.skipToNext()` 跳过后续拦截器
- 拦截器可以用 `@Async` 异步执行，不阻塞主管道

**扩展方式**：新建 `Interceptor` 实现，加 `@Component` 自动注册到链中。

### 4.6 观察者模式

**应用位置**：`EventBus`、`Event`

**解决的问题**：模块之间需要通信，但又不希望直接依赖，需要松耦合的事件通知机制。

**设计说明**：
- 模块通过 `EventBus.publish(event)` 发布事件
- 监听器通过 `EventBus.subscribe(eventType, handler)` 订阅事件
- 发布者不知道有哪些订阅者，订阅者不知道事件是谁发布的
- 事件类型包括：`TokenConsumedEvent`、`ToolCalledEvent`、`AgentStateChangedEvent`、`ErrorEvent`

**扩展方式**：新建 `Event` 子类定义新事件类型，新建监听器订阅对应事件。

### 4.7 命令模式

**持久化层应用**：`PersistenceExecutor`

**解决的问题**：将"写入操作"封装为可执行的命令，决策层不知道如何执行，执行层不知道如何决策。

**设计说明**：
- `SessionPersistence`/`MemoryPersistence` 只负责返回 `PersistenceDecision`（WRITE/DEFER/SKIP），不认识任何存储类
- `PersistenceExecutor` 只负责接收 `PersistenceDecision`，调对应的存储操作（`sessionStorage.save()`/`memoryManager.flush()`）
- 两者通过 `PersistenceDecision` 值对象通信，完全解耦

**扩展方式**：替换策略实现即可改变决策行为，无需修改 Executor

**应用位置**：`Tool` 接口及其实现

**解决的问题**：每个工具是一个独立的可执行单元，需要统一的生命周期管理（执行、超时、重试）。

**设计说明**：
- 每个工具封装为一个 `Tool` 对象，包含 `getDefinition()` 和 `execute()` 两个方法
- `ToolRegistry` 管理所有工具，提供注册、发现、执行功能
- 工具支持超时控制、错误处理、结果序列化

**扩展方式**：新建 `Tool` 实现，加 `@Component` 自动注册。

### 4.8 注册表模式

**应用位置**：`ToolRegistry`、`InterceptorChain`、`EngineSelector`

**解决的问题**：系统中存在多个同类组件（工具、拦截器、引擎），需要一个中央注册表来管理它们。

**设计说明**：
- `ToolRegistry`：启动时扫描所有 `Tool` 实现，存入 `Map<String, Tool>`
- `InterceptorChain`：启动时扫描所有 `Interceptor` 实现，按 `getOrder()` 排序
- `EngineSelector`：启动时扫描所有 `Engine` 实现，按需选择

**扩展方式**：新建组件类实现对应接口，加 `@Component` 自动注册到注册表。

### 4.9 工厂模式

**应用位置**：`ModelProvider`、`PipelineBuilder`

**解决的问题**：对象的创建过程比较复杂，或者需要根据条件创建不同类型的对象。

**设计说明**：
- `ModelProvider`：根据 `provider` 名称创建或获取对应的 `ModelAdapter`
- `PipelineBuilder`：通过建造者模式构建复杂的 `Pipeline` 对象

**扩展方式**：`ModelProvider` 的实现类（在 lyclaw-adapter 中）自动支持新注册的 Adapter。

### 4.10 建造者模式

**应用位置**：`PipelineBuilder`

**解决的问题**：一个对象有很多配置选项，且大部分是可选的，直接使用构造函数会导致参数爆炸。

**设计说明**：
- `PipelineBuilder` 提供链式 API：`.addStage(...).addStage(...).build()`
- 每个 `addStage()` 返回 Builder 自身，支持链式调用
- `build()` 方法执行最终的构建逻辑

**扩展方式**：不需要扩展，Builder 本身已经是通用模式。

### 4.11 状态模式

**应用位置**：`AgentState`

**解决的问题**：Agent 在其生命周期中会经历多个状态，每个状态下可执行的操作不同。

**设计说明**：
- `AgentState` 枚举定义了 Agent 的所有状态：`IDLE`、`RUNNING`、`WAITING_TOOL`、`COMPLETED`、`ERROR`、`TERMINATED`
- `AgentCoordinator` 根据当前状态决定可执行的操作
- 状态转换有明确的规则（如只有 `RUNNING` 状态可以进入 `WAITING_TOOL`）

**扩展方式**：新增状态时，在枚举中加值，在 `AgentCoordinator` 中加对应的处理逻辑。

### 4.12 装饰器模式

**应用位置**：未来的 `CacheDecorator`、`RetryDecorator`、`MetricsDecorator`

**解决的问题**：在不修改原有类的情况下，动态地为对象添加额外的功能。

**设计说明**：
- 装饰器实现与被装饰对象相同的接口
- 装饰器内部持有被装饰对象的引用
- 装饰器可以在调用被装饰对象前后添加自己的逻辑

**扩展方式**：新建装饰器类实现对应接口，通过 Builder 叠加到目标对象上。

### 4.13 中介者模式

**应用位置**：`AgentCoordinator`、`AgentChannel`

**解决的问题**：多个 Agent 之间需要通信，但不希望它们直接依赖，需要一个中介者来协调。

**设计说明**：
- `AgentCoordinator` 作为中介者，管理主 Agent 和子 Agent 之间的通信
- `AgentChannel` 定义了通信拓扑：星型、树形、网状、广播
- Agent 之间不直接通信，所有消息通过 Channel 传递

**扩展方式**：新建 `AgentChannel` 实现，切换通信拓扑。

---

## 第五章：核心接口设计

### 5.1 Engine 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/engine/Engine.java`

**设计模式**：策略模式

**接口职责**：

定义 AI 引擎的顶层执行协议。每一个引擎实现代表一种完全不同的对话处理逻辑。例如：
- `DefaultEngine`：标准的"收到消息 → 调用模型 → 可能调用工具 → 返回回复"流程
- `ReasoningEngine`（未来）：Chain-of-Thought 推理流程
- `PlanningEngine`（未来）：目标分解 + 任务规划流程
- `RagEngine`（未来）：检索增强生成流程

所有引擎共享底层的 `ToolRegistry`、`MemoryManager`、`EventBus` 等组件，但它们的执行逻辑可以完全不同。

**方法说明**：

`getName()` — 返回引擎名称，用于日志和元信息展示。如 "default"、"reasoning"、"planning"。

`supports(ChatRequest request)` — 判断当前引擎是否支持处理这个请求。EngineSelector 会遍历所有注册的 Engine，返回第一个 supports() 返回 true 的引擎。这使得引擎选择逻辑完全由引擎自己决定，而不是由一个中心化的 if-else 判断。

`execute(ChatRequest request)` — 执行对话，返回流式响应。使用 Flux 而不是 List，是因为模型调用本身是流式的（逐 token 返回），引擎应该保持这种流式特性，让上层可以实时消费。

`getMetadata()` — 返回引擎的元信息，包括名称、描述、版本、支持的能力列表、当前配置。用于运维监控和管理界面展示。

### 5.2 EngineSelector 类

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/engine/EngineSelector.java`

**设计模式**：策略模式 + 注册表模式

**职责说明**：

`EngineSelector` 是引擎层的入口门面。所有上层调用者（Controller、WebSocket Handler 等）都通过 EngineSelector 来执行对话请求。

工作流程：
1. 启动时，Spring 自动注入所有 `Engine` 实现
2. 收到请求时，遍历所有 Engine，调用 `supports()` 方法
3. 返回第一个返回 `true` 的 Engine
4. 如果没有匹配的 Engine，抛出 `NoEngineFoundException`

这种设计使得：
- 新增引擎不需要修改选择器代码
- 引擎的匹配逻辑由引擎自己决定（通过 `supports()` 方法）
- 选择器只是一个简单的遍历器，不包含任何业务逻辑

### 5.3 Pipeline 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/Pipeline.java`

**设计模式**：管道模式

**职责说明**：

`Pipeline` 是一个由多个 `PipelineStage` 组成的处理管道。每个请求依次经过管道中的每个阶段。

Pipeline 本身不包含业务逻辑，它只是一个容器和调度器。所有的业务逻辑都在各 PipelineStage 中实现。

Pipeline 的实现由 `PipelineBuilder` 构建，Builder 决定了哪些 Stage 参与、以什么顺序参与。

### 5.4 PipelineBuilder 类

**类路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/PipelineBuilder.java`

**注解**：`@Component`

**设计模式**：建造者模式 + 自动发现

**职责说明**：

`PipelineBuilder` 负责自动发现所有 `PipelineStage` 实现并构建 `Pipeline` 实例。

采用 Spring 的自动注入机制：
1. 构造器收到 `List<PipelineStage>` — Spring 自动收集所有 `@Component` 实现的 `PipelineStage`
2. 按 `getOrder()` 升序排列
3. 构造器中直接构建 `DefaultPipeline` — 启动时 Pipeline 已就绪

**无需手动注册**：新增一个 Stage 只需：
```
@Component       // ← 加上 @Component
public class MyStage implements PipelineStage {
    @Override public int getOrder() { return 5; }  // ← 设置顺序
}
```
Spring 启动时自动发现、排序、注册到 Pipeline。**不需要修改任何已有代码**。

**文件路径说明**：设计文档中 `PipelineBuilder.java` 初始设计在 `lyjew/com/lyclaw/pipeline/` 包下（接口层），
实现已移至 `lyjew/com/lyclaw/pipeline/impl/` 包。

**核心方法**：
- `build()` — 返回已构建的 Pipeline（启动时构造器中已构建完成，此方法仅返回单例）
- `getStages()` — 返回当前注册的 Stage 列表（只读）

### 5.5 PipelineStage 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/PipelineStage.java`

**设计模式**：管道模式 + 责任链模式

### 5.5 PipelineStage 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/PipelineStage.java`

**设计模式**：管道模式 + 责任链模式

**职责说明**：

`PipelineStage` 是管道中的一个处理阶段。每个阶段负责一个明确的职责。

核心方法：
- `getName()` — 阶段名称，用于日志和调试
- `supports(ChatContext context)` — 判断当前阶段是否适用于这个上下文。某些阶段可能只在特定条件下执行。
- `execute(ChatContext context, Chain chain)` — 执行阶段逻辑。方法内部需要调用 `chain.proceed(context)` 将控制权传递给下一个阶段。如果阶段决定不继续（如缓存命中），可以不调用 `chain.proceed()`。

这种设计使得：
- 每个阶段独立，互不感知
- 阶段可以条件执行（通过 `supports()`）
- 阶段可以中断管道（不调用 `chain.proceed()`）
- 阶段之间通过 `ChatContext` 传递数据

### 5.6 ContextBuilder 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/context/ContextBuilder.java`

**设计模式**：策略模式

**职责说明**：

`ContextBuilder` 是上下文构建策略接口。它的任务是将原始数据（会话历史、系统提示、记忆）构建为发送给模型的最终消息列表。

不同的 ContextBuilder 实现代表不同的上下文构建策略：
- `FullWindowContextBuilder`：将所有历史消息全部放入上下文（简单但有 token 上限问题）
- `SlidingWindowContextBuilder`：只保留最近的 N 条消息（适合长对话）
- `SummaryContextBuilder`：将较旧的消息用 AI 做摘要，替换原文（适合超长对话）
- `HybridContextBuilder`（未来）：结合滑动窗口和摘要两种策略

核心方法：
- `supports(ChatContext context)` — 判断当前策略是否适用于这个上下文。这使得策略选择逻辑由策略自己决定，而不是由一个中心化的 if-else 判断。例如，SlidingWindowContextBuilder 可以在消息数超过阈值时返回 true，FullWindowContextBuilder 在所有情况下都返回 true（作为兜底）。
- `build(ChatContext context)` — 执行上下文构建，返回构建后的 `ChatContext`（包含最终的消息列表）。

### 5.7 Interceptor 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/interceptor/Interceptor.java`

**设计模式**：责任链模式

**职责说明**：

`Interceptor` 是拦截器接口。拦截器在请求处理前后执行横切逻辑。

核心方法：
- `getOrder()` — 返回拦截器的执行顺序。数字越小越先执行。例如，限流拦截器的 order 应该最小（最先执行），日志拦截器的 order 可以大一些。
- `preHandle(ChatContext context)` — 在请求处理前执行。可以修改上下文、中断请求（抛异常）。
- `postHandle(ChatResult result)` — 在请求处理后执行。可以修改响应、记录日志。

典型拦截器：
- `RateLimitInterceptor`（order=10）：检查请求频率，超限则拒绝
- `SensitiveDataInterceptor`（order=30）：对输入进行脱敏处理
- `LoggingInterceptor`（order=100）：记录请求和响应日志
- `MetricsInterceptor`（order=200）：采集指标数据
- `AuditInterceptor`（order=300，未来）：审计日志
- `CacheInterceptor`（order=20，未来）：检查缓存，命中则直接返回

扩展方式：新建类实现 Interceptor 接口，设置合适的 order，加 @Component 自动注册。

### 5.8 Tool 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/Tool.java`

**设计模式**：命令模式

**职责说明**：

`Tool` 是工具的抽象接口。每个工具都是一个独立的可执行单元。

核心方法：
- `getName()` — 返回工具名称，全局唯一。如 "web_search"、"calculator"。
- `getDefinition()` — 返回工具定义，包含名称、描述、参数 JSON Schema。这个定义会被发送给模型，让模型知道这个工具的功能和参数格式。
- `execute(Map<String, Object> arguments)` — 执行工具，返回执行结果。
- `getTimeout()` — 返回工具的超时时间（毫秒）。0 表示使用默认超时。

扩展方式：新建类实现 Tool 接口，加 @Component 自动注册到 ToolRegistry。

### 5.9 ToolRegistry 类

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/ToolRegistry.java`

**设计模式**：注册表模式

**职责说明**：

`ToolRegistry` 是工具注册表。它管理所有已注册的工具，提供统一的发现和执行接口。

核心方法：
- `register(Tool tool)` — 注册一个工具
- `unregister(String toolName)` — 移除一个工具
- `get(String toolName)` — 根据名称获取工具
- `getAll()` — 获取所有已注册的工具
- `getAllDefinitions()` — 获取所有工具的定义列表（用于发送给模型）
- `execute(String toolName, Map<String, Object> arguments)` — 执行指定工具

初始化流程：
1. 启动时，Spring 自动注入所有 `Tool` 实现
2. 遍历所有 Tool，调用 `getName()` 获取名称
3. 存入 `Map<String, Tool>` 中

### 5.10 ToolCallLoop 类

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/ToolCallLoop.java`

**设计模式**：模板方法模式

**职责说明**：

`ToolCallLoop` 负责执行"模型调用 + 工具执行"的循环。当模型返回工具调用请求时，执行工具并将结果注入上下文，再次调用模型，直到模型不再请求工具或达到最大轮次。

工作流程：
1. 调用模型获取响应
2. 如果模型不请求工具（finishReason="stop"），退出循环
3. 提取工具调用请求
4. 逐个执行工具
5. 将工具结果注入上下文
6. 调用 `ToolCallPolicy.shouldContinue()` 判断是否继续
7. 继续下一轮调用模型

核心依赖：
- `ModelProvider`：获取模型适配器
- `ToolRegistry`：执行工具
- `ToolCallPolicy`：决定循环终止条件

### 5.11 ToolCallPolicy 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/ToolCallPolicy.java`

**设计模式**：策略模式

**职责说明**：

`ToolCallPolicy` 是工具调用循环的终止策略。它决定循环是否应该继续。

核心方法：
- `shouldContinue(ChatContext context, int currentRound)` — 判断是否应该继续下一轮。参数包括当前上下文和已执行的轮次。
- `getMaxRounds()` — 返回最大允许轮次。
- `onToolError(ToolCall toolCall, Throwable error, int currentRound)` — 处理工具执行错误，决定是继续、跳过还是中止。

默认实现：
- `DefaultToolCallPolicy`：最多 10 轮，超过则抛出异常。工具错误时记录日志并跳过该工具，继续执行其他工具。

未来可选实现：
- `BudgetAwareToolCallPolicy`：根据 Token 预算决定是否继续
- `ModelDrivenToolCallPolicy`：让模型自己决定是否需要继续

### 5.12 MemoryManager 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/memory/MemoryManager.java`

**设计模式**：策略模式

**职责说明**：

`MemoryManager` 是记忆管理接口。它负责跨会话的记忆存储和读取。

核心方法：
- `remember(Session session, MemoryStrategy strategy)` — 从会话中提取记忆并存储。提取逻辑由 MemoryStrategy 决定。
- `recall()` — 读取所有已启用的记忆。
- `forget(String memoryId)` — 删除指定记忆。
- `buildContext(List<Memory> memories)` — 将记忆列表格式化为可注入上下文的字符串。
- `setStrategy(MemoryStrategy strategy)` — 切换记忆提取策略。


---

### 5.19 Persistence 持久化决策接口（v2.0 新增）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/persistence/`

**设计模式**：策略模式 + 命令模式

**职责说明**：

持久化决策层负责回答"什么时候存"的问题。与"如何存储"（由 MemoryStorage/SessionStorage 负责）完全解耦。

#### PersistenceSignal 枚举

`lyclaw-engine/src/main/java/lyjew/com/lyclaw/persistence/PersistenceSignal.java`

| 信号 | 含义 |
|------|------|
| `WRITE` | 立即落盘 |
| `DEFER` | 暂缓，积累更多后再落盘 |
| `SKIP` | 不需要落盘（如空内容） |

#### PersistenceDecision 值对象

`lyclaw-engine/src/main/java/lyjew/com/lyclaw/persistence/PersistenceDecision.java`

工厂方法创建：
- `PersistenceDecision.write(String reason)` — 立即落盘
- `PersistenceDecision.defer(String reason)` — 暂缓
- `PersistenceDecision.skip(String reason)` — 跳过

下游通过 `decision.shouldWrite()` 判断是否需要执行写入。

#### SessionPersistence 接口

`lyclaw-engine/src/main/java/lyjew/com/lyclaw/persistence/session/SessionPersistence.java`

核心方法：
- `evaluate(Session session, int turnCount, long millisSinceLastWrite)` — 决策当前轮次是否写入
- `evaluateOnClose(Session session)` — 决策会话关闭时是否写入（通常应 WRITE）

默认实现：
- `EveryTurnSessionPersistence`：每轮都存（当前行为，保持兼容）

未来实现：
- `TimeWindowSessionPersistence`：距上次写入不到 30 秒则暂缓
- `EndOfConversationSessionPersistence`：仅关闭时存

#### MemoryPersistence 接口

`lyclaw-engine/src/main/java/lyjew/com/lyclaw/persistence/memory/MemoryPersistence.java`

核心方法：
- `evaluate(MemoryWriteState writeState)` — 根据累积变更状态决策是否刷盘

参数 `MemoryWriteState` 值对象：
- `pendingChangeCount` — 待刷盘的变更次数（append 调用次数）
- `pendingCharCount` — 待刷盘的累积字符数
- `lastFlushTimestamp` — 上次刷盘时间戳
- `accumulate(String newContent)` — 追加一次变更，返回新状态（不可变）
- `reset()` — 刷盘后重置

默认实现：
- `ImmediateMemoryPersistence`：每次追加即刷盘（当前行为）

未来实现：
- `ThresholdMemoryPersistence`：累积 N 次或 N 字符后才刷盘

#### PersistenceExecutor 执行器

`lyclaw-engine/src/main/java/lyjew/com/lyclaw/persistence/executor/PersistenceExecutor.java`

系统中唯一负责"将决策映射到存储操作"的组件。
只认识存储接口，不认识任何策略。

核心方法：
- `executeSessionWrite(Session, PersistenceDecision)` — 决策 WRITE 时调 `sessionStorage.save()`
- `executeMemoryFlush(PersistenceDecision)` — 决策 WRITE 时调 `memoryManager.flush()`

#### 装配关系

```
ResponseBuildStage
 │
 │ Step 1: 追加消息到 Session 对象
 ├── session.getMessages().add(assistantMsg)
 │
 │ Step 2: 会话持久化决策
 ├── sessionPersistence.evaluate(session, turnCount, millisSinceLastWrite)
 │ └── 返回 PersistenceDecision（纯数据）
 ├── persistenceExecutor.executeSessionWrite(session, decision)
 │ └── if WRITE → sessionStorage.save(session)
 │
 │ Step 3: 记忆追加（只在内存，不刷盘）
 ├── memoryManager.append(content)
 │
 │ Step 4: 记忆持久化决策
 ├── memoryWriteState = memoryWriteState.accumulate(content)
 ├── memoryPersistence.evaluate(memoryWriteState)
 │ └── 返回 PersistenceDecision（纯数据）
 └── persistenceExecutor.executeMemoryFlush(decision)
     └── if WRITE → memoryManager.flush()
```

**设计要点**：
- `SessionPersistence` 不认识 `SessionStorage`
- `MemoryPersistence` 不认识 `MemoryManager`
- `PersistenceExecutor` 不认识任何策略
- 三者通过 `PersistenceDecision` 值对象通信，完全解耦
- 新增策略只需新建类 `implements SessionPersistence`/`MemoryPersistence` + `@Component`，0 行已有代码改动

### 5.13 MemoryStrategy 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/memory/MemoryStrategy.java`

**设计模式**：策略模式

**职责说明**：

`MemoryStrategy` 是记忆提取策略接口。它决定从会话中提取哪些信息作为记忆。

核心方法：
- `extract(Session session)` — 从会话中提取记忆内容。
- `shouldExtract(Session session)` — 判断是否需要从该会话中提取记忆。

默认实现：
- `ManualMemoryStrategy`：只有用户明确说"记住这个"时才提取

未来可选实现：
- `KeyEventMemoryStrategy`：检测到关键事件（如用户分享偏好、项目信息）时自动提取
- `AiDrivenMemoryStrategy`：让 AI 判断哪些信息值得记住

### 5.14 EventBus 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/event/EventBus.java`

**设计模式**：观察者模式

**职责说明**：

`EventBus` 是事件总线接口。它是模块间解耦通信的核心机制。

核心方法：
- `publish(Event event)` — 发布事件。所有订阅了该事件类型的监听器都会收到通知。
- `subscribe(Class<T> eventType, Consumer<T> handler)` — 订阅事件。返回一个 Subscription 对象，可用于取消订阅。
- `unsubscribe(Subscription subscription)` — 取消订阅。

默认实现：
- `InMemoryEventBus`：基于内存的事件总线，使用 `ConcurrentHashMap` 管理订阅关系。

未来可选实现：
- `KafkaEventBus`：基于 Kafka 的分布式事件总线（微服务场景）
- `RabbitMQEventBus`：基于 RabbitMQ 的事件总线
- `SpringEventBus`：基于 Spring ApplicationEvent 的事件总线

### 5.15 AgentCoordinator 类

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/agent/AgentCoordinator.java`

**设计模式**：中介者模式

**职责说明**：

`AgentCoordinator` 负责管理 Agent 的生命周期和通信。

核心方法：
- `spawn(Session parentSession, AgentTask task)` — 创建一个子 Agent 执行指定任务。
- `terminate(String agentId)` — 终止指定 Agent。
- `getStatus(String agentId)` — 获取 Agent 当前状态。
- `cascadeTerminate(String sessionId)` — 级联终止一个会话下的所有 Agent。

Agent 状态机：
- `IDLE` → `RUNNING` → (`WAITING_TOOL` → `RUNNING`)* → `COMPLETED` / `ERROR` / `TERMINATED`

第一版约束：
- 同一会话最多 1 个子 Agent 并发
- 子 Agent 超时时间 5 分钟
- 子 Agent 不可再 spawn 孙 Agent（深度限制为 1）

### 5.16 AgentChannel 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/agent/AgentChannel.java`

**设计模式**：中介者模式

**职责说明**：

`AgentChannel` 定义了 Agent 之间的通信拓扑。

核心方法：
- `send(AgentMessage message)` — 发送消息到指定 Agent。
- `receive(String agentId)` — 接收发给指定 Agent 的消息流。

默认实现：
- `StarAgentChannel`：星型拓扑，主 Agent 可以直接与任何子 Agent 通信，子 Agent 之间不能直接通信。

未来可选实现：
- `TreeAgentChannel`：树形拓扑，Agent 形成层级结构
- `MeshAgentChannel`：网状拓扑，任意 Agent 之间可以通信
- `BroadcastAgentChannel`：广播拓扑，消息发送给所有 Agent

### 5.17 ErrorPolicy 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/error/ErrorPolicy.java`

**设计模式**：策略模式

**职责说明**：

`ErrorPolicy` 是错误处理策略接口。它决定在模型调用失败、工具执行失败、超时等异常情况下如何处理。

核心方法：
- `onModelError(ModelCallException e, ChatContext context)` — 模型调用失败时的处理。返回一个 ChatResult（可能是重试后的结果、降级结果或错误信息）。
- `onToolError(ToolExecuteException e, ChatContext context)` — 工具执行失败时的处理。
- `onTimeout(ChatContext context, long elapsedMs)` — 超时处理。

默认实现：
- `DefaultErrorPolicy`：模型调用失败重试 1 次，工具执行失败返回错误信息，超时抛出异常。

未来可选实现：
- `RetryErrorPolicy`：失败重试 N 次
- `FallbackModelErrorPolicy`：失败时切换到备用模型
- `CircuitBreakerErrorPolicy`：连续失败后熔断

### 5.18 ModelProvider 接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/provider/ModelProvider.java`

（注意：此接口定义在 lyclaw-engine 或 lyclaw-core 中，实现在 lyclaw-adapter 中）

**设计模式**：工厂模式

**职责说明**：

`ModelProvider` 是模型适配器的提供者接口。引擎层通过此接口获取模型适配器，而不直接依赖 lyclaw-adapter 模块的具体类。

核心方法：
- `getAdapter(String provider)` — 根据厂商标识获取适配器。
- `listProviders()` — 列出所有可用的厂商。
- `configure(String provider, ModelConfig config)` — 配置指定厂商的适配器。

这种设计的优势：
- 引擎层完全不依赖 lyclaw-adapter 模块
- 未来替换适配器实现（如改用 gRPC 适配器），引擎层零修改
- 可以在测试中 mock 此接口

### 5.19 State 接口（toolcall 包）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/toolcall/State.java`

**设计模式**：状态模式

**接口定义**：
```java
@FunctionalInterface
public interface State {
    StateResult execute(ChatContext context);
}
```

**职责说明**：
`State` 是流式工具调用状态机中每个独立状态的接口。每个状态只做一件事：
- `ModelCallState` — 调用模型并消费 SSE 流
- `ToolDetectState` — 检测模型输出中是否有工具调用
- `ToolExecuteState` — 执行检测到的工具

**核心设计原则**：状态不知道下一个状态是谁，只返回自己的执行结果 `StateResult`。状态机引擎根据结果中的 `Signal` 查转换表决定流转。新增状态只需实现此接口并更新转换表，已有状态零修改。

### 5.20 StateEngine 接口（toolcall 包）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/toolcall/StateEngine.java`

**设计模式**：状态模式 + 策略模式

**接口定义**：
```java
public interface StateEngine {
    Flux<String> execute(ChatContext context, ModelAdapter adapter);
}
```

**职责说明**：
`StateEngine` 是状态机引擎接口，持有转换表，驱动"模型调用 → 检测工具 → 执行工具"的多轮循环。每次循环取当前状态 → 执行 → 读 signal → 查转换表 → 实例化下一状态。

**核心职责**：
1. 持有 `Map<当前状态class, Map<Signal, 下一状态>>` 转换表
2. 循环执行：for(round=0; round<MAX_ROUNDS && currentState!=null; round++)
3. 收集所有轮次产生的 `Flux<String>`，通过 `Flux.concat` 合并
4. 将工具状态事件 Flux（`__tool_event_flux__`）在每轮之间插入
5. 超过 MAX_ROUNDS（6）轮强制终止

### 5.21 Signal 枚举（toolcall 包）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/toolcall/Signal.java`

**枚举值**：

| 信号 | 含义 | 触发者 |
|------|------|--------|
| `STREAM_COMPLETED` | 流式模型调用完成 | ModelCallState |
| `SYNC_COMPLETED` | 同步模型调用完成 | SyncModelCallState |
| `NO_TOOL_CALLS` | 无工具调用，终止循环 | ToolDetectState |
| `TOOL_CALLS_FOUND` | 检测到工具调用 | ToolDetectState |
| `TOOLS_EXECUTED` | 工具执行完毕，继续下一轮 | ToolExecuteState |
| `ERROR` | 不可恢复错误 | 任意状态 |

**设计要点**：
- 每个状态发出一个信号，状态机引擎根据信号查转换表
- 信号不包含"下一个状态是什么"的信息——只有转换表知道
- ERROR 信号导致状态机终止循环，通过 `Flux.error` 传递给调用方

### 5.22 StateResult 类（toolcall 包）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/toolcall/StateResult.java`

**设计模式**：值对象

**核心字段**：
- `Signal signal` — 状态执行结果信号
- `Flux<String> outputFlux` — 状态产生的输出 Flux
- `List<ModelResponse.ToolCallRequest> toolCalls` — 检测到的工具调用列表
- `ModelResponse syncResponse` — 同步模式下的完整响应
- `String plainContent` — 流式模式提取的纯文本
- `String tokenUsage` — token 用量信息

**工厂方法**：

| 方法 | Signal | 场景 |
|------|--------|------|
| `streamCompleted(flux, plain, usage)` | STREAM_COMPLETED | 流式模型调用完成 |
| `syncCompleted(response)` | SYNC_COMPLETED | 同步模型调用完成 |
| `toolCallsFound(calls)` | TOOL_CALLS_FOUND | 检测到工具调用 |
| `noToolCalls()` | NO_TOOL_CALLS | 无工具调用 |
| `toolsExecuted()` | TOOLS_EXECUTED | 工具执行完毕 |
| `error(msg)` | ERROR | 执行出错 |

工厂方法消除外部代码的 null 判断和手动构造。`StateResult.toolCallsFound(calls)` 比 `new StateResult(Signal.TOOL_CALLS_FOUND, ...)` 清晰得多。

### 5.23 Sse 数据解析方法（ModelAdapter 接口）

**设计归属**：lyclaw-core，`lyjew.com.lyclaw.adapter.ModelAdapter`

**设计模式**：策略模式（每个适配器实现自己的解析逻辑）

**职责说明**：

SSE 数据解析是 `ModelAdapter` 的职责，不是引擎层的职责。理由：
- `parseResponse()` 已经是适配器负责解析原始响应——SSE 解析与 `parseResponse()` 一样，是适配器对自己输出格式的解析
- 引擎层不应该知道任何厂商格式细节（如 DeepSeek 的 `choices[0].delta.tool_calls` 路径）
- 新增厂商时，只需在适配器中增加 SSE 解析方法，引擎层零修改

**在 `ModelAdapter` 接口中新增三个 `default` 方法**：

```java
default List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
    return List.of();  // 默认空实现，不支持的厂商返回空列表
}

default String extractSsePlainText(String rawSSE) {
    return "";  // 默认空实现
}

default String extractSseTokenUsage(String rawSSE) {
    return "prompt=0 completion=0 total=0";  // 默认实现
}
```

**方法说明**：
- `extractSseToolCalls(rawSSE)` — 从 SSE 字符串中提取所有工具调用请求。处理跨多个 chunk 拼接的 tool_calls（id、name、arguments 分到多个 data 行）。
- `extractSsePlainText(rawSSE)` — 拼接所有 `delta.content` 字段，提取纯文本。
- `extractSseTokenUsage(rawSSE)` — 从最后一个含有 `"usage"` 字段的 chunk 中提取 token 用量。

**引擎层调用方式**：
```java
// ModelCallState.onComplete() 中
String raw = collector.toString();
List<ModelResponse.ToolCallRequest> calls = adapter.extractSseToolCalls(raw);
String plainText = adapter.extractSsePlainText(raw);
String tokenUsage = adapter.extractSseTokenUsage(raw);
```

**已知实现**：`DeepSeekOpenAIAdapter` 实现三个方法，解析 DeepSeek（OpenAI 兼容格式）的 SSE 流。

### 5.24 ToolCallEventEmitter 类（toolcall 包）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/toolcall/emitter/ToolCallEventEmitter.java`

**设计模式**：构建器模式

**职责说明**：
构建工具调用生命周期事件的 Flux。产生 SSE `event:tool_call` 格式的事件，前端据此显示工具调用进度。事件 Flux 被状态机引擎插入到模型输出的 Flux 之间。

**核心方法**：
- `buildBeforeEvent(toolName)` — 工具执行中事件。格式：`data:{"type":"tool_call","name":"xxx","status":"executing"}`
- `buildAfterEvent(toolName, result)` — 工具执行完成事件。格式：`data:{"type":"tool_call","name":"xxx","status":"done","result":"..."}`
- `buildErrorEvent(toolName, error)` — 工具执行失败事件。格式：`data:{"type":"tool_call","name":"xxx","status":"error","error":"..."}`

**JSON 转义**：
所有字符串字段（toolName、result、error）输出到 JSON 前做转义：`"` → `\"`，`\n` → `\\n`，`\r` → `\\r`。防止结果内容损坏 JSON 格式。

---

## 第六章：Engine 顶层抽象设计

### 6.1 设计动机

在传统的 AI 应用架构中，对话引擎通常是一个具体的类（如 `ChatService` 或 `AgentService`），所有对话逻辑都写在这个类中。当需要增加一种新的对话模式时——比如从"标准对话"扩展到"推理链对话"——通常的做法是：

1. 在原有的 `ChatService` 中加一个 `reasoningMode` 标志
2. 在 `execute()` 方法中加一个 if-else 分支
3. 每个新功能都在同一个类中膨胀

这样做的问题：
- 类越来越庞大，难以理解和维护
- 修改一个模式的逻辑可能影响其他模式
- 无法独立测试不同模式的逻辑
- 无法独立部署或优化不同模式的性能

LLM 应用正在快速发展，未来可能出现各种不同的对话范式：
- **标准对话**：一问一答，可能带工具调用
- **推理链（Chain of Thought）**：模型先思考再回答
- **规划执行**：将目标分解为子任务，逐个执行
- **检索增强（RAG）**：先从知识库检索，再结合检索结果回答
- **多模态**：处理图片、音频、视频输入
- **实时对话**：低延迟的流式对话
- **批处理**：批量处理大量请求

每一种范式对应一个"引擎"。引擎之间共享底层组件（工具、记忆、事件总线），但执行逻辑完全不同。

### 6.2 Engine 接口的核心设计

`Engine` 接口是引擎层的最高抽象。它只定义四个方法：

**`getName()`**：返回引擎的唯一标识名称。用于日志、监控和运维界面。

**`supports(ChatRequest request)`**：这是引擎选择的关键。每个引擎自己判断是否支持处理当前请求。EngineSelector 遍历所有引擎，选择第一个支持当前请求的引擎。

这样设计的好处是：
- 引擎的匹配逻辑由引擎自己决定，不需要中心化的规则引擎
- 新增引擎时，只需实现此方法返回 true 的条件，不会影响已有引擎的选择
- 可以通过优先级（如 `@Order` 注解）控制引擎的匹配顺序

**`execute(ChatRequest request)`**：执行对话，返回流式响应。使用 `Flux<String>` 而不是 `String`，是因为模型调用本身是流式的，引擎应保持这种特性，让上层可以实时消费流式输出。

**`getMetadata()`**：返回引擎的元信息，包括名称、描述、版本、能力列表、配置项。用于管理界面展示和运维监控。

### 6.3 DefaultEngine 的实现设计

`DefaultEngine` 是第一版的核心引擎实现。它使用 Pipeline 模式组织对话流程。

DefaultEngine 的 execute(ChatRequest request) 方法返回 Flux<String>，所有请求（流式/非流式）统一走 Pipeline 执行：

```java
pipeline = pipelineBuilder.build();
pipeline.execute(context);
```

**Pipeline 的 5 个 Stage**：
1. ContextBuildStage — 构建上下文（加载会话历史、注入记忆、注入工具列表）
2. InterceptorStage — 拦截器预处理（限流、日志、审计等横切关注点）
3. ToolCallLoopStage — 模型调用 + 工具执行循环（核心阶段）
4. MetricsStage — 指标采集
5. ResponseBuildStage — 响应构建 + 拦截器 postHandle

**执行流程**：
- **同步（非流式）**：5 个 Stage 通过 Chain 串行执行，Pipeline.execute() 返回后从 ChatContext 取 ChatResult
- **流式**：Pipeline 执行到 ToolCallLoopStage 时，内部检测 request.isStream()=true：
  1. 调用 adapter.chatStream() 获取 Flux<String>，存入 ChatContext 属性 __stream_flux__
  2. 调用 chain.next()，继续后续 Stage（不中断 Chain）
  3. MetricsStage 执行（只采集耗时，result 未产生）
  4. ResponseBuildStage 检测 __stream_flux__ 存在，将构建 ChatResult + 持久化注册到 Flux.doOnComplete
  5. Pipeline.execute() 返回后，DefaultEngine 从 ChatContext 取出 Flux（已叠加回调）返回给 Controller

**为什么流式不需要 breakChain**：
Stream 场景下，ToolCallLoopStage 返回的 Flux 不阻塞，后续 Stage 可以正常执行。
MetricsStage 不依赖 ChatResult（只从 TraceContext 取耗时信息）。
ResponseBuildStage 检测到流式模式，不立即构建 ChatResult（此时消息列表 content 为空），
而是把构建 + 持久化逻辑注册到 Flux.doOnComplete，在流输出完成后自动执行。

**核心原则**：流式和非流式**共享同一个 Pipeline**，不走两套编排逻辑。
所有 Stage 通过 Chain 统一遍历执行，结果在 ChatContext 中自然传递。
不需要 breakChain，不需要 NOOP_CHAIN，不需要"人工调 Stage"。

**Pipeline 自动注册机制**：
DefaultEngine 构造器中只注入 `PipelineBuilder`，不做任何手动 addStage：

```java
public DefaultEngine(..., PipelineBuilder pipelineBuilder, ...) {
    this.pipeline = pipelineBuilder.build();  // 已由 Spring 自动构建
}
```

新增 Stage 只需新建类 + `implements PipelineStage` + `@Component` + 设置 `getOrder()`，
Spring 启动时自动发现并注册，**DefaultEngine 不需要改一行代码**。

DefaultEngine 通过依赖注入获取所有需要的组件：
- `PipelineBuilder`：自动构建 Pipeline（已发现所有 Stage）
- `MemoryManager`：管理记忆
- `SessionStorage`：持久化会话
- `ToolRegistry`：管理工具
- `ModelProvider`：获取模型适配器
- `InterceptorChain`：管理拦截器

### 6.4 流式工具调用状态机设计

#### 6.4.1 设计动机

同步模式下，工具调用循环（ToolCallLoop）通过阻塞方式处理：
```
while(hasToolCalls) {
    response = adapter.chat(request);
    toolCalls = response.getToolCalls();
    for(toolCall : toolCalls) toolRegistry.execute(toolCall);
    request.setMessages(messages);
}
```

但**流式模式**下不能这样实现。原因：
1. `adapter.chatStream()` 返回的是 `Flux<String>`（SSE 流），不是 `ModelResponse`
2. 不能 `Flux.block()` 阻塞线程等待整个流收完——打破了流式实时推送的特性
3. 工具调用并不一定在流末尾出现（DeepSeek 的 tool_calls 可能出现在 SSE 流的中间、末尾或同时包含文本+工具）
4. 用户等待时间问题：如果用 CountDownLatch 同步等待整个流，用户要等到 API 返回完 + 工具执行完才开始看到输出 → 延迟翻倍

**解决方案**：将"模型调用 → 检测工具 → 执行工具"的多轮循环拆为独立**状态**，用**状态机引擎**驱动流转。

#### 6.4.2 状态机设计原则

1. **状态只返回自己的结果**：每个状态执行完后返回 `StateResult(signal, flux, toolCalls)`，不决定下一个是谁
2. **状态机引擎持有转换表**：`Map<当前状态class, Map<Signal, 下一状态>>`，负责流转控制
3. **轮次统一控制**：最多 6 轮，防止无限循环
4. **流式/同步共用一个状态机体系**：只替换 ModelCallState 为不同的实现

#### 6.4.3 核心组件

| 组件 | 职责 |
|------|------|
| `State` | 状态接口：`execute(ChatContext) → StateResult` |
| `StateEngine` | 状态机引擎接口：持有转换表，驱动循环 |
| `DefaultStateEngine` | 默认实现，构建流式/同步两个转换表 |
| `Signal` | 信号枚举：STREAM_COMPLETED / SYNC_COMPLETED / TOOL_CALLS_FOUND / NO_TOOL_CALLS / TOOLS_EXECUTED / ERROR |
| `StateResult` | 状态执行结果，含 signal + outputFlux + toolCalls |
| `ModelCallState` | 流式模型调用状态：后台线程消费 SSE，边收边发 |
| `SyncModelCallState` | 同步模型调用状态：直接调用 adapter.chat() |
| `ToolDetectState` | 工具检测状态：三路径检测 |
| `ToolExecuteState` | 工具执行状态：逐个执行，结果注入消息列表 |
| `ToolCallEventEmitter` | 工具调用事件 Flux 构建器（sse:event:tool_call） |
| `extractSseToolCalls()` | ModelAdapter 的 default 方法，各厂商适配器自行实现 |
| `extractSsePlainText()` | ModelAdapter 的 default 方法，提取 delta.content |
| `extractSseTokenUsage()` | ModelAdapter 的 default 方法，从 usage 字段提取 |

#### 6.4.4 状态转换图

```
                   ┌─────────────────────────────────────┐
                   │        ModelCallState               │
                   │  (后台线程消费 SSE, 边收边发)         │
                   │  Signal: STREAM_COMPLETED            │
                   └──────────────┬──────────────────────┘
                                  │
                                  ▼
                   ┌─────────────────────────────────────┐
                   │        ToolDetectState               │
                   │  三路径检测（见 6.4.5）               │
                   │  Signal: TOOL_CALLS_FOUND             │
                   │        : NO_TOOL_CALLS → 终止         │
                   └──────────────┬──────────────────────┘
                                  │ TOOL_CALLS_FOUND
                                  ▼
                   ┌─────────────────────────────────────┐
                   │        ToolExecuteState              │
                   │ 逐个执行工具 + 事件发射               │
                   │ 插入 assistant(tool_calls) 消息       │
                   │ 设 toolChoice=none 防止死循环          │
                   │ Signal: TOOLS_EXECUTED → ModelCall    │
                   │        : ERROR → 终止                 │
                   └─────────────────────────────────────┘
                                  │ TOOLS_EXECUTED
                                  ▼
                         回到 ModelCallState (下一轮)
```

同步模式用 `SyncModelCallState` 替换 `ModelCallState`，状态流转相同。

#### 6.4.5 ToolDetectState 三路径检测（优先级从高到低）

| 优先级 | 检测路径 | 触发条件 | 说明 |
|--------|---------|---------|------|
| 1 | `__tool_choice_executed__` 保护 | 已执行过强制工具调用 | 直接跳过，返回 NO_TOOL_CALLS。防止 toolChoice 死循环 |
| 2 | 同步模式 | `__sync_response__` 存在 | 从 ModelResponse.hasToolCalls() 检测 |
| 3 | toolChoice 显式指定 | request.getToolChoice() 非 auto/none | 直接构造 fake ToolCallRequest |
| 4 | 增量检测标志 | `__has_tool_call__` = true | 后台线程在 onData 中设的 |
| 5 | 后备：完整 collector 解析 | 后台线程完成后从 collector 全文解析 | 最后一道防线 |

**为什么需要 toolChoice 显式检测（路径 3）**：
Controller 检测到"时间/日期"关键词时设了 `tool_choice=current_time`。但流式模式下增量检测太慢（SSE 流的工具调用在 300-500ms 后才到达）。ToolDetectState 的 5 秒超时会先触发 → 判定 NO_TOOL_CALLS。通过直接检查 toolChoice，在 SSE 解析前就强制构造 fakeCall。

**为什么需要 `__tool_choice_executed__` 保护**：
第一次 ToolExecuteState 执行完 fakeCall 后 `setToolChoice("none")`，但 DeepSeek 在 `tool_choice=none` 下仍可能返回工具调用。后台线程的增量检测会再次检测到工具调用。双层防护：`tool_choice=none` + `__tool_choice_executed__=true` = 彻底切断工具调用循环。

#### 6.4.6 ModelCallState 后台线程 + Sinks 边收边发设计

**旧方案（同步收集，已废弃）**：
```
ModelCallState.handle() → CountDownLatch.await()
→ 后台线程消费完整 SSE 流 → collector 收完所有 data
→ 等 collectorLatch.countDown() → 再消费 collector
→ 再从 collector 全文解析 tool_calls
→ 最后通过 Flux.fromIterable 重放给 Controller
```
**问题**：用户等待时间 = API 延迟 + 全部收集时间（3+ 秒无输出）。

**新方案（边收边发）**：
```
ModelCallState.handle()
→ 创建 Sinks.Many<String>（实时数据通道）
→ 启动 daemon 后台线程
→ 立刻返回 StateResult(flux=sink.asFlux())
→ 后台线程调用 adapter.chatStream() 发起 HTTP 请求
→ 每次收到 data:
    1. sink.tryEmitNext(data) → 实时推送给 Controller
    2. collector.append(data) → 累计
    3. 增量检测工具调用 → 检测到时设 __has_tool_call__=true
→ onComplete:
    sink.tryEmitComplete()
    collectorLatch.countDown()
    保存 plainText + tokenUsage 到 context
```
**优势**：用户等待时间 ≈ API 首包延迟（约 200ms），后续内容边看边收。

**增量工具调用检测**：
- 后台线程每次收到 data 就调用 `sseParser.extractToolCalls(currentCollector)`
- 检测到工具调用时设 `__has_tool_call__ = true`
- ToolDetectState 检查此标志位，无需等 collector 收完

#### 6.4.7 StateEngine 状态转换表

**流式模式**：
| 当前状态 | 信号 | 下一状态 |
|----------|------|---------|
| ModelCallState | STREAM_COMPLETED | ToolDetectState |
| ToolDetectState | TOOL_CALLS_FOUND | ToolExecuteState |
| ToolDetectState | NO_TOOL_CALLS | null（终止） |
| ToolDetectState | ERROR | null（终止） |
| ToolExecuteState | TOOLS_EXECUTED | ModelCallState（下一轮） |
| ToolExecuteState | ERROR | null（终止） |

**同步模式**：
同上，`ModelCallState` → `SyncModelCallState`，信号 `STREAM_COMPLETED` → `SYNC_COMPLETED`。

#### 6.4.8 ToolExecuteState 消息插入逻辑

OpenAI/DeepSeek API 协议要求 messages 格式必须为：
```
[
    {"role": "user",    "content": "现在几点？"},
    {"role": "assistant", "content": "", "tool_calls": [{"id":"call_xxx","function":{...}}]},
    {"role": "tool",    "tool_call_id": "call_xxx", "content": "当前时间: 12:00"},
    {"role": "assistant", "content": "现在是 12:00"}
]
```
核心要求：`role=tool` 的消息必须跟在含 `tool_calls` 的 `role=assistant` 消息后。

ToolExecuteState 执行步骤：
1. 检查上一条消息是否是含 `tool_calls` 的 `assistant`，不是则插入
2. 逐个执行工具：`toolRegistry.execute(toolCall, context) → ToolResult`
3. 工具结果追加为 `role=tool` 消息（含 `tool_call_id`）
4. 通过 ToolCallEventEmitter 发送 executing/done/error SSE 事件
5. 设 `toolChoice="none"` + `__tool_choice_executed__=true`

#### 6.4.9 SSE 事件格式设计

**事件协议**：

| 事件类型 | 格式 | 说明 |
|----------|------|------|
| message | `event:message\ndata:{文本片段}` | 模型输出的文本块，每 5 字符合并一次 |
| tool_call | `event:tool_call\ndata:{"type":"tool_call","name":"xxx","status":"executing"}` | 工具执行中 |
| tool_call | `event:tool_call\ndata:{"type":"tool_call","name":"xxx","status":"done","result":"..."}` | 工具执行完成 |
| tool_call | `event:tool_call\ndata:{"type":"tool_call","name":"xxx","status":"error","error":"..."}` | 工具执行失败 |
| message | `event:message\ndata:[DONE]` | 流结束标记 |

**SSE 逐字 buffer 策略**：
- 如果不加 buffer，后端每次 `SseEmitter.send()` 只发一个 token（如"当"、"前"、"时"、"间"）
- 前端每 token 都触发 Vue 3 DOM diff，开销大
- buffer 策略：累积至少 5 字符再发一次 `event:message`
- 流结束前 flush 剩余字符

**Tomcat buffer 关闭**：
`response.setBufferSize(0)` — 防止 Tomcat 8KB 缓存导致所有 SSE 事件一次发出。

#### 6.4.10 文件清单与包结构

```
lyclaw-engine/src/main/java/lyjew/com/lyclaw/toolcall/
├── State.java              # 状态接口（@FunctionalInterface）
├── StateEngine.java        # 状态机引擎接口
├── Signal.java             # 信号枚举
├── StateResult.java        # 状态执行结果（含工厂方法）
├── impl/
│   ├── DefaultStateEngine.java    # 默认状态机引擎实现
│   ├── ModelCallState.java        # 流式模型调用状态
│   ├── SyncModelCallState.java    # 同步模型调用状态
│   ├── ToolDetectState.java       # 工具检测状态
│   └── ToolExecuteState.java      # 工具执行状态
├── emitter/
│   └── ToolCallEventEmitter.java  # 工具调用事件 Flux 构建器
└── parser/
    # 无需 parser 包 — SSE 解析由 ModelAdapter.extractSse*() 提供

lyclaw-web/src/main/java/lyjew/com/lyclaw/controller/
└── ChatController.java               # SSE 透传 Controller
```

#### 6.4.11 与已有设计的关系

| 已有组件 | 与状态机的关系 |
|----------|---------------|
| ToolCallLoopStage | 改为委托 DefaultStateEngine，不再自己搞循环 |
| ToolCallLoop | 状态机模式下不再使用，保持兼容以备同步使用 |
| ToolRegistry | ToolExecuteState 调用 toolRegistry.execute() |
| ErrorPolicy | ToolExecuteState 调用 errorPolicy.onToolError() 决定重试/跳过/终止 |
| ChatContext | 状态间通过 context 属性（`__adapter__`、`__round__`、`__has_tool_call__` 等）传递数据 |
| ModelAdapter | ModelCallState/SyncModelCallState 调用 adapter.chatStream()/chat() |

### 6.5 未来引擎的实现设想

**ReasoningEngine（推理引擎）**：

不同于标准对话的一问一答，推理引擎会执行 Chain-of-Thought：
1. 模型先输出推理过程
2. 对推理过程进行验证
3. 可能调用工具辅助推理
4. 最终输出结论

推理引擎的执行流程与 DefaultEngine 不同（多了一个验证阶段），但它共享同一套 ToolRegistry 和 MemoryManager。

**PlanningEngine（规划引擎）**：

规划引擎将复杂目标分解为子任务：
1. 分析用户目标
2. 制定执行计划（多个子任务）
3. 逐个执行子任务（每个子任务可能 spawn 子 Agent）
4. 汇总结果
5. 输出最终回答

规划引擎需要 AgentCoordinator 的更高级功能（如并行 spawn）。

**RagEngine（RAG 引擎）**：

检索增强生成引擎：
1. 从用户问题中提取检索关键词
2. 调用检索工具从知识库中查询
3. 将检索结果注入上下文
4. 基于检索结果生成回答

RAG 引擎需要一个额外的"检索阶段"，这个阶段可能需要向量数据库的支持。

---

## 第七章：Pipeline 可编排管道设计

### 7.1 设计动机

传统的对话处理流程通常写在一个方法中：

```java
void execute() {
    构建上下文();
    执行拦截器();
    调用模型();
    if (需要工具) {
        执行工具();
        再次调用模型();
    }
    构建响应();
}
```

这种写法的问题是：**流程是固定的，修改流程需要修改代码。**

- 如果要在"调用模型"之后、"构建响应"之前加一个"安全审核"阶段，需要改 `execute()` 方法
- 如果要根据条件跳过某个阶段，需要在 `execute()` 中加 if-else
- 如果要改变阶段的执行顺序，需要调整代码行的顺序
- 如果在不同的场景需要不同的流程，需要写多个 `execute()` 方法

这些问题在原型阶段不明显，但在长期维护中会成为代码腐化的根源。

### 7.2 Pipeline 的设计思路

Pipeline 模式将处理流程分解为多个独立的 Stage（阶段），每个 Stage 负责一个明确的职责。Pipeline 只是一个容器，负责按顺序调用 Stage。

**核心思想：流程与步骤分离。**

- 流程由 Pipeline 控制（按顺序调用 Stage）
- 步骤由 Stage 实现（每个 Stage 独立处理）
- 编排由 PipelineBuilder 完成（决定哪些 Stage 参与、以什么顺序）

这样的设计使得：
- 新增一个处理阶段：新建一个 Stage 类，在 Builder 中 add 即可
- 移除一个处理阶段：在 Builder 中 remove 即可
- 调整阶段顺序：在 Builder 中调整 add 顺序即可
- 条件执行：Stage 的 `supports()` 方法返回 false 时自动跳过

### 7.3 PipelineBuilder 的编排能力

PipelineBuilder 提供以下编排能力：

**基本编排**：
- `addStage(stage)` — 在末尾添加阶段
- `build()` — 构建 Pipeline

**高级编排**：
- `addStageBefore(existingClass, newStage)` — 在某个阶段之前插入
- `addStageAfter(existingClass, newStage)` — 在某个阶段之后插入
- `replaceStage(oldClass, newStage)` — 替换某个阶段
- `removeStage(stageClass)` — 移除某个阶段

**条件编排**：
- Stage 自身通过 `supports(context)` 决定是否执行
- Builder 可以根据配置动态添加或跳过 Stage

这种编排能力使得运维人员可以通过配置文件调整 Pipeline 的行为，而不需要修改代码。例如：

```yaml
lyclaw:
  engine:
    pipeline:
      stages:
        - context-build
        - rate-limit
        - cache          # 如果不需要缓存，删除这行即可
        - model-call
        - tool-loop
        - metrics
        - response-build
```

### 7.4 Stage 之间的数据传递

Pipeline 中的 Stage 通过 `ChatContext` 对象传递数据。ChatContext 是一个可变的上下文对象，包含：

- 原始 ChatRequest
- 当前构建的消息列表
- 工具调用历史
- Token 用量累计
- 执行时间统计
- 可扩展的元数据 Map

Stage 可以读取 ChatContext 的任何字段，也可以向其中添加新的数据。后面的 Stage 可以读取前面 Stage 添加的数据。

这种设计避免了 Stage 之间的直接耦合（它们不需要互相知道对方的存在），同时保持了数据流的灵活性。

### 7.5 第一版的默认阶段编排

第一版的 DefaultEngine 使用以下阶段编排：

```
Pipeline = PipelineBuilder
    .addStage(ContextBuildStage)        ← 阶段1：构建上下文
    .addStage(InterceptorStage)         ← 阶段2：拦截器预处理
    .addStage(ToolCallLoopStage)        ← 阶段3：模型调用+工具循环
    .addStage(MetricsStage)            ← 阶段4：指标采集
    .addStage(ResponseBuildStage)      ← 阶段5：响应构建 + 持久化
    .build();
```

流式和非流式**完全共享同一组 Stage 实例和同一个 Pipeline**，在 Pipeline.execute() 中统一遍历执行，区别在 Stage 内部策略：

**同步（非流式）执行**：
```
Pipeline.execute(context)
  └─ DefaultChain.proceed(context)
      ├─ ContextBuildStage  → chain.next()    ← 构建消息列表
      ├─ InterceptorStage   → chain.next()    ← 拦截器预处理
      ├─ ToolCallLoopStage  → stream=false
      │   └─ adapter.chat() 循环直至无工具调用 → chain.next()
      ├─ MetricsStage       → chain.next()    ← 采集指标
      └─ ResponseBuildStage → chain.next()    ← 构建 ChatResult + 持久化
DefaultEngine 从 context.getResult() 取 ChatResult → Flux.just(content)
```

**流式执行**：
```
Pipeline.execute(context)
  └─ DefaultChain.proceed(context)
      ├─ ContextBuildStage  → chain.next()    ← 构建消息列表（同步）
      ├─ InterceptorStage   → chain.next()    ← 同步执行
      ├─ ToolCallLoopStage  → stream=true
      │   ├─ adapter.chatStream() → Flux<String>
      │   ├─ doOnNext: 收集原始 SSE 数据
      │   ├─ doOnComplete: 写入 __stream_full_content__ / __stream_token_usage__
      │   ├─ Flux 存入 __stream_flux__
      │   └─ chain.next()                    ← 不中断，继续后续 Stage
      ├─ MetricsStage       → chain.next()    ← 只采集耗时（result 尚未产生）
      └─ ResponseBuildStage → stream分支
          └─ 检测 __stream_flux__ 存在
          └─ 把构建 ChatResult + 持久化注册到 Flux.doOnComplete
          └─ chain.next()

DefaultEngine 从 __stream_flux__ 取出 Flux（已叠加回调）→ 返回给 Controller

Controller 消费 Flux 的同时，Flux.doOnComplete 中执行：
  └─ 构建 ChatResult → postHandle → 持久化记忆 → 持久化会话
```

**关键设计原则**：
- Pipeline 本身不知道流式/同步的区别，所有 Stage 通过 Chain 统一遍历
- 结果在各 Stage 之间通过 ChatContext 属性自然传递
- 不需要 breakChain，不需要 NOOP_CHAIN，不需要任何"人工调 Stage.process()"的代码
- 流式的异步处理逻辑封装在 ResponseBuildStage 内部，对 Pipeline 透明

**各阶段职责**：

**ContextBuildStage**：
- 从 SessionStorage 加载会话历史
- 从 MemoryManager 加载长期记忆
- 选择合适的 ContextBuilder 策略
- 构建发送给模型的消息列表
- 注入 system prompt 和可用工具列表

**InterceptorStage**：
- 按 @Order 顺序执行所有拦截器的 preHandle()
- 任何一个拦截器抛异常都会中断流程

**ToolCallLoopStage**：
- **非流式模式**：调用 ModelAdapter.chat() 阻塞获取完整响应。
  如果模型返回工具调用请求，执行工具 → 再次调用模型 → 循环直至无工具调用。
- **流式模式**：委托 {@link StateEngine} 驱动"模型调用 → 检测工具 → 执行工具"的多轮状态机循环。
  状态机引擎持有转换表，按轮次执行各状态，收集每轮的 Flux，最终通过 Flux.concat 合并为一个 Flux 返回。
  详见 6.4 流式工具调用状态机设计。

**MetricsStage**：
- 记录 Token 用量
- 记录延迟
- 发布 TokenConsumedEvent

**ResponseBuildStage**：
- **同步模式**：从消息列表提取 content，构建 ChatResult，执行 postHandle，持久化记忆+会话
- **流式模式**：检测 __stream_flux__ 存在，把构建+持久化注册到 Flux.doOnComplete
- 返回最终结果

---

---

## 第八章：ContextBuilder 上下文构建设计

### 8.1 设计动机

在 AI 对话中，"上下文"是指发送给模型的完整消息列表。上下文的质量直接影响 AI 回复的质量。上下文构建需要解决以下问题：

**问题一：上下文窗口有限**

每个模型都有最大上下文长度（如 4096 tokens、8192 tokens、128K tokens 等）。当对话历史过长时，无法将所有消息都发送给模型。

**问题二：信息密度不均**

对话历史中并非所有消息都同等重要。有些消息包含关键信息（如用户偏好、项目需求），有些消息只是过渡性的寒暄。

**问题三：记忆需要注入**

除了当前会话的历史消息，还需要注入跨会话的长期记忆。记忆的注入方式和位置需要精心设计。

**问题四：不同场景需要不同策略**

- 短对话场景：全量窗口即可
- 长对话场景：需要滑动窗口或摘要压缩
- 多会话场景：需要注入跨会话记忆
- RAG 场景：需要注入检索结果

### 8.2 ContextBuilder 的策略设计

ContextBuilder 是一个策略接口，不同的实现代表不同的上下文构建策略。

核心设计理念：**策略自描述 + 自动选择。**

每个 ContextBuilder 实现都包含一个 `supports(ChatContext context)` 方法，用于判断自己是否适用于当前场景。上下文构建阶段会遍历所有已注册的 ContextBuilder，选择第一个 `supports()` 返回 true 的来执行。

这种设计的优势：
- 新增策略：新建类实现 ContextBuilder，supports() 返回匹配条件
- 条件由策略自己决定：不需要中心化的规则引擎
- 多策略共存：不同场景自动选择不同策略
- 兜底机制：FullWindowContextBuilder 始终返回 true，作为兜底

### 8.3 第一版的三种策略

#### FullWindowContextBuilder（全量窗口策略）

**适用场景**：对话历史较短，所有消息都能放入上下文窗口。

**策略逻辑**：
- `supports()`：始终返回 true（作为兜底）
- `build()`：将所有消息按时间顺序排列，注入 system prompt，注入记忆，注入工具列表

**优点**：实现简单，保留完整上下文。
**缺点**：对话过长时会超出模型上下文窗口。

#### SlidingWindowContextBuilder（滑动窗口策略）

**适用场景**：对话历史较长，但只有最近的消息最重要。

**策略逻辑**：
- `supports()`：当消息数超过阈值（如 50 条）时返回 true
- `build()`：保留最近的 N 条消息（如 20 条），丢弃较早的消息。但 system prompt 和记忆始终保留。

**优点**：控制 token 消耗，关注最新信息。
**缺点**：可能丢失较早的重要信息。

#### SummaryContextBuilder（摘要压缩策略）

**适用场景**：对话历史非常长，需要保留早期信息但原始消息太占 token。

**策略逻辑**：
- `supports()`：当消息数超过较高阈值（如 100 条）时返回 true
- `build()`：将较早的消息用模型做摘要，摘要结果替换原始消息。最近的消息保持原样。

**优点**：保留早期信息的语义，大幅减少 token 消耗。
**缺点**：摘要过程本身消耗 token，且可能丢失细节信息。

### 8.4 上下文构建的完整流程

```
ContextBuildStage.execute(context)
  │
  ├── 1. 加载会话历史
  │     从 SessionStorage 读取当前会话的所有消息
  │
  ├── 2. 加载长期记忆
  │     从 MemoryManager.recall() 读取所有已启用的记忆
  │
  ├── 3. 选择上下文策略
  │     遍历所有 ContextBuilder 实现，调用 supports()
  │     选择第一个返回 true 的策略
  │
  ├── 4. 构建上下文
  │     调用选中策略的 build() 方法
  │     build() 内部：
  │       ├── 4.1 确定消息窗口范围
  │       ├── 4.2 对超出窗口的消息执行压缩或丢弃
  │       ├── 4.3 在消息列表开头注入 system prompt
  │       ├── 4.4 在最前面注入记忆内容
  │       └── 4.5 返回构建后的消息列表
  │
  ├── 5. 注入工具列表
  │     从 ToolRegistry.getAllDefinitions() 获取可用工具
  │     将工具定义添加到发送给模型的消息中
  │
  └── 6. 输出 ChatContext
        包含完整的消息列表、token 估算值等
```

> **⚠️ 记忆角色选择说明（v2 修复，2026-04-30）**：
> FullWindowContextBuilder 将长期记忆注入为 **"user"** 角色消息
> （而非原始设计的 "system"），因为 DeepSeekOpenAIAdapter.buildMessages()
> 会过滤掉 role=system 的消息（用 ChatRequest.systemPrompt 替代），
> 导致记忆无法传递给模型。

### 8.5 未来扩展的上下文策略

**SemanticWindowContextBuilder（语义窗口策略）**：
- 使用 Embedding 计算每条历史消息与当前问题的语义相似度
- 只保留相似度最高的消息
- 适合需要精确上下文匹配的场景

**PriorityContextBuilder（优先级策略）**：
- 为每条消息分配优先级（用户标记"重要"的消息优先级更高）
- 优先保留高优先级消息
- 适合用户需要手动控制上下文的场景

**TimeDecayContextBuilder（时间衰减策略）**：
- 消息的重要性随时间的增加而衰减
- 最近的消息权重最高，较旧的消息权重逐渐降低
- 窗口大小固定，但通过权重影响模型对消息的关注度

**RuleBasedContextBuilder（规则驱动策略）**：
- 通过配置规则决定哪些消息保留、哪些丢弃
- 规则可以是关键词匹配、消息角色、消息长度等
- 适合有明确上下文管理规则的场景

---

## 第九章：Interceptor 拦截器链设计

### 9.1 设计动机

在请求处理流程中，有许多横切关注点（cross-cutting concerns）需要在请求前和请求后执行：

- **限流**：检查请求频率是否超限
- **日志**：记录请求和响应的详细信息
- **脱敏**：对敏感信息进行脱敏处理
- **认证**：验证用户身份
- **缓存**：检查是否有缓存的响应
- **审计**：记录操作审计日志
- **指标**：采集性能指标

如果将这些逻辑直接写在核心流程中，会导致：
- 核心流程代码膨胀，难以理解
- 横切关注点的修改影响核心流程
- 不同关注点之间互相干扰
- 新增关注点需要修改核心流程代码

拦截器链模式将这些横切关注点从核心流程中分离出来，形成独立的拦截器。每个拦截器只负责一个关注点，拦截器之间通过责任链模式串联。

### 9.2 拦截器链的设计

拦截器链由以下组件构成：

**Interceptor 接口**：
- `getOrder()`：返回执行顺序，数字越小越先执行
- `preHandle(context)`：请求前处理，可以修改上下文或中断请求
- `postHandle(result)`：请求后处理，可以修改响应

**InterceptorChain 管理器**：
- 启动时扫描所有 Interceptor 实现
- 按 getOrder() 排序
- 提供 proceed() 方法驱动链的执行

**执行流程**：
```
请求到达
  │
  ▼
InterceptorChain.proceed(context)
  │
  ├── Interceptor[0].preHandle(context)
  │   │ 可能：修改 context、抛异常中断、调用 chain.skipToNext()
  │   │
  ├── Interceptor[1].preHandle(context)
  │   │
  ├── Interceptor[2].preHandle(context)
  │   │
  ├── ... （核心流程执行）...
  │   │
  ├── Interceptor[2].postHandle(result)
  │   │
  ├── Interceptor[1].postHandle(result)
  │   │
  └── Interceptor[0].postHandle(result)
  │
  ▼
返回最终结果
```

### 9.3 第一版的拦截器

**RateLimitInterceptor（限流拦截器）— order=10**

职责：检查当前用户/IP 的请求频率是否超限。

preHandle 逻辑：
- 从 ChatContext 中获取用户标识/IP
- 查询 Redis/内存中的请求计数
- 如果超限，抛出 RateLimitExceededException
- 如果未超限，计数器加 1，继续执行

postHandle 逻辑：无。

**LoggingInterceptor（日志拦截器）— order=100**

职责：记录请求和响应的日志。

preHandle 逻辑：
- 记录请求开始时间
- 打印请求的基本信息（用户ID、消息长度、模型名）

postHandle 逻辑：
- 计算请求耗时
- 记录 Token 用量
- 打印响应的基本信息

**SensitiveDataInterceptor（脱敏拦截器）— order=50**

职责：对输入中的敏感信息进行脱敏。

preHandle 逻辑：
- 扫描用户输入中的敏感信息（手机号、邮箱、身份证号等）
- 将敏感信息替换为脱敏后的占位符

postHandle 逻辑：无（第一版不做响应脱敏）。

### 9.4 未来扩展的拦截器

**CacheInterceptor（缓存拦截器）— order=20**

- preHandle：检查是否有相同请求的缓存结果，有则直接返回缓存，跳过后续所有拦截器和核心流程
- postHandle：将当前结果存入缓存

**AuthInterceptor（认证拦截器）— order=5**

- preHandle：验证 API Key、JWT Token 或 OAuth Token
- postHandle：无

**AuditInterceptor（审计拦截器）— order=300**

- preHandle：无
- postHandle：将完整请求和响应写入审计日志

**CostControlInterceptor（成本控制拦截器）— order=30**

- preHandle：检查用户当日成本是否超限，超限则拒绝请求
- postHandle：记录本次请求的成本

**FeatureFlagInterceptor（功能开关拦截器）— order=15**

- preHandle：检查功能开关，决定是否启用某些特性
- postHandle：无

### 9.5 拦截器的分支和并行能力

拦截器链支持两种高级能力：

**分支（跳过后续拦截器）**：
如果某个拦截器决定跳过后续的拦截器，可以调用 `chain.skipToNext()` 或抛出一个特殊的 `SkipInterceptorException`。例如，CacheInterceptor 在命中缓存时，可以直接返回缓存结果，不执行后续拦截器。

**并行（异步执行）**：
如果一个拦截器的 postHandle 不需要阻塞主管道（如 MetricsInterceptor 上报指标），可以使用 `@Async` 注解异步执行。这样不会影响响应的返回速度。

---

## 第十章：ToolExecutor 工具执行设计

### 10.1 设计动机

在 AI 对话中，模型可能需要调用外部工具来获取信息或执行操作。例如：

- 用户问"今天北京天气怎么样？"，模型需要调用天气查询工具
- 用户问"帮我计算 123 * 456"，模型需要调用计算器工具
- 用户问"搜索最新的 Java 21 特性"，模型需要调用网络搜索工具
- 用户问"读取 /tmp/config.json 文件"，模型需要调用文件读取工具（MCP）

工具调用涉及以下问题：

**问题一：工具发现**
模型需要知道当前有哪些可用工具。这个列表是动态变化的（可以运行时添加 MCP 工具）。

**问题二：工具执行**
模型返回工具调用请求后，需要找到对应的工具并执行。执行可能涉及超时、错误处理、结果截断。

**问题三：循环控制**
模型可能在一次对话中多次调用工具。需要控制最大轮次，防止死循环。

**问题四：工具来源多样化**
工具可能来自多种来源：内置工具（代码写死）、MCP 工具（外部进程）、用户自定义工具（插件）。

### 10.2 Tool 接口设计

Tool 是所有工具的抽象接口。它只定义三个核心方法：

**`getName()`**：返回工具的唯一名称。内置工具如 "web_search"、"calculator"，MCP 工具如 "mcp_filesystem_read_file"。

**`getDefinition()`**：返回工具的定义，包括名称、描述、参数 JSON Schema。这个定义会被发送给模型，让模型知道工具的功能和调用方式。

**`execute(Map<String, Object> arguments)`**：执行工具，返回 ToolResult。ToolResult 包含：
- 执行状态：SUCCESS / ERROR / TIMEOUT
- 执行结果：文本内容
- 错误信息：如果执行失败
- 执行耗时：毫秒

**`getTimeout()`**：返回工具的超时时间。

### 10.3 ToolRegistry 注册表设计

ToolRegistry 是所有工具的中心注册表。它负责：

**注册管理**：
- 启动时，Spring 自动注入所有 `Tool` 实现，按名称存入 Map
- 支持运行时动态注册和移除（MCP Server 连接/断开时）

**工具发现**：
- `get(name)`：根据名称获取工具
- `getAll()`：获取所有工具
- `getAllDefinitions()`：获取所有工具的定义列表（用于发送给模型）

**工具执行**：
- `execute(name, arguments)`：根据名称查找工具并执行
- 自动处理超时（使用 CompletableFuture 或超时线程池）
- 自动截断过大的结果（超过配置的最大长度）

### 10.4 第一版的内置工具

**WebSearchTool（网络搜索工具）**：
- 功能：搜索网络，返回相关结果摘要
- 参数：query（搜索关键词）
- 超时：30秒
- 结果截断：50KB

**CalculatorTool（计算器工具）**：
- 功能：计算数学表达式
- 参数：expression（数学表达式字符串）
- 超时：5秒
- 结果截断：无限制

**CurrentTimeTool（当前时间工具）**：
- 功能：返回当前日期和时间
- 参数：无
- 超时：1秒

**McpToolAdapter（MCP 工具适配器）**：
- 功能：将外部 MCP Server 提供的工具适配为 Tool 接口
- 参数：由 MCP Server 定义
- 超时：由 MCP Server 配置决定

### 10.5 ToolCallLoop 循环设计

ToolCallLoop 是工具调用的核心循环组件。它负责"调用模型 → 检查工具调用 → 执行工具 → 注入结果 → 再次调用模型"的循环。

**循环流程**：

```
ToolCallLoop.execute(context)
  │
  ├── 初始化：rounds = 0
  │
  ├── 循环开始：
  │   │
  │   ├── 1. 调用模型
  │   │     ModelResponse response = modelProvider.getAdapter().chat(context)
  │   │
  │   ├── 2. 检查是否有工具调用
  │   │     if (!response.hasToolCalls()) {
  │   │         return response;  // 无工具调用，正常退出循环
  │   │     }
  │   │
  │   ├── 3. 执行工具
  │   │     for (ToolCall tc : response.getToolCalls()) {
  │   │         try {
  │   │             ToolResult result = toolRegistry.execute(tc.getName(), tc.getArguments())
  │   │             将 result 注入 context
  │   │         } catch (Exception e) {
  │   │             调用 toolCallPolicy.onToolError(tc, e, rounds) 决定如何处理
  │   │         }
  │   │     }
  │   │
  │   ├── 4. 判断是否继续
  │   │     if (!toolCallPolicy.shouldContinue(context, rounds)) {
  │   │         break;  // 策略决定终止
  │   │     }
  │   │
  │   ├── 5. 轮次递增
  │   │     rounds++
  │   │
  │   └── 循环回到步骤1
  │
  └── 循环结束，返回最终结果
```

循环终止条件：
1. 模型不再返回工具调用请求（finishReason="stop"）
2. ToolCallPolicy.shouldContinue() 返回 false
3. 达到 ToolCallPolicy.getMaxRounds() 的最大轮次
4. 发生不可恢复的错误

### 10.6 ToolCallPolicy 策略设计

ToolCallPolicy 是循环终止策略接口。

**默认策略（DefaultToolCallPolicy）**：
- 最大轮次：10
- 工具错误处理：记录日志，跳过该工具，继续执行其他工具
- 超过最大轮次：抛出 ToolCallLimitExceededException

**未来可选策略**：

**BudgetAwareToolCallPolicy**：
- 根据总 Token 预算决定是否继续
- 当已使用的 Token 接近预算上限时终止循环
- 适合需要控制成本的场景

**ModelDrivenToolCallPolicy**：
- 让模型自己决定是否需要继续调用工具
- 在每次循环后询问模型"是否还需要调用其他工具？"
- 适合复杂、开放式的工具调用场景

### 10.7 流式工具调用状态机设计

流式场景下的工具调用不能像同步模式那样阻塞等待执行结果，需要特殊设计。

本架构使用**状态机模式**（State Pattern）解决这个问题，实际实现使用 `State` / `StateEngine` 接口替代了设计初期的 `StreamToolCallState` / `StreamToolCallStateMachine` 命名。

#### 10.7.1 状态机结构

```
package lyjew.com.lyclaw.toolcall/

State（状态接口）
  └─ StateResult execute(ChatContext context)

StateEngine（状态机引擎接口）
  └─ Flux<String> execute(ChatContext context, ModelAdapter adapter)

Signal（信号枚举）
   ├─ STREAM_COMPLETED     ← 流式模型调用完成
   ├─ SYNC_COMPLETED       ← 同步模型调用完成
   ├─ NO_TOOL_CALLS        ← 无工具调用（终止循环）
   ├─ TOOL_CALLS_FOUND     ← 检测到工具调用
   ├─ TOOLS_EXECUTED       ← 工具执行完毕，继续下一轮
   └─ ERROR                ← 不可恢复错误

StateResult（状态执行结果）
   ├─ Signal signal         ─ 执行信号
   ├─ Flux<String> flux     ─ 当前状态产生的输出
   ├─ List<ToolCallRequest> ─ 工具调用列表
   ├─ ModelResponse         ─ 同步模式响应
   ├─ String plainContent   ─ 流式模式提取的纯文本
   └─ String tokenUsage     ─ Token 用量

具体实现：

DefaultStateEngine（状态机引擎，@Component）
  ├─ 持有 streamTable / syncTable 两张转换表
  ├─ 执行循环：for(round=0; round<MAX_ROUNDS(6); round++)
  │   ├─ currentState.execute(context) → StateResult
  │   ├─ 收集 result.outputFlux → allFluxes
  │   ├─ 检查 result.signal → 查转换表 → 实例化下一状态
  │   └─ 插入 __tool_event_flux__（每轮之间）
  └─ Flux.concat(allFluxes) → 返回合并的 Flux

ModelCallState（流式模型调用）
  ├─ 启动 daemon 后台线程
  ├─ 创建 Sinks.Many<String> 实时数据通道
  ├─ 后台线程调用 adapter.chatStream() → 消费 SSE
  │   ├─ onData: sink.tryEmitNext(data) + collector.append(data) + 增量工具检测
  │   └─ onComplete: sink.tryEmitComplete() + latch.countDown()
  └─ 立即返回 StateResult(flux=sink.asFlux()) — 不阻塞

SyncModelCallState（同步模型调用）
  └─ adapter.chat() → ModelResponse → 存入 __sync_response__

ToolDetectState（工具检测）
  ├─ 路径1: 已执行强制工具 → 跳过
  ├─ 路径2: 同步模式 → ModelResponse.hasToolCalls()
  ├─ 路径3: toolChoice 显式指定 → 构造 fakeCall
  ├─ 路径4: 增量检测标志 __has_tool_call__
  └─ 路径5: 后备从完整 collector 解析

ToolExecuteState（工具执行）
  ├─ 插入 assistant(tool_calls) 消息（满足 API 协议）
  ├─ 逐个执行工具 → role=tool 追加到消息列表
  ├─ 触发 ToolCallEventEmitter 事件
  └─ 设 toolChoice="none" + __tool_choice_executed__=true
```

#### 10.7.2 状态转换表

**流式模式**：

| 当前状态 | Signal | 下一状态 |
|----------|--------|---------|
| ModelCallState | STREAM_COMPLETED | ToolDetectState |
| ToolDetectState | TOOL_CALLS_FOUND | ToolExecuteState |
| ToolDetectState | NO_TOOL_CALLS | null（终止） |
| ToolDetectState | ERROR | null（终止） |
| ToolExecuteState | TOOLS_EXECUTED | ModelCallState（下一轮） |
| ToolExecuteState | ERROR | null（终止） |

**同步模式**：替换 `ModelCallState` → `SyncModelCallState`，信号 `STREAM_COMPLETED` → `SYNC_COMPLETED`。

转换表在 `DefaultStateEngine.buildStreamTable()` / `buildSyncTable()` 中构建。

#### 10.7.3 边收边发设计

**旧方案（已废弃）**：CountDownLatch.await() 等待整个 SSE 流收完再重放。用户等待时间 = API 延迟 + 全部收集时间。

**新方案（实际实现）**：

```
ModelCallState.execute()
  → 创建 Sinks.Many<String>（实时数据通道）
  → 启动 daemon 后台线程
  → 立即返回 StateResult(flux=sink.asFlux())
  → 后台线程：
      每次收到 data：
        1. sink.tryEmitNext(data) → Controller 实时收到
        2. collector.append(data) → 累积
        3. sseParser.extractToolCalls(currentCollector) → 增量检测
        4. 检测到工具调用 → __has_tool_call__ = true
      onComplete：
        sink.tryEmitComplete()
        collectorLatch.countDown()
        保存 plainText + tokenUsage 到 context
```

用户等待时间 ≈ API 首包延迟（约 200ms）。

#### 10.7.4 三路径检测（ToolDetectState）

| 优先级 | 路径 | 说明 |
|--------|------|------|
| 1 | `__tool_choice_executed__` 保护 | 已执行过强制工具，跳过所有检测，防死循环 |
| 2 | 同步模式 | 从 ModelResponse.hasToolCalls() 检测 |
| 3 | toolChoice 显式指定 | request 设了具体函数名，直接构造 fakeCall |
| 4 | `__has_tool_call__` 增量标志 | 后台线程 onData 设的，直接从缓存取 |
| 5 | 后备：完整 collector 解析 | 后台线程完成后的全文解析 |

**toolChoice 显式检测的作用**：Controller 检测到时间关键词时设 `toolChoice="current_time"`，但流式模式下增量检测太慢（工具调用 300-500ms 后到达）。路径 3 绕过等待直接构造 fakeCall，确保工具被执行。

**死循环防护**：第一次 ToolExecuteState 执行完 fakeCall 后 `setToolChoice("none")` + `__tool_choice_executed__=true`。双层防护防止 DeepSeek 在 `tool_choice=none` 下仍返回工具调用。

#### 10.7.5 Flux 合并策略

多轮流式输出合并为一个连贯的 Flux：

```
mergedFlux = Flux.concat(
    streamFlux_round1,    // 模型文本输出
    toolEventFlux,        // tool_call 状态事件
    streamFlux_round2,    // 含工具结果的新一轮输出
    ...
)
```

**concat 保证顺序**：前一个 Flux 完全结束后才启动下一个。

同步模式：从 `__sync_response__` 提取 content，包装为 `Flux.just(content)`。

#### 10.7.6 SSE 数据解析（ModelAdapter 职责）

引擎层不直接解析 SSE——解析工作由 `ModelAdapter` 的 `extractSse*()` 方法完成。

**接口定义**（在 `ModelAdapter.java` 中，lyclaw-core）：

```java
default List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
    return List.of();
}
default String extractSsePlainText(String rawSSE) { return ""; }
default String extractSseTokenUsage(String rawSSE) {
    return "prompt=0 completion=0 total=0";
}
```

**引擎层 `ModelCallState` 如何使用**：

```java
// onComplete() 中
String raw = collector.toString();
List<ModelResponse.ToolCallRequest> calls = adapter.extractSseToolCalls(raw);
String plainText = adapter.extractSsePlainText(raw);
String tokenUsage = adapter.extractSseTokenUsage(raw);
```

**DeepSeek 实现示例**，按 index 分组拼接 id + name + arguments：
```
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_xxx","type":"function","function":{"name":"current_time","arguments":""}}]}}]}
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}
```

**设计原则**：SSE 解析是 ModelAdapter 的天然职责（因为它知道自己的输出格式），引擎层不应包含任何厂商格式细节。

#### 10.7.7 ToolCallEventEmitter

```java
public Flux<String> buildBeforeEvent(String toolName);   // executing
public Flux<String> buildAfterEvent(String toolName, String result);  // done
public Flux<String> buildErrorEvent(String toolName, String error);   // error
```

输出格式：`event:tool_call\ndata:{"type":"tool_call","name":"xxx","status":"done","result":"..."}\n\n`

JSON 转义：`"` → `\"`，`\n` → `\\n`，`\r` → `\\r`。

前端监听 `event:tool_call` 事件，解析 JSON 显示工具状态。

#### 10.7.8 SSE 透传与 buffer 策略（ChatController）

**事件协议**：

| 事件 | 格式 | 说明 |
|------|------|------|
| message | `event:message\ndata:{文本片段}` | 每 5 字符合并发送 |
| tool_call | `event:tool_call\ndata:{"status":"executing"}` | 工具执行中 |
| tool_call | `event:tool_call\ndata:{"status":"done","result":"..."}` | 完成 |
| message | `event:message\ndata:[DONE]` | 流结束标记 |

**buffer 策略**：累积至少 5 字符再发一次 `event:message`，减少 SSE 事件数，流结束前 flush 剩余字符。

**Tomcat buffer 关闭**：`response.setBufferSize(0)` 防止 8KB 缓存导致所有事件一次发出。

#### 10.7.9 扩展场景

| 场景 | 扩展方式 |
|------|---------|
| 新增厂商 SSE 格式 | ModelAdapter 实现自己的 extractSseToolCalls() / extractSsePlainText() |
| 流式工具调用监控 | 在 ModelCallState.onData() 中加回调 |
| 多工具并行执行 | 修改 ToolExecuteState 执行逻辑 |
| 工具调用需用户确认 | 在 ToolExecuteState 之前插入 HumanInterruptState |
| 限制总轮次/超时 | 改 DefaultStateEngine.MAX_ROUNDS 或加 context 属性控制 |

#### 10.7.10 文件清单

```
lyclaw-engine/src/main/java/lyjew/com/lyclaw/toolcall/
├── State.java                   # 状态接口
├── StateEngine.java             # 状态机引擎接口
├── Signal.java                  # 信号枚举
├── StateResult.java             # 状态执行结果
├── impl/
│   ├── DefaultStateEngine.java  # 默认状态机引擎
│   ├── ModelCallState.java      # 流式模型调用（后台线程+Sinks）
│   ├── SyncModelCallState.java  # 同步模型调用
│   ├── ToolDetectState.java     # 三路径工具检测
│   └── ToolExecuteState.java    # 工具执行+消息注入
├── emitter/
│   └── ToolCallEventEmitter.java # 工具调用事件发射器
└── parser/
    # 无需 parser 包 — SSE 解析由 ModelAdapter.extractSse*() 提供
```

### 11.1 设计动机

在 AI 对话中，"记忆"是指跨会话的持久化信息。与"上下文"（单次会话的历史消息）不同，记忆是独立于会话的，可以在多个会话之间共享。

记忆管理的核心需求：

**需求一：记住重要信息**
用户可能在一次对话中透露了重要信息（名字、偏好、项目需求），希望在后续的新会话中，AI 仍然"记得"这些信息。

**需求二：自动提取**
第一版采用手动触发（用户说"记住这个"），但未来可能需要 AI 自动判断哪些信息值得记住。

**需求三：存储灵活性**
第一版使用文件存储，未来可能需要切换到 Redis、数据库或向量数据库。存储实现的变化不应影响记忆管理的调用方。

**需求四：过期清理**
历史记忆可能累积过多，需要定期清理。例如，30 天前的会话日志可以自动删除。

### 11.2 MemoryManager 接口设计

MemoryManager 是记忆管理的核心接口。它定义了对记忆的 CRUD 操作：

**`remember(Session session, MemoryStrategy strategy)`**：
- 从会话中提取记忆内容
- 提取逻辑由 MemoryStrategy 决定
- 将记忆存储到后端

**`recall()`**：
- 读取所有已启用的记忆
- 返回 Memory 列表

**`forget(String memoryId)`**：
- 删除指定记忆
- 可以是物理删除或软删除（标记为 disabled）

**`buildContext(List<Memory> memories)`**：
- 将记忆列表格式化为可注入上下文的字符串
- 格式化后的内容会被放入 system prompt 或消息列表的首部

### 11.3 MemoryStrategy 策略设计

MemoryStrategy 决定了从会话中提取哪些信息作为记忆。

**ManualMemoryStrategy（手动策略）**：
- `shouldExtract()`：检查用户是否明确说"记住这个"或"记住xxx"
- `extract()`：提取用户要求记住的具体内容

**KeyEventMemoryStrategy（关键事件策略，未来）**：
- `shouldExtract()`：检测会话中是否有"关键事件"（如用户分享个人信息、项目需求、偏好设置）
- `extract()`：提取关键事件中的核心信息

**AiDrivenMemoryStrategy（AI 驱动策略，未来）**：
- `shouldExtract()`：始终返回 true，由 AI 判断
- `extract()`：调用 AI 接口，让 AI 判断哪些信息值得记住

### 11.4 记忆的存储格式

第一版使用文件存储，文件格式为 Markdown：

```
memory/2026-04-27-001.md
memory/2026-04-27-002.md
memory/MEMORY.md            ← 长期记忆（跨会话）
```

Memory 实体包含以下字段：
- `id`：唯一标识，如 "2026-04-27-001"
- `title`：记忆标题
- `content`：记忆内容（Markdown 格式）
- `enabled`：是否启用
- `tags`：标签列表
- `createdAt`：创建时间
- `updatedAt`：更新时间

### 11.5 未来存储实现的可替换性

由于 MemoryManager 是一个接口，未来切换存储实现只需要：

1. 新建 `RedisMemoryManager implements MemoryManager`
2. 在 Spring 配置中切换注入 `@Primary RedisMemoryManager`

所有调用 MemoryManager 的代码（ContextBuilder、Pipeline、Engine）都不需要修改。

同理，如果需要切换到数据库存储：
1. 新建 `DatabaseMemoryManager implements MemoryManager`
2. 配置数据库连接
3. 切换注入

---

## 第十二章：EventBus 事件总线设计

### 12.1 设计动机

在一个复杂的系统中，模块之间经常需要通信。例如：

- 工具执行完成后，需要通知监控模块记录指标
- Token 消耗后，需要通知成本模块更新账单
- Agent 状态变化时，需要通知 UI 模块推送更新
- 错误发生时，需要通知告警模块发送通知

如果模块之间直接调用，会导致：
- 模块之间紧密耦合，修改一个模块可能影响多个模块
- 新增一个监听者需要修改发布者的代码
- 难以追踪事件的流向

事件总线模式通过一个中央总线来解耦发布者和订阅者：
- 发布者只负责发布事件，不知道谁会接收
- 订阅者只负责处理事件，不知道事件是谁发布的
- 总线负责将事件从发布者路由到订阅者

### 12.2 EventBus 接口设计

EventBus 是事件总线的核心接口。

**`publish(Event event)`**：
- 发布一个事件
- 所有订阅了该事件类型的监听器都会收到通知
- 发布是异步的（不阻塞发布者）

**`subscribe(Class<T> eventType, Consumer<T> handler)`**：
- 订阅指定类型的事件
- 当该类型的事件发布时，handler 会被调用
- 返回一个 Subscription 对象，可用于取消订阅

**`unsubscribe(Subscription subscription)`**：
- 取消订阅
- 释放相关资源

### 12.3 第一版的事件类型

**TokenConsumedEvent（Token 消耗事件）**：
- 触发时机：每次模型调用完成后
- 携带数据：sessionId、model、promptTokens、completionTokens、totalTokens
- 典型监听者：MetricsService（记录指标）、CostService（更新账单）

**ToolCalledEvent（工具调用事件）**：
- 触发时机：工具执行完成后
- 携带数据：sessionId、toolName、arguments、result、duration
- 典型监听者：ToolLogService（记录工具调用日志）

**AgentStateChangedEvent（Agent 状态变更事件）**：
- 触发时机：Agent 状态发生变化时
- 携带数据：agentId、oldState、newState、sessionId
- 典型监听者：UI 推送服务（实时更新界面）

**ErrorEvent（错误事件）**：
- 触发时机：发生不可恢复的错误时
- 携带数据：errorType、message、stackTrace、context
- 典型监听者：AlertService（发送告警）

### 12.4 默认实现：InMemoryEventBus

第一版使用基于内存的事件总线实现。核心数据结构：

- `Map<Class<?>, List<Consumer>>`：事件类型 → 监听器列表
- `ExecutorService`：异步执行监听器的线程池

工作流程：
1. `publish(event)` → 获取事件类型 → 查找监听器列表 → 提交到线程池异步执行
2. `subscribe(type, handler)` → 将 handler 添加到对应类型的监听器列表
3. `unsubscribe(subscription)` → 从监听器列表中移除

### 12.5 未来实现的可替换性

**KafkaEventBus**：基于 Kafka 的分布式事件总线。
- 适合微服务场景
- 事件持久化，支持重放
- 跨服务通信

**SpringEventBus**：基于 Spring ApplicationEvent 的事件总线。
- 与 Spring 生态深度集成
- 支持 @EventListener 注解
- 支持事务事件

切换方式：新建实现类，在配置中切换注入即可。所有发布者和订阅者代码无需修改。

---

## 第十三章：AgentCoordinator Agent 协调设计

### 13.1 设计动机

在复杂的 AI 对话中，一个任务可能需要分解为多个子任务并行或串行执行。每个子任务由一个"子 Agent"负责。

AgentCoordinator 的职责是管理这些 Agent 的生命周期和通信。

**核心概念**：
- **主 Agent**：与用户直接对话的 Agent，即当前会话本身
- **子 Agent**：主 Agent 派生出的独立执行单元，负责执行特定任务
- **Agent 任务**：子 Agent 需要执行的具体任务描述

### 13.2 Agent 生命周期

Agent 的状态机：

```
IDLE（初始状态）
  │
  ▼
RUNNING（执行中）
  │
  ├──► WAITING_TOOL（等待工具结果）
  │       │
  │       └──► RUNNING（工具结果返回，继续执行）
  │
  ├──► COMPLETED（正常完成）
  │
  ├──► ERROR（执行出错）
  │
  └──► TERMINATED（被手动终止）
```

状态转换规则：
- IDLE → RUNNING：spawn 时
- RUNNING → WAITING_TOOL：需要调用工具时
- WAITING_TOOL → RUNNING：工具结果返回时
- RUNNING → COMPLETED：任务正常完成时
- RUNNING → ERROR：发生不可恢复的错误时
- 任意状态 → TERMINATED：被手动终止时

### 13.3 第一版的约束

- 同一会话最多 1 个子 Agent 并发
- 子 Agent 超时时间 5 分钟
- 子 Agent 不可再 spawn 孙 Agent（深度限制 1）
- 主会话终止时级联终止所有子 Agent

### 13.4 AgentChannel 通信拓扑设计

AgentChannel 定义了 Agent 之间的通信拓扑。第一版使用 StarAgentChannel（星型拓扑），未来可切换到 TreeAgentChannel（树形）、MeshAgentChannel（网状）、BroadcastAgentChannel（广播）。

这种设计使得通信拓扑的变化不影响 AgentCoordinator 的核心逻辑。

---

## 第十四章：ErrorPolicy 错误处理设计

### 14.1 设计动机

在 AI 对话中，可能发生各种错误：

- 模型 API 返回 401（Key 无效）
- 模型 API 返回 429（限流）
- 模型 API 返回 5xx（服务器错误）
- 模型响应超时
- 工具执行失败
- 工具执行超时

不同的错误需要不同的处理策略：
- 限流错误：等待后重试
- Key 无效：直接返回错误，不重试
- 服务器错误：重试 N 次，仍失败则降级
- 工具超时：返回超时错误，不影响其他工具

### 14.2 ErrorPolicy 接口设计

**`onModelError(exception, context)`**：
- 模型调用失败时调用
- 返回 ChatResult（可能是重试结果或错误信息）

**`onToolError(exception, context)`**：
- 工具执行失败时调用
- 返回 ToolResult（可能是错误信息或降级结果）

**`onTimeout(context, elapsedMs)`**：
- 超时时调用
- 返回 ChatResult

### 14.3 默认策略

DefaultErrorPolicy：
- 模型调用失败：401/403 不重试，429 等待 5 秒重试 1 次，5xx 重试 1 次
- 工具执行失败：返回错误信息，跳过该工具
- 超时：抛出超时异常

### 14.4 未来扩展策略

RetryErrorPolicy：失败重试 N 次，指数退避
FallbackModelErrorPolicy：失败时切换到备用模型
CircuitBreakerErrorPolicy：连续失败后熔断，一段时间后尝试恢复

---

## 第十五章：模块依赖关系

### 15.1 依赖架构图

```
lyclaw-web（启动模块）
  │
  └──► lyclaw-engine（引擎层）
         │
         ├──► lyclaw-core（引擎层接口 + 模型适配器接口）
         │       │
         │       └──► lyclaw-common（公共 DTO）
         │
         ├──► lyclaw-common（公共 DTO）
         │
         └──► lyclaw-storage（存储层）
                │
                └──► lyclaw-core
                       │
                       └──► lyclaw-common

lyclaw-adapter（模型适配器实现）
  │
  ├──► lyclaw-core（实现 ModelAdapter 接口）
  └──► lyclaw-common

注意：lyclaw-engine 不直接依赖 lyclaw-adapter。
引擎通过 lyclaw-core 中的 ModelProvider 接口获取适配器，
具体实现由 Spring 在运行时注入。
```

### 15.2 依赖方向验证

- lyclaw-engine → lyclaw-core：✅ 依赖接口，不依赖实现
- lyclaw-engine → lyclaw-common：✅ 依赖纯 DTO，无循环依赖
- lyclaw-engine → lyclaw-storage：✅ 依赖存储接口，未来可替换
- lyclaw-engine → lyclaw-adapter：❌ 无直接依赖，通过 ModelProvider 解耦

无循环依赖，单向依赖，符合 Clean Architecture。

### 15.3 防腐层设计

lyclaw-engine 不直接依赖 lyclaw-adapter 的具体类。所有对适配器的调用通过 lyclaw-core 中的 ModelProvider 接口进行。具体实现由 lyclaw-adapter 模块提供，通过 Spring 注入。

这种设计使得：
- 引擎层与适配器实现完全解耦
- 未来替换适配器实现（如 gRPC 适配器）不影响引擎层
- 测试时可以轻松 mock ModelProvider 接口

---

## 第十六章：扩展性验证矩阵

### 16.1 验证结果

本架构可以支撑以下全部 100+ 个场景的扩展，**所有场景均不需要修改已有代码**。

### A 类：功能新增

| 场景 | 扩展方式 | 改动已有代码 |
|------|----------|-------------|
| 新增会话持久化策略 | 新建类实现 SessionPersistence 接口，@Component | 0 行 |
| 新增记忆持久化策略 | 新建类实现 MemoryPersistence 接口，@Component | 0 行 |
| 新增内置工具 | 新建类实现 Tool 接口，@Component | 0 行 |
| 新增 MCP Server | McpToolAdapter 实现 Tool 接口 | 0 行 |
| 新增上下文策略 | 新建类实现 ContextBuilder 接口 | 0 行 |
| 新增拦截器 | 新建类实现 Interceptor 接口 | 0 行 |
| 新增事件类型 | 新建类继承 Event，新建监听器 | 0 行 |
| 新增 Agent 类型 | 新建类，通过 AgentChannel 切换拓扑 | 0 行 |
| 新增 Engine | 新建类实现 Engine 接口，@Component | 0 行 |
| 新增模型厂商 | 新建 ModelAdapter 实现（在 adapter 模块） | 0 行 |
| 新增 Pipeline Stage | 新建类实现 PipelineStage，addStage() | 0 行 |

### B 类：功能增强

| 场景 | 扩展方式 | 改动已有代码 |
|------|----------|-------------|
| 增强工具执行 | 替换 ToolExecutor 实现 | 0 行（改注入） |
| 增强上下文 | 新建 ContextBuilder 实现 | 0 行 |
| 增强记忆管理 | 新建 MemoryManager 实现 | 0 行（改注入） |
| 增强拦截器 | 替换对应拦截器实现 | 0 行 |
| 增强持久化策略（如累积阈值） | 替换 MemoryPersistence 实现 | 0 行（改注入） |
| 增强会话持久化策略（如时间窗口） | 替换 SessionPersistence 实现 | 0 行（改注入） |
| 增强模型调用 | ModelProvider + 装饰器 | 0 行 |
| 增强 Agent 协调 | 替换 AgentChannel 实现 | 0 行（改注入） |

### C 类：功能修改

| 场景 | 扩展方式 | 改动已有代码 |
|------|----------|-------------|
| 修改工具循环 | 替换 ToolCallPolicy 实现 | 0 行 |
| 修改 Agent 协调 | 替换 AgentChannel 实现 | 0 行 |
| 修改上下文构建 | 替换 ContextBuilder 实现 | 0 行 |
| 修改阶段顺序 | PipelineBuilder.addStageBefore/After | 0 行 |
| 修改错误处理 | 替换 ErrorPolicy 实现 | 0 行 |
| 修改拦截器顺序 | 调整 @Order 注解 | 0 行 |

### D 类：实现替换

| 场景 | 扩展方式 | 改动已有代码 |
|------|----------|-------------|
| 替换存储 | 新建 MemoryManager 实现 | 0 行（改注入） |
| 替换缓存 | 新建 CacheDecorator 实现 | 0 行（改注入） |
| 替换事件总线 | 新建 EventBus 实现 | 0 行（改注入） |
| 切换持久化时机策略 | 替换 SessionPersistence/MemoryPersistence 实现 | 0 行（改注入） |
| 替换 HTTP 客户端 | ModelProvider 内部替换 | 0 行 |

### E-L 类：全部场景

所有 E（性能优化）、F（可观测性）、G（运维）、H（协议集成）、I（第三方集成）、J（完全不同引擎）、K（业务逻辑）、L（安全）类场景均通过接口隔离 + 依赖注入支撑，0 行已有代码改动。

---

## 第十七章：未来演进路线

### 17.1 第一版实现清单

以下模块在第一版需要实现：

**核心流程**：
- Engine/EngineSelector/DefaultEngine
- Pipeline/PipelineBuilder/PipelineStage/Chain
- ContextBuildStage/InterceptorStage/ToolCallLoopStage/MetricsStage/ResponseBuildStage

**上下文构建**：
- ContextBuilder 接口
- FullWindowContextBuilder
- ChatContext

**拦截器**：
- Interceptor 接口/InterceptorChain
- RateLimitInterceptor
- LoggingInterceptor

**工具执行**：
- Tool 接口/ToolRegistry
- ToolCallLoop
- ToolCallPolicy 接口/DefaultToolCallPolicy
- WebSearchTool/CalculatorTool/CurrentTimeTool

**记忆管理**：
- MemoryManager 接口
- ManualMemoryStrategy
- FileMemoryManager（复用 lyclaw-storage）

**事件总线**：
- EventBus 接口/InMemoryEventBus
- Event 基类
- TokenConsumedEvent/ToolCalledEvent

**错误处理**：
- ErrorPolicy 接口
- DefaultErrorPolicy

**配置**：
- EngineProperties
- EngineAutoConfiguration

### 17.2 第二版演进方向

- 新增 SlidingWindowContextBuilder、SummaryContextBuilder
- 新增 CacheInterceptor
- 新增 McpToolAdapter（MCP 工具支持）
- 新增 AgentCoordinator、StarAgentChannel
- 新增 AuditInterceptor
- 新增 MetricsInterceptor
- 实现装饰器模式（CacheDecorator、RetryDecorator）

### 17.3 第三版及以后

- 新增 ReasoningEngine、PlanningEngine、RagEngine
- 新增 SemanticWindowContextBuilder（Embedding 语义窗口）
- 新增 TreeAgentChannel、MeshAgentChannel
- 新增 KafkaEventBus
- 新增 RedisMemoryManager
- 新增 CircuitBreakerErrorPolicy
- 支持多 Agent 并行
- 支持插件系统

---

**文档结束**

本文档详细描述了 AI 引擎层的完整架构设计，包括 13 种设计模式的应用、100+ 种未来扩展场景的支撑方案，以及严格的模块依赖控制。所有设计都遵循"新增类而不修改已有代码"的原则，确保系统在长期演进中保持可维护性。