# Agent Mesh 修复与完善计划

## 问题清单

### P0 — LLM Agent 无法调用
- **根因**: MeshAutoConfiguration 未在 auto-configuration imports 中注册，且 AgentFactory 与 AgentMesh 之间的依赖导致 ChatFacade 无法注入
- **影响**: 通过 REST API 注册的 LLM Agent 全部无法调用 LLM

### P0 — 子 Agent 进度消息不可见
- **根因**: ProgressBus 使用 DefaultReActEngine 的 static ConcurrentHashMap，但 MeshController.send() 只返回最终结果
- **影响**: 用户完全看不到子 Agent 执行进度

### P1 — 没有 Shell 执行能力
- **根因**: REST API 注册的 Agent 没有绑定任何 Tool
- **影响**: Agent 只能生成文本，无法执行命令/创建文件

### P1 — LLMAgentInstance 循环依赖
- **根因**: AgentFactory 需要 AgentMesh，AgentMesh 需要 AgentFactory
- **影响**: 编译可通过，但运行时依赖链断裂

### P2 — 前端无实时进度
- **根因**: MeshView 没有对接 SSE 事件流
- **影响**: 用户看不到编排过程中的实时状态

---

## 修复策略（统一执行，不分阶段）

### 1. MeshAutoConfiguration + 依赖注入（一次性修复）

```
改动:
  1. 确保 AutoConfiguration.imports 注册 MeshAutoConfiguration
  2. DefaultAgentMesh 不再自己创建 DefaultAgentFactory
  3. DefaultAgentFactory 不再依赖 AgentMesh（通过 setter 延迟注入）
  4. 在 MeshAutoConfiguration 中使用单独的 wiring bean
```

### 2. SSE 进度事件端点（新增 REST API）

```
改动:
  1. MeshController 新增 GET /api/mesh/agents/{id}/stream — SSE 流式端点
  2. 对接 DefaultReActEngine.PROGRESS_EMITTERS
  3. send 端点改为返回 correlationId + SSE 事件推送
  4. 前端 MeshView 对接 EventSource 接收进度
```

### 3. Shell ToolAgent 注册

```
改动:
  1. 启动时自动注册 shell-executor 和 file-writer 两个 ToolAgent
  2. 每个 LLM Agent 的 ToolRegistry 包含这些工具
  3. 工具定义中包含 name/description/parameters 供 LLM 调用
```

### 4. 前端进度面板

```
改动:
  1. MeshView 新增 SSE 流式消息面板
  2. 编排执行时展示每个 Agent 的实时进度
  3. 拓扑图中 Agent 节点显示当前状态动画
```

### 5. 端到端验证

```
测试:
  1. 注册 Builder Agent → 分配创建博客项目任务
  2. Builder Agent 使用 ReAct 循环 → 生成项目结构
  3. 调用 shell-executor → 创建目录和文件
  4. 前端 SSE 实时展示进度
  5. 验证 /tmp/blog-frontend 项目完整
```
