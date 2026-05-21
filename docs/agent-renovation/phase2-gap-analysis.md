# Phase 2 代码与设计文档差距分析

> 对比基准：`06-renovation-phase2-subagent-models.md`  
> 对比日期：2026-05-21  
> 对比范围：子代理委派系统 (2.1) + 模型管理增强 (2.2)

---

## 一、总体评估

当前代码已经实现了 Phase 2 文档中约 **70%** 的类，但存在 **3 个关键性架构差距**，导致子代理无法复用父代理的完整管线能力。

| 子系统 | 文档要求 | 当前状态 | 差距等级 |
|--------|---------|---------|----------|
| SubagentConfig | 完整 | **已实现**，merge 逻辑比文档更完善 | 无 |
| SubagentResult | 完整 | **已实现**，formatAsObservation 比文档更丰富 | 无 |
| SubagentHook | 完整 | **已实现** | 无 |
| DelegateToAgentToolProvider | 完整 | **已实现** | 无 |
| RunMetadata | 完整 | **已实现**，字段比文档更多 | 无 |
| SubagentSessionManager | 完整 | **部分实现**，使用 Object 代替 SessionStore | 低 |
| ModelCatalog/ModelCatalogEntry | 完整 | **已实现** | 无 |
| ModelCompatConfig | 完整 | **已实现** | 无 |
| AgentModelConfig | 完整 | **已实现** | 无 |
| ModelResolutionService | 完整 | **已实现**，比文档更完善 | 无 |
| ModelRef | record | **已实现**（class 形式） | 无 |
| **SubagentSpawner 核心执行路径** | 走 AgentInvocationHandler + 管线 | **直接调 reActEngine.execute()** | **严重** |
| **AgentInvocationHandler.executeBlocking** | 公开方法供子代理调用 | **不存在**（只有 private 方法） | **严重** |
| **AgentRegistry 集成** | 注入 AgentRegistry 解析子代理配置 | **未注入**，用硬编码 systemPrompt | **严重** |
| AgentInvocationHandler 使用 ModelResolutionService | 通过 ModelResolutionService 解析模型 | **未集成** | 中 |
| SubagentSpawner 集成 SubagentSessionManager | 调用 scheduleSessionArchive | **未集成** | 中 |

---

## 二、关键架构差距（需优先修复）

### 差距 1：子代理不经过完整管线（最严重）

**文档设计**（文档 2.1.2 第 456-466 行）：

```java
// 文档：为子代理创建 AgentInvocationHandler，走完整管线
AgentInvocationHandler childHandler = new AgentInvocationHandler(
        chatFacade, reActEngine, toolRegistry,
        childAgentConfig.getDescription(),  // 系统提示来自 @Agent
        childAgentConfig.getModel(),
        childAgentConfig.getProvider(),
        defaultHooks,
        defaultStages
);

String result = childHandler.executeBlocking(childCtx);
```

**当前代码**（SubagentSpawner.java 第 435 行）：

```java
// 当前：直接调 ReActEngine，跳过所有管线
String output = reActEngine.execute(chatFacade, childRequest, toolExecutor);
```

**损失的能力**：
- 无管线阶段：子代理没有 ContextBuild → SecurityCheck → PlanExecution → Reflection → Metrics
- 无 Plan-Execute-Reflect 反思重试闭环（评分 < 0.6 自动重试）
- 无 beforeRequest/afterResult 钩子链
- 无 `@SystemMessage` / `@UserMessage` 注解解析
- 无 thinking/reasoning/verbose 级别解析
- 无 AgentConfigResolver 三层配置合并

**修复方案**：

1. 在 `AgentInvocationHandler` 中添加公开方法 `executeBlocking(AgentContext ctx)`
2. 在 `SubagentSpawner.runSubagent()` 中创建 `AgentInvocationHandler` 并调用 `executeBlocking()`
3. 通过 `AgentConfigResolver` 解析子代理的完整配置

---

### 差距 2：AgentInvocationHandler 缺少公开的 executeBlocking 方法

**文档要求**：`AgentInvocationHandler` 需要提供一个接受预构建 `AgentContext` 的公开 `executeBlocking` 方法。

**当前状态**：`AgentInvocationHandler` 只有两个私有的阻塞执行方法：
- `executeStagesBlocking(AgentContext ctx)` — private，使用实例字段（hooks, stages 等）
- `simpleConcatBlocking(AgentContext ctx)` — private

这两方法都不能被外部（SubagentSpawner）直接调用。

**修复方案**：

```java
// 在 AgentInvocationHandler 中添加：
public String executeBlocking(AgentContext ctx) {
    // 1. 设置 thinking/reasoning/verbose 级别（从 ctx.runMetadata 或 resolvedConfig）
    // 2. 派发 beforeAgentRun 钩子
    // 3. 派发 beforeRequest 钩子
    // 4. 执行管线阶段（调用已有的 executeStagesBlocking）
    // 5. 派发 afterResult 钩子
    // 6. 派发 agentEnd 钩子
    // 7. 返回最终响应文本
}
```

具体步骤：
1. 将 `invoke()` 方法中第 153-270 行的"Phase 2 解析 + 钩子派发 + 管线执行"逻辑提取为独立方法
2. 让 `invoke()` 和新的 `executeBlocking(AgentContext)` 都调用提取后的方法
3. `executeBlocking` 接受预构建的 `AgentContext`（包含已设置好的 ChatRequest 等），跳过方法注解解析步骤

---

### 差距 3：SubagentSpawner 未注入 AgentRegistry，子代理配置解析不完整

**文档设计**（文档 2.1.2 第 316-317 行）：

```java
// 文档：SubagentSpawner 注入 AgentRegistry
private final AgentRegistry agentRegistry;

public SubagentSpawner(ChatFacade chatFacade, ReActEngine reActEngine,
                       ToolRegistry toolRegistry, AgentRegistry agentRegistry,
                       AgentConfigResolver agentConfigResolver, ...)
```

**文档的 buildChildContext**（第 505-546 行）：

```java
// 从 AgentConfigResolver 解析子代理的 AgentConfig
AgentConfig childAgentConfig = agentConfigResolver.resolve(childAgentId);

// 系统提示来自子代理的 @Agent 注解描述
AgentContext childCtx = AgentContext.sessionScoped(
        sessionKey, task,
        childAgentConfig.getDescription(),  // ← 来自 @Agent 注解
        toolRegistry, ...);
```

**当前代码的 buildChildContext**（第 493-573 行）：

```java
// 硬编码系统提示
String systemPrompt = "You are a subagent: " + targetAgentId;
// 没有调用 agentConfigResolver.resolve(targetAgentId)
// 没有获取子代理的 @Agent 注解配置
```

**修复方案**：

1. `SubagentSpawner` 构造函数添加 `AgentRegistry` 参数（或直接使用 `AgentConfigResolver`，当前已有）
2. `buildChildContext` 中调用 `configResolver.resolve(targetAgentId)` 或通过 `AgentRegistry.lookup(targetAgentId)` 获取子代理配置
3. 系统提示从子代理的 `@Agent.description` 或 `@SystemMessage` 取值，而非硬编码
4. 子代理的模型、provider、thinking 级别从解析后的配置获取

---

## 三、中等差距

### 差距 4：ModelResolutionService 未被 AgentInvocationHandler 使用

**当前状态**：`ModelResolutionService` 已完整实现（553 行），功能比文档更完善，但 `AgentInvocationHandler.invoke()` 中仍然直接从 `ResolvedAgentConfig` 取值设置到 `RunMetadata`，没有经过 `ModelResolutionService` 的统一解析。

**文档设计**：`ModelResolutionService.resolveEffectiveModel(AgentContext)` 作为所有模型解析的唯一入口，统一处理：
- RunMetadata 覆盖
- ChatRequest.model 解析
- AgentConfig 扩展
- 全局默认
- 别名解析
- 回退链

**修复方案**：在 `AgentInvocationHandler.invoke()` 中，将当前的模型解析逻辑替换为调用 `modelResolutionService.resolveEffectiveModel(ctx)`。

### 差距 5：SubagentSessionManager 未集成到 SubagentSpawner

**当前状态**：
- `SubagentSessionManager` 已实现（243 行），但构造函数使用 `Object` 类型（因为 `SessionStore` 类尚不存在）
- `SubagentSpawner` 没有注入 `SubagentSessionManager`，也没有调用 `scheduleSessionArchive()`

**文档设计**（文档 2.1.2 第 492 行）：

```java
// 子代理完成后调度会话归档
scheduleSessionArchive(childSessionKey, parentConfig.getArchiveAfterMinutes());
```

**修复方案**：
1. `SubagentSpawner` 添加 `SubagentSessionManager` 字段
2. 在 `runSubagent()` 的 `doFinally` 中调用 `sessionManager.archiveSession(sessionKey, archiveAfterMinutes)`
3. 当 `SessionStore` 类就绪后，将 `SubagentSessionManager` 的 `Object sessionStore` 改为 `SessionStore`

### 差距 6：delegationMode "off" 未处理

**文档设计**：`delegationMode` 有三个值：`"suggest"`、`"prefer"`、`"off"`。`"off"` 表示完全禁用委派。

**当前状态**：`SubagentSpawner.spawnSubagent()` 没有检查 `delegationMode == "off"` 的情况。虽然 `DelegateToAgentToolProvider.isEnabled()` 可以禁用工具注册，但如果 spawnSubagent 被其他路径调用，没有防护。

**修复方案**：在 `spawnSubagent()` 开头添加检查：
```java
if ("off".equalsIgnoreCase(config.getDelegationMode())) {
    return Mono.just(SubagentResult.error("Delegation is disabled for this agent"));
}
```

---

## 四、细节差距

### 差距 7：SubagentConfig.allowAgents 空列表语义

**文档**：`allowAgents` 空列表 = 委派完全禁用  
**当前**：空列表被跳过，不检查白名单（等于允许所有）

`SubagentSpawner.spawnSubagent()` 第 165-176 行：
```java
// 当前：空列表直接跳过检查
if (allowAgents != null && !allowAgents.isEmpty()) {
    // 白名单检查...
}
```

应改为：
```java
// 文档：空列表 = 禁用
if (allowAgents == null || allowAgents.isEmpty()) {
    return Mono.just(SubagentResult.error("Delegation is disabled"));
}
```

### 差距 8：文档 SubagentResult.formatAsObservation 格式差异

- **文档**：简洁格式 `[子代理结果] agent=xxx status=成功...`
- **当前**：Markdown 格式 `### Subagent Result: \`xxx\`...`

当前格式更丰富、更结构化，建议保留当前实现，不修改。

---

## 五、完全对齐项（无需修改）

| 组件 | 状态 | 备注 |
|------|------|------|
| `SubagentConfig` | 完全对齐 | merge 逻辑比文档更智能（isNotDefault 辅助方法） |
| `SubagentResult` | 完全对齐 | formatAsObservation 比文档版本更好 |
| `SubagentHook` | 完全对齐 | safelyExecute 包装是额外增强 |
| `DelegateToAgentToolProvider` | 完全对齐 | 参数解析、上下文解析、阻塞执行均已实现 |
| `RunMetadata` | 完全对齐 | 额外包含 verboseLevel, reasoningLevel, imageModel |
| `AgentContext` 子代理支持 | 完全对齐 | addActiveSubagent/removeActiveSubagent、RunMetadata 集成完整 |
| `ModelCatalog` | 完全对齐 | 线程安全，含别名解析 |
| `ModelCatalogEntry` | 完全对齐 | |
| `ModelCompatConfig` | 完全对齐 | openAiDefaults/anthropicDefaults 工厂方法完整 |
| `ModelInputType` | 完全对齐 | |
| `AgentModelConfig` | 完全对齐 | resolveChatModel 继承链正确 |
| `ModelResolutionService` | 超过文档 | 同时支持 typed RunMetadata 和 legacy map-based |
| `ModelRef` | 完全对齐 | parse/toCanonicalId 完整 |

---

## 六、按优先级排序的修复任务

### P0 — 阻塞性子代理能力缺失

| # | 任务 | 涉及文件 | 工作量 |
|---|------|---------|--------|
| 1 | `AgentInvocationHandler` 添加公开 `executeBlocking(AgentContext)` 方法 | `AgentInvocationHandler.java` | 2-3h |
| 2 | `SubagentSpawner.runSubagent()` 改为创建 `AgentInvocationHandler` + 调用 `executeBlocking()` | `SubagentSpawner.java` | 2-3h |
| 3 | `SubagentSpawner.buildChildContext()` 通过 `AgentConfigResolver` 解析子代理配置，使用 `@Agent` 注解的 description 作系统提示 | `SubagentSpawner.java` | 1-2h |

### P1 — 集成连线

| # | 任务 | 涉及文件 | 工作量 |
|---|------|---------|--------|
| 4 | `AgentInvocationHandler.invoke()` 集成 `ModelResolutionService` 做统一模型解析 | `AgentInvocationHandler.java` | 1-2h |
| 5 | `SubagentSpawner` 集成 `SubagentSessionManager`，完成后调度归档 | `SubagentSpawner.java` | 1h |
| 6 | `SubagentSpawner` 添加 `AgentRegistry` 依赖（或复用 `AgentConfigResolver`） | `SubagentSpawner.java` | 1h |

### P2 — 防御性修复

| # | 任务 | 涉及文件 | 工作量 |
|---|------|---------|--------|
| 7 | `SubagentConfig.allowAgents` 空列表 = 禁用委派 | `SubagentSpawner.java` | 0.5h |
| 8 | `SubagentSpawner.spawnSubagent()` 添加 `delegationMode="off"` 检查 | `SubagentSpawner.java` | 0.5h |

---

## 七、修复后的子代理执行流程

修复后，子代理的执行路径将从"裸 ReAct 循环"变为"完整代理管线"：

```
父代理 LLM 调用 delegate_to_agent 工具
  │
  ▼
DelegateToAgentToolProvider.execute()
  │
  ▼
SubagentSpawner.spawnSubagent()
  ├─ 1. 解析 SubagentConfig（白名单/深度/并发限制校验）
  ├─ 2. 获取并发信号量
  ├─ 3. 通过 AgentConfigResolver 解析子代理 @Agent 配置 ★新增★
  ├─ 4. buildChildContext() ★使用解析后的配置★
  │     ├─ 系统提示 = @Agent.description（而非硬编码）
  │     ├─ 模型/提供商 = 子代理配置的模型
  │     └─ thinking 级别 = 从配置解析
  ├─ 5. 派发 subagentSpawning 钩子
  ├─ 6. 创建 AgentInvocationHandler ★新增★
  ├─ 7. childHandler.executeBlocking(childCtx) ★新增★
  │     ├─ 解析 thinking/reasoning/verbose 级别
  │     ├─ 派发 beforeAgentRun 钩子
  │     ├─ 派发 beforeRequest 钩子（按 order 升序）
  │     ├─ 执行管线阶段 ★子代理首次获得完整管线★
  │     │   ├─ ContextBuildStage
  │     │   ├─ SecurityCheckStage
  │     │   ├─ PlanExecutionStage
  │     │   ├─ RespondStage (ReAct 循环)
  │     │   ├─ ReflectionStage (质量评分 + 不达标重试)
  │     │   └─ MetricsStage
  │     ├─ 派发 afterResult 钩子（按 order 降序）
  │     └─ 派发 agentEnd 钩子
  ├─ 8. 构建 SubagentResult
  ├─ 9. 派发 subagentSpawned / subagentEnded 钩子
  └─ 10. 释放信号量 + 调度会话归档

对比当前执行路径（差距 1-3 修复前）：
  ├─ 3. 跳过（无配置解析）
  ├─ 4. buildChildContext() ★硬编码 systemPrompt★
  ├─ 5. 派发 subagentSpawning 钩子
  ├─ 6. 跳过 ★直接调 reActEngine.execute()★
  └─ ...（无管线、无钩子链、无反思重试）
```

---

## 八、P0 修复的具体代码改动

### 8.1 AgentInvocationHandler 新增 executeBlocking 方法

在 `AgentInvocationHandler.java` 中添加以下公开方法（约 50 行）：

```java
/**
 * Execute the full agent pipeline in blocking mode for a pre-built AgentContext.
 * Used by SubagentSpawner to run child agents through the complete pipeline.
 *
 * @param ctx a fully prepared AgentContext with ChatRequest, tools, etc. already set
 * @return the final response text from the agent
 */
public String executeBlocking(AgentContext ctx) {
    // 1. Resolve thinking/reasoning/verbose from context or resolvedConfig
    resolveLevels(ctx);

    // 2. Set resolved model/provider on RunMetadata
    if (resolvedConfig != null) {
        if (resolvedConfig.getModel() != null && !resolvedConfig.getModel().isEmpty()) {
            ctx.getRunMetadata().setResolvedModel(resolvedConfig.getModel());
        }
        if (resolvedConfig.getProvider() != null && !resolvedConfig.getProvider().isEmpty()) {
            ctx.getRunMetadata().setResolvedProvider(resolvedConfig.getProvider());
        }
    }

    // 3. Dispatch beforeAgentRun
    if (hookRegistry != null) {
        hookRegistry.dispatchBeforeAgentRun(ctx);
    }

    // 4. Dispatch beforeRequest hooks (ascending order)
    dispatchBeforeRequest(ctx);

    // 5. Run pipeline stages with reflection retry loop
    String result;
    try {
        result = executeStagesBlocking(ctx);
    } catch (Exception e) {
        log.error("Subagent pipeline execution failed: {}", e.getMessage(), e);
        result = "Error: " + e.getMessage();
    }

    // 6. Dispatch afterResult hooks (descending order)
    dispatchAfterResult(ctx, result);

    // 7. Dispatch agentEnd
    if (hookRegistry != null) {
        hookRegistry.dispatchAgentEnd(ctx);
    }

    return result;
}
```

需要从 `invoke()` 中提取的辅助方法：
- `resolveLevels(AgentContext ctx)` — 当前在 invoke() 第 153-179 行
- `dispatchBeforeRequest(AgentContext ctx)` — 当前在 invoke() 第 219-245 行
- `dispatchAfterResult(AgentContext ctx, String result)` — 当前在 invoke() 第 269-280 行

### 8.2 SubagentSpawner.runSubagent 改为走 AgentInvocationHandler

修改 `SubagentSpawner.java` 的 `runSubagent()` 方法（第 403-452 行）：

```java
private Mono<SubagentResult> runSubagent(String targetAgentId, String task,
                                          String sessionKey, SubagentConfig config,
                                          AgentContext parentCtx) {
    long startTime = System.currentTimeMillis();

    return Mono.fromCallable(() -> {
        // Step 1: Resolve child agent config via AgentConfigResolver
        lyjew.com.lyclaw.annotation.Agent childAnnotation = null;
        // Try to find the @Agent annotation for the target agent
        // (via AgentRegistry or annotation scanning)
        ResolvedAgentConfig childResolvedConfig = configResolver != null
                ? configResolver.resolve(targetAgentId)  // by agent ID
                : null;

        // Step 2: Build isolated child context using resolved config
        AgentContext childCtx = buildChildContext(
                targetAgentId, task, sessionKey, config, parentCtx, childResolvedConfig);

        // Step 3: Dispatch subagentSpawning hooks
        dispatchSubagentSpawning(childCtx);

        // Step 4: Create AgentInvocationHandler for the child
        String systemPrompt = childResolvedConfig != null
                && childResolvedConfig.getDescription() != null
                ? childResolvedConfig.getDescription()
                : "You are a subagent: " + targetAgentId;

        String childModel = childResolvedConfig != null
                ? childResolvedConfig.getModel() : null;
        String childProvider = childResolvedConfig != null
                ? childResolvedConfig.getProvider() : null;

        AgentInvocationHandler childHandler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                systemPrompt, childModel, childProvider,
                defaultHooks, defaultStages, childResolvedConfig);

        // Step 5: Execute via the handler (full pipeline)
        String output = childHandler.executeBlocking(childCtx);

        long elapsed = System.currentTimeMillis() - startTime;

        // Step 6: Build result
        SubagentResult result = SubagentResult.success(
                sessionKey, targetAgentId, output, elapsed,
                childCtx.getSuccessCount().get(), childCtx.getFailCount().get());

        // Step 7: Dispatch post-execution hooks
        dispatchSubagentSpawned(childCtx, result);
        dispatchSubagentEnded(childCtx, result);

        return result;
    }).subscribeOn(Schedulers.boundedElastic());
}
```

### 8.3 AgentConfigResolver 添加按 agentId 解析的方法

当前 `AgentConfigResolver.resolve(Agent ann)` 接受注解对象。需要添加按 agentId 字符串解析的方法：

```java
// 在 AgentConfigResolver 中添加：
public ResolvedAgentConfig resolve(String agentId) {
    // 通过 AgentRegistry 查找 @Agent 注解
    // 或通过 Spring 上下文扫描所有 @Agent bean
    // 如果找不到，返回基于 agentId 构造的默认配置
    return ResolvedAgentConfig.builder()
            .agentId(agentId)
            .agentName(agentId)
            .description("You are a subagent: " + agentId)
            .build();
}
```

---

## 九、测试验证清单

修复完成后，需验证以下场景：

- [ ] 父代理调用 `delegate_to_agent("code-reviewer", "review PR #342")`，子代理走完整管线
- [ ] 子代理的 `@Agent.description` 正确注入为 systemPrompt
- [ ] 子代理的模型配置（model/provider）从 `@Agent` 注解正确解析
- [ ] 子代理的管线阶段全部执行（日志中可见 ContextBuild → SecurityCheck → PlanExecution → Respond → Reflection → Metrics）
- [ ] 子代理的 ReflectionStage 评分 < 0.6 时触发重试（最多 2 次）
- [ ] 子代理的 beforeRequest/afterResult 钩子正确执行
- [ ] 嵌套子代理（孙代理）正确传递深度信息
- [ ] 子代理超时后正确返回 timeout 结果
- [ ] 白名单为空时正确拒绝委派
- [ ] delegationMode="off" 时正确拒绝委派
- [ ] 并发信号量正确释放（即使子代理异常）
