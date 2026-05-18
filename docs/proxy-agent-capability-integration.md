# 代理模式能力集成分析

## 现状对比

| 能力 | 编排管线路径 | 代理路径 | 差距 |
|------|-------------|---------|------|
| 沙箱隔离 | RespondStage → ActionFeignClient → ToolSandbox | 直接调用 toolRegistry.execute()，无隔离 | **缺失** |
| 工具审批 | DefaultReActEngine.executeStream() → ApprovalStore | 未设置 approvalRequired | **缺失** |
| 安全审核 | SecurityCheckStage → SecurityManager + ContentFilter | 无安全检查 | **缺失** |
| 计划生成 | PlanExecutionStage → PlanFeignClient (远程) | 无计划 | **缺失** |
| 反思评估 | ReflectionStage → ReflectFeignClient (远程) | 无反思 | **缺失** |

## 核心原则

**代理模式是"轻量级入口"，不是管线替代品。** 代理的价值在于：
- 用户只需定义接口+注解，零配置即可工作
- 对于简单场景（内部工具、只读查询），不需要管线的完整重量

集成时遵循三个原则：
1. **安全类能力（沙箱、安全审核）→ 必须集成**，否则代理路径是安全漏洞
2. **审批类能力 → 流式模式下自动生效，阻塞模式下跳过**
3. **增强类能力（计划、反思）→ 可选用，默认关闭**

---

## 1. 沙箱隔离

### 现状

```
AgentInvocationHandler.buildToolExecutor()
    → toolRegistry.execute(ToolCall, null)    ← 无沙箱
    → toolRegistry.executeByName(...)
```

而管线路径是 `ActionExecutorImpl.executeTool() → toolSandbox.execute(tool, args, level)`。

### 集成方案

在 `AgentInvocationHandler.buildToolExecutor()` 中引入 `ToolSandbox`：

```
AgentInvocationHandler.buildToolExecutor()
    → ToolExecutor lambda
        → ToolSandbox.resolveLevel(toolName)     // 查配置或注解
        → ToolSandbox.execute(tool, args, level)  // 沙箱执行
        → 错误回退到 executeByName
```

具体改动：

1. `AgentInvocationHandler` 新增可选字段 `ToolSandbox toolSandbox`（可为 null）
2. `buildToolExecutor()` 内检查 `toolSandbox != null`：
   - 从 `ToolRegistry.get(toolName)` 获取 `Tool` 实例
   - 解析 JSON arguments 为 `Map<String, Object>`
   - 调用 `toolSandbox.execute(tool, args, level)`
   - 若 `toolSandbox` 为 null，退回当前行为（直接 ToolRegistry）
3. `AgentProxyFactory` 新增可选构造参数，`LyClawAgent.Builder` 新增 `.sandbox(toolSandbox)` 方法
4. `AgentProxyAutoConfiguration` 自动注入 `ToolSandbox` Bean（如果存在）

**结论：直接集成，可降级。** 约 30 行改动。

---

## 2. 工具审批

### 现状

审批在 `DefaultReActEngine` 的流式路径中实现：
- `RespondStage` 调用 `reActEngine.setApprovalRequired(toolNames)`
- `DefaultReActEngine.emitRoundToolCallEvents()` 对审批工具调用 `emitApprovalFlow()`
- `emitApprovalFlow()` 创建 `ApprovalStore` 条目，发射 `tool_approval` SSE 事件，阻塞等待前端响应

### 关键约束

**审批依赖 SSE 双向通信**：前端需要收到 `tool_approval` 事件并展示审批对话框，用户点击后通过 REST API 回调 `ApprovalStore.approve()`。

- `Flux<String>` 返回类型：流式 SSE → 审批可以工作
- `String` 返回类型：阻塞式 → 审批也会阻塞等待

### 集成方案

**流式代理（Flux 返回）——自动生效**：

`DefaultReActEngine.executeStream()` 已内置审批逻辑。只需在 `AgentInvocationHandler.buildChatRequest()` 中传入审批工具集合：

```java
// AgentInvocationHandler 新增字段
private final Set<String> approvalTools;

// buildChatRequest() 中
if (!approvalTools.isEmpty()) {
    reActEngine.setApprovalRequired(approvalTools);
}
```

**阻塞式代理（String 返回）——默认无审批，可配置开启**：

`DefaultReActEngine.execute()` 是非流式路径，不包含审批。可注入 `ApprovalStore` 到 `AgentInvocationHandler`，在 `ToolExecutor` lambda 中实现简化的审批等待：

```java
// 伪代码
if (approvalStore != null && approvalTools.contains(toolName)) {
    CompletableFuture<Boolean> future = approvalStore.create(toolCallId);
    Boolean approved = future.get(30, TimeUnit.SECONDS);
    if (!approved) return "Tool execution denied by user";
}
```

### 审批工具集合来源

`approvalTools` 的获取方式（优先级从高到低）：
1. `@Agent` 注解新增 `approvalTools()` 属性
2. `LyClawAgent.Builder.approvalTools(Set<String>)`
3. 全局配置 `lyclaw.agent.default-approval-tools`

**结论：流式自动生效，阻塞式可选用。** 约 40 行改动。

---

## 3. 安全审核

### 现状

`SecurityCheckStage` 做三件事：
1. `securityManager.approve(context, "EXECUTE_CHAT")` → 返回 `ApprovalResult`（含 `sandboxLevel`）
2. `contentFilter.filter(userMessage, context)` → 检测提示注入 / PII
3. 拒绝时终止管线

这些都在管线上下文中运行，有 `ChatContext` 和 `PipelineContext`。

### 集成方案

在 `AgentInvocationHandler.invoke()` 中，构建 `ChatRequest` 之前插入安全检查：

```
invoke() {
    // 1. 用户消息过滤（如果 ContentFilter 存在）
    if (contentFilter != null) {
        FilterResult result = contentFilter.filter(userMessage, chatContext);
        if (result.isBlocked()) throw new SecurityException(result.reason());
        userMessage = result.filteredContent();  // 用过滤后的内容
    }

    // 2. 权限校验（如果 SecurityManager 存在）
    if (securityManager != null) {
        ApprovalResult approval = securityManager.approve(chatContext, "EXECUTE_CHAT");
        if (!approval.approved()) throw new SecurityException(approval.reason());
        sandboxLevel = approval.sandboxLevel();  // 传递给下游 ToolSandbox
    }

    // 3. 继续正常流程...
}
```

具体改动：
1. `AgentInvocationHandler` 新增可选字段：`SecurityManager`、`ContentFilter`
2. `AgentProxyFactory` 新增可选参数
3. `AgentProxyAutoConfiguration` 自动注入（`@Autowired(required = false)`）

**无需 ChatContext？** `SecurityManager.approve(ChatContext, String)` 需要 `ChatContext`，但代理路径没有完整的 ChatContext。方案：
- 构造一个最小化的 `ChatContext`（sessionId + user message）
- 或者扩展 `SecurityManager` 接口新增一个轻量级 `approve(String sessionId, String userId, String action)` 方法

**结论：直接集成，需要最小化 ChatContext。** 约 25 行改动（不含 SecurityManager 接口扩展）。

---

## 4. 计划生成

### 现状

`PlanExecutionStage` 通过 Feign 调用远程 `PlanService`。输入是 `PlanRequest{userIntent}`，输出是 `TaskNode` 列表。

### 分析

**计划生成的适用场景**：复杂多步骤任务（"帮我分析这个数据集并生成报表"），需要分解为子任务。

**不适合的场景**：简单对话（"今天天气怎么样"），计划是多余开销。

计划生成本身需要 LLM 调用，会增加 2-5 秒延迟。

### 集成方案

**方案 A：代理层内嵌**（不推荐）
- 在 `AgentInvocationHandler` 中注入 `TaskPlanner`
- 每次 chat 调用前先生成计划，再按计划执行
- 问题：所有调用都变慢，大量简单场景不需要

**方案 B：代理层可选**（推荐）
- `@Agent` 注解新增 `planning()` 布尔属性，默认 `false`
- 开启后，`AgentInvocationHandler` 在调用 `reActEngine` 之前：
  1. 调用 `taskPlanner.plan(userIntent)` 获取 TaskNode 列表
  2. 将 TaskNode 注入到 system prompt 中（"请按以下步骤执行：1. ... 2. ..."）
  3. 或者跳过 ReAct 循环，直接用 `PlanExecutor` 顺序执行 TaskNode

**方案 C：保留在管线**（当前最优）
- 计划是管线独有的能力
- 代理模式专注于"单次 ReAct 循环"
- 用户需要多步骤规划时使用管线

**结论：保留在管线，不在代理层集成。** 计划是管线的核心差异化能力，代理模式的价值是"零配置快速调用"。

---

## 5. 反思评估

### 现状

`ReflectionStage` 在 ReAct 循环结束后，通过 Feign 调用远程 `ReflectService`。评估输出质量（准确性、完整性、安全性、用户体验），生成 `ReflectionReport`。

### 分析

反思评估和计划一样是**管线增强能力**。它需要：
- 完整的对话历史（tool call / tool result 序列）
- LLM 调用（评估质量）
- 所有工具执行结果

在代理路径中，ReAct 循环执行完成后，结果直接返回给调用者。反思评估本可以 fire-and-forget，但评估的结果（`ReflectionReport`）需要有地方消费——当前是存入 `PipelineContext` 并在 `MetricsStage` 中持久化。

### 集成方案

**不集成到代理层**，原因：
1. 反思是有成本的（额外 LLM 调用），不应该强加给所有代理调用
2. 反思结果需要消费（持久化、告警），代理层没有这个上下文
3. 和计划一样，反思是管线的差异化能力

**替代方案**：如果用户确实想在代理场景中获得质量反馈，可以通过以下方式实现：
- `AgentInvocationHandler` 在返回结果前做一个**轻量级本地校验**：
  - 空响应检测
  - 工具调用错误计数
  - 响应长度异常检测
  - 不需要远程调用或额外 LLM

**结论：保留在管线，代理层只做轻量级本地校验。** 零额外延迟。

---

## 汇总

```
                    ┌─────────────────────────────────────────────┐
                    │        AgentInvocationHandler.invoke()       │
                    │                                             │
  用户消息 ──────────┤  1. ContentFilter.filter()     ← 新增      │
                    │  2. SecurityManager.approve()   ← 新增      │
                    │  3. 构建 ChatRequest + ToolExecutor         │
                    │     ┌──────────────────────────┐            │
                    │     │ ToolExecutor lambda       │            │
                    │     │  → ToolSandbox.execute()  │ ← 新增     │
                    │     │  → ApprovalStore 检查     │ ← 新增     │
                    │     └──────────────────────────┘            │
                    │  4. ReActEngine.execute() / executeStream() │
                    │  5. 轻量级结果校验              ← 新增      │
                    │  6. 返回结果                                │
                    └─────────────────────────────────────────────┘

  计划生成 ─── 保留在管线 ❌
  反思评估 ─── 保留在管线 ❌
```

| 能力 | 集成到代理 | 方式 | 工作量 |
|------|-----------|------|--------|
| 沙箱隔离 | **是** | ToolExecutor 包装 ToolSandbox | 小（~30行） |
| 安全审核 | **是** | invoke() 前置 SecurityManager + ContentFilter | 小（~25行） |
| 工具审批 | **流式自动 / 阻塞可选** | 流式路径 DefaultReActEngine 已内置；阻塞式可选注入 ApprovalStore | 小（~40行） |
| 计划生成 | **否** | 管线差异化能力 | 0 |
| 反思评估 | **否** | 管线差异化能力（代理只做轻量校验） | 小（~10行） |

## 实施顺序

1. **安全审核** — 最高优先级，填补安全漏洞
2. **沙箱隔离** — 工具执行必须隔离
3. **工具审批** — 流式已自动，补阻塞式可选支持
4. **轻量级校验** — 低优先级，锦上添花
