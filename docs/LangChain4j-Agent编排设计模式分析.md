# LangChain4j Agent 编排设计模式深度分析

## 一、整体架构概览

LangChain4j 的 Agent 编排系统采用**四层分层架构**：

```
┌──────────────────────────────────────────────────────────────────┐
│  AiServices 声明式层（@UserMessage, @SystemMessage 注解驱动）      │
├──────────────────────────────────────────────────────────────────┤
│  Agentic 编排层（Planner、Workflow、Patterns、A2A/MCP 协议）      │
├──────────────────────────────────────────────────────────────────┤
│  Core 核心原语层（ChatModel、Tool、Memory、RAG、Guardrails）       │
├──────────────────────────────────────────────────────────────────┤
│  SPI / 模型提供商层（OpenAI、Gemini、Ollama 等适配实现）           │
└──────────────────────────────────────────────────────────────────┘
```

**核心设计理念**：LangChain4j 与 Python LangChain 最根本的区别在于，它使用 **JDK 动态代理**（`java.lang.reflect.Proxy`）作为主要组合机制，而非 Python 的运行时鸭子类型。所有 Agent 行为在代理构造时通过 `InvocationHandler` 链编织在一起，编译器可静态验证类型安全。

---

## 二、AiServices — 声明式 Agent 模式（核心入口）

**包路径**：`dev.langchain4j.service`  
**核心类**：`DefaultAiServices<T>`（`@Internal` 注解，包私有）

### 2.1 动态代理作为 Agent

整个 AiServices 层建立在单一设计模式之上：**基于接口的声明式 Agent，通过 JDK 动态代理实现**。

```java
// 用户只需定义接口 —— 无需实现类
interface MyAgent {
    @SystemMessage("你是一个有帮助的助手，角色是{{role}}")
    String chat(@UserMessage String message, @V("role") String role);
}

// LangChain4j 在运行时创建代理实例
MyAgent agent = AiServices.builder(MyAgent.class)
    .chatLanguageModel(model)
    .tools(new Calculator())
    .build();
```

`DefaultAiServices.build()` 调用 `Proxy.newProxyInstance()` 创建代理，所有方法调用被 `InvocationHandler` 拦截并路由到完整执行管道。

### 2.2 InvocationHandler 方法调度

`invoke()` 方法根据被调方法的声明类型进行多路分发：

| 方法类型 | 处理方式 |
|---------|---------|
| `default` 接口方法 | `InvocationHandler.invokeDefault(proxy, method, args)` — Java 16+ API |
| `Object` 方法（equals/hashCode/toString） | 标准处理：引用相等、身份哈希 |
| `ChatMemoryAccess` 方法 | 委托给 `handleChatMemoryAccess()`，通过 switch 表达式分发 |
| 所有其他抽象方法 | **进入完整执行管道** `invoke(method, args, invocationContext)` |

### 2.3 完整执行管道

每个抽象方法调用经过以下 15 个步骤：

```
步骤1:  获取/创建 ChatMemory（通过 memoryId）
步骤2:  准备 SystemMessage
        ├── 优先从 @SystemMessage 注解获取模板（支持 fromResource 从文件加载）
        └── 回退到 systemMessageProvider.apply(memoryId)
步骤3:  准备 UserMessage
        ├── 优先级链：方法 @UserMessage → 参数 @UserMessage → 唯一参数推断
        │              → Content 参数 → userMessageProvider
        └── 用 PromptTemplate + variables 渲染模板
步骤4:  RAG 检索增强（若配置了 RetrievalAugmentor）
        └── 将 systemMessage、chatMemory、userMessage 打包为 Metadata 调用 augment()
步骤5:  添加 Content 到 UserMessage（处理 @UserMessage 的 Content、Map 类型等）
步骤6:  输入护栏（InputGuardrail）验证
步骤7:  确定返回类型与 JSON Schema
        ├── 模型支持 JSON Schema → 用 serviceOutputParser.jsonSchema() 生成结构化输出
        └── 不支持 → 在消息末尾追加文本格式指令（责任链回退模式）
步骤8:  组装消息列表
        ├── 有 ChatMemory：先获取历史消息，再添加当前用户消息
        └── 无 ChatMemory：SystemMessage + UserMessage 直接放入列表
步骤9:  触发审核（Moderation）— 若方法上有 @Moderate 注解，异步执行
步骤10: 创建 ToolServiceContext — 合并静态工具和 ToolProvider 动态工具
步骤11: 流式处理分支（若返回类型为 TokenStream）
        └── 构建 AiServiceTokenStream，支持 TokenStreamAdapter SPI 适配到 Publisher/Flux
步骤12: 构建 ChatRequest 并调用 LLM
        ├── chatRequestTransformer 装饰器允许最终修改请求
        └── ChatExecutor 包装事件触发逻辑
步骤13: 响应事件触发 + 审核结果验证
步骤14: 工具调用循环 — ToolService.executeInferenceAndToolsLoop()
步骤15: 输出护栏 + 结果解析
        └── ServiceOutputParser.parse() 根据返回类型选择对应 OutputParser
```

### 2.4 提示词模板系统

注解完全驱动提示词构建：

```java
@SystemMessage("你是一个{{role}}，今天是{{current_date}}。")
@UserMessage("分析以下内容：{{it}}")
Result analyze(String input, @V("role") String role);
```

模板变量从方法参数中按名解析（通过 `@V` 注解或参数名推断），由注册的 `PromptTemplateFactory` 渲染。

### 2.5 本层使用的主要设计模式

| 设计模式 | 说明 |
|---------|------|
| **动态代理（Proxy）** | JDK `Proxy.newProxyInstance()` 生成用户接口的代理实现 |
| **建造者（Builder）** | 所有不可变对象：`InvocationContext`、`ChatRequest`、`ChatResponse` 等 |
| **观察者（Observer）** | 事件系统：`AiServiceStartedEvent`、`AiServiceCompletedEvent`、`ToolExecutedEvent` 等 |
| **策略（Strategy）** | 返回值解析、工具提供者、消息提供者等组件接口可替换实现 |
| **装饰器（Decorator）** | `systemMessageTransformer`、`chatRequestTransformer` 在组件调用前后添加逻辑 |
| **责任链（Chain of Responsibility）** | JSON Schema → 文本格式指令回退 |
| **上下文对象（Context Object）** | `InvocationContext`、`AiServiceContext` 封装跨组件传递的上下文 |
| **适配器（Adapter）** | `TokenStreamAdapter` SPI 将 TokenStream 适配到 Publisher/Flux 等 |
| **服务定位器（Service Locator）** | SPI 机制 `loadFactories()` 加载扩展实现 |

---

## 三、Agentic 编排层 — Planner 状态机模式

**包路径**：`dev.langchain4j.agentic.planner`  
**核心接口**：`Planner`

### 3.1 Planner 接口 — 所有编排策略的统一抽象

`Planner` 将 Agent 执行建模为**状态机**：

```java
public interface Planner {
    // === 生命周期钩子 ===
    default void init(InitPlanningContext ctx) {}               // 初始化（可选覆写）

    // === 状态持久化（崩溃恢复） ===
    default Map<String, Object> executionState() {}             // 保存当前状态
    default void restoreExecutionState(Map<String, Object> s) {} // 从持久化恢复

    // === 拓扑声明 ===
    default AgenticSystemTopology topology() { return SEQUENCE; }

    // === 核心：Action 决策（状态机转换函数） ===
    default Action firstAction(PlanningContext ctx) { return nextAction(ctx); }
    Action nextAction(PlanningContext ctx);  // 唯一抽象方法，必须实现

    // === 终止条件 ===
    default boolean terminated() { return false; }

    // === Action 工厂方法 ===
    default Action noOp() {...}                    // 空操作（等待）
    default Action call(AgentInstance... agents) {...} // 调用指定 Agent
    default Action done() {...}                       // 正常结束
    default Action done(Object result) {...}          // 带结果的结束
}
```

**生命周期**：

```
init() → firstAction() → [nextAction() → 执行]^N → terminated() == true
```

每次子 Agent 执行完毕后，`PlanningContext` 携带上一次调用的 `AgentInvocation` 回传给 `nextAction()`，使 Planner 可以根据上次结果做动态决策。

### 3.2 AgenticSystemTopology — 八种拓扑类型

```java
public enum AgenticSystemTopology {
    AI_AGENT,           // 单一 LLM 驱动的 Agent
    NON_AI_AGENT,       // 非 LLM Agent（纯代码逻辑/工具调用）
    HUMAN_IN_THE_LOOP,  // 人在回路（等待人类输入）
    SEQUENCE,           // 线性链式执行
    PARALLEL,           // 并发执行
    LOOP,               // 循环执行
    ROUTER,             // 条件分支路由
    STAR                // 中心辐射型（P2P/数据流/Supervisor）
}
```

### 3.3 PlannerBasedInvocationHandler — 核心执行引擎

**包路径**：`dev.langchain4j.agentic.internal`

这是整个 Agentic 系统的执行引擎，通过 JDK 动态代理将用户对 Agent 接口的方法调用转化为编排执行流程。同时实现 `InvocationHandler` 和 `InternalAgent`，管理 Proxy 多接口分发、Scope 生命周期、PlannerLoop 状态机。

**`invoke()` 方法 — 多接口分发**：代理对象实现了 4-5 个接口，`invoke` 根据声明类进行路由：

```
AgenticScopeOwner    → withAgenticScope() / registry()
AgenticScopeAccess   → getAgenticScope() / evictAgenticScope()
AgentInstance        → 代理到自身 handler 方法
MonitoredAgent       → 返回 AgentMonitor
Object 类方法         → toString / hashCode
ChatMemoryAccess     → 委托给 ChatMemoryAccessProvider
其他所有方法          → executeAgentMethod() — 进入主流程
```

**`executeAgentMethod()` 核心流程**：

```
1.  获取/创建 AgenticScope（通过 memoryId）
2.  将方法参数写入 Scope 共享状态
3.  前置回调
4.  参数命名化（仅 root call）
5.  通知 AgentListener（开始）
6.  Planner 实例创建：plannerSupplier.get()
7.  planner.init(InitPlanningContext(scope, this, subagents))
8.  进入主循环：new PlannerLoop(planner, scope, registry).loop()
9.  提取输出：outputKey != null ? scope.readState(outputKey) : result
10. 结束回调 + 清理/持久化
11. 返回结果（支持 ResultWithAgenticScope 包装）
```

### 3.4 PlannerLoop 内部类 — 主循环状态机

```java
class PlannerLoop {
    private final Planner planner;
    private final AgenticScope agenticScope;
    private final AgenticScopeRegistry registry;
    private final ReentrantLock lock;
    private volatile Action nextAction;  // volatile + ReentrantLock 生产者-消费者
}
```

**`loop()` 主循环**：

```
1. 读取持久化的执行状态（savedState = scope.readState("__planner_state_<agentId>", {})）
2. 若非空 → planner.restoreExecutionState(savedState)  // 崩溃恢复
3. nextAction = planner.firstAction(planningContext)
4. WHILE nextAction == null || !nextAction.isDone():
     a. 若 nextAction == null → Thread.yield()  // 忙等待（很快会被填充）
     b. agents = ((AgentCallAction) nextAction).agentsToCall()
     c. nextAction = null  // 消费
     d. switch (agents.size()):
        0 → Thread.yield()
        1 → agents[0].execute(scope, this)     // 同步执行
        N → parallelExecution(agents)           // CompletableFuture 并发
5. 清除执行状态
6. return result()
```

**`onSubagentInvoked()` 回调 — Agent 执行完成后的处理**：

```
1. lock.lock()
2. nextAction = composeActions(nextAction, planner.nextAction(planningContext))
   // composeActions：合并新旧 Action，都是 AgentCallAction 时合并 agent 列表
3. execState = planner.executionState()
4. scope.writeState(executionStateId, execState)     // 持久化执行状态
5. scope.checkpoint(registry)                        // 写快照到持久化存储
6. lock.unlock()
```

**关键设计点**：
- `nextAction` 是 **volatile** 变量：loop 线程读取后置空为 null，`onSubagentInvoked` 回调线程写入新值
- `composeActions()` 处理并行场景：多个 Agent 完成后累积新的调用目标
- 每次 Agent 调用后**立即持久化**执行状态，实现精确的崩溃恢复点

**`parallelExecution()` 并行执行**：使用 `CompletableFuture.supplyAsync()` 包装每个 Agent，`future.get()` 等待所有完成。默认使用 `DefaultExecutorProvider.getDefaultExecutorService()`。

### 3.5 本层使用的主要设计模式

| 设计模式 | 说明 |
|---------|------|
| **策略（Strategy）** | `Planner` 及其 6+ 子类 — 每种编排拓扑是一种策略 |
| **代理（Proxy）** | `PlannerBasedInvocationHandler` 通过 InvocationHandler 实现多接口代理 |
| **状态（State）** | `PlannerLoop` — nextAction 状态转换：null → AgentCallAction → DoneAction |
| **黑板（Blackboard）** | `AgenticScope` — Agent 间共享状态空间 |
| **命令（Command）** | `Action` 及其子类（AgentCallAction、DoneAction、DoneWithResultAction、NoOpAction） |
| **备忘录（Memento）** | `executionState()` / `restoreExecutionState()` — 执行状态保存与恢复 |
| **生产者-消费者** | `onSubagentInvoked`（生产）→ `nextAction`（消费）— volatile + ReentrantLock |
| **模板方法（Template Method）** | `firstAction` 默认委托给 `nextAction`，子类可选择性覆写 |
| **迭代器（Iterator）** | SequentialPlanner / LoopPlanner 中的游标遍历 |
| **Fork-Join** | `parallelExecution()` — CompletableFuture 并发 + 等待 |
| **读写锁** | `DefaultAgenticScope` — ReentrantReadWriteLock 安全的 checkpoint |

---

## 四、工作流编排模式详解

**包路径**：`dev.langchain4j.agentic.workflow.impl`

### 4.1 SequentialPlanner — 顺序执行（SEQUENCE 拓扑）

按固定顺序逐一执行子 Agent，使用**游标**（cursor）遍历子代理列表。

```
内部状态：List<AgentInstance> agents, int agentCursor = 0

init():      agents = initPlanningContext.subagents()
nextAction(): return terminated() ? done() : call(agents.get(agentCursor++))
terminated(): return agentCursor >= agents.size()
```

**崩溃恢复**：保存 `cursor - 1`（最后调度的 Agent 索引），恢复时直接赋值。因为 `firstAction` 继承默认实现（调用 `nextAction()`），所以恢复后的 cursor 指向需要重新执行的 Agent。

**设计模式**：**迭代器模式** — `agentCursor` 作为游标遍历子代理列表。

### 4.2 ParallelPlanner — 并行执行（PARALLEL 拓扑）

第一次 Action 发出所有子 Agent 调用，之后立即返回 done()。

```
firstAction(): return call(agents)  // 一次性调用所有子 Agent
nextAction():  return done()        // 之后立即结束
```

执行路径：`firstAction` 返回包含 N 个 Agent 的 `AgentCallAction` → `PlannerLoop.parallelExecution()` 用 `CompletableFuture` 并发执行所有 → 所有完成后 `onSubagentInvoked()` 累积回调 → `nextAction` 返回 `done()`。

**无状态**：无需持久化执行状态，因为 `firstAction` 每次都重新发起所有调用。

**设计模式**：**Fork-Join 模式** — 发起 N 个并行任务，等待全部完成。

### 4.3 ConditionalPlanner — 条件路由（ROUTER 拓扑）

使用 Java `record` 类型（天生不可变）。根据 `Predicate<AgenticScope>` 条件判断哪些子 Agent 需要执行。

```java
public record ConditionalPlanner(List<ConditionalAgent> conditionalSubagents)
        implements Planner {

    public Action firstAction(PlanningContext ctx) {
        List<AgentInstance> agentsToCall = conditionalSubagents.stream()
            .filter(ca -> ca.predicate().test(ctx.agenticScope()))
            .flatMap(ca -> ca.agentInstances().stream())
            .toList();
        return agentsToCall.isEmpty() ? done() : call(agentsToCall);
    }
}

// ConditionalAgent 封装：(条件名, Predicate<AgenticScope>, Agent实例列表)
```

`terminated()` 返回 `true` — 所有匹配的 Agent 在第一次 Action 中已全部发出。

**设计模式**：**Predicate 模式** — Java 8 `Predicate<AgenticScope>` 条件过滤 + **策略模式**每个 ConditionalAgent 是独立策略。

### 4.4 LoopPlanner — 循环执行（LOOP 拓扑）

在子 Agent 列表上循环执行，支持最大迭代次数和条件退出。

```
内部状态：
  maxIterations, iterationsCounter=1, testExitAtLoopEnd
  exitCondition: BiPredicate<AgenticScope, Integer>
  agentCursor=0

firstAction():
  return call(agents.get(agentCursor))  // 不移动 cursor

nextAction():
  agentCursor = (agentCursor + 1) % agents.size()  // 循环移动
  if (agentCursor == 0) {  // 一轮结束
      if (超限 || exitCondition满足) return done()
      iterationsCounter++
  } else if (!testExitAtLoopEnd && exitCondition满足) {
      return done()  // 非循环结束模式下每次检查
  }
  return call(agents.get(agentCursor))
```

**两种退出模式**：
- `testExitAtLoopEnd == true`：仅在完成完整一轮时检查退出条件
- `testExitAtLoopEnd == false`：每次 Agent 调用后都检查

**崩溃恢复**：保存 `(cursor, iterationsCounter)`。

**设计模式**：**迭代器模式**（循环变体）+ **策略模式**（`exitCondition` 作为可插拔 `BiPredicate`）。

### 4.5 SupervisorPlanner — LLM 监督者（STAR 拓扑）

使用 LLM 作为中央监督者，动态决定下一步调用哪个子 Agent。实现 `Planner` 和 `ChatMemoryAccessProvider` 两个接口。

```java
核心字段：
  chatModel          — LLM 模型
  maxAgentsInvocations — 最大 Agent 调用次数
  loopCount          — 当前循环计数
  contextStrategy    — 上下文策略：CHAT_MEMORY / SUMMARIZATION / CHAT_MEMORY_AND_SUMMARIZATION
  responseStrategy   — 响应策略：LAST / SUMMARY / SCORED
  requestGenerator   — 从 Scope 提取用户请求的函数
  agents / agentsList — 子 Agent 索引/描述字符串
```

**`nextAction()` 核心流程**：

```
1. if (loopCount++ >= maxAgentsInvocations) → doneAction()
2. 调用 PlannerAgent（通过 AiServices.builder() 创建的 LLM 代理）:
   - LLM 看到所有子 Agent 的描述列表 + 当前上下文
   - LLM 返回 AgentInvocation(agentName, 参数字典)
3. if (agentName.equalsIgnoreCase("done")) → doneAction()
4. 否则 → 查找对应 Agent → 将 LLM 返回的参数写入 Scope → call(agent)
```

**三种响应策略**：
- `LAST`：直接返回最后一个 Agent 的响应
- `SUMMARY`：返回 LLM 生成的总结（done Agent 的 response 参数）
- `SCORED`：通过 ResponseAgent（LLM）对比两个候选响应打分（0.0-1.0），返回高分者

**设计模式**：**Supervisor 模式** — 一个 LLM 驱动的中央协调者，Star 拓扑。

### 4.6 ParallelMapperPlanner — 并行映射（PARALLEL 拓扑）

对集合中每个元素并行执行同一个 Agent（MapReduce 模式）。

```
firstAction():
  1. collectionObj = scope.readState(itemsProvider)
  2. items = collectItems(collectionObj)  // 支持 List/Collection/数组
  3. 对每个元素创建 MapperAgentInvoker（注入元素值 + 索引）
  4. return call(所有 MapperAgentInvoker 包装的 Agent 实例) → 并行执行

nextAction():
  1. completedCount.incrementAndGet()
  2. if (全部完成):
       for item in items: results.add(scope.readState(resultKeyPrefix + "_" + i))
       result = isArrayResult ? toArray(results) : results
       if (outputKey != null) scope.writeState(outputKey, result)
       return done(result)
  3. return done()  // 还有未完成的，等待更多回调
```

**`MapperAgentInvoker`**：包装原始 AgentInvoker，将集合元素值注入到参数中（覆盖从 Scope 读取的默认值）。

**设计模式**：**MapReduce 模式** — Map 阶段创建 N 个实例并行执行，Reduce 阶段收集结果。

### 4.7 WorkflowAgentsBuilderImpl — 流式构建器（入口工厂）

**枚举单例**实现 `WorkflowAgentsBuilder`，为五种工作流拓扑提供构建器工厂方法：

```
sequenceBuilder()         → SequentialAgentServiceImpl.builder()
parallelBuilder()         → ParallelAgentServiceImpl.builder()
loopBuilder()             → LoopAgentServiceImpl.builder()
conditionalBuilder()      → ConditionalAgentServiceImpl.builder()
parallelMapperBuilder()   → ParallelMapperServiceImpl.builder()
```

每种 Builder 支持泛型类型（`UntypedAgent` 或自定义 Agent 接口类）。

**设计模式**：**抽象工厂模式** + **单例模式**（枚举单例）。

---

## 五、AgenticScope — 黑板模式核心

**包路径**：`dev.langchain4j.agentic.scope`  
**核心类**：`DefaultAgenticScope`（实现 `AgenticScope`、`LangChain4jManaged`）

### 5.1 核心数据结构

```java
// 可序列化的共享状态（ConcurrentHashMap 保证线程安全）
private final Map<String, Object> state = new ConcurrentHashMap<>();

// Agent 调用记录（同步包装的 ArrayList）
private final List<AgentInvocation> agentInvocations =
    Collections.synchronizedList(new ArrayList<>());

// 对话上下文（同步包装的 ArrayList）
private final List<AgentMessage> context =
    Collections.synchronizedList(new ArrayList<>());

// 内部 Agent 缓存（transient，不可序列化）
private final transient Map<String, Object> agents = new ConcurrentHashMap<>();

// 执行上下文（transient，瞬态数据不持久化）
private final transient Map<String, Object> executionContexts = new ConcurrentHashMap<>();
```

### 5.2 三种 Scope 类型

```java
public enum Kind {
    EPHEMERAL,   // 一次性：root 调用结束立即清除（默认）
    REGISTERED,  // 注册到内存 registry，不持久化到外部存储
    PERSISTENT   // 持久化到外部存储（通过 AgenticScopeStore），崩溃后可恢复
}
```

只有 `PERSISTENT` 类型才创建 `ReentrantReadWriteLock`：
- **读锁**：用于 `writeState`、`registerAgentInvocation` 等写操作
- **写锁**：用于 `flush()`（checkpoint/持久化）
- 非持久化 scope 无需锁（ConcurrentHashMap 已保证线程安全）

### 5.3 关键设计：DelayedResponse 延迟求值

```java
private Object readStateBlocking(String key, Object state) {
    if (state instanceof DelayedResponse asyncResponse) {
        state = asyncResponse.blockingGet();  // 阻塞等待异步结果
        writeState(key, state);               // 替换为实际值
    }
    return state;
}
```

这是实现 **async agent** 的关键机制：异步执行的 Agent 结果先以 `DelayedResponse` 形式存入 Scope，后续 Agent 读取时自动阻塞等待，对调用者完全透明。`rootCallEnded()` 中会调用 `state.replaceAll(this::readStateBlocking)` 确保所有异步操作完成。

### 5.4 对话上下文构建

`registerContext()`：每次 Agent 调用结束后，从 ChatMemory 或 ChatMessagesAccess 提取最近的 UserMessage + AiMessage 存入 `context` 列表。

`contextAsConversation(String... agentNames)`：将对话历史格式化为人类可读字符串，供后续 Agent 通过 `summarizedContext` 选择性查看。

### 5.5 使用的设计模式

| 设计模式 | 说明 |
|---------|------|
| **黑板模式（Blackboard）** | 多个 Agent（知识源）通过中央黑板共享和交换数据 |
| **元组空间（Tuple Space）** | 类似 Linda 模型的键值对空间，Agent 通过 writeState/readState 通信 |
| **代理模式（延迟求值）** | `DelayedResponse` 在读取时自动阻塞解析 |
| **读写锁分离** | ReentrantReadWriteLock — 实现高并发读写和安全的 checkpoint |

---

## 六、高级 Agentic 模式

**包路径**：`dev.langchain4j.agentic.patterns`

### 6.1 VotingPlanner — 投票/集成模式（PARALLEL 拓扑）

多个 Agent 并行回答同一问题，然后通过投票策略聚合结果。

```java
内部状态：
  VotingStrategy strategy;      // 聚合策略
  List<Object> votes = new ArrayList<>();
  int completedCount = 0;

firstAction(): return call(subagents);  // 并行调用所有子 Agent

nextAction():
  votes.add(previousAgentInvocation.output());
  completedCount++;
  if (completedCount >= subagents.size())
      return done(strategy.aggregate(votes));  // 全部完成 → 聚合
  else
      return noOp();  // 等待剩余的 Agent
```

**VotingStrategy 函数式接口**：

```java
@FunctionalInterface
public interface VotingStrategy {
    Object aggregate(Collection<Object> votes);

    static VotingStrategy majority() {  // 多数投票：按出现次数取最大值
        return votes -> votes.stream()
            .collect(groupingBy(identity(), counting()))
            .entrySet().stream().max(comparingByValue()).get().getKey();
    }
    static VotingStrategy average()  {  // 平均值：转 Number 后求平均
        return votes -> votes.stream()
            .mapToDouble(v -> ((Number) v).doubleValue()).average().orElse(0.0);
    }
    static VotingStrategy highest()  {  // 最高值：转 Comparable 取最大值
        return votes -> votes.stream()
            .map(v -> (Comparable<Object>) v).max(naturalOrder()).orElse(null);
    }
}
```

**设计模式**：**策略模式** — `VotingStrategy` 作为可插拔的聚合策略；三种内置策略覆盖多数/均值/最优场景。

### 6.2 P2PPlanner — 点对点数据流模式（STAR 拓扑）

Agent 通过共享的 `AgenticScope` 交换数据，每个 Agent 在其所有输入就绪时被自动激活。这是一个**数据驱动**的编排引擎。

**核心内部类 `AgentActivator`**：

```java
canActivate(AgenticScope scope):
  return !executing
      && shouldExecute
      && 所有输入参数名称在 scope 中都有对应状态;

startExecution():  executing = true; shouldExecute = false;
finishExecution(): executing = false;

onStateChanged(String state):
  if (state 在这个 Agent 的输入列表中)
      shouldExecute = true;  // 重新标记为可激活
```

**执行流程**：

```
1. init(): 将每个子 Agent 包装为 AgentActivator，建立 agentId → AgentActivator 映射

2. firstAction():
   - 若 Scope 中存在 P2P_REQUEST_KEY（用户原始请求 → 首次执行）:
       * 收集所有 Agent 的输入变量名
       * 创建 VariablesExtractorAgent（通过 AiServices + LLM）
       * extractVariables(p2pRequest, allVariableNames) → 写入 Scope
   - 找出所有 canActivate 的 Agent → 并行调用

3. nextAction():
   - 检查终止条件：超限 || exitCondition 满足 → done()
   - 标记刚完成的 Agent 为 finishExecution
   - 通知所有 AgentActivator: onStateChanged(刚完成Agent的outputKey)
     // 关键：数据到达→重新标记依赖该数据的 Agent 为可激活
   - 找出下一个 canActivate 的 Agent → 并行调用
```

**核心创新 — 数据驱动激活**：类似于数据流图中"数据到达即触发执行"的语义。当 Agent A 产生输出 X 后，所有声明需要 X 作为输入的 Agent 被自动标记为可激活。

**设计模式**：
- **数据流模式（Dataflow/Reactive）** — Agent 间通过状态变化触发级联激活
- **观察者模式** — 每个 AgentActivator 观察 Scope 中的状态变化
- **前提条件图（PLG）** — 每个 Agent 声明输入前提，全部满足才激活

### 6.3 GoalOrientedPlanner — 目标导向规划 GOAP（SEQUENCE 拓扑）

使用 **A\* 搜索算法**在 Agent 依赖图中搜索从当前状态到目标的最短 Agent 执行序列。

```
init():
  goal = plannerAgent.outputKey()                           // 目标状态键
  graph = new GoalOrientedSearchGraph(subagents)            // 构建 Agent 依赖图

firstAction():
  preconditions = scope.state().keySet()                    // 当前已达成状态
  path = graph.search(preconditions, goal)                  // A* 搜索最优路径
  if (path.isEmpty()) throw IllegalStateException("无可行路径")
  return call(path[0])                                      // 执行第一个 Agent

nextAction():
  agentCursor++
  return agentCursor >= path.size() ? done() : call(path[agentCursor])
```

**崩溃恢复的特殊设计**：GoalOrientedPlanner 明确**不做状态持久化**。因为 `firstAction` 每次根据当前 Scope 状态重新计算路径，已完成的 Agent 输出已在 Scope 中，因此搜索会自然产生更短的路径。

**GoalOrientedSearchGraph**：将 Agent 建模为有向图——输入参数为输入节点，输出键为输出节点，Agent 本身为从输入到输出的有向边。

**DependencyGraphSearch — 改进的 A\* 算法**：

与标准 A\* 的关键区别：一个节点可能有多个输入依赖，所有输入都满足后才能激活该节点。

```java
SearchState(activatedNodes, currentNode, depth)
  canActivate(node): return 该节点的所有输入节点都在 activatedNodes 中

findActivatableNodes(state):
  遍历已激活节点的所有输出节点，
  找出所有输入依赖已满足但尚未激活的节点

A* 主循环:
  1. 从 PriorityQueue<StateScore> 取出 fScore 最小的状态（f = g + h）
  2. if (currentNode == goal) → reconstructPath  // 目标达成
  3. for (nextNode : findActivatableNodes(current)):
       tentativeG = currentG + 1.0               // 每步代价为 1
       if (tentativeG < gScore[nextNode])         // 找到更优路径
           更新 cameFrom、gScore、fScore → 压入 openSet
```

**启发式函数（可采纳，保证 A\* 最优性）**：从目标节点开始广度遍历其输入节点链，统计仍未激活的节点数量。此启发式不会高估实际代价。

**设计模式**：
- **GOAP（Goal-Oriented Action Planning）** — 从世界状态和目标搜索最优 Action 序列
- **A\* 搜索算法** — f(n)=g(n)+h(n) 的启发式图搜索，保证最短路径
- **依赖图搜索** — 节点需所有依赖满足才能被探索
- **状态空间搜索** — 搜索空间通过 `SearchState` 表示

---

## 七、A2A 协议（Agent-to-Agent）

**包路径**：`dev.langchain4j.agentic.a2a`  
**核心类**：`DefaultA2AClientBuilder<T>`（同时实现 `A2AClientBuilder<T>`、`InternalAgent`、`InvocationHandler`）

### 7.1 设计原理

A2A 使**远程 Agent**（运行在独立进程/服务器中）可以像本地 Agent 一样被调用。通过 `java.lang.reflect.Proxy` 将 Java 接口调用透明转化为 A2A 协议消息。

### 7.2 完整工作流程

```
1. 构建器接收 a2aServerUrl + agentServiceClass

2. 从远程服务器获取 AgentCard（Agent 元信息/能力清单）

3. 创建 A2A Client: Client.builder(agentCard).disableStreaming().build()

4. build() 创建 JDK 动态代理:
   proxy = Proxy.newProxyInstance(loader,
       [agentServiceClass, A2AClientInstance], this)

5. 代理上的每个方法调用 → invoke() → invokeAgent():

   a. 构建 TextPart 列表:
      - UntypedAgent: 从 Map 参数按 inputKeys 取值
      - 其他: 直接用参数值

   b. 构造 Message(Role.USER, parts)
   
   c. 创建 CompletableFuture<String> + 事件消费者:
      - MessageEvent → 提取所有 TextPart 文本，换行符拼接
      - TaskEvent → 从 task.artifacts 提取文本
      - TaskUpdateEvent → 类似但带 null 检查
      - 其他事件 → 异常完成
   
   d. a2aClient.sendMessage(message, consumers, streamingErrorHandler)
   
   e. future.get() 阻塞等待响应
   
   f. ServiceOutputParser.parseText(returnType, responseText) 解析结果
```

### 7.3 协议特征

| 特性 | 说明 |
|------|------|
| 拓扑类型 | `AI_AGENT` |
| 通信协议 | A2A（JSON-RPC over HTTP） |
| 发现机制 | AgentCard（`/well-known/agent-card.json` 端点） |
| 执行方式 | 异步消息 + CompletableFuture |
| 响应处理 | 事件流处理：MessageEvent / TaskEvent / TaskUpdateEvent |
| 生命周期 | Message → Task（submitted → working → completed/failed） |

**设计模式**：
- **远程代理（Remote Proxy）** — 将远程 A2A 服务封装为本地 Java 动态代理
- **适配器模式** — 将 A2A 的事件流适配为 `CompletableFuture<String>`

---

## 八、MCP 协议（Model Context Protocol）

**包路径**：`dev.langchain4j.agentic.mcp`（Agentic 层）、`dev.langchain4j.mcp.client`（Core 层）  
**核心类**：`DefaultMcpClient`（1105行）、`DefaultMcpClientBuilder<T>`

### 8.1 协议栈三层架构

```
┌──────────────────────────────────────────────────────────┐
│  DefaultMcpClientBuilder（Agentic 层）                    │
│  → 将 MCP 工具封装为 LangChain4j Agent 代理               │
│  → 拓扑类型：NON_AI_AGENT                                │
├──────────────────────────────────────────────────────────┤
│  DefaultMcpClient（Core MCP 层）                          │
│  → 工具列表/执行、资源访问、提示管理                       │
│  → 缓存策略、健康检查、重连机制、分页获取                  │
│  → McpOperationHandler 消息分发                          │
├──────────────────────────────────────────────────────────┤
│  McpTransport 传输层（策略接口 + 4 种实现）                │
│  → Stdio | HTTP(SSE) | StreamableHTTP | WebSocket       │
└──────────────────────────────────────────────────────────┘
```

### 8.2 四种传输实现详解

| 传输方式 | 类（行数） | 通信机制 | 适用场景 |
|---------|----------|---------|---------|
| **Stdio** | `StdioMcpTransport`（237行） | 启动子进程 → stdin/stdout JSON-RPC | 本地 MCP 服务器（Node/Python） |
| **HTTP(SSE)** | `HttpMcpTransport`（297行，已废弃） | OkHttp + SSE 推送 | 旧版 MCP 2024-11-05 规范 |
| **StreamableHTTP** | `StreamableHttpMcpTransport`（541行） | JDK HttpClient + SSE 流 | 新版 MCP 2025-11-25 规范 |
| **WebSocket** | `WebSocketMcpTransport`（344行） | JDK WebSocket 双向通信 | 实时双向通信 |
| **Docker** | `DockerMcpTransport`（407行） | Docker 容器 attach API | 容器化 MCP 服务器 |

### 8.3 StreamableHttpMcpTransport 核心创新（541行）

使用 JDK 内置 `java.net.http.HttpClient`，支持：

- **会话管理**：`Mcp-Session-Id` 请求/响应头自动携带
- **双通道架构**：主通道（HTTP POST）+ 辅助 SSE 通道（GET，服务端主动推送通知）
- **自动重连**：辅助通道断线后根据服务端 `retry` 字段延迟重连（`scheduleSubsidiaryReconnect`）
- **双响应格式协商**：`Accept: application/json,text/event-stream` 让服务端决定
- **SSL 动态更新**：`reloadSslContext(SSLContext)` 运行时更新证书
- **JDK 版本兼容**：通过反射调用 `httpClient.close()`，兼容 JDK 17/20/21+

### 8.4 缓存与重连机制

**通用缓存模板 `retrieveWithPossibleCaching()`**：

```
1. useCache == true:
   a. 缓存命中 → 直接返回
   b. 缓存未命中 → CAS 创建 CompletableFuture，获取后存入缓存
   c. CAS 失败（已有更新进行中）→ join 等待
2. useCache == false → 每次直接调用 retriever
```

**自动健康检查与重连**：

- 定时健康检查（默认每30秒）：`transport.checkHealth()` + MCP Ping 请求
- 失败时触发 `triggerReconnection()`：`ReentrantLock.tryLock()` 确保同一时间仅一个重连在进行
- 重连时重新调用 `initialize()` 完成 MCP 协议握手
- 传输层 onFailure 回调：等待 `reconnectInterval` 后自动触发重连（除非 `closed == true`）

### 8.5 McpOperationHandler — 消息分发中心

处理来自 MCP 服务端的所有 JSON-RPC 消息：

```
handle(message):
  if (message.has("id"))    → 带ID消息：响应匹配/服务端请求
  if (message.has("method")) → 通知处理

通知路由：
  NOTIFICATION_MESSAGE           → logMessageConsumer 记录日志
  NOTIFICATION_TOOLS_LIST_CHANGED → 工具缓存失效
  NOTIFICATION_RESOURCES_LIST_CHANGED → 资源缓存失效
  NOTIFICATION_PROMPTS_LIST_CHANGED → 提示缓存失效
  NOTIFICATION_RESOURCES_UPDATED  → 通知资源 URI 更新
  NOTIFICATION_PROGRESS           → progressHandler.onProgress()
```

`cancelAllPendingOperations(reason)`：传输层故障时，将所有待处理 Future 以异常完成并清空映射。

### 8.6 MCP 工具作为 Agent

```java
DefaultMcpClientBuilder 的工作流程：

1. 接收 McpClient + 目标接口类
2. build() 调用 findTool():
   - 若未指定 toolName 且只有一个工具 → 自动选中
   - 若未指定 toolName 且有多个 → 抛异常
   - 若指定 toolName → 按名查找
3. build() 创建动态代理:
   proxy = Proxy.newProxyInstance(loader, [agentServiceClass, McpClientInstance], this)
4. 代理上的方法调用 → invoke() → invokeTool():
   a. 从方法参数构建 JSON args（UntypedAgent 从 Map 取值，其他反射获取参数名）
   b. 构建 ToolExecutionRequest(name, Json.toJson(argsMap))
   c. mcpClient.executeTool(executionRequest)
   d. ServiceOutputParser.parseText(returnType, responseText) 解析结果
```

| 维度 | A2A Client | MCP Client |
|------|-----------|------------|
| 拓扑类型 | `AI_AGENT` | `NON_AI_AGENT` |
| 通信协议 | A2A（JSON-RPC over HTTP） | MCP（JSON-RPC over stdio/HTTP/WS） |
| 执行方式 | 异步消息（sendMessage + CompletableFuture） | 同步工具执行（executeTool） |
| 参数获取 | 构建 TextPart 列表 | 构建 JSON 参数 |
| 响应处理 | 事件流处理 | 直接文本响应 |
| 发现机制 | AgentCard | listTools() |

### 8.7 MCP 层使用的设计模式

| 设计模式 | 说明 |
|---------|------|
| **适配器模式** | 四种 McpTransport 将不同通信协议适配到统一接口 |
| **模板方法** | `retrieveWithPossibleCaching()` 通用缓存逻辑 |
| **建造者模式** | `DefaultMcpClient.Builder`（300+行）智能默认值 |
| **CAS 无锁并发** | `AtomicReference.compareAndExchange` 实现并发安全的缓存更新和重连保护 |
| **ReentrantLock** | `triggerReconnection()` 确保单线程重连 |
| **观察者模式** | `McpClientListener`（256行，20+事件回调）全方位生命周期通知 |
| **分页模式** | `fetchPaginatedList()` 通用分页获取（tools/resources/prompts） |
| **未来/承诺模式** | CompletableFuture 贯穿整个通信层 |

---

## 九、Skills 技能系统

**包路径**：`dev.langchain4j.skills`（`@Experimental`）  
**核心类**：`Skills`

### 9.1 设计原理

Skills 符合 Agent Skills 规范（agentskills.io）的"基于工具的集成"方式。每个 Skill 是自包含的能力包，包含 `SKILL.md` 清单文件和可选资源：

```yaml
---
name: pdf-reader
description: 从 PDF 文件中提取文本和元数据
---
# PDF 阅读器技能
...技能指令和资源引用...
```

### 9.2 完整执行流程

```
1. Skills.from(FileSystemSkillLoader.loadSkills(skillsDir)) 构建 Skills 实例
   └── 所有技能内容在构建时加载到内存

2. 将 formatAvailableSkills() 的 XML 注入系统消息:
   <available_skills>
     <skill>
       <name>pdf-reader</name>
       <description>从 PDF 文件中提取文本和元数据</description>
     </skill>
   </available_skills>

3. 将 skills.toolProvider() 注册到 AiServices（作为动态 ToolProvider）

4. LLM 在对话中调用 activate_skill 工具激活特定技能

5. 激活后，ToolProvider 动态变化：
   - provideTools() 从消息历史中解析已激活的技能名
   - 只返回已激活技能的工具（+ 管理工具 activate_skill / read_skill_resource）
   - 管理工具标记为 ALWAYS_VISIBLE（始终对 LLM 可见）

6. LLM 调用技能工具完成用户请求
```

### 9.3 关键实现

```java
public class Skills {
    // 核心方法
    ToolProvider toolProvider();        // 返回动态 ToolProvider（isDynamic() = true）
    String formatAvailableSkills();     // 格式化可用技能为 XML

    // 管理工具
    // activate_skill: 激活技能，将技能指令注入 LLM 上下文
    // read_skill_resource: 读取技能打包的资源文件

    // 已激活技能解析：从消息历史中查找 ACTIVATED_SKILL_ATTRIBUTE 属性
    static Set<String> getActivatedSkillNames(List<ChatMessage> messages);
}
```

**设计模式**：
- **动态 Tool Provider** — `ToolProvider.isDynamic() = true`，每次请求动态计算可用工具
- **外观模式（Facade）** — 封装技能加载、管理、暴露的复杂逻辑
- **装饰器模式** — 可与已有 ToolProvider 组合使用
- **元数据标记** — `SEARCH_BEHAVIOR = ALWAYS_VISIBLE` 标记管理工具始终可见

---

## 十、RAG 检索增强生成

**包路径**：`dev.langchain4j.rag`  
**核心类**：`DefaultRetrievalAugmentor`

### 10.1 五阶段管道

```
Query → QueryTransformer → QueryRouter → ContentRetriever(s)
                                                 ↓
User ← ContentInjector ← ContentAggregator ←─────┘
```

| 阶段 | 接口 | 职责 | 默认实现 |
|------|------|------|---------|
| **QueryTransformer** | `QueryTransformer` | 重写/扩展查询 | `DefaultQueryTransformer` |
| **QueryRouter** | `QueryRouter` | 路由到合适的检索器 | `DefaultQueryRouter` |
| **ContentRetriever** | `ContentRetriever` | 获取相关内容 | 必须由用户提供 |
| **ContentAggregator** | `ContentAggregator` | 合并/重排序结果 | `DefaultContentAggregator` |
| **ContentInjector** | `ContentInjector` | 将检索内容注入提示词 | `DefaultContentInjector` |

### 10.2 并行检索策略

`process()` 方法根据查询数量和检索器数量选择最优执行策略：

```
分支1: 单查询 + 单检索器 → 同线程同步执行（最优路径，无线程开销）
分支2: 单查询 + 多检索器 → CompletableFuture 并行检索
分支3: 多查询 + 任意检索器 → 每个查询独立线程路由 + 各自并行检索
分支4: 空查询 → 返回空 Map
```

`retrieveFromAll()` 使用 `CompletableFuture.allOf().thenApply()` 等待所有检索完成后收集结果。默认线程池使用 `Executors.newCachedThreadPool()` 变体（keepAlive 1秒）。

**设计模式**：
- **流水线模式（Pipeline）** — 固定顺序的 5 步处理链
- **策略模式** — 5 个组件接口可独立替换实现
- **Future/Promise** — CompletableFuture 并行化检索
- **不可变对象** — 所有字段 final，构造后不可变

---

## 十一、Memory 对话记忆系统

**包路径**：`dev.langchain4j.memory.chat`

### 11.1 核心接口

```java
public interface ChatMemory {
    Object id();
    void add(ChatMessage message);
    default void add(ChatMessage... messages) {...}
    List<ChatMessage> messages();
    void clear();
}

public interface ChatMemoryProvider {
    ChatMemory get(Object memoryId);  // 按用户/会话提供记忆实例
}
```

### 11.2 两种滑动窗口实现

| 类 | 窗口策略 | 关键特性 |
|---|---------|---------|
| `MessageWindowChatMemory` | 消息数量上限 FIFO | `maxMessagesProvider: Function<Object, Integer>` 支持运行时动态调整 |
| `TokenWindowChatMemory` | Token 数量上限 FIFO | 使用 `TokenCountEstimator` 估算，消息不可分割（整条全留或全删） |

**MessageWindowChatMemory 的关键行为（ensureCapacity）**：

```
while (messages.size() > maxMessages):
  1. 确定驱逐位置：若位置0是 SystemMessage → 从位置1开始（保留 SystemMessage）
  2. 移除消息
  3. 若移除的是包含工具调用的 AiMessage:
     继续移除后面所有的"孤儿" ToolExecutionResultMessage
     // 防止 OpenAI 等拒绝接收没有对应 AiMessage 的 ToolExecutionResultMessage
```

**TokenWindowChatMemory 的差异**：
- 同样处理孤儿消息，但额外递减 token 计数（`currentTokenCount -= estimator.estimateTokenCountInMessage(orphan)`）
- 消息不可分割——整条消息要么全保留，要么全移除
- 不能删除最后的 SystemMessage（`if (messages.size() == 1) return`）

### 11.3 与 AiServices 的集成

当用户接口继承 `ChatMemoryAccess` 时：

```java
interface MyAgent extends ChatMemoryAccess {
    String chat(@UserMessage String msg);
}

// 代理自动暴露：
myAgent.getChatMemory(memoryId);    // 获取指定会话的记忆
myAgent.evictChatMemory(memoryId);  // 清除指定会话的记忆
```

---

## 十二、Tool / Function Calling 系统

**包路径**：`dev.langchain4j.agent.tool`（注解）、`dev.langchain4j.service.tool`（执行）

### 12.1 工具声明注解

```java
@Target(METHOD) @Retention(RUNTIME)
public @interface Tool {
    String name() default "";                              // 工具名（默认方法名）
    String[] value() default "";                           // 工具描述
    ReturnBehavior returnBehavior() default TO_LLM;        // 返回行为
    SearchBehavior searchBehavior() default SEARCHABLE;     // 搜索行为
    String metadata() default "{}";                        // 额外元数据（JSON）
}

@Target(PARAMETER) @Retention(RUNTIME)
public @interface P {
    String value() default "";           // 参数描述
    String name() default "";            // 参数名
    boolean required() default true;     // 是否必需
    String defaultValue() default "";    // 默认值
}
```

### 12.2 ToolService 工具调用循环

`executeInferenceAndToolsLoop()` 驱动 LLM↔Tool 的完整交互：

```
while (true) {
    // 步骤1: 安全阀
    if (roundTripsLeft-- == 0) throw 异常("超过最大工具调用轮数")

    // 步骤2-3: 提取并存储 LLM 响应
    aiMessage = chatResponse.aiMessage()
    将 aiMessage 添加到 chatMemory 或 messages 列表

    // 步骤4: 终止条件 — LLM 选择不调用工具（给出最终回复）
    if (!aiMessage.hasToolExecutionRequests()) break

    // 步骤5-6: 执行工具
    toolRequests = aiMessage.toolExecutionRequests()
    if (executor != null && toolRequests.size() > 1)
        executeConcurrently(toolRequests)    // CompletableFuture 并发
    else
        executeSequentially(toolRequests)    // 顺序执行（单工具避免线程浪费）

    // 步骤7: 处理每个工具执行结果
    for (request : toolRequests):
        result = toolResults.get(request)
        resultMessage = toResultMessage(request, result)
        添加到 chatMemory/messages
        触发 ToolExecutedEvent
        记录 returnBehavior

    // 步骤8: 判断是否立即返回
    if (shouldReturnImmediately(anyToolErrored, returnBehaviors)):
        return ToolServiceResult(immediateToolReturn=true)

    // 步骤9: 刷新动态 ToolProvider
    refreshDynamicProviders()  // 只重新调用 isDynamic()==true 的 Provider

    // 步骤10-11: 构建并发送下一次请求
    parameters = parameters.overrideWith(更新后的工具规格)
    chatRequest = chatRequestTransformer.apply(...)
    chatResponse = chatModel.chat(chatRequest)
    累积 token 使用量
}
```

### 12.3 ReturnBehavior 行为矩阵

```java
public enum ReturnBehavior {
    TO_LLM,              // 工具结果发送给 LLM 继续处理（默认）
    IMMEDIATE,           // 立即返回结果，不再调用 LLM
    IMMEDIATE_IF_LAST    // 如果是最后一个工具则立即返回
}
```

完整决策矩阵：
```
[TO_LLM]                            → 继续循环（LLM 看到结果后再决定）
[IMMEDIATE]                         → 立即返回
[IMMEDIATE_IF_LAST]                 → 立即返回
[TO_LLM, IMMEDIATE_IF_LAST]         → 立即返回
[IMMEDIATE, TO_LLM]                 → 继续循环
[IMMEDIATE_IF_LAST, TO_LLM]         → 继续循环
```

### 12.4 工具执行错误处理（责任链模式）

```java
executeWithErrorHandling(request, executor, context):
  try {
      return executor.executeWithContext(request, context)
  } catch (Exception e):
      if (e instanceof ToolArgumentsException)
          错误处理结果 = argumentsErrorHandler.handle(原因, 上下文)
      else
          错误处理结果 = executionErrorHandler.handle(原因, 上下文)
      return ToolExecutionResult(isError=true, resultText=错误处理结果.text())
```

默认错误处理器：
- **参数错误**（`DEFAULT_TOOL_ARGUMENTS_ERROR_HANDLER`）：直接抛 `RuntimeException`
- **执行错误**（`DEFAULT_TOOL_EXECUTION_ERROR_HANDLER`）：返回错误消息文本给 LLM，让 LLM 自行修正

### 12.5 动态 ToolProvider

支持两种模式：
- **非动态**（`isDynamic() == false`）：构建时调用一次 `provideTools()`
- **动态**（`isDynamic() == true`）：每次 LLM 调用前刷新

新工具只做**添加**（`putIfAbsent`），不做删除。

---

## 十三、Guardrails 护栏系统

**包路径**：`dev.langchain4j.guardrail`

### 13.1 架构层级

```
Guardrail<P, R>（接口）
├── InputGuardrail  extends Guardrail<InputGuardrailRequest, InputGuardrailResult>
└── OutputGuardrail extends Guardrail<OutputGuardrailRequest, OutputGuardrailResult>

GuardrailResult<GR>（sealed interface — 限制子类型）
├── SUCCESS              — 放行
├── SUCCESS_WITH_RESULT  — 改写后放行（hasRewrittenResult()）
├── FAILURE              — 拒绝（非致命）
└── FATAL                — 致命失败（可触发 retry/reprompt）

GuardrailRequest<P>（sealed interface）
├── InputGuardrailRequest  — 含 UserMessage、ChatMemory、AugmentationResult
└── OutputGuardrailRequest — 含 AiMessage、ChatMemory、ChatExecutor

GuardrailExecutor<C,P,R,G,E>（sealed interface）
├── InputGuardrailExecutor  — 验证输入，失败直接抛异常
└── OutputGuardrailExecutor — 验证输出，支持重试/重新提示
```

### 13.2 AbstractGuardrailExecutor 模板方法

```java
executeGuardrails(P request):
  1. accumulatedResult = createSuccess()
  2. for (guardrail : guardrails):
       a. result = guardrail.validate(request).validatedBy(guardrail.getClass())
       b. if (result == FATAL) → return handleFatalResult()  // 短路返回
       c. if (result == SUCCESS_WITH_RESULT) → 更新 request 文本为改写后的版本
       d. accumulatedResult = composeResult(accumulatedResult, result)
  3. return accumulatedResult

composeResult(oldResult, newResult):
  - 旧成功 → 返回新结果
  - 新成功 → 保留旧结果（优先保留有问题的结果）
  - 都失败 → 拼接所有 failures 列表
```

### 13.3 OutputGuardrailExecutor 重试机制

```
while (attempt < maxAttempts):
  1. 对所有 guardrail 执行验证
  2. if (成功) → rewriteResult() 返回
  3. if (不是 retry) → 抛出 OutputGuardrailException
  4. if (是 retry):
     - 从 failure 提取 reprompt 文本 → 创建 UserMessage
     - chatMemory.messages() + reprompt 组成新消息列表
     - chatExecutor.execute(messages) 重新调用 LLM
     - 用新响应重建 OutputGuardrailRequest
     - attempt++ 继续循环
5. 达到最大重试次数 → 抛出含所有失败消息的异常
```

---

## 十四、输出解析系统

**包路径**：`dev.langchain4j.service.output`

### 14.1 解析器层次结构

```
OutputParser<T>（泛型接口）
├── 基本类型：Boolean, Byte, Short, Integer, Long, Float, Double
├── 大数类型：BigInteger, BigDecimal
├── 时间类型：Date, LocalDate, LocalTime, LocalDateTime
├── 枚举：EnumOutputParser<E>（大小写不敏感匹配）
├── 集合：StringList, StringSet, EnumList, EnumSet, EnumCollection
├── POJO：PojoOutputParser<T>（JSON 反序列化 + 多态支持）
└── POJO集合：PojoListOutputParser<T>, PojoSetOutputParser<T>
```

### 14.2 工厂模式

`DefaultOutputParserFactory` 注册 16 种基本类型的解析器，`ServiceOutputParser.parse()` 根据返回类型查找对应解析器。

### 14.3 多态 POJO 支持

```java
PojoOutputParser.parse(String text):
  1. 非多态: extractAndParseJson(text, type) — 提取 JSON 反序列化
  2. 多态:
     a. 先反序列化为 Map
     b. 若只有 "value" 键 → 取出 value 再反序列化（多态包装器格式）
     c. 否则直接反序列化

PojoOutputParser.jsonSchema():
  - 多态类型: polymorphicSchemaFrom + wrapPolymorphic
  - 普通类型: jsonObjectOrReferenceSchemaFrom
  - 若无任何可发现子类型 → 抛出 UnsupportedFeatureException
```

---

## 十五、可观测性系统

**包路径**：`dev.langchain4j.agentic.observability`

### 15.1 AgentListener — 生命周期观察者

```java
public interface AgentListener {
    default void beforeAgentInvocation(AgentRequest request) {}
    default void afterAgentInvocation(AgentResponse response) {}
    default void onAgentInvocationError(AgentInvocationError error) {}
    default void afterAgenticScopeCreated(AgenticScope scope) {}
    default void beforeAgenticScopeDestroyed(AgenticScope scope) {}
    default void beforeAgentToolExecution(BeforeAgentToolExecution event) {}
    default void afterAgentToolExecution(AfterAgentToolExecution event) {}
    default boolean inheritedBySubagents() { return true; }
}
```

所有方法均为 `default` 空实现，实现者只需覆写关心的方法。`inheritedBySubagents()` 控制是否自动被子 Agent 继承。

### 15.2 AgentMonitor — 运行时数据收集器

实现 `AgentListener`，按 `memoryId` 组织执行记录：

```java
Map<Object, List<MonitoredExecution>> successfulExecutions;  // 成功执行
Map<Object, List<MonitoredExecution>> failedExecutions;      // 失败执行
Map<Object, MonitoredExecution> ongoingExecutions;           // 进行中执行
```

**`beforeAgentInvocation`**：通过 memoryId 查找/创建 MonitoredExecution。若已存在（嵌套调用），调用嵌套的 `beforeAgentInvocation`。

**`afterAgentInvocation`**：标记完成。若 `execution.done()`（所有嵌套子调用完成），从 ongoing 移到 successful。

**`onAgentInvocationError`**：从 ongoing 移到 failed。

### 15.3 HtmlReportGenerator — 可视化报告（1161行 record 类型）

自包含的 HTML 报告生成器，包含两大可视化：

**A. 系统拓扑图**：
- 纯 CSS 组织图显示 Agent 层级结构
- 每种拓扑类型有专属颜色标识（AI=绿、非AI=灰、人工=橙、顺序=青、并行=蓝、循环=紫、路由=红、Star=黄）
- SVG 贝塞尔曲线显示 Agent 间的数据流依赖边
- 内联 JavaScript 实现边绘制和拓扑展开/折叠

**B. 执行瀑布图**：
- 按会话分组，可折叠的会话卡片
- 每条执行记录：Agent 名称（带缩进和拓扑徽章）、耗时（格式化 "1.2s"/"2m 30s"）、Token 消耗（"1.5k"）、时间线条形图
- 工具执行以 "Tool" 徽章区分
- 输入/输出截断显示 + tooltip 完整内容

---

## 十六、@Agent 注解模型

**包路径**：`dev.langchain4j.agentic`

```java
@Target(METHOD)
@Retention(RUNTIME)
public @interface Agent {
    String name() default "";                                // Agent 名称（默认方法名）
    String value() default "";                               // 描述别名
    String description() default "";                         // 描述别名
    String outputKey() default "";                           // 结果在 Scope 中的存储键
    Class<? extends TypedKey<?>> typedOutputKey()
        default NoTypedKey.class;                            // 类型安全的输出键
    boolean async() default false;                           // 是否异步执行
    boolean optional() default false;                        // 参数缺失时静默跳过
    String[] summarizedContext() default {};                 // 参与上下文定义的 Agent 名称
}
```

**关键设计点**：
- **`outputKey`**：Agent 间数据流的核心机制 — Agent A 写入键 "analysis"，Agent B 从 "analysis" 读取
- **`async`**：在 ParallelPlanner 中启用真正的并行执行（结果以 DelayedResponse 存入 Scope）
- **`optional`**：参数缺失时静默跳过，不抛异常
- **`summarizedContext`**：限定此 Agent 可见的先前 Agent 输出范围，防止上下文污染
- **`typedOutputKey`**：类型安全的键替代方案，使用 `TypedKey<T>` 编译期检查

---

## 十七、完整设计模式汇总

| 设计模式 | LangChain4j 实现 | 关键机制 |
|---------|-----------------|---------|
| **声明式代理** | `DefaultAiServices` | JDK `Proxy.newProxyInstance()` + `InvocationHandler` |
| **Planner 循环** | `Planner` + `PlannerBasedInvocationHandler` | 状态机：firstAction → nextAction 循环 |
| **黑板模式** | `DefaultAgenticScope` | ConcurrentHashMap 共享状态 + DelayedResponse 延迟求值 |
| **链式执行** | `SequentialPlanner` | 游标顺序遍历 Agent 列表 |
| **分叉-合并** | `ParallelPlanner` | ExecutorService + CompletableFuture.allOf() |
| **条件路由** | `ConditionalPlanner` | Predicate\<AgenticScope\> 条件过滤 |
| **LLM 监督者** | `SupervisorPlanner` | LLM 动态决定委托给哪个 Worker Agent |
| **MapReduce** | `ParallelMapperPlanner` | Map: N个实例并行 → Reduce: 收集结果 |
| **投票/集成** | `VotingPlanner` | 并行 + VotingStrategy 聚合（majority/average/highest） |
| **数据流/P2P** | `P2PPlanner` | STAR拓扑 + onStateChanged 数据驱动激活 |
| **GOAP** | `GoalOrientedPlanner` | A\* 搜索在前提条件/效果图上找最短路径 |
| **远程代理** | A2A / MCP ClientBuilder | AgentCard / ToolSpec → 动态代理 → 远程调用 |
| **技能注入** | `Skills`（@Experimental） | SKILL.md 清单 → 动态 ToolProvider → activate_skill |
| **流水线** | `DefaultRetrievalAugmentor` | 5阶段：Transform → Route → Retrieve → Aggregate → Inject |
| **滑动窗口** | MessageWindow / TokenWindow ChatMemory | 固定大小 FIFO + 孤儿消息自动清理 |
| **护栏链** | Composite InputGuardrail / OutputGuardrail | 有序短路评估 + retry/reprompt 重试 |
| **策略模式** | Planner, VotingStrategy, OutputParser, ToolProvider 等 | 几乎所有组件接口均可替换实现 |
| **观察者模式** | AgentListener, McpClientListener, AiServiceListenerRegistrar | 全方位事件回调 |
| **备忘录模式** | executionState() / restoreExecutionState() | 精确的崩溃恢复点 |
| **命令模式** | Action 子类型（AgentCallAction, DoneAction, DoneWithResultAction, NoOpAction） | 封装"下一步要做什么" |
| **建造者模式** | 所有不可变对象 | 链式构建器 + 智能默认值 |
| **模板方法** | firstAction默认实现, retrieveWithPossibleCaching, executeGuardrails | 骨架固定，子类覆写细节 |
| **空对象** | ToolServiceContext.Empty.INSTANCE | 无工具时的空上下文（避免 null 检查） |
| **适配器模式** | McpTransport, TokenStreamAdapter, A2A事件适配 | 统一接口适配不同协议 |
| **抽象工厂** | WorkflowAgentsBuilderImpl（枚举单例） | 五种工作流拓扑的构建器工厂 |
| **不可变对象** | ToolSpecification, ToolServiceResult, ToolServiceContext | 所有字段 final，Builder 构造 |
| **生产者-消费者** | PlannerLoop 的 nextAction | volatile + ReentrantLock 并发控制 |
| **CAS 无锁** | AtomicReference.compareAndExchange | 并发安全的缓存更新和重连保护 |
| **依赖图搜索** | DependencyGraphSearch | 改进 A\*：多依赖满足后才可展开节点 |
| **状态空间搜索** | GOAP 的 SearchState | 搜索状态表示 + PriorityQueue 优先队列 |
| **分页模式** | fetchPaginatedList() | 通用游标分页获取 tools/resources/prompts |
| **Future/Promise** | CompletableFuture 贯穿全系统 | 异步 I/O、并行检索、并行工具执行 |
| **读写锁分离** | DefaultAgenticScope ReentrantReadWriteLock | 高并发读写 + 安全的 checkpoint |

---

## 十八、与 Python LangChain 的核心差异

| 维度 | LangChain4j (Java) | LangChain (Python) |
|------|-------------------|-------------------|
| **组合方式** | JDK 动态代理（编译期类型安全） | `Runnable` 鸭子类型（运行时） |
| **Agent 声明** | `@SystemMessage`、`@UserMessage` 注解驱动 | `ChatPromptTemplate` 字符串模板 |
| **工具绑定** | `@Tool` 注解 + `ToolSpecifications.toolSpecificationFrom()` 自动生成 JSON Schema | `@tool` 装饰器 + Pydantic 模型 |
| **执行模型** | `InvocationHandler.invoke()` 拦截方法调用 | `Chain.invoke()` 函数式调用链 |
| **类型安全** | 强类型：返回类型由编译器强制检查 | 弱类型：边界处为 `dict`/`str` |
| **状态管理** | `AgenticScope` + `TypedKey<T>` 类型化键 | `RunnableConfig` + 任意 dict |
| **编排方式** | `Planner` 状态机（6+ 实现）+ A2A/MCP 协议 | `RunnableLambda`、`RunnableBranch` |
| **崩溃恢复** | `executionState()`/`restoreExecutionState()` 精确恢复 | 依赖外部持久化方案 |
| **并行执行** | `CompletableFuture` + `ExecutorService` | `asyncio` + `AsyncIterator` |
| **流式输出** | `TokenStream` + `TokenStreamAdapter` SPI | `AsyncIterator` 异步生成器 |
| **远程 Agent** | A2A（AgentCard + JSON-RPC）、MCP（4种传输） | 社区方案，无统一标准 |
| **可观测性** | `AgentListener` + `AgentMonitor` + `HtmlReportGenerator`（1161行） | LangSmith / Callback 系统 |
| **Memory** | 滑动窗口 + Token窗口 + 孤儿消息自动清理 | `BaseChatMemory` 子类 |
| **Sealed 类型** | GuardrailResult、GuardrailRequest、GuardrailException 等 | 无（Python 不支持 sealed class） |
| **SPI 扩展** | ServiceLoader 加载插件 | setuptools entry_points |
