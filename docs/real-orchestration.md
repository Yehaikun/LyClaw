# 真正的多 Agent 编排设计

## 当前错误实现

```
CHAIN = Java for循环:
  for (agent in agents) {
      send(agent, task)  ← LLM用不上，跟调API没区别
      wait()
  }
```

## Claude Code 的做法

```
Claude Code 没有"编排引擎"。
它只有一个 Agent，通过 tool call 完成一切：

LLM 收到任务
  → 第1轮: 调用 bash(ls) 查看文件结构
  → 第2轮: 调用 read_file(package.json) 了解项目
  → 第3轮: 调用 edit_file(src/App.vue, "...") 写代码
  → 第4轮: 调用 bash(npm run dev) 启动

LLM 自己决定调什么工具、以什么顺序。
没有 Java 代码替它决定 A→B→C。
```

## OpenClaw 的做法

```
OpenClaw 的核心也是 tool call。
Agent 之间通过 ToolProvider 互相发现：

Agent A 收到任务
  → 第1轮: LLM 决定 "我需要让 search agent 帮忙"
  → 调用 delegate_to_agent("search", "查找资料")
  → search agent 返回结果
  → 第2轮: LLM 综合结果 → 输出
```

## 共同点

**两个系统都没有编排引擎。LLM 自己就是编排器。**

---

## 正确的执行流程

```
用户: "开发OA系统"
  ↓
发送给 architect Agent
  ↓
architect 启动 ReAct 循环（这才是真正的编排循环！）:
  │
  ├─ 第1轮: "先设计架构"
  │   → LLM 生成架构文档
  │   → 写入文件（调用 write_file 工具）
  │
  ├─ 第2轮: "前端可以开始了"
  │   → LLM 决定 delegate_to_agent("frontend", "根据架构文档写前端代码")
  │   → mesh.send({to:"frontend", payload:"架构文档..."})
  │     │
  │     └── frontend 启动自己的 ReAct 循环:
  │           ├─ 第1轮: "需要确认用户表结构"
  │           │   → delegate_to_agent("architect", "用户表有哪些字段？")
  │           │   → architect 快速回复
  │           ├─ 第2轮: "写 UserList.vue"
  │           │   → 调用 write_file 写入
  │           ├─ 第3轮: "写 UserForm.vue"
  │           └─ 返回结果: "前端代码已完成"
  │
  ├─ 第3轮: "后端可以开始了"
  │   → delegate_to_agent("backend", "根据架构文档写后端代码")
  │   → backend 执行自己的 ReAct
  │   → 返回结果
  │
  ├─ 第4轮: "审查代码"
  │   → delegate_to_agent("reviewer", "审查前端和后端代码")
  │   → reviewer 执行
  │   → 返回审查报告
  │
  └─ 第5轮: "综合所有结果，输出最终报告"
      → LLM 综合
      → 返回给用户
```

## 改动点

### 1. 删除 CHAIN/SUPERVISOR 模式的硬编码 for 循环

CHAIN 和 SUPERVISOR 合并成一个：**给第一个 Agent 发任务，它在 ReAct 里自己决定 delegate**

```java
// 改前：executeChain 是个 for 循环
// 改后：直接 send 给第一个 agent，让 LLM 自己编排
AgentMessage response = mesh.send(AgentMessage.builder()
    .to(agentIds.get(0))        // 只发给第一个
    .payload(task)
    .build());
```

### 2. 每个 LLM Agent 自动注入 delegate_to_agent 工具

```java
// LLMAgentInstance.buildChatRequest() 中自动追加
delegateToolDef = ToolDefinition.builder()
    .name("delegate_to_agent")
    .description("委托子任务给其他 Agent。当你需要专业分工时使用。\n可用 Agent: " + availableAgents)
    .parameters(...)
    .build();
```

### 3. Agent 可以互相通信（双向）

frontend 在写代码时可以反过来问 architect：
```
delegate_to_agent("architect", "用户状态枚举有哪些值？")
```

这就是 `buildMeshToolExecutor` 已经做的事——mesh.send() 路由到目标 Agent。

### 4. 保留的模式

```
SINGLE:  给一个 Agent 发任务，它自己执行 ReAct（里面可以 delegate）
CHAIN:   本质同 SINGLE，只是确保发给第一个 Agent
FAN_OUT: 并行派发给多个 Agent，各自独立执行
DEBATE:  多个 Agent 讨论，需要互相看到对方的观点
```

## 实现计划

| Step | 内容 |
|------|------|
| 1 | 删除 executeChain 的 for 循环，改为只发第一个 Agent |
| 2 | LLMAgentInstance.buildChatRequest 自动注入 delegate_to_agent 工具定义 |
| 3 | 工具描述中包含可用 Agent 列表 |
| 4 | 测试：architect 收到任务后在 ReAct 中 delegate 给 frontend |
