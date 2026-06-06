# LyClaw 最小可用 Agent 笔试交付计划

## 目标

把当前 LyClaw 项目收敛成一个可以用于笔试提交的最小可用 Agent 框架。重点不是继续扩张成完整平台，而是补齐题目要求的主流程闭环：

- 支持多轮对话和 session 维护。
- 不依赖 LangChain/OpenHands 等现成 Agent runtime，核心循环由 LyClaw 自己实现。
- Agent 能在每一步判断直接回答或调用工具，执行工具后读取结果并继续，直到最终答案。
- 至少提供 3 个工具。
- 有最大步数限制、异常处理、工具调用 trace 或执行日志。
- 支持跨轮次继续执行：第一轮创建任务并记录状态，第二轮追问进度时能基于已有状态继续。
- 使用真实 LLM API。
- README 能讲清运行方式、系统设计、memory 召回时机与放置方式、AI Prompt 与问题解决记录。

## 当前项目定位判断

当前仓库的实际主线是：

- `lyclaw-framework`: 核心 SPI、模型抽象、ReActEngine、Agent 动态代理、pipeline stage、subagent 基础类。
- `lyclaw-autoconfigure`: Spring Boot 自动配置、注解/YAML Agent 配置、工具和模型自动装配。
- `lyclaw-action`: 工具注册、工具策略、部分多 Agent registry/router/orchestrator/supervisor 实现。
- `lyclaw-starter`: starter 依赖聚合。
- `lyclaw-web`: 基于框架做的 demo，不作为框架主实现评估对象。

README 和部分设计文档还描述 gateway/facade/memory/protocol 等更大平台形态，但当前代码没有对应完整模块。笔试提交时应避免按旧文档叙述，应该按当前可运行框架讲清楚。

## 已具备能力

- `DefaultReActEngine` 已有自研 ReAct 循环：
  - 调 LLM。
  - 识别 tool calls。
  - 执行工具。
  - 把 assistant/tool 消息追加回 `ChatRequest.messages`。
  - 继续下一轮直到纯文本响应或达到 `AgentProperties.maxToolRounds`。
- 已有真实模型适配抽象：`ChatFacade`、`ChatModel`、`OpenAiProtocolChatModel`、`DeepSeekChatModel`。
- 已有工具接口和注册表：`Tool`、`ToolRegistry`、`DefaultToolRegistry`、`ToolProvider`。
- 已有工具策略：`DefaultToolCallPolicy` 支持最大轮次、单工具调用次数、黑白名单、错误策略。
- 已有注解式 Agent 代理：`@Agent` 接口通过 `AgentProxyFactory` 和 `AgentInvocationHandler` 运行。
- 已有基础日志和 SSE trace 事件：tool_call executing/done、thinking、message、done 等。
- 当前 `mvn compile -q` 已通过。

## 主要缺口

### 1. Session 维护还不是真正的框架能力

现状：

- `Session` 只是一个内存模型，有 `sessionId/messages` 和最多 500 条裁剪。
- `AgentInvocationHandler.buildChatRequest()` 每次只用当前用户消息创建新 `ChatRequest`。
- `ContextBuildStage` 只打印“加载会话”，没有真实 SessionStore 注入。
- `SubagentSessionManager` 明确写了 `SessionStore` 不存在，只能内存管理子会话。

影响：

- 多轮对话大概率依赖 web demo 层自己传入历史，而不是框架主动维护。
- 面试题要求的“session 维护”和“跨轮次继续执行”在框架层不完整。

### 2. Memory/任务状态未落地

现状：

- `ContextBuildStage`、`MetricsStage`、`ChatContext` 都有 memory TODO。
- 没有 `MemoryStore`、`TaskStore` 或任务状态模型。
- 没有“第一轮创建任务，第二轮追问进度”的框架级示例。

影响：

- README 很难解释 memory 的召回时机与放置方式。
- 跨轮次继续执行只能靠外部 demo，不符合题目对 Agent runtime 的要求。

### 3. 多 Agent 架构组织不清晰

现状有两套并行体系：

- `lyclaw-action/agent`: `DefaultAgentRegistry`、`RouterChain`、`Orchestrator`、`SupervisorOrchestrator`、任务分解/聚合。
- `lyclaw-framework/react/subagent`: `DelegateToAgentToolProvider`、`SubagentSpawner`、`SubagentSessionManager`。

问题：

- 入口不统一。主 ReAct loop 通过 `delegate_to_agent` 工具调用子 Agent，而 `Orchestrator/SupervisorOrchestrator` 又是另一套服务。
- `SubagentSpawner` 直接持有 `ChatFacade/ReActEngine/ToolRegistry`，但 registry/router 在 `lyclaw-action`，边界反向耦合。
- `SupervisorOrchestrator` 的 DAG 执行是同步阻塞和串行风格，缺少统一 task run id、状态持久化、取消/恢复语义。
- 子 Agent 会话是临时内存对象，没有进入统一 SessionStore。

建议：

- 笔试版本先不要强化复杂 supervisor/DAG。
- 把“多 Agent”降级为可解释的最小能力：父 Agent 可通过内置 `delegate_to_agent` 工具委派给指定子 Agent，子 Agent 独立 ReAct 运行并返回 observation。
- `lyclaw-action/agent` 的 registry/router 可作为后续扩展，笔试 README 中说明目前主线使用 tool-based delegation。

### 4. 工具执行路径重复

现状至少三条路径：

- `DefaultReActEngine` 直接接收 `ToolExecutor`。
- `RespondStage` 自己构造 `ToolExecutor` 并调用 `ToolRegistry`。
- `DefaultToolExecutionPipeline` 实现 7 步工具管线。
- `ToolCallLoop` 是另一套旧 ReAct 循环。

影响：

- trace、policy、hook、异常处理不能保证一致。
- 面试讲“核心 runtime”时会显得主流程不收敛。

建议：

- 主路径固定为：`AgentRuntime -> DefaultReActEngine -> DefaultToolExecutionPipeline -> ToolRegistry`。
- `ToolCallLoop` 标注为 legacy 或测试兼容路径，不作为 README 主流程。

### 5. trace 是日志型，不是可查询执行记录

现状：

- 日志很丰富。
- `AgentContext` 有 `TraceContext`。
- SSE 有工具事件。

缺口：

- 没有结构化 `StepTrace` / `ToolCallTrace` 集合可由 API/终端展示。
- 题目要求的“工具调用 trace 或执行日志”可以用日志勉强满足，但录屏和 README 中最好能展示结构化 trace。

### 6. README 与代码不一致

README 还写大量未实现的大平台模块，不适合直接作为笔试提交 README。

建议：

- 新增或重写一个 `README-agent-demo.md`，只讲当前可运行能力。
- 原 README 可保留项目愿景，但提交链接中应优先指向笔试 README。

## 明确 bug / 风险点

### P0: 动态工具定义缺少 AgentContext，可能导致 `delegate_to_agent` 工具不可见

位置：

- `AgentInvocationHandler.buildChatRequest()`
- `DefaultToolRegistry.getAllDefinitions(request, attributes)`
- `DelegateToAgentToolProvider.provideTools()`

问题：

- `AgentInvocationHandler.buildChatRequest()` 当前调用 `toolRegistry.getAllDefinitions(request)`，没有传入 `agentContext`。
- `DelegateToAgentToolProvider` 需要从 `request.extras["agent.delegation"]` 判断是否提供工具，执行时还需要 `agentContext`。
- 后面 `AgentInvocationHandler` 才把 delegation config 放到 `request.extras`，但 tool definitions 已经提前取过一次。

修复方向：

- 在 delegation config 写入后重新刷新 `request.tools`。
- 或把工具解析延后到 `RespondStage`，并保证传入 `Map.of("agentContext", ctx)`。

### P0: ProgressBus toolCallId 后缀不一致

位置：

- `DefaultReActEngine.emitRoundToolCallEvents()`
- `DelegateToAgentToolProvider.resolveEmitter()`

问题：

- 真正执行工具时使用 `dedupedId = req.getId() + suffix`。
- ProgressBus 注册时用 `registerEmitter(request.getSessionId(), req.getId(), ...)`。
- `delegate_to_agent` 执行时收到的 `toolCallId` 是带 suffix 的 id，先按带 suffix 查找会失败，只能 fallback 扫描 session 前缀。

影响：

- 多个工具并发或同 session 多个 delegation 时，fallback 扫描可能拿错 emitter。

修复方向：

- 注册、查询、移除统一使用 `dedupedId`。
- approval flow 也统一原始 id 与展示 id 的策略。

### P1: 流式路径在“先输出文本后又 tool_call”时可能已经把不完整文本发给用户

位置：

- `DefaultReActEngine.executeStream()`
- `continueReActRounds()`

问题：

- 状态进入 relaying 后如果后续检测到 tool_calls，之前文本已经以 `message` SSE 发出。
- 之后工具执行和后续轮次继续进行，用户端可能看到一段中间推理文本和最终答案混在一起。

修复方向：

- 对支持工具调用的流式请求，首轮可先缓冲到确认无 tool_call 再透传。
- 或定义 `assistant_partial` 与 `message_final` 事件，前端按语义处理。

### P1: `SubagentSpawner` 流式信号量超时后强行 `drainPermits/release(2)` 有并发风险

位置：

- `SubagentSpawner.spawnSubagent(... progressEmitter)`

问题：

- 获取信号量失败后 drain 并 release(2)，会破坏原始最大并发限制。

修复方向：

- 删除强制 drain/release。
- 超时直接返回 rejected/timeout。

### P1: `DefaultToolExecutionPipeline.execute()` 对 `ctx` 没有空值保护

位置：

- `DefaultToolExecutionPipeline.execute()`

问题：

- 异常和成功分支里直接 `ctx.getSuccessCount()`、`ctx.addToolResult()`。
- 如果作为通用工具管线被无上下文调用会 NPE。

修复方向：

- 对 ctx 为空时跳过计数和上下文记录。

### P1: `DefaultToolRegistry.registerProvider()` 可能重复注册

位置：

- `DefaultToolRegistry.onContextRefreshed()`

问题：

- 每次 `ContextRefreshedEvent` 都 add provider，没有去重。

修复方向：

- 用 provider class/name 去重，或用 `Set<ToolProvider>`。

### P2: `SubagentSessionManager.countDescendants()` 会把自身计入后代

位置：

- `SubagentSessionManager.countDescendants()`

问题：

- prefix 是 `sessionKey + "/"`，自身不会匹配，但 archive 后先 remove root 再 count，日志里的 descendants 可能永远不含刚归档前的实际 active root，这个不是严重 bug。
- 更主要问题是 session 状态无法持久化，archive/terminate 只是内存索引操作。

### P2: README 版本、模块、Java 版本不一致

位置：

- 根 `README.md`
- 根 `pom.xml`

问题：

- README 写 Java 17、版本 2.0.0-SNAPSHOT、大量微服务模块；pom 实际 Java 21、版本 1.0.0、当前模块不同。

修复方向：

- 为笔试新增独立 README，避免牵动项目愿景文档。

## 实施计划

### Phase 1: 收敛主 runtime

目标：让代码里有一个可讲清楚的主入口。

任务：

- 新增 `AgentRuntime` 或 `MinimalAgentRuntime`，作为笔试 demo 的主调用入口。
- 主流程固定为：
  1. 接收 `sessionId + userInput`。
  2. 从 `SessionStore` 读取历史消息和任务状态。
  3. 构建 `ChatRequest`。
  4. 调用 `DefaultReActEngine`。
  5. 通过 `DefaultToolExecutionPipeline` 执行工具。
  6. 写回 session 消息、任务状态、trace。
  7. 返回最终答案和 trace。
- 标记 `ToolCallLoop` 为 legacy，不在 README 主流程使用。
- 修复 `delegate_to_agent` 工具定义刷新和 ProgressBus id 不一致问题。

验收：

- 一个单元测试覆盖“LLM 返回工具调用 -> 工具执行 -> LLM 返回最终答案”。
- 一个单元测试覆盖 max step 截断。

### Phase 2: 实现框架级 SessionStore

目标：多轮对话不依赖 web demo。

建议接口：

```java
public interface SessionStore {
    Session getOrCreate(String sessionId);
    void appendMessage(String sessionId, Message message);
    List<Message> loadMessages(String sessionId, int limit);
    void save(Session session);
}
```

最小实现：

- `InMemorySessionStore`，用 `ConcurrentHashMap<String, Session>`。
- 可选 `JsonFileSessionStore`，方便录屏展示重启后恢复。

接入点：

- `ContextBuildStage` 或新 `AgentRuntime` 开始时读取 session。
- `ReActEngine` 每产生 assistant/tool 消息后通过 `ReActMessageHook` 或 runtime 回写。
- 用户输入作为 user message 写入 session。

验收：

- 第一轮：“你好，我叫张三。” 第二轮：“我叫什么？” 能基于 session 历史回答。
- 测试不依赖 web 模块。

### Phase 3: 实现 TaskStore 和跨轮次继续执行

目标：满足题目最关键场景。

建议模型：

```java
public class AgentTaskState {
    private String taskId;
    private String sessionId;
    private String title;
    private String status; // CREATED, RUNNING, WAITING, DONE, FAILED
    private int progress;
    private String lastObservation;
    private List<String> events;
}
```

建议工具：

- `todo_create`: 创建任务并记录状态。
- `todo_progress`: 查询当前 session 下任务进度。
- `todo_advance`: 推进任务一步，模拟或真实更新状态。

最小跨轮次 demo：

- 第一轮用户：“帮我创建一个整理 README 的任务。”
  - Agent 调用 `todo_create`。
  - TaskStore 保存 `CREATED/RUNNING` 状态。
  - Agent 回复任务已创建和 taskId。
- 第二轮用户：“刚才那个任务进度怎么样？”
  - Runtime 从 SessionStore/TaskStore 召回当前 session 的 active task。
  - Agent 调用 `todo_progress` 或直接基于注入上下文回答。
  - 返回已有任务状态，而不是当作全新问题。

验收：

- 单元测试：同一个 session 第二轮能读到第一轮创建的 task。
- README 中明确说明 task memory 的召回时机：构建 ChatRequest 前，从 TaskStore 查询 session active task，作为 system/context message 注入。

### Phase 4: 提供 3 个稳定工具

目标：满足题目工具要求，并保证录屏稳定。

建议保留/新增：

- `calculator`: 只支持安全表达式，避免执行任意脚本。
- `search`: mock search，返回固定结构结果，避免网络不稳定。
- `todo`: 负责跨轮次任务创建/查询/推进。

可选：

- `read_docs`: 读取项目内指定白名单 docs 文件。
- `weather`: mock 天气。

验收：

- `/actuator/lyclaw-tools` 或 demo 命令能列出至少 3 个工具。
- 工具调用 trace 能显示 toolName、arguments、result、success、elapsedMs。

### Phase 5: 结构化 trace

目标：让“执行日志”不只散落在应用日志里。

建议模型：

```java
public class AgentRunTrace {
    private String runId;
    private String sessionId;
    private List<AgentStepTrace> steps;
}

public class AgentStepTrace {
    private int step;
    private String type; // MODEL_CALL, TOOL_CALL, FINAL_ANSWER, ERROR
    private String toolName;
    private String arguments;
    private String observation;
    private boolean success;
    private long elapsedMs;
}
```

接入点：

- `DefaultReActEngine` 每轮 model call 和 tool call 写 trace。
- `DefaultToolExecutionPipeline` 返回 `ToolExecutionResult` 时带 metadata。
- runtime 返回最终答案时附带 trace。

验收：

- 终端 demo 输出：
  - step 1 model requested calculator
  - step 2 calculator result
  - step 3 final answer

### Phase 6: 多 Agent 收敛

目标：让多 Agent 能讲清楚，但不拖垮最小可用交付。

建议交付形态：

- 主 runtime 是单 Agent ReAct。
- 多 Agent 是内置工具 `delegate_to_agent` 的扩展能力。
- 父 Agent 的工具列表中出现 `delegate_to_agent`。
- 子 Agent 复用同一个 `AgentRuntime/ReActEngine`，但使用子 sessionId。
- 子 session 使用层级 key：`parent/subagent/{agentId}/{uuid8}`。

暂缓：

- `SupervisorOrchestrator` 的 DAG 分解、投票、聚合。
- LLMRouter 自动选 Agent。
- 并行多 Agent 执行。

验收：

- 一个 demo：父 Agent 把“计算并总结”委派给 `math-agent`，子 Agent 调用 calculator 后返回 observation，父 Agent 汇总最终回答。

### Phase 7: 文档与录屏材料

新增：

- `README-agent-demo.md`
- `docs/ai-prompt-and-debug-log.md`

README 必须包含：

- 如何配置真实 LLM API key。
- 如何运行后端或终端 demo。
- 系统设计图，聚焦 `SessionStore -> AgentRuntime -> ReActEngine -> ToolPipeline -> Tools -> LLM`。
- memory 召回时机：
  - session history: 每轮构建请求前加载。
  - task memory: 每轮构建请求前按 sessionId 注入 active task summary。
  - tool observations: 每步执行后追加到 session messages。
- 最大步数与错误处理说明。
- 工具 trace 示例。
- 跨轮次继续执行示例。

录屏脚本：

1. 启动应用。
2. 发送计算问题，展示 calculator tool trace。
3. 发送 search 问题，展示 mock search tool trace。
4. 第一轮创建任务。
5. 第二轮追问进度，展示 session/task memory 生效。

## 推荐优先级

必须先做：

1. SessionStore。
2. TaskStore + todo 工具。
3. 主 runtime 收敛。
4. 结构化 trace。
5. 3 个稳定工具。
6. 笔试 README。

可以后做：

1. 多 Agent supervisor/DAG。
2. 持久化数据库。
3. 向量 memory。
4. Web demo 美化。
5. MCP/A2A。

## 最小验收清单

- `mvn compile` 通过。
- 单元测试覆盖：
  - 直接回答。
  - 工具调用循环。
  - 最大步数限制。
  - 工具异常转 observation。
  - 多轮 session。
  - 跨轮次 task 继续。
- 运行 demo 可以看到真实 LLM API 调用。
- 至少 3 个工具可被模型调用。
- trace 可在终端或 HTTP 响应中查看。
- README 不再依赖未实现的大平台模块叙述。

