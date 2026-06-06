# Agent Mesh 完整架构设计方案

## 核心原则

**Agent 的创建与销毁由 AI 自己控制，不是用户手动注册的。**

```
用户 (@Agent / UI) → 定义的 Agent 是"模板"
AI 运行时 → 基于模板动态创建实例 → 派发任务 → 完成后销毁
```

---

## 5 个问题的统一方案

### 问题 1：ReAct 事件推送

```
DefaultReActEngine.execute(chatFacade, request, toolExecutor, eventCallback)
                                                              ↑
                        每轮推理  → AgentExecutionEvent.STAGE (progress)
                        每个工具调用 → AgentExecutionEvent.TOOL_CALL
                        子Agent委托 → AgentExecutionEvent.SUBAGENT_SPAWN
                        完成      → AgentExecutionEvent.COMPLETED

eventCallback → publishEvent() → AgentExecutionStore → SSE /api/mesh/events
```

### 问题 2：Agent 三种来源

```
来源                    创建时机                 生命周期
─────────────────────────────────────────────────────────
@Agent 注解            启动时自动注册           持久
UI添加                用户操作                持久
AI 动态创建（核心）    运行时其他Agent spawn    临时，用完销毁
```

### 问题 3：AI 运行时动态创建 Agent

```
Agent A 执行 ReAct 循环
  → LLM 调用 delegate_to_agent("code-reviewer", "审查这段代码")
  → MeshToolExecutor 发现 code-reviewer 不存在（临时创建）
  → 创建 AgentSpec { agentId, parentId, ephemeral: true, ttlMs: 300000 }
  → mesh.register(spec)  → 注册到注册表
  → mesh.send({to:"code-reviewer", payload:"审查..."})
  → code-reviewer 执行 → 返回结果
  → mesh.unregister("code-reviewer")  → 自动清理
  → 结果返回给 Agent A
```

### 问题 4：Agent 自动销毁

```
AgentSpec 新增字段：
  boolean ephemeral = false;   // 是否临时 Agent
  long ttlMs = 0;              // 生存时间，超时自动销毁
  String parentId;             // 父 Agent ID

DefaultAgentMesh 行为：
  register() 时如果是 ephemeral → 记录销毁计划
  unregister() → 清理 + 发布 DESTROYED 事件
  超时未完成 → 强制销毁
```

### 问题 5：异步编排

```
POST /api/mesh/orchestrate/async
  → 返回 {"taskId":"task-xxx", "status":"pending"}
  → 后台执行
  → SSE 实时推送每个 Agent 的 STARTED→STAGE→TOOL_CALL→COMPLETED
  → GET /api/mesh/orchestrate/result/{taskId} 查结果
```

---

## 完整执行流程

```
用户: "帮我审查这个 PR"
  ↓
ChatController → 找到 chat Agent
  ↓
chat Agent (LLMAgentInstance.send)
  → ReAct 引擎 start
    → 第1轮: LLM 决定调用 delegate_to_agent("code-reviewer")
    → mesh.send({to:"code-reviewer"})
      → code-reviewer 不存在 → 自动创建 ephemeral Agent
      → code-reviewer 执行 ReAct 循环
        → 第1轮: 调用 github-tool (获取 diff)
        → 第2轮: 调用 linter-tool (运行 ESLint)
        → 第3轮: 生成审查报告
      → 结果返回 → unregister(code-reviewer)
    → 第2轮: LLM 综合结果 → 输出最终审查报告
  → ReAct 引擎完成
  → 返回给用户
```

## Agent 生命周期

```
动态 Agent:
  spawnSubagent()
    → PENDING (spec 创建)
    → STARTING (mesh.register)
    → ACTIVE (接收消息)
    → PROGRESS (执行 ReAct 循环)
      ├→ TOOL_CALL (每步工具)
      └→ SUBAGENT_SPAWN (子 Agent)
    → COMPLETED (返回结果)
    → DESTROYED (mesh.unregister)

持久 Agent:
  PENDING → STARTING → ACTIVE → (等待) → PROGRESS → ACTIVE → ...
  不会被自动销毁
```

## 实现顺序

| Step | 内容 | 影响文件 |
|------|------|---------|
| 1 | AgentSpec 增加 ephemeral/ttlMs/parentId | AgentSpec.java |
| 2 | DefaultAgentMesh 支持自动销毁 | DefaultAgentMesh.java |
| 3 | MeshToolExecutor 动态创建子 Agent | LLMAgentInstance.java |
| 4 | ReAct 事件回调（已完成） | DefaultReActEngine.java |
| 5 | 异步编排端点 | MeshController.java |
| 6 | 启动自动注册（已完成） | MeshAutoConfiguration.java |
