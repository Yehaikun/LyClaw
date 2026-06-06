# Agent Mesh 执行进度可见性设计

## 当前问题

```
用户发任务 → Agent 默默执行几十秒 → 返回结果
                           ↑
                    中间完全黑盒，看不到任何进展
```

## 设计目标

```
用户发任务 → Agent 开始执行 ←→ 用户可以随时查看每个 Agent 当前在做什么
                 │                    ├── writer: 正在生成 App.vue...
                 ▼                    ├── reviewer: 审查代码中...
             执行完成                  └── search: 搜索API文档中...
```

---

## 整体架构

```
┌──────────────────────────────────────────────────────────┐
│                        前端                                │
│  MeshView / ChatView                                      │
│  ┌──────────────────────┐  ┌──────────────────────────┐   │
│  │ Agent 进度面板        │  │ 执行时间线 / 甘特图       │   │
│  │ [writer] ● 代码生成中 │  │ writer ████████░░░░ 60% │   │
│  │ [search] ● 搜索中    │  │ search ████░░░░░░░░ 30% │   │
│  │ [review] ● 审查中    │  │ review ░░░░░░░░░░░░  0% │   │
│  └──────────┬───────────┘  └───────────┬──────────────┘   │
│             │                          │                   │
│          EventSource(SSE)          REST API(历史查询)     │
└─────────────┼──────────────────────────┼──────────────────┘
              │                          │
              ▼                          ▼
┌──────────────────────────────────────────────────────────┐
│                    后端                                    │
│  ┌────────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ SSE Endpoint    │  │ AgentExec    │  │ Event Store  │  │
│  │ /mesh/events    │  │ Repository   │  │ (环形缓冲)   │  │
│  │ ?agentId=xxx    │  │ CRUD + 查询  │  │ 每个 Agent   │  │
│  └───────┬────────┘  └──────┬───────┘  │ 保留最近 N 条 │  │
│          │                  │          └──────┬─────────┘  │
│          └──────────────────┼─────────────────┘             │
│                             │                              │
│  ┌──────────────────────────▼────────────────────────────┐ │
│  │              AgentExecutionEvent                       │ │
│  │  { agentId, taskId, stage, status, progress%,          │ │
│  │    message, timestamp, parentTaskId }                  │ │
│  └──────────────────────────┬────────────────────────────┘ │
│                             │                              │
│  ┌──────────────────────────▼────────────────────────────┐ │
│  │              事件生产方                                 │ │
│  │  ReActEngine → LLMAgentInstance → DefaultAgentMesh     │ │
│  │  DefaultOrchestrationEngine → SubagentSpawner          │ │
│  └───────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

---

## 后端设计

### 1. AgentExecutionEvent — 执行事件模型

```java
public class AgentExecutionEvent {
    String eventId;          // 事件唯一 ID
    String agentId;          // 产生事件的 Agent
    String taskId;           // 任务 ID（关联多个事件）
    String parentTaskId;     // 父任务 ID（用于树形展示）
    EventType type;          // STARTED | PROGRESS | TOOL_CALL | 
                             // SUBAGENT_SPAWN | COMPLETED | FAILED
    String stage;            // 当前阶段名 ("ContextBuild","Respond"...)
    String message;          // 人类可读的描述
    int progress;            // 0-100 进度百分比
    long timestamp;          // 事件时间戳
    Map<String, Object> metadata; // 扩展数据（工具名、子Agent ID等）
}
```

### 2. AgentExecutionStore — 事件环形存储

```java
public interface AgentExecutionStore {
    void append(AgentExecutionEvent event);
    List<AgentExecutionEvent> getEvents(String agentId, int limit);
    List<AgentExecutionEvent> getEventsByTask(String taskId);
    AgentExecutionEvent getLatest(String agentId);
    Map<String, AgentExecutionEvent> getLatestForAll();
}
```

默认实现用 `ConcurrentHashMap<String, ConcurrentLinkedDeque<AgentExecutionEvent>>`，
每个 Agent 保留最近 100 条事件。

### 3. SSE 端点 — 实时推送

```java
@GetMapping(value = "/api/mesh/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamEvents(
    @RequestParam(required = false) String agentId,
    @RequestParam(required = false) String taskId) {
    
    // 返回 Flux 流，有新事件时推送给前端
    return executionEventBus.stream()  // Flux<AgentExecutionEvent>
        .filter(e -> agentId == null || agentId.equals(e.getAgentId()))
        .filter(e -> taskId == null || taskId.equals(e.getTaskId()))
        .map(e -> ServerSentEvent.builder(e.toJson())
            .event("agent_execution")
            .build());
}
```

### 4. REST 端点 — 历史查询

```java
@GetMapping("/api/mesh/agents/{agentId}/events")
public List<AgentExecutionEvent> getAgentEvents(
    @PathVariable String agentId,
    @RequestParam(defaultValue = "50") int limit) {
    return executionStore.getEvents(agentId, limit);
}

@GetMapping("/api/mesh/tasks/{taskId}/events")
public List<AgentExecutionEvent> getTaskEvents(@PathVariable String taskId) {
    return executionStore.getEventsByTask(taskId);
}
```

### 5. 事件生产 — 在 AgentInstance 中埋点

```java
// LLMAgentInstance.send() 中：
eventBus.publish(AgentExecutionEvent.started(agentId, taskId, "开始执行"));
// ReAct 每轮：
eventBus.publish(AgentExecutionEvent.progress(agentId, taskId, 
    "第 " + round + " 轮推理", progress));
// 工具调用：
eventBus.publish(AgentExecutionEvent.toolCall(agentId, taskId, 
    "调用工具 " + toolName, toolName));
// 子 Agent 委托：
eventBus.publish(AgentExecutionEvent.subagentSpawn(agentId, taskId, 
    "委托给 " + childAgentId, childAgentId));
// 完成：
eventBus.publish(AgentExecutionEvent.completed(agentId, taskId, "执行完成"));
```

### 6. OrchesrationEngine 集成

```java
// DefaultOrchestrationEngine 各模式中：
eventBus.publish(AgentExecutionEvent.stage(orchestratorId, taskId,
    "FAN_OUT 并行派发给 " + agentCount + " 个 Agent"));
// 每个子 Agent 开始时：
eventBus.publish(AgentExecutionEvent.subagentSpawn(orchestratorId, taskId,
    "→ " + agentId, agentId));
```

---

## 前端设计

### 1. API 客户端

```typescript
// api/mesh.ts 新增
export function subscribeAgentEvents(agentId?: string): EventSource {
  const params = agentId ? `?agentId=${agentId}` : ''
  return new EventSource(`/api/mesh/events${params}`)
}

export async function fetchAgentEvents(agentId: string): Promise<AgentExecutionEvent[]> {
  return get(`/api/mesh/agents/${agentId}/events?limit=50`)
}
```

### 2. Pinia Store

```typescript
// stores/agentExecution.ts
export const useAgentExecutionStore = defineStore('agentExecution', () => {
  const events = ref<AgentExecutionEvent[]>([])
  const connected = ref(false)
  let eventSource: EventSource | null = null
  
  function connect(agentId?: string) {
    eventSource = subscribeAgentEvents(agentId)
    eventSource.onmessage = (e) => {
      const event = JSON.parse(e.data)
      events.value.unshift(event)
      // 只保留最近 200 条
      if (events.value.length > 200) events.value.pop()
    }
  }
  
  function disconnect() { eventSource?.close(); connected.value = false }
  
  // 按 agentId 分组的最新事件
  const latestByAgent = computed(() => {
    const map = new Map<string, AgentExecutionEvent>()
    for (const e of events.value) {
      map.set(e.agentId, e)
    }
    return map
  })
  
  return { events, latestByAgent, connect, disconnect }
})
```

### 3. 前端组件

**AgentProgressPanel** — Agent 执行进度面板

```
┌─ Agent 执行状态 ─────────────────────────────┐
│                                               │
│  [🤖] writer     ● 代码生成中         ██░░ 45%│
│  └─ 正在生成 App.vue 组件...                  │
│                                               │
│  [🔧] search     ● 搜索中             █░░░ 20%│
│  └─ 查询 Vue 3 文档...                        │
│                                               │
│  [👁] reviewer   ○ 等待中                     │
│                                               │
│  ═══ 完成 ═══                                 │
│  [✓] planner    100% 任务分解完成             │
│      结果: 3 个子任务                          │
└───────────────────────────────────────────────┘
```

**ExecutionTimeline** — 时间线视图

```
  10:23:15  writer  开始生成班级管理系统
  10:23:16  writer  ├─ 生成 package.json     ✅
  10:23:18  writer  ├─ 生成 App.vue          ✅
  10:23:22  writer  ├─ 生成 StudentList.vue  ⏳
  10:23:15  search  ├─ 搜索 Element Plus 文档 ✅
```

**ChatView 集成** — 聊天界面中的 Agent 执行指示

```
用户: 帮我写个班级管理系统
                      ┌─────────────────────────┐
                      │ AI 正在处理你的请求...    │
                      │                          │
                      │ [→] writer: 生成代码     │
                      │ [→] search: 搜索组件库   │
                      │ [→] reviewer: 待命      │
                      └─────────────────────────┘
                 ↓
                      ┌─────────────────────────┐
                      │ 项目已创建               │
                      │ 前端: App.vue, ...       │
                      │ 后端: main.py, ...       │
                      └─────────────────────────┘
```

---

## 实现顺序

### Phase 1 — 后端事件系统 + SSE

| # | 内容 | 文件 |
|---|------|------|
| 1 | `AgentExecutionEvent` 事件模型 | mesh/AgentExecutionEvent.java |
| 2 | `AgentExecutionStore` 环形存储 + 默认实现 | mesh/AgentExecutionStore.java |
| 3 | 事件总线集成到 DefaultAgentMesh | mesh/impl/DefaultAgentMesh.java |
| 4 | `LLMAgentInstance` 埋点（round/tool/subagent） | mesh/impl/LLMAgentInstance.java |
| 5 | `DefaultOrchestrationEngine` 埋点 | mesh/impl/DefaultOrchestrationEngine.java |
| 6 | SSE 端点 `GET /api/mesh/events` | web/controller/MeshController.java |
| 7 | 历史查询 `GET /api/mesh/agents/{id}/events` | web/controller/MeshController.java |

### Phase 2 — 前端可视化

| # | 内容 | 文件 |
|---|------|------|
| 8 | `agentExecution` Pinia store | stores/agentExecution.ts |
| 9 | `AgentProgressPanel` 进度面板 | components/AgentProgressPanel.vue |
| 10 | `ExecutionTimeline` 时间线 | components/ExecutionTimeline.vue |
| 11 | MeshView 集成进度面板 | views/MeshView.vue |
| 12 | ChatView 集成 Agent 执行指示 | views/ChatView.vue |
