# ReAct 事件打通 + Agent 自动注册 + 异步编排

## 现有资产（已经有但没连起来的）

```
ReAct 引擎已产生的 SSE 事件：
  thinking           → 推理过程
  message            → 文本内容
  tool_call_executing → 工具开始执行
  tool_call_done     → 工具执行完成
  tool_approval      → 需要审批
  status             → 状态更新

SseEventFactory 已封装的方法：
  message(), thinking(), status(), toolCall(),
  toolCallExecuting(), toolCallDone(), toolApproval(),
  subagentProgress(), pipelineStatus()

AgentExecutionEvent 已有类型：
  STARTED, STAGE, TOOL_CALL, SUBAGENT_SPAWN,
  PROGRESS, COMPLETED, FAILED
```

## 问题

```
执行流程：
  LLMAgentInstance.send()
    → reActEngine.execute()  ← 阻塞方法，没有事件回调
    → 返回 String
    → 只发了 STARTED / COMPLETED 两个事件

ReAct 引擎内部 30 多轮的推理、工具调用全部黑盒
```

## 修复方案

### 1. ReActEngine 增加事件回调

```java
public interface ReActEngine {
    // 现有方法（不变）
    String execute(ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor);

    // 新增：带事件回调的阻塞执行
    default String execute(ChatFacade chatFacade, ChatRequest request,
                           ToolExecutor toolExecutor,
                           java.util.function.Consumer<AgentExecutionEvent> eventCallback) {
        return execute(chatFacade, request, toolExecutor);
    }
}
```

### 2. DefaultReActEngine 在 execute() 中回调

```java
// 在每轮推理前：
if (eventCallback != null) {
    eventCallback.accept(AgentExecutionEvent.stage(agentId, taskId,
        "ReAct 第 " + round + " 轮推理", round * 10));
}

// 工具调用前：
if (eventCallback != null) {
    eventCallback.accept(AgentExecutionEvent.toolCall(agentId, taskId,
        toolName, "调用 " + toolName));
}
```

### 3. LLMAgentInstance 传回调

```java
public CompletableFuture<AgentMessage> send(AgentMessage message) {
    return Mono.fromCallable(() -> {
        // 传回调给 ReAct 引擎
        ToolExecutor toolExecutor = buildMeshToolExecutor(message);
        String result = reActEngine.execute(chatFacade, request, toolExecutor,
            event -> publishEvent(event));
        return AgentMessage.responseTo(message, result);
    }).subscribeOn(Schedulers.boundedElastic()).toFuture();
}
```

### 4. Agent 自动注册（启动时）

```
@Agent 注解扫描 → 自动注册到 AgentMesh
Tool 自动包装 → 注册为 ToolAgent
不再需要用户 curl 手动注册
```

### 5. 编排改为异步

```
当前：POST /orchestrate → 阻塞 30 秒 → 返回结果
改为：POST /orchestrate → 立刻返回 taskId → SSE 流推送进度
    GET  /orchestrate/{taskId}/result → 查结果
    GET  /orchestrate/{taskId}/events → SSE 流
```

## 实现顺序

| Step | 内容 |
|------|------|
| 1 | ReActEngine 接口增加 eventCallback 参数 |
| 2 | DefaultReActEngine.execute() 每轮推理/工具调用时回调 |
| 3 | LLMAgentInstance 传回调 → ReAct 内部事件接入 SSE |
| 4 | Agent 自动注册（扫描 @Agent + Tool） |
| 5 | 编排端点改为异步（返回 taskId） |
