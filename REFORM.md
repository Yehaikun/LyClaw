# LyClaw 框架重构方案

## 对照笔试题：当前框架的差距

### ✅ 已满足
| 要求 | 当前状态 |
|------|---------|
| 接收用户输入 → 判断回答还是调工具 → 执行工具 → 继续 | ReActEngine.execute() 已实现 |
| 至少 3 个工具 | calculator / search / command 等注册为 ToolAgent |
| 最大步数限制 | maxToolRounds（默认 100） |
| 异常处理 | try-catch 每轮 LLM 调用 |
| 工具调用 trace | AgentExecutionEvent 已推送 SSE |
| 真实 LLM API | DeepSeek 已对接 |

### ❌ 未满足
| 要求 | 当前状态 | 根因 |
|------|---------|------|
| **多轮对话 + session 维护** | ❌ 每次 send() 是新对话 | LLMAgentInstance 不加载历史 session |
| **跨轮次继续执行** | ❌ 第二轮追问时 Agent 不记得上一轮 | 没有跨轮次的上下文持久化 |
| **核心 runtime 简洁** | ❌ 30+ 文件，编排引擎 800 行 | 过度设计，把简单问题搞复杂 |

## 重构方案

### 1. 删掉编排引擎（6 个文件）

```
OrchestrationEngine.java       ← 接口
DefaultOrchestrationEngine.java ← 实现（800 行 for 循环）
OrchestrationSpec.java
OrchestrationResult.java
OrchestrationEvent.java
OrchestrationPattern.java
```

### 2. 核心 Agent 循环简化

当前 `LLMAgentInstance.send()` 做了两件事：
1. 构建 ChatRequest（不加载 session）
2. 调 ReActEngine（无状态）

改为：
1. **加载 session 历史** → ChatRequest 包含之前所有消息
2. **调 ReActEngine** → 生成新回复
3. **保存到 session** → 追加到 MessageStore

### 3. 跨轮次延续

```
第1轮: 用户 → "帮我写个博客项目"
  → Agent 创建 session
  → 执行 ReAct（生成项目结构）
  → 保存 session

第2轮: 用户 → "进度如何？"
  → Agent 加载同一 session
  → 看到历史消息 + 之前的结果
  → 回复 "已经生成了项目结构，正在..."
```

### 4. 最终文件结构

```
mesh/
├── AgentMessage.java       ← 消息协议（保留）
├── AgentRef.java           ← Agent 引用（保留）
├── AgentSpec.java          ← Agent 蓝图（保留）
├── AgentInstance.java      ← Agent 接口（保留）
├── AgentMesh.java          ← 路由核心（保留）
├── AgentExecutionEvent.java ← 事件（保留）
├── AgentExecutionStore.java ← 事件存储（保留）
├── DefaultAgentMesh.java   ← 路由实现（保留）
├── LLMAgentInstance.java   ← 核心 Agent（重写：加 session 加载）
├── ToolAgentInstance.java  ← 工具 Agent（保留）
├── ProxyAgentInstance.java ← 代理 Agent（保留）
│
删掉:
├── OrchestrationEngine.java
├── OrchestrationSpec.java
├── OrchestrationResult.java
├── OrchestrationEvent.java
├── OrchestrationPattern.java
└── DefaultOrchestrationEngine.java
```

## 执行顺序

| Step | 内容 |
|------|------|
| 1 | git checkout 删除编排文件 |
| 2 | 改 MeshController：删 orchestrate，保留 send |
| 3 | 重写 LLMAgentInstance：加载 session 历史 |
| 4 | 测试多轮对话延续 |
| 5 | 编译 + 测试 |
